from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


core_path = Path("app/src/main/java/ru/mgsu/schedule/data/MgsuGridParserCore.kt")
core = core_path.read_text()

core = replace_once(
    core,
    '''    data class Page(
        val number: Int,
        val width: Float,
        val height: Float,
        val glyphs: List<Glyph>
    )
''',
    '''    data class Rule(
        val x1: Float,
        val y1: Float,
        val x2: Float,
        val y2: Float
    ) {
        val minX: Float get() = minOf(x1, x2)
        val maxX: Float get() = maxOf(x1, x2)
        val minY: Float get() = minOf(y1, y2)
        val maxY: Float get() = maxOf(y1, y2)
        val horizontal: Boolean get() = abs(y2 - y1) <= 2.2f
        val vertical: Boolean get() = abs(x2 - x1) <= 2.2f
        val length: Float get() = max(abs(x2 - x1), abs(y2 - y1))
    }

    data class Page(
        val number: Int,
        val width: Float,
        val height: Float,
        val glyphs: List<Glyph>,
        val rules: List<Rule> = emptyList()
    )
''',
    "Page/rule model",
)

core = replace_once(
    core,
    '''        val columns = findGroupColumns(page, lines)
        val column = columns.firstOrNull { it.group == targetGroup } ?: return emptyList()
        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f
        val firstGroupLeft = columns.minOfOrNull { it.left } ?: column.left
        val pairColumn = findPairColumn(page, firstGroupLeft, headerBottom) ?: return emptyList()
''',
    '''        val columns = findGroupColumns(page, lines)
        val textColumn = columns.firstOrNull { it.group == targetGroup } ?: return emptyList()
        val column = refineColumnFromGrid(page, textColumn)
        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f
        val firstGroupLeft = columns.minOfOrNull { it.left } ?: column.left
        val pairColumn = findPairColumn(page, lines, firstGroupLeft, headerBottom) ?: return emptyList()
''',
    "lesson structural setup",
)

old_loop = '''        val out = mutableListOf<ScheduleEvent>()
        for (cycle in cycles) {
            val anchorsInDay = cycle.anchors.sortedBy { it.y }
            for (i in anchorsInDay.indices) {
                val anchor = anchorsInDay[i]
                val range = ScheduleParsingRules.officialPairTimes[anchor.pairNo] ?: continue
                val top = if (i == 0) cycle.top else (anchorsInDay[i - 1].y + anchor.y) / 2f
                val bottom = if (i == anchorsInDay.lastIndex) cycle.bottom else (anchor.y + anchorsInDay[i + 1].y) / 2f
                if (bottom <= top + 2f) continue

                val cellLines = extractCellLines(page.glyphs, column.left, column.right, top, bottom)
                val event = buildLessonEvent(
                    lines = cellLines,
                    group = targetGroup,
                    weekday = cycle.day,
                    start = range.first,
                    end = range.second,
                    sourceUrl = sourceUrl,
                    sourceLabel = sourceLabel
                )
                if (event != null) out += event
            }
        }
        return out.distinctBy { it.id }
'''
new_loop = '''        val out = mutableListOf<ScheduleEvent>()
        for (cycle in cycles) {
            val anchorsInDay = cycle.anchors.sortedBy { it.y }
            for (i in anchorsInDay.indices) {
                val anchor = anchorsInDay[i]
                val range = ScheduleParsingRules.officialPairTimes[anchor.pairNo] ?: continue
                val nextAnchorY = anchorsInDay.getOrNull(i + 1)?.y
                val fallbackTop = if (i == 0) cycle.top else (anchorsInDay[i - 1].y + anchor.y) / 2f
                val fallbackBottom = if (i == anchorsInDay.lastIndex) cycle.bottom
                    else (anchor.y + anchorsInDay[i + 1].y) / 2f

                val (top, bottom) = findPairRowBand(
                    page = page,
                    column = column,
                    pairCenter = pairColumn.center,
                    anchor = anchor,
                    nextAnchorY = nextAnchorY,
                    cycle = cycle,
                    fallbackTop = fallbackTop,
                    fallbackBottom = fallbackBottom
                )
                if (bottom <= top + 2f) continue

                val segments = cellSegments(page, column, top, bottom)
                val segmentLines = segments.map { (segmentTop, segmentBottom) ->
                    extractCellLines(page.glyphs, column.left, column.right, segmentTop, segmentBottom)
                }
                val rowText = segmentLines.flatten().joinToString(" ")
                val inferTwoWeekParity = segments.size == 2 && !subgroupRegex.containsMatchIn(rowText)

                for (segmentIndex in segments.indices) {
                    val forcedParity = if (inferTwoWeekParity) {
                        if (segmentIndex == 0) "ODD" else "EVEN"
                    } else null
                    val event = buildLessonEvent(
                        lines = segmentLines[segmentIndex],
                        group = targetGroup,
                        weekday = cycle.day,
                        start = range.first,
                        end = range.second,
                        sourceUrl = sourceUrl,
                        sourceLabel = sourceLabel,
                        forcedParity = forcedParity
                    )
                    if (event != null) out += event
                }
            }
        }
        return out.distinctBy { it.id }
'''
core = replace_once(core, old_loop, new_loop, "lesson loop")

