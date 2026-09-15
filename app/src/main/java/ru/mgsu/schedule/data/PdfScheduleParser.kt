package ru.mgsu.schedule.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File
import java.security.MessageDigest
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Parser for the table-style PDF schedules published by NIU MGSU.
 *
 * Important safety rules for parsing:
 *  - lesson times must be one of the official MGSU pair slots; a specialty code such as
 *    08.03.01 can therefore never become a fake time like 08:30-20:03;
 *  - student mode selects one exact canonical group column, never a fuzzy "course" column;
 *  - teacher mode only accepts an exact surname + two initials;
 *  - weekday labels are converted to vertical bands instead of assigning every row to the
 *    nearest single marker (the old behaviour could put most of a PDF onto one weekday).
 */
class PdfScheduleParser(private val profile: UserProfile) {
    private data class G(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val t: String)
    private data class Frag(val page: Int, val y: Float, val x1: Float, val x2: Float, val text: String) {
        val cx get() = (x1 + x2) / 2f
    }
    private data class SourcePage(val page: Int, val width: Float, val height: Float, val glyphs: List<G>, val frags: List<Frag>)
    private data class Column(val center: Float, val left: Float, val right: Float, val header: String, val canonicalGroup: String?)
    private data class TimeRow(val y: Float, val start: String, val end: String, val pairNo: Int)
    private data class DatedTimeRow(val row: TimeRow, val weekday: Int)
    private data class DayBand(val day: Int, val top: Float, val bottom: Float, val center: Float)
    private data class RawDayMarker(val day: Int, val top: Float, val bottom: Float, val center: Float)

    fun parse(file: File, sourceUrl: String, sourceLabel: String): List<ScheduleEvent> {
        PDDocument.load(file).use { doc ->
            val stripper = CoordinateStripper()
            stripper.sortByPosition = true
            stripper.getText(doc)
            val pages = stripper.glyphs.groupBy { it.page }.map { (pageNo, glyphs) ->
                val page = doc.getPage(pageNo - 1)
                SourcePage(pageNo, page.mediaBox.width, page.mediaBox.height, glyphs, fragments(glyphs))
            }
            return if (sourceLabel.startsWith("Экзамены")) parseExamPages(pages, sourceUrl, sourceLabel)
            else parseLessonPages(pages, sourceUrl, sourceLabel)
        }
    }

