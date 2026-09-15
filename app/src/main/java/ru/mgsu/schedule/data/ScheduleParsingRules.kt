package ru.mgsu.schedule.data

import java.time.LocalDate

object ScheduleParsingRules {
    private val teacherSurnameFirstRegex = Regex("(?i)(?:доц\\.|проф\\.|ст\\.пр\\.|пр\\.|асс\\.|зав\\.каф\\.)?\\s*[А-ЯЁ][а-яё-]{2,}\\s+[А-ЯЁ]\\.?\\s*[А-ЯЁ]\\.?")
    private val teacherInitialsFirstRegex = Regex("(?i)(?:доц\\.|проф\\.|ст\\.пр\\.|пр\\.|асс\\.|зав\\.каф\\.)?\\s*[А-ЯЁ]\\.?\\s*[А-ЯЁ]\\.?\\s+[А-ЯЁ][а-яё-]{2,}")
    private val weekSpecRegex = Regex("(?i)(\\d{1,2}(?:\\s*[-–—]\\s*\\d{1,2})?(?:\\s*[,;]\\s*\\d{1,2}(?:\\s*[-–—]\\s*\\d{1,2})?)*)\\s*(?:нед(?:ел[яиь])?\\.?|н\\.)")

    fun norm(s: String): String = s.uppercase().replace('Ё', 'Е')
        .replace(Regex("[^А-ЯA-Z0-9. -]"), " ").replace(Regex("\\s+"), " ").trim()

    fun matchesTeacher(text: String, teacher: String): Boolean {
        val target = teacherParts(teacher) ?: return false
        val candidates = (teacherSurnameFirstRegex.findAll(text).map { it.value } + teacherInitialsFirstRegex.findAll(text).map { it.value }).toList()
        if (candidates.any { candidateMatches(it, target) }) return true

        val n = norm(text).replace('.', ' ')
        val surname = Regex("(?<![А-ЯA-Z])${Regex.escape(target.first)}(?![А-ЯA-Z])")
        val m = surname.find(n) ?: return false
        if (target.second.isEmpty()) return true
        val from = (m.range.first - 12).coerceAtLeast(0)
        val to = (m.range.last + 14).coerceAtMost(n.lastIndex)
        val window = n.substring(from, to + 1)
        return target.second.all { ini -> Regex("(?<![А-ЯA-Z])${Regex.escape(ini)}(?![А-ЯA-Z])").containsMatchIn(window) }
    }

    fun findTeacher(text: String): String = teacherSurnameFirstRegex.find(text)?.value?.trim()
        ?: teacherInitialsFirstRegex.find(text)?.value?.trim().orEmpty()

    fun extractWeekNumbers(text: String): List<Int> {
        val out = linkedSetOf<Int>()
        weekSpecRegex.findAll(text).forEach { m ->
            m.groupValues[1].split(',', ';').forEach { token ->
                val t = token.trim()
                val range = Regex("(\\d{1,2})\\s*[-–—]\\s*(\\d{1,2})").matchEntire(t)
                if (range != null) {
                    val a = range.groupValues[1].toInt(); val b = range.groupValues[2].toInt()
                    if (a <= b) for (w in a..b) if (w in 1..30) out += w
                } else t.toIntOrNull()?.takeIf { it in 1..30 }?.let(out::add)
            }
        }
        return out.toList()
    }

    fun extractParity(text: String): String {
        val n = norm(text)
        val odd = n.contains("НЕЧЕТ") || n.contains("НЕЧЁТ") || n.contains("ЧИСЛ")
        val even = n.contains("ЧЕТН") || n.contains("ЧЁТН") || n.contains("ЗНАМ")
        return when { odd && !even -> "ODD"; even && !odd -> "EVEN"; else -> "ANY" }
    }

    fun validTime(value: String): Boolean = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(value)
    fun validDate(value: String): Boolean = runCatching { LocalDate.parse(value); true }.getOrDefault(false)

    private fun candidateMatches(candidate: String, target: Pair<String, List<String>>): Boolean {
        val p = teacherParts(candidate) ?: return false
        return p.first == target.first && (target.second.isEmpty() || p.second.take(2) == target.second.take(2))
    }

    private fun teacherParts(value: String): Pair<String, List<String>>? {
        val tokens = norm(value).replace('.', ' ').split(Regex("\\s+")).filter { it.isNotBlank() }
        val surname = tokens.filter { it.length >= 3 && it.any(Char::isLetter) }.maxByOrNull { it.length } ?: return null
        val initials = tokens.filter { it.length == 1 && it[0].isLetter() }.take(2)
        return surname.replace("-", "") to initials
    }
}

object ScheduleSanitizer {
    fun clean(events: List<ScheduleEvent>, profile: UserProfile): List<ScheduleEvent> {
        val valid = events.filter { e ->
            val dateOk = e.exactDate?.let(ScheduleParsingRules::validDate) ?: (e.weekday in 1..7)
            val timeOk = e.startTime.isBlank() || ScheduleParsingRules.validTime(e.startTime)
            val teacherOk = profile.role != UserRole.TEACHER || ScheduleParsingRules.matchesTeacher(e.teacher.ifBlank { profile.teacher }, profile.teacher)
            dateOk && timeOk && e.title.isNotBlank() && teacherOk
        }

        val firstPass = valid.groupBy { logicalKey(it, profile.role == UserRole.TEACHER) }.values.map { merge(it) }
        val secondPass = if (profile.role == UserRole.TEACHER) {
            firstPass.groupBy { slotKey(it) }.values.map { slot ->
                if (slot.size == 1) slot.first() else merge(slot)
            }
        } else firstPass

        return secondPass.sortedWith(compareBy<ScheduleEvent>({ it.exactDate ?: "9999" }, { it.weekday ?: 9 }, { it.startTime }, { it.title }))
    }

    private fun logicalKey(e: ScheduleEvent, teacherMode: Boolean): String = listOf(
        e.exactDate.orEmpty(), e.weekday?.toString().orEmpty(), e.startTime, e.endTime,
        ScheduleParsingRules.norm(e.title), ScheduleParsingRules.norm(e.type), ScheduleParsingRules.norm(e.room),
        ScheduleParsingRules.norm(e.teacher), e.weekNumbers.sorted().joinToString(","), e.weekParity,
        if (teacherMode) "" else ScheduleParsingRules.norm(e.group)
    ).joinToString("|")

    private fun slotKey(e: ScheduleEvent): String = listOf(
        e.exactDate.orEmpty(), e.weekday?.toString().orEmpty(), e.startTime, e.endTime,
        e.weekNumbers.sorted().joinToString(","), e.weekParity
    ).joinToString("|")

    private fun merge(items: List<ScheduleEvent>): ScheduleEvent {
        val base = items.first()
        fun distinct(selector: (ScheduleEvent) -> String): List<String> = items.map(selector).map { it.trim() }.filter { it.isNotBlank() }.distinct()
        val titles = distinct { it.title }
        val groups = distinct { it.group }
        val rooms = distinct { it.room }
        val teachers = distinct { it.teacher }
        val types = distinct { it.type }
        val mergedTitle = when { titles.size <= 2 -> titles.joinToString(" / "); else -> titles.minByOrNull { it.length } ?: base.title }
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
