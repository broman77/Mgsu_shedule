from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


parser_path = Path("app/src/main/java/ru/mgsu/schedule/data/PdfScheduleParser.kt")
text = parser_path.read_text()

text = replace_once(
    text,
    "class PdfScheduleParser(private val profile: UserProfile) {",
    '''internal object LessonRowGeometry {
    data class Anchor(val y: Float, val startsAtTop: Boolean)

    fun band(anchors: List<Anchor>, index: Int, pageHeight: Float): Pair<Float, Float>? {
        if (index !in anchors.indices) return null
        val tops = estimatedTops(anchors)
        val top = tops[index].coerceAtLeast(0f)
        val bottom = if (index < anchors.lastIndex) {
            tops[index + 1]
        } else {
            tops[index] + localGap(anchors, index)
        }.coerceAtMost(pageHeight)
        return if (bottom > top + 3f) top to bottom else null
    }

    internal fun estimatedTops(anchors: List<Anchor>): List<Float> = anchors.indices.map { index ->
        val gap = localGap(anchors, index)
        val shift = if (anchors[index].startsAtTop) {
            // Current MGSU PDFs print 08.30 and 09.50 on separate lines. The 08.30 baseline is
            // close to the TOP of the table cell, not its vertical centre. Midpoint boundaries
            // therefore leak the previous lesson into an empty following row (IAG 1-41 exposed
            // this as five Monday lessons instead of the real 08:30, 10:00 and 13:00 lessons).
            (gap * 0.20f).coerceIn(4f, 9f)
        } else {
            gap / 2f
        }
        anchors[index].y - shift
    }

    private fun localGap(anchors: List<Anchor>, index: Int): Float {
        val gaps = buildList {
            if (index > 0) (anchors[index].y - anchors[index - 1].y).takeIf { it in 12f..120f }?.let(::add)
            if (index < anchors.lastIndex) (anchors[index + 1].y - anchors[index].y).takeIf { it in 12f..120f }?.let(::add)
        }
        return gaps.minOrNull() ?: 32f
    }
}

class PdfScheduleParser(private val profile: UserProfile) {''',
    "insert row geometry helper",
)

text = replace_once(
    text,
    "private data class TimeRow(val y: Float, val start: String, val end: String, val pairNo: Int)",
    "private data class TimeRow(val y: Float, val start: String, val end: String, val pairNo: Int, val startsAtTop: Boolean)",
    "extend TimeRow",
)

text = replace_once(
    text,
    '''            for ((index, dated) in datedRows.withIndex()) {
                val row = dated.row
                // Row boundaries are defined only by neighbouring official pair rows. This is
                // considerably more stable than using the centre of a vertically printed weekday
                // word as the row anchor.
                val top = if (index == 0) row.y - 30f else (datedRows[index - 1].row.y + row.y) / 2f
                val bottom = if (index == datedRows.lastIndex) row.y + 46f else (row.y + datedRows[index + 1].row.y) / 2f
                if (bottom <= top) continue
''',
    '''            val rowAnchors = datedRows.map { LessonRowGeometry.Anchor(it.row.y, it.row.startsAtTop) }
            for ((index, dated) in datedRows.withIndex()) {
                val row = dated.row
                val (top, bottom) = LessonRowGeometry.band(rowAnchors, index, p.height) ?: continue
''',
    "replace midpoint row boundaries",
)

text = replace_once(
    text,
    '''        val leftLimit = maxOf(firstDataLeft, p.width * .22f).coerceAtMost(p.width * .34f)
        val leftFrags = p.frags.filter { it.x1 < leftLimit }
''',
    '''        // The time column is strictly to the left of the first student-group column.
        // Do not broaden this region into the first group: subject text there can distort the
        // detected time-row baseline and is the root cause of several false empty-row lessons.
        val leftFrags = p.frags.filter { it.x2 <= firstDataLeft + 3f }
''',
    "restrict time column",
)

text = replace_once(
    text,
    "if (no != null) rows += TimeRow(y, range.first, range.second, no)",
    "if (no != null) rows += TimeRow(y, range.first, range.second, no, startsAtTop = false)",
    "full-range anchor mode",
)

text = replace_once(
    text,
    "if (match != null) rows += TimeRow(y, match.value.first, match.value.second, match.key)",
    "if (match != null) rows += TimeRow(y, match.value.first, match.value.second, match.key, startsAtTop = true)",
    "split-time anchor mode",
)

parser_path.write_text(text)

store_path = Path("app/src/main/java/ru/mgsu/schedule/data/AppStore.kt")
store = store_path.read_text()
store = replace_once(store, "const val CURRENT_PARSER_VERSION = 6", "const val CURRENT_PARSER_VERSION = 7", "parser cache version")
store_path.write_text(store)

changelog_path = Path("CHANGELOG.md")
changelog = changelog_path.read_text()
entry = '''## Parser v7 — IAG table row boundary fix\n\n- Fixed false lessons leaking into empty rows when MGSU prints start/end time on separate lines.\n- Time-row detection is now restricted to the real `Часы` column instead of overlapping the first group column.\n- Bumped parser cache version so previously misparsed schedules are rebuilt.\n\n'''
if "## Parser v7 — IAG table row boundary fix" not in changelog:
    changelog_path.write_text(entry + changelog)

print("IAG row-boundary parser fix applied")
