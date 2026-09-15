package ru.mgsu.schedule.data

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Pure parsing/validation rules shared by the PDF parser, autocomplete, calendar filtering and
 * sanitizer. Keep this file Android-free so the important schedule invariants can be tested on CI.
 */
object ScheduleParsingRules {
    private val rolePrefix = Regex(
        "(?:доц\\.|проф\\.|ст\\.\\s*пр\\.|ст\\.пр\\.|преп\\.|асс\\.|зав\\.\\s*каф\\.|зав\\.каф\\.)\\s*",
        RegexOption.IGNORE_CASE
    )

    // Teacher names in official PDFs can be printed as "Молоткова П.А." or fully in
    // uppercase. We still require an uppercase first letter/initials and disallow a leading
    // hyphen so fragments such as "в-молоткова" can never become a profile.
    private val surnameFirstRegex = Regex(
        """(?<![А-ЯЁа-яё-])(?:доц\.|проф\.|ст\.\s*пр\.|ст\.пр\.|преп\.|асс\.|зав\.\s*каф\.|зав\.каф\.)?\s*([А-ЯЁ][А-ЯЁа-яё]{2,}(?:-[А-ЯЁ][А-ЯЁа-яё]{2,})?)\s+([А-ЯЁ])\.?\s*([А-ЯЁ])\.?"""
    )
    private val initialsFirstRegex = Regex(
        """(?<![А-ЯЁа-яё-])(?:доц\.|проф\.|ст\.\s*пр\.|ст\.пр\.|преп\.|асс\.|зав\.\s*каф\.|зав\.каф\.)?\s*([А-ЯЁ])\.?\s*([А-ЯЁ])\.?\s+([А-ЯЁ][А-ЯЁа-яё]{2,}(?:-[А-ЯЁ][А-ЯЁа-яё]{2,})?)"""
    )

    private val exactTeacherValueRegex = Regex(
        """^(?:доц\.|проф\.|ст\.\s*пр\.|ст\.пр\.|преп\.|асс\.|зав\.\s*каф\.|зав\.каф\.)?\s*(?:[А-ЯЁ][А-ЯЁа-яё]{2,}(?:-[А-ЯЁ][А-ЯЁа-яё]{2,})?\s+[А-ЯЁ]\.?\s*[А-ЯЁ]\.?|[А-ЯЁ]\.?\s*[А-ЯЁ]\.?\s+[А-ЯЁ][А-ЯЁа-яё]{2,}(?:-[А-ЯЁ][А-ЯЁа-яё]{2,})?)$"""
    )

    private val explicitWeekSpecRegex = Regex(
        "(?i)(\\d{1,2}(?:\\s*[-–—]\\s*\\d{1,2})?(?:\\s*[,;]\\s*\\d{1,2}(?:\\s*[-–—]\\s*\\d{1,2})?)*)\\s*(?:нед(?:ел[яиь])?\\.?|н\\.)"
    )
    private val untilWeekRegex = Regex("(?i)(?:до|по)\\s*(\\d{1,2})\\s*(?:нед(?:ел[яиь])?\\.?|н\\.)")
    private val fromWeekRegex = Regex("(?i)(?:с|начиная\\s+с)\\s*(\\d{1,2})\\s*(?:нед(?:ел[яиь])?\\.?|н\\.)")


    private val exactGroupValueRegex = Regex(
        "(?i)^(?:ИАГ|ИПГСс?|ИГЭСс?|ИИЭСМ|ИЦТМС|ИЭУКСН|ИИС(?:\\s+ОИАЭ)?|ИДО)\\s*[-–—]?\\s*(?:[1-6]|I{1,3}|IV|V|VI)\\s*(?:[кk]|курс)?\\s*[-–—]?\\s*\\d{1,3}$"
    )

    private val groupRegexes = listOf(
        // ИПГС 3к 18, ИПГС 3 к 18, ИПГС 3-18, ИАГ-2-1
        Regex("(?i)(?<![А-ЯЁа-яёA-Z0-9])(ИАГ|ИПГСс?|ИГЭСс?|ИИЭСМ|ИЦТМС|ИЭУКСН|ИИС(?:\\s+ОИАЭ)?|ИДО)\\s*[-–—]?\\s*([1-6])\\s*(?:[кk]|курс)?\\s*[-–—]?\\s*(\\d{1,3})(?!\\d)"),
        Regex("(?i)(?<![А-ЯЁа-яёA-Z0-9])(ИАГ|ИПГСс?|ИГЭСс?|ИИЭСМ|ИЦТМС|ИЭУКСН|ИИС(?:\\s+ОИАЭ)?|ИДО)\\s*[-–—]?\\s*(I{1,3}|IV|V|VI)\\s*[-–—]?\\s*(\\d{1,3})(?!\\d)")
    )

