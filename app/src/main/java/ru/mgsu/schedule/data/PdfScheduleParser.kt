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

/**
 * Heuristic parser for the table-style PDFs currently published by NIU MGSU.
 * It uses PDF text coordinates, not fixed filenames, so renamed PDF files remain supported.
 */
class PdfScheduleParser(private val profile: UserProfile) {
    private data class G(val page: Int, val x: Float, val y: Float, val w: Float, val h: Float, val t: String)
    private data class Frag(val page: Int, val y: Float, val x1: Float, val x2: Float, val text: String) { val cx get() = (x1 + x2) / 2f }
    private data class SourcePage(val page: Int, val width: Float, val height: Float, val glyphs: List<G>, val frags: List<Frag>)
    private data class Column(val center: Float, val left: Float, val right: Float, val header: String)

    fun parse(file: File, sourceUrl: String, sourceLabel: String): List<ScheduleEvent> {
        PDDocument.load(file).use { doc ->
            val stripper = CoordinateStripper()
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
        for (p in pages) {
            val columns = findColumns(p)
            if (columns.isEmpty()) continue
            val selected = selectedColumns(columns)
            val firstDataLeft = columns.minOf { it.left }
            var timeFragments = p.frags.filter { f -> f.x1 < firstDataLeft && timeRegex.containsMatchIn(f.text) }
            if (timeFragments.isEmpty()) timeFragments = p.frags.filter { f -> f.x1 < p.width * .25f && timeRegex.containsMatchIn(f.text) }
            val timeRows = timeFragments.mapNotNull { f -> timeRegex.find(f.text)?.let { f.y to it } }
                .groupBy { (it.first / 3).toInt() }.values.map { bucket -> bucket.minBy { it.first } }.sortedBy { it.first }
            if (timeRows.isEmpty()) continue
            val dayMarkers = findDayMarkers(p)

            for ((index, pair) in timeRows.withIndex()) {
                val y = pair.first
                val match = pair.second
                val top = if (index == 0) y - 22 else (timeRows[index - 1].first + y) / 2f
                val bottom = if (index == timeRows.lastIndex) y + 34 else (y + timeRows[index + 1].first) / 2f
                val weekday = dayForY(dayMarkers, y) ?: continue
                val start = normalizeTime(match.groupValues[1])
                val end = normalizeTime(match.groupValues[2])

                for (c in selected) {
                    val cell = cellText(p.glyphs, c, top, bottom)
                    if (cell.length < 3 || looksLikeHeader(cell)) continue
                    if (profile.role == UserRole.TEACHER && !matchesTeacher(cell, profile.teacher)) continue
                    val event = toEvent(cell, c.header, weekday, null, start, end, url, label)
                    if (event != null) out += event
                }
            }
        }
        return out
    }

    private fun parseExamPages(pages: List<SourcePage>, url: String, label: String): List<ScheduleEvent> {
        val out = mutableListOf<ScheduleEvent>()
        for (p in pages) {
            val columns = findColumns(p)
            if (columns.isEmpty()) continue
            val selected = selectedColumns(columns)
            val firstDataLeft = columns.minOf { it.left }
            var dateFragments = p.frags.filter { f -> f.x1 < firstDataLeft && dateRegex.containsMatchIn(f.text) }
            if (dateFragments.isEmpty()) dateFragments = p.frags.filter { f -> f.x1 < p.width * .25f && dateRegex.containsMatchIn(f.text) }
            val dateRows = dateFragments.mapNotNull { f -> dateRegex.find(f.text)?.let { f.y to it.value } }
                .groupBy { (it.first / 3).toInt() }.values.map { bucket -> bucket.first() }.sortedBy { it.first }
            for ((index, row) in dateRows.withIndex()) {
                val y = row.first
                val exactDate = parseDate(row.second) ?: continue
                val top = if (index == 0) y - 20 else (dateRows[index - 1].first + y) / 2f
                val bottom = if (index == dateRows.lastIndex) y + 40 else (y + dateRows[index + 1].first) / 2f
                for (c in selected) {
                    val cell = cellText(p.glyphs, c, top, bottom)
                    if (cell.length < 3 || looksLikeHeader(cell)) continue
                    if (profile.role == UserRole.TEACHER && !matchesTeacher(cell, profile.teacher)) continue
                    val time = singleTimeRegex.find(cell)?.value?.replace('.', ':').orEmpty()
                    val cleaned = if (time.isNotBlank()) cell.replace(time.replace(':', '.'), "").replace(time, "") else cell
                    val event = toEvent(cleaned, c.header, null, exactDate.toString(), time, "", url, label, forceType = "Экзамен/зачёт")
                    if (event != null) out += event
                }
            }
        }
        return out
    }

    private fun selectedColumns(columns: List<Column>): List<Column> {
        return if (profile.role == UserRole.TEACHER) columns
        else {
            val query = norm(profile.group)
            val groupDigits = Regex("\\b\\d{1,3}\\b").findAll(query).map { it.value }.toList()
            columns.map { c ->
                val h = norm(c.header)
                val tokenScore = query.split(' ').filter { it.length >= 2 }.count { h.contains(it) }
                val digitScore = groupDigits.count { h.contains(it) } * 2
                c to (tokenScore + digitScore + if (h.contains(norm(profile.institute))) 2 else 0)
            }.filter { it.second > 0 }.sortedByDescending { it.second }.take(2).map { it.first }
                .ifEmpty { columns.filter { norm(it.header).contains(norm(profile.institute)) }.take(2) }
        }
    }

    private fun findColumns(p: SourcePage): List<Column> {
        val headerZone = p.frags.filter { it.y < p.height * 0.35f && it.x1 > p.width * 0.12f }
        val headerCandidates = headerZone.filter { f ->
            val n = norm(f.text)
            instituteTokens.any { n.contains(it) } || Regex("\\b[1-6][КK]\\b").containsMatchIn(n)
        }
        val centers = headerCandidates.sortedBy { it.cx }.fold(mutableListOf<MutableList<Frag>>()) { acc, f ->
            val bucket = acc.lastOrNull()
            if (bucket != null && abs(bucket.map { it.cx }.average().toFloat() - f.cx) < 28f) bucket += f
            else acc += mutableListOf(f)
            acc
        }.filter { it.isNotEmpty() }
        if (centers.isEmpty()) return emptyList()
        val cs = centers.map { it.map { f -> f.cx }.average().toFloat() }
        return centers.mapIndexed { i, bucket ->
            val center = cs[i]
            val left = if (i == 0) (center - (if (cs.size > 1) (cs[1] - center) / 2 else 80f)).coerceAtLeast(p.width * .12f)
            else (cs[i - 1] + center) / 2f
            val right = if (i == cs.lastIndex) (center + (if (cs.size > 1) (center - cs[i - 1]) / 2 else 80f)).coerceAtMost(p.width)
            else (center + cs[i + 1]) / 2f
            Column(center, left, right, bucket.joinToString(" ") { it.text }.trim())
        }.filter { it.right - it.left > 15f }
    }

    private fun findDayMarkers(p: SourcePage): Map<Int, Float> {
        val collected = mutableMapOf<Int, MutableList<Float>>()
        p.frags.filter { it.x1 < p.width * .22f }.forEach { f ->
            val n = norm(f.text).replace(" ", "")
            weekdays.forEach { (name, day) ->
                if (n == name || n.startsWith(name)) collected.getOrPut(day) { mutableListOf() } += f.y
            }
        }
        val direct = collected.mapValues { (_, ys) -> ys.sorted()[ys.size / 2] }.toMutableMap()
        if (direct.size >= 2) return direct

        // Some MGSU PDFs print weekday names vertically in one merged table cell.
        // Reconstruct only glyphs that share nearly the same X coordinate; this avoids
        // accidentally assembling weekday names from unrelated text elsewhere on the page.
        val leftGlyphs = p.glyphs.filter { it.x < p.width * .12f && it.t.any(Char::isLetter) }
        val xBuckets = leftGlyphs.groupBy { (it.x / 6f).toInt() }
        for (bucket in xBuckets.values) {
            val chars = bucket.sortedBy { it.y }.flatMap { g ->
                norm(g.t).filter(Char::isLetter).map { ch -> ch to g.y }
            }
            val text = chars.joinToString("") { it.first.toString() }
            weekdays.forEach { (name, day) ->
                if (day in direct) return@forEach
                val at = text.indexOf(name)
                if (at >= 0 && at + name.length <= chars.size) {
                    direct[day] = chars.subList(at, at + name.length).map { it.second }.average().toFloat()
                }
            }
        }
        return direct
    }

    private fun dayForY(markers: Map<Int, Float>, y: Float): Int? {
        if (markers.isEmpty()) return null
        return markers.entries.minByOrNull { kotlin.math.abs(it.value - y) }?.key
    }

    private fun cellText(glyphs: List<G>, c: Column, top: Float, bottom: Float): String {
        val items = glyphs.filter { g -> g.x + g.w / 2 in c.left..c.right && g.y in top..bottom }
            .sortedWith(compareBy<G> { (it.y / 3).toInt() }.thenBy { it.x })
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
        cell: String, header: String, weekday: Int?, exactDate: String?, start: String, end: String,
        url: String, label: String, forceType: String? = null
    ): ScheduleEvent? {
        val cleaned = cell.replace(Regex("\\s+"), " ").trim(' ', '-', '|')
        if (cleaned.length < 3) return null
        val type = forceType ?: when {
            cleaned.contains("экзам", true) -> "Экзамен"
            cleaned.contains("зачет", true) || cleaned.contains("зачёт", true) -> "Зачёт"
            Regex("(^|\\s)(л\\.|лекц)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Лекция"
            Regex("(^|\\s)(пр\\.|практ)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Практика"
            Regex("(^|\\s)(лаб\\.|лабор)", RegexOption.IGNORE_CASE).containsMatchIn(cleaned) -> "Лабораторная"
            else -> "Занятие"
        }
        val room = roomRegex.findAll(cleaned).lastOrNull()?.value.orEmpty()
        val teacher = ScheduleParsingRules.findTeacher(cleaned).ifBlank { if (profile.role == UserRole.TEACHER) profile.teacher else "" }
        val weeks = ScheduleParsingRules.extractWeekNumbers(cleaned)
        val parity = ScheduleParsingRules.extractParity(cleaned)
        val title = cleaned
            .replace(weekListRegex, "")
            .replace(Regex("(?i)\\b(лекция|практика|лабораторная|экзамен|зач[её]т|л\\.|пр\\.|лаб\\.)\\b"), "")
            .replace(teacherSurnameFirstRegex, "")
            .replace(teacherInitialsFirstRegex, "")
            .replace(Regex("\\s+"), " ").trim(' ', ',', ';', '-')
            .take(180)
        val realTitle = title.ifBlank { cleaned.take(120) }
        val group = header.replace(Regex("\\s+"), " ").trim()
        val raw = "$realTitle|$group|$weekday|$exactDate|$start|$end|$teacher|$room|${weeks.sorted()}|$parity"
        return ScheduleEvent(
            id = sha1(raw), title = realTitle, type = type, teacher = teacher, group = group,
            room = room, weekday = weekday, exactDate = exactDate, startTime = start, endTime = end,
            weekNumbers = weeks, weekParity = parity, sourceUrl = url, sourceLabel = label
        )
    }

    private fun looksLikeHeader(s: String): Boolean {
        val n = norm(s)
        return n.contains("УТВЕРЖДАЮ") || n.contains("РАСПИСАНИЕ УЧЕБНЫХ") || n == "ГРУППА" || n.length < 2
    }

    private fun matchesTeacher(cell: String, teacher: String): Boolean =
        ScheduleParsingRules.matchesTeacher(cell, teacher)

    private fun findTeacher(text: String): String = ScheduleParsingRules.findTeacher(text)

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
                if (text.isNotBlank()) out += Frag(current[0].page, current.map { it.y }.average().toFloat(), current.minOf { it.x }, current.maxOf { it.x + it.w }, text)
                current = mutableListOf()
            }
            for (i in 1 until sorted.size) {
                val prev = sorted[i - 1]; val g = sorted[i]
                val gap = g.x - (prev.x + prev.w)
                if (gap > maxOf(18f, g.h * 1.7f)) { flush(); current += g } else current += g
            }
            flush()
        }
        return out.sortedWith(compareBy<Frag> { it.page }.thenBy { it.y }.thenBy { it.x1 })
    }