start = core.index("    private fun findPairColumn(")
end = core.index("    internal fun splitPairCycles", start)
new_pair_column = '''    private fun findPairColumn(
        page: Page,
        lines: List<Line>,
        firstGroupLeft: Float,
        headerBottom: Float
    ): PairColumn? {
        val bucketSize = max(2.5f, page.width * .004f)
        val pairHeaderCenter = lines
            .filter { it.y <= headerBottom + 28f && it.cx < firstGroupLeft }
            .firstOrNull { norm(it.text).replace(" ", "").contains("ПАРА") }
            ?.cx

        val candidates = lines.asSequence()
            .filter { it.y > headerBottom && it.cx < firstGroupLeft }
            .mapNotNull { line ->
                val value = line.text.trim()
                if (!singlePairRegex.matches(value)) return@mapNotNull null
                val no = value.toIntOrNull()?.takeIf { it in 1..8 } ?: return@mapNotNull null
                PairGlyph(no, line.y, line.cx)
            }
            .groupBy { (it.x / bucketSize).roundToInt() }

        data class Scored(val center: Float, val cycles: List<List<PairAnchor>>, val score: Int)

        return candidates.values.mapNotNull { cluster ->
            val ordered = cluster.sortedBy { it.y }
            val anchors = mutableListOf<PairAnchor>()
            for (item in ordered) {
                val duplicate = anchors.lastOrNull()?.let {
                    it.pairNo == item.pairNo && abs(it.y - item.y) < 3.5f
                } == true
                if (!duplicate) anchors += PairAnchor(item.pairNo, item.y)
            }
            val cycles = splitPairCycles(anchors)
            if (cycles.isEmpty()) return@mapNotNull null
            val strongCycles = cycles.count { cycle ->
                cycle.size >= 5 && cycle.zipWithNext().all { (a, b) -> b.pairNo == a.pairNo + 1 }
            }
            val distinct = anchors.map { it.pairNo }.distinct().size
            val center = cluster.map { it.x }.average().toFloat()
            val headerBonus = pairHeaderCenter?.let { header ->
                (900f - abs(center - header) * 25f).coerceAtLeast(0f).roundToInt()
            } ?: 0
            val score = strongCycles * 10000 + cycles.size * 1000 + distinct * 100 + anchors.size + headerBonus
            Scored(center, cycles, score)
        }.maxByOrNull { it.score }?.let { PairColumn(it.center, it.cycles) }
    }

'''
core = core[:start] + new_pair_column + core[end:]