    /** Official lesson slots printed in the downloadable MGSU timetables. */
    val officialPairTimes: Map<Int, Pair<String, String>> = linkedMapOf(
        1 to ("08:30" to "09:50"),
        2 to ("10:00" to "11:20"),
        3 to ("11:30" to "12:50"),
        4 to ("13:00" to "14:20"),
        5 to ("14:30" to "15:50"),
        6 to ("16:00" to "17:20"),
        7 to ("18:10" to "19:30"),
        8 to ("19:40" to "21:00")
    )

    fun norm(s: String): String = s.uppercase().replace('Ё', 'Е')
        .replace(Regex("[^А-ЯA-Z0-9. -]"), " ").replace(Regex("\\s+"), " ").trim()

    /** Finds a teacher inside a cell/header. Use [isValidTeacher] for a saved profile value. */
    fun canonicalTeacher(raw: String): String? {
        val s = raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim(' ', ',', ';', '|')
        surnameFirstRegex.find(s)?.let { m ->
            return "${normalizeSurname(m.groupValues[1])} ${m.groupValues[2].uppercase()}.${m.groupValues[3].uppercase()}."
        }
        initialsFirstRegex.find(s)?.let { m ->
            return "${normalizeSurname(m.groupValues[3])} ${m.groupValues[1].uppercase()}.${m.groupValues[2].uppercase()}."
        }
        return null
    }

    /** Saved/autocomplete teacher values must be only the name, not text containing a name. */
    fun isValidTeacher(value: String): Boolean {
        val visible = value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim(' ', ',', ';', '|')
        if (!exactTeacherValueRegex.matches(visible)) return false
        return canonicalTeacher(visible) != null
    }

    fun extractTeachers(text: String): List<String> {
        val out = linkedSetOf<String>()
        surnameFirstRegex.findAll(text).forEach { canonicalTeacher(it.value)?.let(out::add) }
        initialsFirstRegex.findAll(text).forEach { canonicalTeacher(it.value)?.let(out::add) }
        return out.toList()
    }

    fun matchesTeacher(text: String, teacher: String): Boolean {
        val target = canonicalTeacher(teacher) ?: return false
        return extractTeachers(text).any { it == target }
    }

    fun findTeacher(text: String): String = extractTeachers(text).firstOrNull().orEmpty()

    /** Removes all recognized teacher names/roles from a timetable cell. */
    fun removeTeachers(text: String): String {
        var out = text
        out = out.replace(surnameFirstRegex, " ").replace(initialsFirstRegex, " ")
        out = out.replace(rolePrefix, " ")
        return out.replace(Regex("\\s+"), " ").trim()
    }

    /** Finds a concrete group inside a PDF header. */
    fun canonicalGroup(raw: String): String? {
        val s = raw.replace('\u00A0', ' ').replace(Regex("\\s+"), " ")
        for ((idx, regex) in groupRegexes.withIndex()) {
            val m = regex.find(s) ?: continue
            val institute = normalizeInstitute(m.groupValues[1])
            val course = if (idx == 0) m.groupValues[2].toIntOrNull() else romanToInt(m.groupValues[2])
            val groupNo = m.groupValues[3].toIntOrNull()
            if (course == null || course !in 1..6 || groupNo == null || groupNo !in 1..999) continue
            return "$institute $course-$groupNo"
        }
        return null
    }

    /** Saved/autocomplete group values are always canonical (e.g. "ИПГС 3-18"). */
    fun isValidGroup(value: String): Boolean {
        val visible = value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
        return exactGroupValueRegex.matches(visible) && canonicalGroup(visible) != null
    }

    fun extractGroups(text: String): List<String> {
        val out = linkedSetOf<String>()
        for (regex in groupRegexes) regex.findAll(text).forEach { m -> canonicalGroup(m.value)?.let(out::add) }
        return out.toList()
    }

    fun groupInstitute(value: String): String = canonicalGroup(value)?.substringBefore(' ').orEmpty()
    fun groupCourse(value: String): Int? = canonicalGroup(value)?.substringAfter(' ')?.substringBefore('-')?.toIntOrNull()