    private fun joinGlyphs(gs: List<G>): String {
        val sb = StringBuilder(); var last: G? = null
        for (g in gs) {
            val p = last
            if (p != null && g.x - (p.x + p.w) > maxOf(3f, g.h * .30f)) sb.append(' ')
            sb.append(g.t); last = g
        }
        return sb.toString().replace(Regex("\\s+"), " ").trim()
    }

    private class CoordinateStripper : PDFTextStripper() {
        val glyphs = mutableListOf<G>()
        private var pageNo = 0
        override fun startPage(page: PDPage?) { pageNo++; super.startPage(page) }
        override fun processTextPosition(text: TextPosition) {
            val u = text.unicode ?: return
            if (u.isNotBlank()) glyphs += G(pageNo, text.xDirAdj, text.yDirAdj, text.widthDirAdj, text.heightDir, u)
        }
    }

    companion object {
        private val timeRegex = Regex("(\\d{1,2}[.:]\\d{2})\\s*[-–—]\\s*(\\d{1,2}[.:]\\d{2})")
        private val singleTimeRegex = Regex("\\b(?:[01]?\\d|2[0-3])[.:][0-5]\\d\\b")
        private val dateRegex = Regex("\\b\\d{1,2}[.]\\d{1,2}[.](?:20)?\\d{2}\\b")
        private val weekListRegex = Regex("(?i)\\b(\\d{1,2}(?:\\s*[,;]\\s*\\d{1,2})+)\\s*нед")
        private val roomRegex = Regex("(?i)\\b\\d{2,4}(?:[.]\\d+)?\\s*[А-ЯA-Z]?\\s*(?:/\\s*)?(?:КМК|УЛК|УЛБ|ЛАБ)?\\b")
        private val teacherSurnameFirstRegex = Regex("(?i)(?:доц\\.|проф\\.|ст\\.пр\\.|пр\\.|асс\\.|зав\\.каф\\.)?\\s*[А-ЯЁ][а-яё-]{2,}\\s+[А-ЯЁ]\\.?\\s*[А-ЯЁ]\\.?")
        private val teacherInitialsFirstRegex = Regex("(?i)(?:доц\\.|проф\\.|ст\\.пр\\.|пр\\.|асс\\.|зав\\.каф\\.)?\\s*[А-ЯЁ]\\.?\\s*[А-ЯЁ]\\.?\\s+[А-ЯЁ][а-яё-]{2,}")
        private val instituteTokens = listOf("ИАГ", "ИПГС", "ИГЭС", "ИИЭСМ", "ИЦТМС", "ИЭУКСН", "ИИС", "IAG", "IPGS", "IGES", "IIESM", "ICTMS", "IZTMS")
        private val weekdays = mapOf(
            "ПОНЕДЕЛЬНИК" to DayOfWeek.MONDAY.value,
            "ВТОРНИК" to DayOfWeek.TUESDAY.value,
            "СРЕДА" to DayOfWeek.WEDNESDAY.value,
            "ЧЕТВЕРГ" to DayOfWeek.THURSDAY.value,
            "ПЯТНИЦА" to DayOfWeek.FRIDAY.value,
            "СУББОТА" to DayOfWeek.SATURDAY.value
        )
        private fun norm(s: String) = s.uppercase().replace('Ё', 'Е').replace(Regex("[^А-ЯA-Z0-9. ]"), " ").replace(Regex("\\s+"), " ").trim()
        private fun normalizeTime(s: String) = s.replace('.', ':')
        private fun parseDate(s: String): LocalDate? {
            val clean = s.trim().trimEnd('.')
            val patterns = listOf("d.M.yyyy", "dd.MM.yyyy", "d.M.yy", "dd.MM.yy")
            for (p in patterns) runCatching { return LocalDate.parse(clean, DateTimeFormatter.ofPattern(p)) }
            return null
        }
        private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
