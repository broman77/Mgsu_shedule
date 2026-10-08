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
    '''    data class Page(\n        val number: Int,\n        val width: Float,\n        val height: Float,\n        val glyphs: List<Glyph>\n    )\n''',
    '''    data class Rule(\n        val x1: Float,\n        val y1: Float,\n        val x2: Float,\n        val y2: Float\n    ) {\n        val minX: Float get() = minOf(x1, x2)\n        val maxX: Float get() = maxOf(x1, x2)\n        val minY: Float get() = minOf(y1, y2)\n        val maxY: Float get() = maxOf(y1, y2)\n        val horizontal: Boolean get() = abs(y2 - y1) <= 2.2f\n        val vertical: Boolean get() = abs(x2 - x1) <= 2.2f\n        val length: Float get() = max(abs(x2 - x1), abs(y2 - y1))\n    }\n\n    data class Page(\n        val number: Int,\n        val width: Float,\n        val height: Float,\n        val glyphs: List<Glyph>,\n        val rules: List<Rule> = emptyList()\n    )\n''',
    "Page model",
)

core = replace_once(
    core,
    '''        val columns = findGroupColumns(page, lines)\n        val column = columns.firstOrNull { it.group == targetGroup } ?: return emptyList()\n        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f\n''',
    '''        val columns = findGroupColumns(page, lines)\n        val textColumn = columns.firstOrNull { it.group == targetGroup } ?: return emptyList()\n        val column = refineColumnFromGrid(page, textColumn)\n        val headerBottom = columns.maxOfOrNull { it.headerY }?.plus(7f) ?: page.height * .08f\n''',
    "lesson target column",
)

core = replace_once(
    core,
    '''                val top = if (i == 0) cycle.top else (anchorsInDay[i - 1].y + anchor.y) / 2f\n                val bottom = if (i == anchorsInDay.lastIndex) cycle.bottom else (anchor.y + anchorsInDay[i + 1].y) / 2f\n                if (bottom <= top + 2f) continue\n\n                val cellLines = extractCellLines(page.glyphs, column.left, column.right, top, bottom)\n''',
    '''                // Prefer the actual rectangle drawn by the timetable spreadsheet. MGSU PDFs\n                // contain unequal-height and merged rows, so midpoint geometry can shift a lesson\n                // into the preceding pair even when pair numbers themselves were detected correctly.\n                val exactGridBand = findGridCellBand(page, column, anchor, cycle)\n                val top = exactGridBand?.first\n                    ?: if (i == 0) cycle.top else (anchorsInDay[i - 1].y + anchor.y) / 2f\n                val bottom = exactGridBand?.second\n                    ?: if (i == anchorsInDay.lastIndex) cycle.bottom else (anchor.y + anchorsInDay[i + 1].y) / 2f\n                if (bottom <= top + 2f) continue\n\n                val cellLines = extractCellLines(page.glyphs, column.left, column.right, top, bottom)\n''',
    "lesson row band",
)

insert_before = '''    internal fun extractCellLines(\n        glyphs: List<Glyph>,\n'''
helpers = '''    /** Tighten a text-derived group column to the real vertical borders when available. */\n    private fun refineColumnFromGrid(page: Page, column: Column): Column {\n        if (page.rules.isEmpty()) return column\n        val candidates = page.rules.asSequence()\n            .filter { it.vertical && it.length >= 40f }\n            .filter { it.maxY >= column.headerY - 12f }\n            .map { (it.x1 + it.x2) / 2f }\n            .sorted()\n            .toList()\n        if (candidates.size < 2) return column\n        val xs = collapseCoordinates(candidates, 3.5f)\n        val left = xs.lastOrNull { it < column.center - 4f } ?: return column\n        val right = xs.firstOrNull { it > column.center + 4f } ?: return column\n        if (right - left !in 30f..(page.width * .55f)) return column\n        return column.copy(left = left + 0.8f, right = right - 0.8f)\n    }\n\n    /**\n     * Finds the exact horizontal borders of the cell containing [anchor]. A rule only counts\n     * when it visibly crosses the selected group's column; rules from time/pair columns or\n     * another group therefore cannot move a lesson.\n     */\n    private fun findGridCellBand(\n        page: Page,\n        column: Column,\n        anchor: PairAnchor,\n        cycle: DayCycle\n    ): Pair<Float, Float>? {\n        if (page.rules.isEmpty()) return null\n        val yValues = page.rules.asSequence()\n            .filter { it.horizontal && it.length >= 24f }\n            .filter { it.minX <= column.center - 2f && it.maxX >= column.center + 2f }\n            .map { (it.y1 + it.y2) / 2f }\n            .filter { it >= cycle.top - 18f && it <= cycle.bottom + 18f }\n            .sorted()\n            .toList()\n        if (yValues.size < 2) return null\n\n        // Thick spreadsheet borders are often emitted as two almost-identical rectangles/lines.\n        // Collapse them into one logical border before choosing the neighbours around the pair.\n        val ys = collapseCoordinates(yValues, 4.2f)\n        val top = ys.lastOrNull { it < anchor.y - 3f } ?: return null\n        val bottom = ys.firstOrNull { it > anchor.y + 3f } ?: return null\n        val height = bottom - top\n        if (height !in 12f..240f) return null\n        if (anchor.y <= top || anchor.y >= bottom) return null\n        return top to bottom\n    }\n\n    private fun collapseCoordinates(values: List<Float>, tolerance: Float): List<Float> {\n        if (values.isEmpty()) return emptyList()\n        val out = mutableListOf<MutableList<Float>>()\n        for (value in values.sorted()) {\n            val last = out.lastOrNull()\n            if (last == null || abs(value - last.average().toFloat()) > tolerance) {\n                out += mutableListOf(value)\n            } else {\n                last += value\n            }\n        }\n        return out.map { it.average().toFloat() }\n    }\n\n'''
if insert_before not in core:
    raise SystemExit("helper insertion point missing")
core = core.replace(insert_before, helpers + insert_before, 1)
core_path.write_text(core)

print("parser v9 grid-rule core patch applied")