    fun extractWeekNumbers(text: String): List<Int> {
        val out = linkedSetOf<Int>()
        explicitWeekSpecRegex.findAll(text).forEach { m ->
            m.groupValues[1].split(',', ';').forEach { token ->
                val t = token.trim()
                val range = Regex("(\\d{1,2})\\s*[-–—]\\s*(\\d{1,2})").matchEntire(t)
                if (range != null) {
                    val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
                    if (a <= b) for (w in a..b) if (w in 1..30) out += w
                } else t.toIntOrNull()?.takeIf { it in 1..30 }?.let(out::add)
            }
        }
        untilWeekRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..30 }?.let { last ->
            for (w in 1..last) out += w
        }
        fromWeekRegex.find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()?.takeIf { it in 1..30 }?.let { first ->
            for (w in first..30) out += w
        }
        return out.toList().sorted()
    }

    fun removeWeekSpecs(text: String): String = text
        .replace(untilWeekRegex, " ").replace(fromWeekRegex, " ").replace(explicitWeekSpecRegex, " ")
        .replace(Regex("\\s+"), " ").trim()

    fun extractParity(text: String): String {
        val n = norm(text)
        val odd = n.contains("НЕЧЕТ") || n.contains("ЧИСЛ")
        val even = n.contains("ЧЕТН") || n.contains("ЗНАМ")
        return when { odd && !even -> "ODD"; even && !odd -> "EVEN"; else -> "ANY" }
    }

    fun validTime(value: String): Boolean = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(value)
    fun validDate(value: String): Boolean = runCatching { LocalDate.parse(value); true }.getOrDefault(false)

    /** Lesson rows are accepted only when both ends are an official pair range. */
    fun normalizeLessonTimeRange(raw: String): Pair<String, String>? {
        val m = Regex("(?<!\\d)(\\d{1,2})[.:](\\d{2})\\s*[-–—]\\s*(\\d{1,2})[.:](\\d{2})(?!\\d)").find(raw) ?: return null
        val sh = m.groupValues[1].toIntOrNull() ?: return null
        val eh = m.groupValues[3].toIntOrNull() ?: return null
        val sm = m.groupValues[2].toIntOrNull() ?: return null
        val em = m.groupValues[4].toIntOrNull() ?: return null
        if (sh !in 0..23 || eh !in 0..23 || sm !in 0..59 || em !in 0..59) return null
        val start = "%02d:%02d".format(sh, sm)
        val end = "%02d:%02d".format(eh, em)
        return officialPairTimes.values.firstOrNull { it.first == start && it.second == end }
    }

    fun pairNumber(start: String, end: String): Int? = officialPairTimes.entries
        .firstOrNull { it.value.first == start && it.value.second == end }?.key

    /** Single source of truth for weekly/date occurrence logic across UI, widgets and workers. */
    fun occursOnDate(event: ScheduleEvent, date: LocalDate, semesterStartIso: String): Boolean {
        event.exactDate?.let { return runCatching { LocalDate.parse(it) == date }.getOrDefault(false) }
        if (event.weekday != date.dayOfWeek.value) return false
        val start = runCatching { LocalDate.parse(semesterStartIso) }.getOrElse { LocalDate.of(date.year, 9, 1) }
        val monday = start.minusDays((start.dayOfWeek.value - 1).toLong())
        val week = ChronoUnit.WEEKS.between(monday, date) + 1
        if (week < 1) return false
        if (event.weekNumbers.isNotEmpty() && week.toInt() !in event.weekNumbers) return false
        return when (event.weekParity) {
            "EVEN" -> week % 2L == 0L
            "ODD" -> week % 2L == 1L
            else -> true
        }
    }

    private fun normalizeSurname(value: String): String = value.split('-').joinToString("-") { part ->
        part.lowercase().replaceFirstChar { ch -> ch.uppercase() }
    }

    private fun normalizeInstitute(value: String): String {
        val n = value.uppercase().replace('Ё', 'Е').replace(Regex("\\s+"), " ").trim()
        return when (n) {
            "ИПГСС" -> "ИПГС"
            "ИГЭСС" -> "ИГЭС"
            else -> n
        }
    }

    private fun romanToInt(value: String): Int? = when (value.uppercase()) {
        "I" -> 1; "II" -> 2; "III" -> 3; "IV" -> 4; "V" -> 5; "VI" -> 6; else -> null
    }
}

