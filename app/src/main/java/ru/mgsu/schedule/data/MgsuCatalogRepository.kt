package ru.mgsu.schedule.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jsoup.Jsoup
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Builds a local index from the official MGSU lesson/exam PDFs.
 *
 * Version 5 stores not only autocomplete values but also teacher/group -> PDF mappings. This is
 * important: after the first launch the application does not have to guess a PDF from its file
 * name and does not silently miss schedules whose filenames are abbreviated.
 */
class MgsuCatalogRepository(private val context: Context) {
    private val store = AppStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val hosts = listOf("mgsu.ru", "www.mgsu.ru", "www-20.mgsu.ru", "iges.mgsu.ru", "isa.mgsu.ru", "euis.mgsu.ru")
    private val lessonsPath = "/student/Raspisanie_zanyatii_i_ekzamenov/fayly-raspisaniya-dlya-skachivaniya/"
    private val examsPath = "/student/Raspisanie_zanyatii_i_ekzamenov/raspisanie-ekzamenov/"

    init { PDFBoxResourceLoader.init(context) }

    fun cached(): SuggestionCatalog = mergeWithSchedule(sanitizeCatalog(store.readCatalog()))

    suspend fun refresh(force: Boolean = false): SuggestionCatalog = withContext(Dispatchers.IO) {
        val current = mergeWithSchedule(sanitizeCatalog(store.readCatalog()))
        val fresh = current.updatedAtMillis > 0L &&
            System.currentTimeMillis() - current.updatedAtMillis < 24L * 60L * 60L * 1000L &&
            current.teachers.isNotEmpty() && current.groups.isNotEmpty() &&
            (current.teacherSources.isNotEmpty() || current.groupSources.isNotEmpty())
        if (!force && fresh) return@withContext current

        if (force) {
            // A forced bootstrap must not reuse timetable PDFs from an older semester/version.
            runCatching { File(context.cacheDir, "mgsu_catalog_pdf").deleteRecursively() }
        }

        val sources = discoverAll()
        if (sources.isEmpty()) {
            if (current.teachers.isEmpty() && current.groups.isEmpty()) {
                throw IllegalStateException("Не удалось получить список официальных файлов расписания НИУ МГСУ")
            }
            return@withContext current
        }

        val teachers = linkedSetOf<String>()
        teachers += current.teachers
        val groups = linkedSetOf<String>()
        groups += current.groups

        val teacherSources = current.teacherSources
            .mapValues { (_, urls) -> urls.toMutableSet() }
            .toMutableMap()
        val groupSources = current.groupSources
            .mapValues { (_, urls) -> urls.toMutableSet() }
            .toMutableMap()
        val labels = current.sourceLabels.toMutableMap()

        // Filenames/labels sometimes already expose a group, so index these immediately.
        sources.forEach { src ->
            labels[src.url] = src.label
            ScheduleParsingRules.extractGroups(src.label + " " + src.url).forEach { group ->
                groups += group
                groupSources.getOrPut(group) { linkedSetOf() }.add(src.url)
            }
        }

        var successfulPdfs = 0
        for (source in sources.take(MAX_SCAN_PDFS)) {
            runCatching {
                val file = downloadCached(source.url)
                val text = PDDocument.load(file).use { PDFTextStripper().apply { sortByPosition = true }.getText(it) }
                val foundTeachers = ScheduleParsingRules.extractTeachers(text)
                val foundGroups = ScheduleParsingRules.extractGroups(text)
                foundTeachers.forEach { teacher ->
                    val canonical = ScheduleParsingRules.canonicalTeacher(teacher) ?: return@forEach
                    teachers += canonical
                    teacherSources.getOrPut(canonical) { linkedSetOf() }.add(source.url)
                }
                foundGroups.forEach { group ->
                    val canonical = ScheduleParsingRules.canonicalGroup(group) ?: return@forEach
                    groups += canonical
                    groupSources.getOrPut(canonical) { linkedSetOf() }.add(source.url)
                }
                successfulPdfs++
            }
        }

        if (successfulPdfs == 0 && current.teachers.isEmpty() && current.groups.isEmpty()) {
            throw IllegalStateException("Файлы МГСУ найдены, но ни один PDF не удалось прочитать")
        }

        val catalog = SuggestionCatalog(
            schemaVersion = 5,
            teachers = teachers.mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            groups = groups.mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            teacherSources = teacherSources.mapNotNull { (k, urls) ->
                val canonical = ScheduleParsingRules.canonicalTeacher(k) ?: return@mapNotNull null
                canonical to urls.filter(::isOfficialUrl).distinctBy(::canonicalPath)
            }.toMap(),
            groupSources = groupSources.mapNotNull { (k, urls) ->
                val canonical = ScheduleParsingRules.canonicalGroup(k) ?: return@mapNotNull null
                canonical to urls.filter(::isOfficialUrl).distinctBy(::canonicalPath)
            }.toMap(),
            sourceLabels = labels.filterKeys(::isOfficialUrl),
            updatedAtMillis = System.currentTimeMillis()
        )
        store.writeCatalog(catalog)
        catalog
    }

    fun mergeFromSchedule(cache: CachedSchedule): SuggestionCatalog {
        val old = sanitizeCatalog(store.readCatalog())
        val teachers = (old.teachers + cache.events.mapNotNull { ScheduleParsingRules.canonicalTeacher(it.teacher) })
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val groups = (old.groups + cache.events.mapNotNull { ScheduleParsingRules.canonicalGroup(it.group) })
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val merged = old.copy(schemaVersion = 5, teachers = teachers, groups = groups)
        store.writeCatalog(merged)
        return merged
    }