insert_before = '''    internal fun extractCellLines(
        glyphs: List<Glyph>,
'''
helpers = '''    private fun refineColumnFromGrid(page: Page, column: Column): Column {
        if (page.rules.isEmpty()) return column
        val xs = collapseCoordinates(
            page.rules.asSequence()
                .filter { it.vertical && it.length >= 36f }
                .filter { it.maxY >= column.headerY - 15f }
                .map { (it.x1 + it.x2) / 2f }
                .sorted()
                .toList(),
            tolerance = max(2.2f, page.width * .0014f)
        )
        if (xs.size < 2) return column
        val left = xs.lastOrNull { it < column.center - 4f } ?: return column
        val right = xs.firstOrNull { it > column.center + 4f } ?: return column
        if (right - left !in 28f..(page.width * .55f)) return column
        return column.copy(left = left + 0.8f, right = right - 0.8f)
    }

    private fun findPairRowBand(
        page: Page,
        column: Column,
        pairCenter: Float,
        anchor: PairAnchor,
        nextAnchorY: Float?,
        cycle: DayCycle,
        fallbackTop: Float,
        fallbackBottom: Float
    ): Pair<Float, Float> {
        if (page.rules.isEmpty()) return fallbackTop to fallbackBottom

        val pairYs = horizontalRuleYsAt(
            page,
            x = pairCenter,
            minY = cycle.top - 30f,
            maxY = cycle.bottom + 30f
        )
        val pairTop = pairYs.lastOrNull { it < anchor.y - 2.5f }
        val pairBottom = pairYs.firstOrNull { it > anchor.y + 2.5f }
        if (pairTop != null && pairBottom != null && pairBottom - pairTop in 12f..260f) {
            return pairTop to pairBottom
        }

        val groupYs = horizontalRuleYsAt(
            page,
            x = column.center,
            minY = cycle.top - 30f,
            maxY = cycle.bottom + 30f
        )
        val top = groupYs.lastOrNull { it < anchor.y - 2.5f } ?: fallbackTop
        val bottom = if (nextAnchorY != null) {
            groupYs.lastOrNull { it > anchor.y + 2.5f && it < nextAnchorY - 2.5f }
        } else {
            groupYs.lastOrNull { it > anchor.y + 2.5f && it <= cycle.bottom + 12f }
        } ?: fallbackBottom

        return if (bottom > top + 10f && bottom - top <= 280f) top to bottom
        else fallbackTop to fallbackBottom
    }

    private fun cellSegments(
        page: Page,
        column: Column,
        top: Float,
        bottom: Float
    ): List<Pair<Float, Float>> {
        if (page.rules.isEmpty()) return listOf(top to bottom)
        val inner = horizontalRuleYsAt(page, column.center, top, bottom)
            .filter { it > top + 3f && it < bottom - 3f }
        val bounds = (listOf(top) + inner + listOf(bottom)).sorted()
        val segments = bounds.zipWithNext().filter { (a, b) -> b > a + 4f }
        return segments.ifEmpty { listOf(top to bottom) }
    }

    private fun horizontalRuleYsAt(
        page: Page,
        x: Float,
        minY: Float,
        maxY: Float
    ): List<Float> {
        val tolerance = max(2.0f, page.height * .00085f)
        val values = page.rules.asSequence()
            .filter { it.horizontal && it.length >= 18f }
            .filter { it.minX - 2f <= x && it.maxX + 2f >= x }
            .map { (it.y1 + it.y2) / 2f }
            .filter { it >= minY && it <= maxY }
            .sorted()
            .toList()
        return collapseCoordinates(values, tolerance)
    }

    private fun collapseCoordinates(values: List<Float>, tolerance: Float): List<Float> {
        if (values.isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<Float>>()
        for (value in values.sorted()) {
            val last = groups.lastOrNull()
            if (last == null || abs(value - last.average().toFloat()) > tolerance) {
                groups += mutableListOf(value)
            } else {
                last += value
            }
        }
        return groups.map { it.average().toFloat() }
    }

'''
if insert_before not in core:
    raise SystemExit("grid helper insertion point missing")
core = core.replace(insert_before, helpers + insert_before, 1)

core = replace_once(
    core,
    '''        end: String,
        sourceUrl: String,
        sourceLabel: String
    ): ScheduleEvent? {
''',
    '''        end: String,
        sourceUrl: String,
        sourceLabel: String,
        forcedParity: String? = null
    ): ScheduleEvent? {
''',
    "buildLessonEvent signature",
)
core = replace_once(
    core,
    '''            sourceUrl = sourceUrl,
            sourceLabel = sourceLabel,
            forceType = null
        )
''',
    '''            sourceUrl = sourceUrl,
            sourceLabel = sourceLabel,
            forceType = null,
            forcedParity = forcedParity
        )
''',
    "buildLessonEvent call",
)
core = replace_once(
    core,
    '''        sourceUrl: String,
        sourceLabel: String,
        forceType: String?
    ): ScheduleEvent? {
''',
    '''        sourceUrl: String,
        sourceLabel: String,
        forceType: String?,
        forcedParity: String? = null
    ): ScheduleEvent? {
''',
    "buildEvent signature",
)
core = replace_once(
    core,
    '''        val weeks = ScheduleParsingRules.extractWeekNumbers(cleaned)
        val parity = ScheduleParsingRules.extractParity(cleaned)
        val type = forceType ?: inferType(cleaned)
''',
    '''        val weeks = ScheduleParsingRules.extractWeekNumbers(cleaned)
        val detectedParity = ScheduleParsingRules.extractParity(cleaned)
        val parity = if (detectedParity != "ANY") detectedParity else forcedParity ?: "ANY"
        val type = forceType ?: inferType(cleaned)
''',
    "event parity",
)
core = replace_once(
    core,
    '''    private val singlePairRegex = Regex("^[1-8]$")
''',
    '''    private val singlePairRegex = Regex("^[1-8]$")
    private val subgroupRegex = Regex("(?i)(?:п\\s*/?\\s*г|подгрупп)")
''',
    "subgroup regex",
)
core_path.write_text(core)