object ScheduleSanitizer {
    fun clean(events: List<ScheduleEvent>, profile: UserProfile): List<ScheduleEvent> {
        val canonicalTeacher = if (profile.role == UserRole.TEACHER) {
            profile.teacher.takeIf(ScheduleParsingRules::isValidTeacher)?.let(ScheduleParsingRules::canonicalTeacher)
        } else null
        val canonicalGroup = if (profile.role == UserRole.STUDENT) {
            profile.group.takeIf(ScheduleParsingRules::isValidGroup)?.let(ScheduleParsingRules::canonicalGroup)
        } else null

        // Invalid saved profile must never accidentally turn into an unfiltered global timetable.
        if (profile.role == UserRole.TEACHER && canonicalTeacher == null) return emptyList()
        if (profile.role == UserRole.STUDENT && canonicalGroup == null) return emptyList()

        val valid = events.filter { e ->
            val dateOk = e.exactDate?.let(ScheduleParsingRules::validDate) ?: (e.weekday in 1..7)
            val timeOk = if (e.exactDate == null) {
                ScheduleParsingRules.pairNumber(e.startTime, e.endTime) != null
            } else {
                e.startTime.isBlank() || ScheduleParsingRules.validTime(e.startTime)
            }
            val teacherOk = canonicalTeacher == null || ScheduleParsingRules.canonicalTeacher(e.teacher) == canonicalTeacher
            val groupOk = canonicalGroup == null || ScheduleParsingRules.canonicalGroup(e.group) == canonicalGroup
            val contentOk = e.title.isNotBlank() && !looksLikeGarbage(e)
            dateOk && timeOk && teacherOk && groupOk && contentOk
        }

        val firstPass = valid.groupBy { logicalKey(it, profile.role == UserRole.TEACHER) }.values.map(::merge)
        val secondPass = if (profile.role == UserRole.TEACHER) {
            // A teacher has one physical timeslot. The same lesson may appear in several group
            // columns/PDF mirrors, so merge by occurrence + slot and preserve all group names.
            firstPass.groupBy(::teacherSlotKey).values.map(::merge)
        } else firstPass

        return secondPass.sortedWith(compareBy<ScheduleEvent>({ it.exactDate ?: "9999" }, { it.weekday ?: 9 }, { it.startTime }, { it.title }))
    }

    private fun looksLikeGarbage(e: ScheduleEvent): Boolean {
        val n = ScheduleParsingRules.norm(e.title)
        if (n.matches(Regex("^(?:\\d{2,4}|[А-ЯA-Z])(?: .*)?$")) && n.length < 24) return true
        if (Regex("\\b(?:08|09|38)[.]03[.]\\d{2}\\b").containsMatchIn(n)) return true
        if (n.startsWith("ГРУППА") || n.startsWith("ДНИ ПАРА") || n.contains("УТВЕРЖДАЮ")) return true
        if (n.contains("УЧЕБНАЯ НЕДЕЛЯ") || n.contains("КУРС РАСПИСАНИЕ")) return true
        return false
    }

    private fun logicalKey(e: ScheduleEvent, teacherMode: Boolean): String = listOf(
        e.exactDate.orEmpty(), e.weekday?.toString().orEmpty(), e.startTime, e.endTime,
        ScheduleParsingRules.norm(e.title), ScheduleParsingRules.norm(e.type), ScheduleParsingRules.norm(e.room),
        ScheduleParsingRules.norm(e.teacher), e.weekNumbers.sorted().joinToString(","), e.weekParity,
        if (teacherMode) "" else ScheduleParsingRules.norm(e.group)
    ).joinToString("|")

    private fun teacherSlotKey(e: ScheduleEvent): String = listOf(
        e.exactDate.orEmpty(), e.weekday?.toString().orEmpty(), e.startTime, e.endTime,
        e.weekNumbers.sorted().joinToString(","), e.weekParity
    ).joinToString("|")

    private fun merge(items: List<ScheduleEvent>): ScheduleEvent {
        val base = items.first()
        fun distinct(selector: (ScheduleEvent) -> String): List<String> = items.map(selector).map(String::trim).filter(String::isNotBlank).distinct()
        val titles = distinct { it.title }
        val groups = distinct { ScheduleParsingRules.canonicalGroup(it.group) ?: it.group }
        val rooms = distinct { it.room }
        val teachers = distinct { ScheduleParsingRules.canonicalTeacher(it.teacher) ?: it.teacher }
        val types = distinct { it.type }
        val mergedTitle = if (titles.size <= 2) titles.joinToString(" / ") else titles.minByOrNull(String::length) ?: base.title
        return base.copy(
            title = mergedTitle.take(180),
            group = groups.joinToString(" / ").take(220),
            room = rooms.joinToString(" / ").take(120),
            teacher = teachers.firstOrNull().orEmpty().ifBlank { base.teacher },
            type = types.firstOrNull().orEmpty().ifBlank { base.type },
            sourceUrl = items.firstNotNullOfOrNull { it.sourceUrl.takeIf(String::isNotBlank) }.orEmpty(),
            sourceLabel = items.firstNotNullOfOrNull { it.sourceLabel.takeIf(String::isNotBlank) }.orEmpty()
        )
    }
}