    private fun mergeWithSchedule(base: SuggestionCatalog): SuggestionCatalog {
        val cleanBase = sanitizeCatalog(base)
        val cache = store.readCache()
        if (cache.events.isEmpty()) return cleanBase
        return cleanBase.copy(
            schemaVersion = 5,
            teachers = (cleanBase.teachers + cache.events.mapNotNull { ScheduleParsingRules.canonicalTeacher(it.teacher) })
                .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            groups = (cleanBase.groups + cache.events.mapNotNull { ScheduleParsingRules.canonicalGroup(it.group) })
                .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        )
    }

    private fun sanitizeCatalog(value: SuggestionCatalog): SuggestionCatalog {
        if (value.schemaVersion < 5) return SuggestionCatalog(schemaVersion = 5)
        val teachers = value.teachers.filter(ScheduleParsingRules::isValidTeacher)
            .mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val groups = value.groups.filter(ScheduleParsingRules::isValidGroup)
            .mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val validTeachers = teachers.toSet()
        val validGroups = groups.toSet()
        return value.copy(
            schemaVersion = 5,
            teachers = teachers,
            groups = groups,
            teacherSources = value.teacherSources.mapNotNull { (key, urls) ->
                val canonical = ScheduleParsingRules.canonicalTeacher(key) ?: return@mapNotNull null
                if (canonical !in validTeachers) return@mapNotNull null
                canonical to urls.filter(::isOfficialUrl).distinctBy(::canonicalPath)
            }.toMap(),
            groupSources = value.groupSources.mapNotNull { (key, urls) ->
                val canonical = ScheduleParsingRules.canonicalGroup(key) ?: return@mapNotNull null
                if (canonical !in validGroups) return@mapNotNull null
                canonical to urls.filter(::isOfficialUrl).distinctBy(::canonicalPath)
            }.toMap(),
            sourceLabels = value.sourceLabels.filterKeys(::isOfficialUrl)
        )
    }

    private data class Source(val url: String, val label: String)

    private suspend fun discoverAll(): List<Source> {
        val out = mutableListOf<Source>()
        for ((path, kind) in listOf(lessonsPath to "Занятия", examsPath to "Экзамены")) {
            for (host in hosts) {
                val url = "https://$host$path"
                val found = runCatching { discover(url, kind) }.getOrDefault(emptyList())
                out += found
                // A successful official host is enough for this section; mirrors contain the same PDFs.
                if (found.isNotEmpty()) break
            }
        }
        return out.distinctBy { canonicalPath(it.url) }
    }

    private suspend fun discover(pageUrl: String, kind: String): List<Source> {
        executeWithRetry(pageUrl, "https://mgsu.ru/").use { response ->
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, pageUrl)
            return doc.select("a[href]").mapNotNull { a ->
                val href = normalizeOfficialUrl(a.absUrl("href"))
                if (!href.substringBefore('?').endsWith(".pdf", ignoreCase = true) || !isOfficialUrl(href)) null
                else Source(href, "$kind · ${a.text().ifBlank { href.substringAfterLast('/') }}")
            }
        }
    }

    private suspend fun downloadCached(url: String): File {
        val dir = File(context.cacheDir, "mgsu_catalog_pdf").apply { mkdirs() }
        val file = File(dir, sha1(canonicalPath(url)) + ".pdf")
        if (file.exists() && file.length() > 1024L) return file

        var last: Throwable? = null
        for (candidate in alternateUrls(url)) {
            try {
                executeWithRetry(candidate, "https://${URI(candidate).host}$lessonsPath").use { response ->
                    val bytes = response.body?.bytes() ?: error("Пустой PDF")
                    if (!looksLikePdf(bytes)) error("Сервер вернул не PDF")
                    file.writeBytes(bytes)
                    return file
                }
            } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("Не удалось скачать PDF")
    }

    private suspend fun executeWithRetry(url: String, referer: String): Response {
        var last: Throwable? = null
        repeat(3) { attempt ->
            try {
                val request = Request.Builder().url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/pdf;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5")
                    .header("Referer", referer)
                    .build()
                val response = client.newCall(request).execute()
                if (response.isSuccessful) return response
                val code = response.code
                response.close()
                last = IllegalStateException("HTTP $code")
            } catch (t: Throwable) { last = t }
            if (attempt < 2) delay(700L * (attempt + 1))
        }
        throw last ?: IllegalStateException("Ошибка сети")
    }

    private fun alternateUrls(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val host = uri.host ?: return listOf(url)
        if (host !in hosts) return listOf(url)
        return (listOf(host) + hosts).distinct().mapNotNull { newHost ->
            runCatching { URI(uri.scheme ?: "https", uri.userInfo, newHost, uri.port, uri.path, uri.query, uri.fragment).toString() }.getOrNull()
        }
    }

    private fun isOfficialUrl(url: String): Boolean = runCatching {
        val host = URI(url).host?.lowercase().orEmpty()
        host == "mgsu.ru" || host.endsWith(".mgsu.ru")
    }.getOrDefault(false)

    private fun normalizeOfficialUrl(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        val host = uri.host?.lowercase().orEmpty()
        if (!(host == "mgsu.ru" || host.endsWith(".mgsu.ru"))) return url
        if (uri.scheme.equals("https", true)) return url
        return runCatching {
            URI("https", uri.userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        }.getOrDefault(url)
    }

    private fun canonicalPath(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return (uri.path ?: url) + (uri.query?.let { "?$it" } ?: "")
    }

    private fun looksLikePdf(bytes: ByteArray): Boolean = bytes.size > 5 &&
        bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_SCAN_PDFS = 260
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