    private fun parseLessonPages(pages: List<SourcePage>, url: String, label: String): List<ScheduleEvent> {
        val out = mutableListOf<ScheduleEvent>()
        val targetTeacher = if (profile.role == UserRole.TEACHER) ScheduleParsingRules.canonicalTeacher(profile.teacher) else null
        if (profile.role == UserRole.TEACHER && targetTeacher == null) return emptyList()

        for (p in pages) {
            val columns = findColumns(p)
            if (columns.isEmpty()) continue
            val selected = selectedColumns(columns)
            if (selected.isEmpty()) continue
            val firstDataLeft = columns.minOf { it.left }
            val timeRows = findLessonTimeRows(p, firstDataLeft)
            if (timeRows.isEmpty()) continue
            val datedRows = assignWeekdays(p, timeRows)
            if (datedRows.isEmpty()) continue

            for ((index, dated) in datedRows.withIndex()) {
                val row = dated.row
                // Row boundaries are defined only by neighbouring official pair rows. This is
                // considerably more stable than using the centre of a vertically printed weekday
                // word as the row anchor.
                val top = if (index == 0) row.y - 30f else (datedRows[index - 1].row.y + row.y) / 2f
                val bottom = if (index == datedRows.lastIndex) row.y + 46f else (row.y + datedRows[index + 1].row.y) / 2f
                if (bottom <= top) continue

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
        return out
    }

    private fun parseExamPages(pages: List<SourcePage>, url: String, label: String): List<ScheduleEvent> {
        val out = mutableListOf<ScheduleEvent>()
        val targetTeacher = if (profile.role == UserRole.TEACHER) ScheduleParsingRules.canonicalTeacher(profile.teacher) else null
        if (profile.role == UserRole.TEACHER && targetTeacher == null) return emptyList()

        for (p in pages) {
            val columns = findColumns(p)
            if (columns.isEmpty()) continue
            val selected = selectedColumns(columns)
            if (selected.isEmpty()) continue
            val firstDataLeft = columns.minOf { it.left }
            val dateRows = p.frags
                .filter { f -> f.x1 < firstDataLeft && dateRegex.containsMatchIn(f.text) }
                .mapNotNull { f -> dateRegex.find(f.text)?.value?.let { raw -> parseDate(raw)?.let { d -> Triple(f.y, raw, d) } } }
                .groupBy { (it.first / 3f).toInt() }
                .values.map { it.first() }
                .sortedBy { it.first }
            if (dateRows.isEmpty()) continue

            for ((index, row) in dateRows.withIndex()) {
                val y = row.first
                val exactDate = row.third
                val top = if (index == 0) y - 24f else (dateRows[index - 1].first + y) / 2f
                val bottom = if (index == dateRows.lastIndex) y + 48f else (y + dateRows[index + 1].first) / 2f
                for (c in selected) {
                    val rawCell = cellText(p.glyphs, c, top, bottom)
                    if (rawCell.length < 3 || looksLikeHeader(rawCell)) continue
                    val cell = if (targetTeacher != null) isolateTeacherCell(rawCell, targetTeacher) else rawCell
                    if (cell.isBlank()) continue
                    val time = findExamTime(cell)
                    val event = toEvent(cell, c, null, exactDate.toString(), time, "", url, label, forceType = "Экзамен/зачёт")
                    if (event != null) out += event
                }
            }
        }
        return out
    }

    private fun findLessonTimeRows(p: SourcePage, firstDataLeft: Float): List<TimeRow> {
        val primary = p.frags.filter { it.x1 < firstDataLeft }
        val fallback = p.frags.filter { it.x1 < p.width * .26f }
        val candidates = (primary + fallback).distinctBy { "${it.page}:${(it.y / 2f).toInt()}:${it.text}" }
            .mapNotNull { f ->
                val normalized = ScheduleParsingRules.normalizeLessonTimeRange(f.text) ?: return@mapNotNull null
                val pair = ScheduleParsingRules.pairNumber(normalized.first, normalized.second) ?: return@mapNotNull null
                TimeRow(f.y, normalized.first, normalized.second, pair)
            }
            .groupBy { (it.y / 3f).toInt() }
            .values.map { bucket -> bucket.minByOrNull { it.y }!! }
            .sortedBy { it.y }
        return candidates
    }

    private fun selectedColumns(columns: List<Column>): List<Column> {
        if (profile.role == UserRole.TEACHER) return columns.filter { it.canonicalGroup != null }
        val target = ScheduleParsingRules.canonicalGroup(profile.group) ?: return emptyList()
        return columns.filter { it.canonicalGroup == target }.take(1)
    }

    private fun findColumns(p: SourcePage): List<Column> {
        // Group headers live in the top part of each page. Only fragments containing a real
        // institute/course/group identity are accepted. This intentionally rejects labels such as
        // "ИПГС 1 курс" because they do not identify one timetable column.
        val headerZone = p.frags.filter { it.y < p.height * .38f && it.x1 > p.width * .10f }
        val groupCandidates = headerZone.mapNotNull { f ->
            ScheduleParsingRules.canonicalGroup(f.text)?.let { g -> f to g }
        }
        if (groupCandidates.isEmpty()) return emptyList()

        val buckets = mutableListOf<MutableList<Pair<Frag, String>>>()
        for (candidate in groupCandidates.sortedBy { it.first.cx }) {
            val last = buckets.lastOrNull()
            val mean = last?.map { it.first.cx }?.average()?.toFloat()
            if (last != null && mean != null && abs(mean - candidate.first.cx) < 26f) last += candidate
            else buckets += mutableListOf(candidate)
        }
        val filtered = buckets.filter { it.isNotEmpty() }
        if (filtered.isEmpty()) return emptyList()
        val centers = filtered.map { it.map { p2 -> p2.first.cx }.average().toFloat() }

        return filtered.mapIndexed { i, bucket ->
            val center = centers[i]
            val left = if (i == 0) {
                (center - (if (centers.size > 1) (centers[1] - center) / 2f else 82f)).coerceAtLeast(p.width * .10f)
            } else (centers[i - 1] + center) / 2f
            val right = if (i == centers.lastIndex) {
                (center + (if (centers.size > 1) (center - centers[i - 1]) / 2f else 82f)).coerceAtMost(p.width)
            } else (center + centers[i + 1]) / 2f
            val canonical = bucket.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.key
            Column(center, left, right, canonical ?: bucket.first().second, canonical)
        }.filter { it.right - it.left > 16f }
    }

    /**
     * MGSU lesson tables repeat pair numbers 1..8 for every weekday. Splitting by pair-number
     * reset is deterministic even when the weekday word is printed vertically in a merged cell.
     * Detected weekday labels are used only to identify which weekday each cycle represents.
     */
    private fun assignWeekdays(p: SourcePage, rows: List<TimeRow>): List<DatedTimeRow> {
        if (rows.isEmpty()) return emptyList()
        val cycles = mutableListOf<MutableList<TimeRow>>()
        for (row in rows.sortedBy { it.y }) {
            val current = cycles.lastOrNull()
            if (current == null || (current.isNotEmpty() && row.pairNo <= current.last().pairNo)) {
                cycles += mutableListOf(row)
            } else {
                current += row
            }
        }
        if (cycles.isEmpty()) return emptyList()

        val detected = findDayBands(p).sortedBy { it.center }.map { it.day }.distinct()
        val days: List<Int> = when {
            // A standard weekly table has six cycles: Mon..Sat. This invariant is safer than
            // trusting PDF glyph coordinates from a rotated weekday label.
            cycles.size == 6 -> (1..6).toList()
            detected.size == cycles.size -> detected
            detected.isNotEmpty() -> {
                val start = detected.first().coerceIn(1, 7)
                List(cycles.size) { i -> (start + i).coerceAtMost(7) }
            }
            else -> List(cycles.size) { i -> (1 + i).coerceAtMost(7) }
        }

        return cycles.flatMapIndexed { index, cycle ->
            val day = days.getOrElse(index) { (index + 1).coerceAtMost(7) }
            cycle.map { DatedTimeRow(it, day) }
        }
    }

    private fun findDayBands(p: SourcePage): List<DayBand> {
        val raw = mutableListOf<RawDayMarker>()

        // Horizontal weekday labels, if present.
        p.frags.filter { it.x1 < p.width * .24f }.forEach { f ->
            val compact = norm(f.text).replace(" ", "")
            weekdays.forEach { (name, day) ->
                if (compact == name || compact.startsWith(name)) raw += RawDayMarker(day, f.y - 5f, f.y + 5f, f.y)
            }
        }

        // Most MGSU lesson PDFs print weekday names vertically in a merged first column.
        val leftGlyphs = p.glyphs.filter { it.x < p.width * .14f && it.t.any(Char::isLetter) }
        for (bucket in leftGlyphs.groupBy { (it.x / 6f).toInt() }.values) {
            val chars = bucket.sortedBy { it.y }.flatMap { g ->
                norm(g.t).filter(Char::isLetter).map { ch -> ch to g.y }
            }
            if (chars.size < 4) continue
            val text = chars.joinToString("") { it.first.toString() }
            weekdays.forEach { (name, day) ->
                var from = 0
                while (from < text.length) {
                    val at = text.indexOf(name, from)
                    if (at < 0 || at + name.length > chars.size) break
                    val ys = chars.subList(at, at + name.length).map { it.second }
                    raw += RawDayMarker(day, ys.minOrNull()!!, ys.maxOrNull()!!, ys.average().toFloat())
                    from = at + name.length
                }
            }
        }

        if (raw.isEmpty()) return emptyList()
        // Prefer the marker with the largest vertical span for each weekday (usually the real
        // merged day cell), then turn marker centers into non-overlapping bands.
        val chosen = raw.groupBy { it.day }.values.map { markers ->
            markers.maxByOrNull { it.bottom - it.top } ?: markers.first()
        }.sortedBy { it.center }

        return chosen.mapIndexed { i, marker ->
            val top = if (i == 0) min(marker.top - 12f, marker.center - 26f)
            else (chosen[i - 1].center + marker.center) / 2f
            val bottom = if (i == chosen.lastIndex) max(marker.bottom + 24f, marker.center + 38f)
            else (marker.center + chosen[i + 1].center) / 2f
            DayBand(marker.day, top.coerceAtLeast(0f), bottom.coerceAtMost(p.height), marker.center)
        }
    }

    /**
     * If a cell contains several alternating-week entries, keep only the segment around the
     * requested teacher. This also guarantees that a teacher profile cannot inherit another
     * teacher's name from the first line of a merged cell.
     */
    private fun isolateTeacherCell(cell: String, targetTeacher: String): String {
        if (!ScheduleParsingRules.matchesTeacher(cell, targetTeacher)) return ""

        // Alternating-week cells often contain two complete entries. Split at lesson-kind markers
        // and keep only the segment that actually names the selected teacher.
        val starts = classStartRegex.findAll(cell).map { it.range.first }.distinct().sorted().toList()
        if (starts.size >= 2) {
            for (i in starts.indices) {
                val from = starts[i]
                val to = starts.getOrNull(i + 1) ?: cell.length
                val segment = cell.substring(from, to).trim()
                if (ScheduleParsingRules.matchesTeacher(segment, targetTeacher)) return segment
            }
        }
        return cell
    }

    private fun cellText(glyphs: List<G>, c: Column, top: Float, bottom: Float): String {
        val items = glyphs.filter { g -> g.x + g.w / 2 in c.left..c.right && g.y in top..bottom }
            .sortedWith(compareBy<G> { (it.y / 3f).toInt() }.thenBy { it.x })
        if (items.isEmpty()) return ""
        val sb = StringBuilder()
        var lastY = items.first().y
        var lastX = items.first().x
        var lastW = 0f
        for (g in items) {
            if (abs(g.y - lastY) > 4f) sb.append(' ')
            else if (g.x - (lastX + lastW) > maxOf(3.2f, g.h * .32f)) sb.append(' ')
            sb.append(g.t)
            lastY = g.y; lastX = g.x; lastW = g.w
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun toEvent(
        cell: String,
        column: Column,
        weekday: Int?,
        exactDate: String?,
        start: String,
        end: String,
        url: String,
        label: String,
        forceType: String? = null
    ): ScheduleEvent? {
        var cleaned = cell.replace(Regex("\\s+"), " ").trim(' ', '-', '|')
        if (cleaned.length < 3 || looksLikeHeader(cleaned)) return null

        val targetTeacher = if (profile.role == UserRole.TEACHER) ScheduleParsingRules.canonicalTeacher(profile.teacher) else null
        if (targetTeacher != null && !ScheduleParsingRules.matchesTeacher(cleaned, targetTeacher)) return null
        val foundTeacher = if (targetTeacher != null) targetTeacher else ScheduleParsingRules.findTeacher(cleaned)

        val type = forceType ?: when {
            cleaned.contains("экзам", true) -> "Экзамен"
            cleaned.contains("зачет", true) || cleaned.contains("зачёт", true) -> "Зачёт"
            Regex("(^|\\s)(л\\.|лекц)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Лекция"
            Regex("(^|\\s)(пр\\.|практ)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Практика"
            Regex("(^|\\s)(лаб\\.|лабор)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Лабораторная"
            else -> "Занятие"
        }

        val room = roomRegex.findAll(cleaned).lastOrNull()?.value?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        val weeks = ScheduleParsingRules.extractWeekNumbers(cleaned)
        val parity = ScheduleParsingRules.extractParity(cleaned)

        // Remove structured metadata from the visible title. The old parser left strings such as
        // "203 А УЛБ с 3 нед" as the subject when cell boundaries were slightly off.
        cleaned = ScheduleParsingRules.removeWeekSpecs(cleaned)
        cleaned = ScheduleParsingRules.removeTeachers(cleaned)
        if (room.isNotBlank()) cleaned = cleaned.replace(room, " ")
        cleaned = cleaned
            .replace(specialtyCodeRegex, " ")
            .replace(Regex("(?i)\\b(?:неч[её]т(?:ная|ные|н)?|ч[её]т(?:ная|ные|н)?|числитель|знаменатель)\\b"), " ")
            .replace(Regex("(?i)(^|\\s)(?:лекция|практика|лабораторная|экзамен|зач[её]т|л\\.|пр\\.|лаб\\.)\\s*"), " ")
            .replace(Regex("\\s+"), " ")
            .trim(' ', ',', ';', '-', '|', '/')

        if (cleaned.length < 3) return null
        val normTitle = norm(cleaned)
        if (normTitle.contains("УТВЕРЖДАЮ") || normTitle.startsWith("РАСПИСАНИЕ") || normTitle == "ГРУППА") return null
        if (specialtyCodeRegex.containsMatchIn(cleaned)) return null
        if (cleaned.matches(Regex("(?i)^\\d{2,4}\\s*[А-ЯA-Z]?\\s*/?\\s*(КМК|УЛК|УЛБ|КПА|ЛАБ).*$"))) return null

        val group = column.canonicalGroup ?: ScheduleParsingRules.canonicalGroup(column.header).orEmpty()
        if (group.isBlank()) return null
        val raw = "$cleaned|$group|$weekday|$exactDate|$start|$end|$foundTeacher|$room|${weeks.sorted()}|$parity"
        return ScheduleEvent(
            id = sha1(raw),
            title = cleaned.take(180),
            type = type,
            teacher = foundTeacher,
            group = group,
            room = room,
            weekday = weekday,
            exactDate = exactDate,
            startTime = start,
            endTime = end,
            weekNumbers = weeks,
            weekParity = parity,
            sourceUrl = url,
            sourceLabel = label
        )
    }

    private fun findExamTime(cell: String): String {
        // Prefer colon notation. It cannot be confused with curriculum codes such as 08.03.01.
        colonTimeRegex.find(cell)?.let { return normalizeClock(it.value) }
        // Some older PDFs use 10.00. Only accept a dot time when it is an official pair start.
        dotTimeRegex.findAll(cell).forEach { m ->
            val value = normalizeClock(m.value)
            if (ScheduleParsingRules.officialPairTimes.values.any { it.first == value }) return value
        }
        return ""
    }

    private fun looksLikeHeader(s: String): Boolean {
        val n = norm(s)
        return n.contains("УТВЕРЖДАЮ") || n.contains("РАСПИСАНИЕ УЧЕБНЫХ") || n == "ГРУППА" || n.length < 2
    }

    private fun fragments(glyphs: List<G>): List<Frag> {
        val lines = glyphs.groupBy { (it.y / 3f).toInt() }
        val out = mutableListOf<Frag>()
        for ((_, lineGlyphs) in lines) {
            val sorted = lineGlyphs.sortedBy { it.x }
            if (sorted.isEmpty()) continue
            var current = mutableListOf(sorted.first())
            fun flush() {
                if (current.isEmpty()) return
                val text = joinGlyphs(current)
                if (text.isNotBlank()) {
                    out += Frag(
                        current[0].page,
                        current.map { it.y }.average().toFloat(),
                        current.minOf { it.x },
                        current.maxOf { it.x + it.w },
                        text
                    )
                }
                current = mutableListOf()
            }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]
                val g = sorted[i]
                val gap = g.x - (prev.x + prev.w)
                if (gap > maxOf(18f, g.h * 1.7f)) {
                    flush(); current += g
                } else current += g
            }
            flush()
        }
        return out.sortedWith(compareBy<Frag> { it.page }.thenBy { it.y }.thenBy { it.x1 })
    }

    private fun joinGlyphs(gs: List<G>): String {
        val sb = StringBuilder()
        var last: G? = null
        for (g in gs) {
            val p = last
            if (p != null && g.x - (p.x + p.w) > maxOf(3f, g.h * .30f)) sb.append(' ')
            sb.append(g.t)
            last = g
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private fun parseDate(raw: String): LocalDate? {
        val clean = raw.trim().trimEnd('.')
        val patterns = listOf("d.M.yyyy", "dd.MM.yyyy", "d.M.yy", "dd.MM.yy")
        val semesterYear = runCatching { LocalDate.parse(profile.semesterStart).year }.getOrElse { LocalDate.now().year }
        for (pattern in patterns) {
            val date = runCatching { LocalDate.parse(clean, DateTimeFormatter.ofPattern(pattern)) }.getOrNull() ?: continue
            // Reject curriculum codes accidentally shaped like dates (e.g. 08.03.01 -> 2001).
            if (date.year !in (semesterYear - 1)..(semesterYear + 1)) continue
            return date
        }
        return null
    }

    private class CoordinateStripper : PDFTextStripper() {
        val glyphs = mutableListOf<G>()
        private var pageNo = 0
        override fun startPage(page: PDPage?) {
            pageNo++
            super.startPage(page)
        }
        override fun processTextPosition(text: TextPosition) {
            val u = text.unicode ?: return
            if (u.isNotBlank()) glyphs += G(pageNo, text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.heightDir, u)
        }
    }

    companion object {
        private val dateRegex = Regex("(?<!\\d)\\d{1,2}[.]\\d{1,2}[.](?:20)?\\d{2}(?!\\d)")
        private val colonTimeRegex = Regex("(?<!\\d)(?:[01]?\\d|2[0-3]):[0-5]\\d(?!\\d)")
        private val dotTimeRegex = Regex("(?<!\\d)(?:[01]?\\d|2[0-3])[.][0-5]\\d(?![.]?\\d)")
        private val roomRegex = Regex("(?i)\\b\\d{2,4}(?:[.]\\d+)?\\s*[А-ЯA-Z]?\\s*(?:/\\s*)?(?:КМК|УЛК|УЛБ|КПА|ЛАБ)\\b")
        private val specialtyCodeRegex = Regex("\\b\\d{2}[.]\\d{2}[.]\\d{2}(?:[_A-Za-zА-Яа-я0-9-]+)?\\b")
        private val classStartRegex = Regex("(?i)(?:^|\\s)(?:КРП|КОП|л\\.|пр\\.|лаб\\.|лекц(?:ия)?|практ(?:ика)?|лабор(?:аторная)?)\\s*")
        private val weekdays = mapOf(
            "ПОНЕДЕЛЬНИК" to DayOfWeek.MONDAY.value,
            "ВТОРНИК" to DayOfWeek.TUESDAY.value,
            "СРЕДА" to DayOfWeek.WEDNESDAY.value,
            "ЧЕТВЕРГ" to DayOfWeek.THURSDAY.value,
            "ПЯТНИЦА" to DayOfWeek.FRIDAY.value,
            "СУББОТА" to DayOfWeek.SATURDAY.value
        )

        private fun norm(s: String) = s.uppercase().replace('Ё', 'Е')
            .replace(Regex("[^А-ЯA-Z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun normalizeClock(value: String): String {
            val parts = value.replace('.', ':').split(':')
            return "%02d:%02d".format(parts[0].toInt(), parts[1].toInt())
        }
        private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1")
            .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