live_path = Path("app/src/test/java/ru/mgsu/schedule/data/LiveMgsuGridParserTest.kt")
live_path.write_text(r'''package ru.mgsu.schedule.data

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

class LiveMgsuGridParserTest {
    @Test
    fun `current official PDFs match verified pair matrices`() {
        val dir = liveDirOrSkip()
        val samples = listOf(
            Sample("IAG_1k_0826_20.pdf", "ИАГ", "ИАГ 1-41", mapOf(
                1 to setOf(1, 2, 4), 2 to setOf(1, 2, 3, 4, 5),
                3 to setOf(1, 2, 4, 5), 4 to setOf(1, 2, 3, 4, 5),
                5 to setOf(1, 2, 4, 5, 6), 6 to emptySet()
            )),
            Sample("IPGSb_1k_0610.pdf", "ИПГС", "ИПГС 1-1", mapOf(
                1 to setOf(1, 2, 3, 4, 5), 2 to setOf(1, 2, 3, 4, 5, 6),
                3 to setOf(2, 3, 4, 5, 6), 4 to setOf(3, 5, 6),
                5 to setOf(1, 2, 4, 5), 6 to emptySet()
            )),
            Sample("IGES_1k.pdf", "ИГЭС", "ИГЭС 1-1", mapOf(
                1 to setOf(4, 5, 6), 2 to setOf(3, 5, 6),
                3 to setOf(1, 2, 4), 4 to setOf(2, 3, 4, 5, 6),
                5 to setOf(1, 3, 4, 5, 6), 6 to emptySet()
            )),
            Sample("IIESM_1k_0210.pdf", "ИИЭСМ", "ИИЭСМ 1-10", mapOf(
                1 to setOf(2, 3, 4), 2 to setOf(3, 5, 6),
                3 to setOf(1, 2, 3, 4, 5, 6), 4 to setOf(1, 2, 3),
                5 to setOf(2, 3, 4, 5), 6 to emptySet()
            ))
        )

        for (sample in samples) {
            val file = File(dir, sample.fileName)
            assertTrue("Live fixture missing: ${sample.fileName}", file.isFile && file.length() > 10_000)
            val pages = extractPages(file)
            printDiagnostics(sample.group, pages)
            val events = parsePages(pages, file, sample.institute, sample.group)
            println("LIVE EVENTS ${sample.group}: ${formatEvents(events)}")
            assertTrue("${sample.group}: parser returned no lessons", events.isNotEmpty())
            val actual = pairsByDay(events)
            for (day in 1..6) {
                assertEquals(
                    "${sample.group}: wrong pair set for weekday $day; events=${formatEvents(events.filter { it.weekday == day })}",
                    sample.expectedPairs[day].orEmpty(),
                    actual[day].orEmpty()
                )
            }
        }
    }

    @Test
    fun `every discovered group stays inside official table structure`() {
        val dir = liveDirOrSkip()
        val files = listOf(
            "IAG_1k_0826_20.pdf", "IPGSb_1k_0610.pdf", "IGES_1k.pdf", "IIESM_1k_0210.pdf"
        )
        for (fileName in files) {
            val file = File(dir, fileName)
            val pages = extractPages(file)
            val groups = pages.flatMap { page ->
                MgsuGridParserCore.findGroupColumns(page, MgsuGridParserCore.buildLines(page.glyphs))
                    .map { it.group }
            }.distinct()
            assertTrue("$fileName: no student groups discovered", groups.isNotEmpty())
            for (group in groups) {
                val events = parsePages(pages, file, group.substringBefore(' '), group)
                assertTrue("$group: invalid weekday", events.all { it.weekday in 1..6 })
                assertTrue("$group: non-official lesson time", events.all { event ->
                    ScheduleParsingRules.officialPairTimes.values.any {
                        it.first == event.startTime && it.second == event.endTime
                    }
                })
                assertTrue("$group: more than 8 distinct pairs in a day", pairsByDay(events).values.all { it.size <= 8 })
            }
        }
    }

    private fun pairsByDay(events: List<ScheduleEvent>): Map<Int, Set<Int>> =
        (1..6).associateWith { day ->
            events.asSequence().filter { it.weekday == day }.mapNotNull { event ->
                ScheduleParsingRules.officialPairTimes.entries
                    .firstOrNull { it.value.first == event.startTime && it.value.second == event.endTime }?.key
            }.toSet()
        }

    private fun parsePages(
        pages: List<MgsuGridParserCore.Page>,
        file: File,
        institute: String,
        group: String
    ): List<ScheduleEvent> {
        val profile = UserProfile(
            role = UserRole.STUDENT, institute = institute, course = 1, studyForm = "Очная",
            group = group, semesterStart = "2026-08-31"
        )
        return pages.flatMap { page ->
            MgsuGridParserCore.parseLessons(page, profile, file.toURI().toString(), "$institute 1 курс live")
        }.distinctBy { it.id }
    }

    private fun printDiagnostics(group: String, pages: List<MgsuGridParserCore.Page>) {
        for (page in pages) {
            val lines = MgsuGridParserCore.buildLines(page.glyphs)
            val columns = MgsuGridParserCore.findGroupColumns(page, lines)
            val numericLines = lines.filter { it.text.trim().matches(Regex("^[1-8]$")) }.take(80)
            println("LIVE DIAG $group page=${page.number} size=${page.width}x${page.height} " +
                "glyphs=${page.glyphs.size} rules=${page.rules.size} lines=${lines.size}")
            println("LIVE DIAG $group columns=" +
                columns.joinToString { "${it.group}@${it.center}[${it.left},${it.right}] y=${it.headerY}" })
            println("LIVE DIAG $group numeric=${numericLines.joinToString { "${it.text}@(${it.cx},${it.y})" }}")
        }
    }

    private fun formatEvents(events: List<ScheduleEvent>): String =
        events.sortedWith(compareBy<ScheduleEvent> { it.weekday }.thenBy { it.startTime }.thenBy { it.title })
            .joinToString(" || ") {
                "d=${it.weekday} ${it.startTime}-${it.endTime} [${it.weekParity}] ${it.title}"
            }

    private fun extractPages(file: File): List<MgsuGridParserCore.Page> {
        PDDocument.load(file).use { document ->
            val stripper = DesktopStripper()
            stripper.sortByPosition = true
            stripper.getText(document)
            return stripper.glyphs.groupBy { it.page }.map { (pageNo, glyphs) ->
                val page = document.getPage(pageNo - 1)
                val rotated = ((page.rotation % 180) + 180) % 180 == 90
                val visualWidth = if (rotated) page.mediaBox.height else page.mediaBox.width
                val visualHeight = if (rotated) page.mediaBox.width else page.mediaBox.height
                val rules = DesktopPdfGridRuleExtractor(page).extract()
                MgsuGridParserCore.Page(pageNo, visualWidth, visualHeight, glyphs, rules)
            }
        }
    }

    private fun liveDirOrSkip(): File {
        val raw = System.getenv("MGSU_LIVE_DIR").orEmpty()
        assumeTrue("MGSU_LIVE_DIR is not set; live parser verification skipped", raw.isNotBlank())
        val dir = File(raw)
        assumeTrue("MGSU_LIVE_DIR does not exist", dir.isDirectory)
        return dir
    }

    private data class Sample(
        val fileName: String, val institute: String, val group: String,
        val expectedPairs: Map<Int, Set<Int>>
    )

    private class DesktopStripper : PDFTextStripper() {
        val glyphs = mutableListOf<MgsuGridParserCore.Glyph>()
        private var pageNo = 0
        override fun startPage(page: PDPage?) {
            pageNo++
            super.startPage(page)
        }
        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode ?: return
            if (unicode.isBlank()) return
            glyphs += MgsuGridParserCore.Glyph(
                page = pageNo, x = text.xDirAdj, y = text.yDirAdj,
                width = text.widthDirAdj, height = text.heightDir, text = unicode
            )
        }
    }
}
''')

print("parser v9 exact-grid rewrite applied")
