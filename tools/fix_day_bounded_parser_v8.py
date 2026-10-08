from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly 1 match, found {count}")
    return text.replace(old, new, 1)

parser_path = Path("app/src/main/java/ru/mgsu/schedule/data/PdfScheduleParser.kt")
text = parser_path.read_text()

old = '''            val datedRows = assignWeekdays(p, timeRows)
            if (datedRows.isEmpty()) continue

            val rowAnchors = datedRows.map { LessonRowGeometry.Anchor(it.row.y, it.row.startsAtTop) }
            for ((index, dated) in datedRows.withIndex()) {
                val row = dated.row
                val (top, bottom) = LessonRowGeometry.band(rowAnchors, index, p.height) ?: continue

                for (c in selected) {
                    val rawCell = cellText(p.glyphs, c, top, bottom)
                    if (rawCell.length < 3 || looksLikeHeader(rawCell)) continue
                    val cell = if (targetTeacher != null) isolateTeacherCell(rawCell, targetTeacher) else rawCell
                    if (cell.isBlank()) continue
                    val event = toEvent(cell, c, dated.weekday, null, row.start, row.end, url, label)
                    if (event != null) out += event
                }
            }
'''
new = '''            val datedRows = assignWeekdays(p, timeRows)
            if (datedRows.isEmpty()) continue

            // Never calculate pair-cell boundaries across a weekday boundary. The v7 parser used
            // one global anchor list for the whole page, so the last pair of Monday could extend
            // down toward Tuesday's first pair. On an empty 19:40 row that captured the first
            // glyphs of the next day and created a fake lesson ending at 21:00. Build geometry
            // independently for each weekday and, when weekday markers are available, clamp every
            // cell to that day's visual band as a second guard.
            val visualDayBands = findDayBands(p).associateBy { it.day }
            for ((weekday, unsortedDayRows) in datedRows.groupBy { it.weekday }) {
                val dayRows = unsortedDayRows.sortedBy { it.row.y }
                val rowAnchors = dayRows.map { LessonRowGeometry.Anchor(it.row.y, it.row.startsAtTop) }
                val dayBand = visualDayBands[weekday]

                for ((index, dated) in dayRows.withIndex()) {
                    val row = dated.row
                    val rawBand = LessonRowGeometry.band(rowAnchors, index, p.height) ?: continue
                    val top = max(rawBand.first, dayBand?.top ?: 0f)
                    val bottom = min(rawBand.second, dayBand?.bottom ?: p.height)
                    if (bottom <= top + 3f) continue

                    for (c in selected) {
                        val rawCell = cellText(p.glyphs, c, top, bottom)
                        if (rawCell.length < 3 || looksLikeHeader(rawCell)) continue
                        val cell = if (targetTeacher != null) isolateTeacherCell(rawCell, targetTeacher) else rawCell
                        if (cell.isBlank()) continue
                        val event = toEvent(cell, c, dated.weekday, null, row.start, row.end, url, label)
                        if (event != null) out += event
                    }
                }
            }
'''
text = replace_once(text, old, new, "day-bounded row geometry")

old = '''    private fun assignWeekdays(p: SourcePage, rows: List<TimeRow>): List<DatedTimeRow> {
        if (rows.isEmpty()) return emptyList()
        val cycles = mutableListOf<MutableList<TimeRow>>()
'''
new = '''    private fun assignWeekdays(p: SourcePage, rows: List<TimeRow>): List<DatedTimeRow> {
        if (rows.isEmpty()) return emptyList()

        // Prefer the actual weekday bands printed in the first column. This avoids depending on
        // an exact 1..8 reset pattern: some MGSU files omit unused time labels or start an evening
        // day at pair 7. Only fall back to pair-number cycles when the visual markers are too weak.
        val bands = findDayBands(p).sortedBy { it.center }
        if (bands.isNotEmpty()) {
            val direct = rows.sortedBy { it.y }.mapNotNull { row ->
                bands.firstOrNull { band -> row.y >= band.top - 4f && row.y <= band.bottom + 4f }
                    ?.let { band -> DatedTimeRow(row, band.day) }
            }
            val required = maxOf(1, (rows.size * 3 + 3) / 4) // at least 75% confidently assigned
            if (direct.size >= required) return direct
        }

        val cycles = mutableListOf<MutableList<TimeRow>>()
'''
text = replace_once(text, old, new, "weekday band assignment")

parser_path.write_text(text)

store_path = Path("app/src/main/java/ru/mgsu/schedule/data/AppStore.kt")
store = store_path.read_text()
store = replace_once(store, "const val CURRENT_PARSER_VERSION = 7", "const val CURRENT_PARSER_VERSION = 8", "parser version")
store_path.write_text(store)

# Extend the existing row-geometry regression test with a cross-day leakage case.
test_path = Path("app/src/test/java/ru/mgsu/schedule/data/LessonRowGeometryTest.kt")
test = test_path.read_text()
insert = '''

    @Test
    fun `last row of one day is bounded using only that day's anchors`() {
        // Monday has pairs 1..8; Tuesday starts immediately below. The old page-global geometry
        // used Tuesday's first anchor as the bottom of Monday pair 8, allowing Tuesday text to
        // become a fake Monday 19:40 lesson. Per-day geometry must end pair 8 by Monday's own gap.
        val monday = listOf(
            LessonRowGeometry.Anchor(100f, true),
            LessonRowGeometry.Anchor(132f, true),
            LessonRowGeometry.Anchor(164f, true),
            LessonRowGeometry.Anchor(196f, true),
            LessonRowGeometry.Anchor(228f, true),
            LessonRowGeometry.Anchor(260f, true),
            LessonRowGeometry.Anchor(292f, true),
            LessonRowGeometry.Anchor(324f, true)
        )
        val pair8 = LessonRowGeometry.band(monday, 7, 700f)!!
        assertTrue(pair8.second < 356f)
        assertTrue(pair8.second > 324f)
    }
'''
marker = "\n}\n"
pos = test.rfind(marker)
if pos < 0:
    raise SystemExit("test class closing brace not found")
test = test[:pos] + insert + test[pos:]
test_path.write_text(test)

changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text()
entry = '''\n### Parser v8 — day-bounded lesson cells\n- Lesson-cell geometry is now calculated independently inside each weekday instead of across the whole PDF page.\n- Weekday labels are used directly to assign time rows when reliable, with pair-number cycles kept only as a fallback.\n- Prevents an empty 19:40 row from swallowing the next day's first lesson and falsely showing classes through 21:00 across many groups.\n- Parser cache version bumped to 8 so devices rebuild previously corrupted schedules.\n'''
if "Parser v8 — day-bounded lesson cells" not in changelog:
    changelog = entry + changelog
changelog_path.write_text(changelog)
