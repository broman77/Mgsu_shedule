from pathlib import Path

path = Path('app/src/main/java/ru/mgsu/schedule/data/MgsuGridParserCore.kt')
text = path.read_text()


def replace_once(old: str, new: str, label: str):
    global text
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected 1 match, got {count}')
    text = text.replace(old, new, 1)

replace_once(
'''    data class PairAnchor(val pairNo: Int, val y: Float)\n\n    data class DayCycle(''',
'''    data class PairAnchor(val pairNo: Int, val y: Float)\n\n    private data class HeaderCandidate(\n        val line: Line,\n        val group: String,\n        val direct: Boolean,\n        val parts: Int\n    )\n\n    private data class PairGlyph(val pairNo: Int, val y: Float, val x: Float)\n    private data class PairColumn(val center: Float, val cycles: List<List<PairAnchor>>)\n\n    data class DayCycle(''',
'add structural candidate types'
)

replace_once(
'''        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f\n        val pairHeader = findHeader(lines, "ПАРА", headerBottom + page.height * .08f)\n        val pairCenter = pairHeader?.cx ?: inferPairCenter(columns, page.width)\n        val anchors = findPairAnchors(lines, pairCenter, page.width, headerBottom)\n        val rawCycles = splitPairCycles(anchors)\n        if (rawCycles.isEmpty()) return emptyList()\n\n        val dayMarkers = findWeekdayMarkers(page, pairCenter)\n''',
'''        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f\n        val firstGroupLeft = columns.minOfOrNull { it.left } ?: column.left\n        val pairColumn = findPairColumn(page, firstGroupLeft, headerBottom) ?: return emptyList()\n        val rawCycles = pairColumn.cycles\n\n        val dayMarkers = findWeekdayMarkers(page, pairColumn.center)\n''',
'switch lesson parser to structural pair column'
)

replace_once(
'''        val candidates = mutableListOf<Pair<Line, String>>()\n\n        headerZone.forEach { line ->\n            ScheduleParsingRules.canonicalGroup(line.text)?.let { candidates += line to it }\n        }\n''',
'''        val candidates = mutableListOf<HeaderCandidate>()\n\n        // A complete line that already contains one canonical group is always the safest\n        // header candidate. Reconstructed windows exist only for PDFs that split the header.\n        headerZone.forEach { line ->\n            ScheduleParsingRules.canonicalGroup(line.text)?.let { group ->\n                candidates += HeaderCandidate(line, group, direct = true, parts = 1)\n            }\n        }\n''',
'prefer direct group headers'
)

replace_once(
'''                    candidates += Line(\n                        y = parts.map { it.y }.average().toFloat(),\n                        x1 = parts.minOf { it.x1 },\n                        x2 = parts.maxOf { it.x2 },\n                        text = text\n                    ) to group\n''',
'''                    candidates += HeaderCandidate(\n                        line = Line(\n                            y = parts.map { it.y }.average().toFloat(),\n                            x1 = parts.minOf { it.x1 },\n                            x2 = parts.maxOf { it.x2 },\n                            text = text\n                        ),\n                        group = group,\n                        direct = false,\n                        parts = length\n                    )\n''',
'record reconstructed group header metadata'
)

replace_once(
'''        val best = candidates\n            .groupBy { it.second }\n            .mapNotNull { (_, values) ->\n                values.minWithOrNull(compareBy<Pair<Line, String>> { it.first.y }.thenByDescending { it.first.x2 - it.first.x1 })\n            }\n            .sortedBy { it.first.cx }\n\n        if (best.isEmpty()) return emptyList()\n        val centers = best.map { it.first.cx }\n        return best.mapIndexed { index, item ->\n            val line = item.first\n            val group = item.second\n''',
'''        val best = candidates\n            .groupBy { it.group }\n            .mapNotNull { (_, values) ->\n                values.minWithOrNull(\n                    compareBy<HeaderCandidate> { it.line.y }\n                        .thenBy { if (it.direct) 0 else 1 }\n                        .thenBy { it.parts }\n                        .thenBy { it.line.x2 - it.line.x1 }\n                )\n            }\n            .sortedBy { it.line.cx }\n\n        if (best.isEmpty()) return emptyList()\n        val centers = best.map { it.line.cx }\n        return best.mapIndexed { index, item ->\n            val line = item.line\n            val group = item.group\n''',
'choose compact exact group header'
)

start = text.index('    internal fun findPairAnchors(')
end = text.index('    internal fun splitPairCycles(', start)
old = text[start:end]
new = '''    /**\n     * Finds the real "Пара" column from the repeated numeric structure itself. Some official\n     * PDFs expose the header as one merged string ("Дни Часы Пара"), while others do not\n     * expose the word at all. The pair numbers are still a stable vertical cluster.\n     */\n    private fun findPairColumn(page: Page, firstGroupLeft: Float, headerBottom: Float): PairColumn? {\n        val bucketSize = max(2.5f, page.width * .004f)\n        val candidates = page.glyphs.asSequence()\n            .filter { it.y > headerBottom && it.cx < firstGroupLeft }\n            .mapNotNull { glyph ->\n                val value = glyph.text.trim()\n                if (!singlePairRegex.matches(value)) return@mapNotNull null\n                val no = value.toIntOrNull()?.takeIf { it in 1..8 } ?: return@mapNotNull null\n                PairGlyph(no, glyph.y, glyph.cx)\n            }\n            .groupBy { (it.x / bucketSize).roundToInt() }\n\n        data class Scored(val center: Float, val cycles: List<List<PairAnchor>>, val score: Int)\n\n        return candidates.values.mapNotNull { cluster ->\n            val ordered = cluster.sortedBy { it.y }\n            val anchors = mutableListOf<PairAnchor>()\n            for (item in ordered) {\n                val duplicate = anchors.lastOrNull()?.let {\n                    it.pairNo == item.pairNo && abs(it.y - item.y) < 3.5f\n                } == true\n                if (!duplicate) anchors += PairAnchor(item.pairNo, item.y)\n            }\n            val cycles = splitPairCycles(anchors)\n            if (cycles.isEmpty()) return@mapNotNull null\n            val strongCycles = cycles.count { cycle ->\n                cycle.size >= 5 && cycle.zipWithNext().all { (a, b) -> b.pairNo == a.pairNo + 1 }\n            }\n            val distinct = anchors.map { it.pairNo }.distinct().size\n            val score = strongCycles * 10000 + cycles.size * 1000 + distinct * 100 + anchors.size\n            Scored(cluster.map { it.x }.average().toFloat(), cycles, score)\n        }.maxByOrNull { it.score }?.let { PairColumn(it.center, it.cycles) }\n    }\n\n'''
text = text[:start] + new + text[end:]

replace_once(
'''            .filter { cycle ->\n                cycle.size >= 3 && cycle.zipWithNext().all { (a, b) -> b.pairNo > a.pairNo }\n            }\n''',
'''            .filter { cycle ->\n                // The dedicated pair column is structural: rows must advance 1→2→3... .\n                // Random digits from time/specialty text cannot form a valid day cycle.\n                cycle.size >= 3 && cycle.zipWithNext().all { (a, b) -> b.pairNo == a.pairNo + 1 }\n            }\n''',
'tighten structural pair cycles'
)

path.write_text(text)
print('Applied parser v9 geometry fix')
