package ru.mgsu.schedule.data

import java.net.URI

/**
 * Pure rules for matching official MGSU timetable links to a student's institute/course.
 *
 * The MGSU download page changes filenames regularly, so source selection must be based on the
 * visible link text as well as the URL. Keeping this Android-free also makes the live-page naming
 * rules easy to cover with unit tests.
 */
object MgsuSourceRules {
    private val instituteAliases = mapOf(
        "ИАГ" to listOf("ИАГ", "IAG"),
        "ИПГС" to listOf("ИПГС", "IPGS"),
        "ИГЭС" to listOf("ИГЭС", "IGES", "GES"),
        "ИИЭСМ" to listOf("ИИЭСМ", "IIESM"),
        "ИЦТМС" to listOf("ИЦТМС", "ICTMS", "IZTMS"),
        "ИЭУКСН" to listOf("ИЭУКСН", "IEUKSN", "EUIS"),
        "ИИС ОИАЭ" to listOf("ИИС ОИАЭ", "ИИС", "ОИАЭ", "OIAE"),
        "ИДО" to listOf("ИДО", "IDO")
    )

    fun isOfficialUrl(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase().orEmpty()
        host == "mgsu.ru" || host.endsWith(".mgsu.ru")
    }.getOrDefault(false)

    fun normalizeOfficialUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "mgsu.ru" || host.endsWith(".mgsu.ru"))) return url
        if (uri.scheme.equals("https", true)) return url
        return runCatching {
            URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrDefault(url)
    }

    fun canonicalPath(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return (uri.path ?: url) + (uri.query?.let { "?$it" } ?: "")
    }

    fun classifyKind(url: String, label: String, defaultKind: String = "Занятия"): String {
        val marker = normalizeMarker("$url $label")
        return if (
            marker.contains("РАСПИСАНИЕ ЭКЗАМЕНОВ") || marker.contains("ЭКЗАМ") ||
            marker.contains("СЕССИ") || marker.contains("RAS PISANIE EKZAMENOV") ||
            marker.contains("RASPISANIE EKZAMENOV") || marker.contains("_SESS") ||
            Regex("(^|[^A-Z])SEZ([^A-Z]|$)").containsMatchIn(marker) || marker.contains("EXAM")
        ) "Экзамены" else defaultKind
    }

    fun matchesStudentSelection(url: String, label: String, institute: String, course: Int): Boolean {
        if (course !in 1..6) return false
        val marker = normalizeMarker("$label $url")
        val aliases = instituteAliases[normalizeInstitute(institute)].orEmpty()
        if (aliases.isEmpty() || aliases.none { alias -> containsToken(marker, normalizeMarker(alias)) }) return false

        val explicitCourses = extractCourses(marker)
        return explicitCourses.isEmpty() || course in explicitCourses
    }

    fun isPracticeSource(url: String, label: String): Boolean {
        val marker = normalizeMarker("$label $url")
        return marker.contains("ПРАКТИК") || marker.contains("PRAKTIK") || marker.contains("PRACTIC")
    }

    fun sourcePriority(url: String, label: String): Int = when {
        isPracticeSource(url, label) -> 30
        classifyKind(url, label) == "Экзамены" -> 10
        else -> 0
    }

    private fun extractCourses(marker: String): Set<Int> {
        val out = linkedSetOf<Int>()

        // Visible anchors currently use forms such as "ИПГС бак 1 курс" and "1-4 курс".
        Regex("(?<!\\d)([1-6])\\s*[-–—]\\s*([1-6])\\s*(?:КУРС|К\\b|K\\b)").findAll(marker).forEach { m ->
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            for (c in minOf(a, b)..maxOf(a, b)) out += c
        }
        Regex("(?<!\\d)([1-6])\\s*(?:КУРС|К\\b|K\\b)").findAll(marker).forEach { m ->
            out += m.groupValues[1].toInt()
        }

        // Filenames published by MGSU often look like IPGSb_1k_0210.pdf.
        Regex("(?:^|[^0-9])([1-6])K(?:[^0-9]|$)").findAll(marker).forEach { m ->
            out += m.groupValues[1].toInt()
        }
        return out
    }

    private fun containsToken(marker: String, token: String): Boolean {
        if (token.contains(' ')) return marker.contains(token)
        return Regex("(^|[^А-ЯA-Z0-9])${Regex.escape(token)}([^А-ЯA-Z0-9]|$)").containsMatchIn(marker)
    }

    private fun normalizeInstitute(value: String): String {
        val n = value.uppercase().replace('Ё', 'Е').replace(Regex("\\s+"), " ").trim()
        return when (n) {
            "ИПГСС" -> "ИПГС"
            "ИГЭСС" -> "ИГЭС"
            else -> n
        }
    }

    private fun normalizeMarker(value: String): String = value.uppercase().replace('Ё', 'Е')
        .replace("%D0%", " ")
        .replace(Regex("[_/\\\\.?=&:+-]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
}
