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
 * Builds the student timetable catalog from the official MGSU download page.
 *
 * The previous implementation downloaded and text-indexed up to 260 PDFs on first launch just to
 * discover every group and teacher. Apart from being slow on a phone, that made the app fail when
 * a single semester contained an unusual PDF. Version 6 keeps discovery cheap: first we index the
 * links on the official HTML page; only after the student selects institute/course do we download
 * the small matching subset and extract concrete groups from those PDFs.
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

    /** Refreshes only the official page index. No timetable PDFs are downloaded here. */
    suspend fun refresh(force: Boolean = false): SuggestionCatalog = withContext(Dispatchers.IO) {
        val current = sanitizeCatalog(store.readCatalog())
        val fresh = current.updatedAtMillis > 0L &&
            System.currentTimeMillis() - current.updatedAtMillis < INDEX_TTL_MS &&
            current.sourceLabels.isNotEmpty()
        if (!force && fresh) return@withContext current

        if (force) {
            runCatching { File(context.cacheDir, "mgsu_catalog_pdf").deleteRecursively() }
        }

        val sources = discoverAll()
        if (sources.isEmpty()) {
            if (current.sourceLabels.isNotEmpty()) return@withContext current
            throw IllegalStateException("Не удалось получить список официальных файлов расписания НИУ МГСУ")
        }

        val catalog = current.copy(
            schemaVersion = CATALOG_SCHEMA,
            teachers = emptyList(),
            teacherSources = emptyMap(),
            sourceLabels = sources.associate { it.url to it.label },
            updatedAtMillis = System.currentTimeMillis()
        )
        store.writeCatalog(catalog)
        catalog
    }

    /**
     * Loads concrete groups only for the selected institute/course. In normal semesters this means
     * one or a few PDFs instead of hundreds of files for the whole university.
     */
    suspend fun refreshGroups(
        institute: String,
        course: Int,
        force: Boolean = false
    ): SuggestionCatalog = withContext(Dispatchers.IO) {
        require(course in 1..6) { "Некорректный курс" }

        var base = refresh(force = force)
        val known = groupsFor(base, institute, course)
        val groupIndexFresh = !force && known.isNotEmpty() && known.any { base.groupSources[it].orEmpty().isNotEmpty() }
        if (groupIndexFresh) return@withContext base

        var sources = sourcesFrom(base)
            .filter { MgsuSourceRules.matchesStudentSelection(it.url, it.label, institute, course) }
            .sortedWith(compareBy<Source> { MgsuSourceRules.sourcePriority(it.url, it.label) }.thenBy { it.label })

        // The public page may have changed since the cached index was written. Refresh once before
        // reporting that no matching institute/course exists.
        if (sources.isEmpty() && !force) {
            base = refresh(force = true)
            sources = sourcesFrom(base)
                .filter { MgsuSourceRules.matchesStudentSelection(it.url, it.label, institute, course) }
                .sortedWith(compareBy<Source> { MgsuSourceRules.sourcePriority(it.url, it.label) }.thenBy { it.label })
        }
        if (sources.isEmpty()) {
            throw IllegalStateException("На странице МГСУ не найдены файлы для $institute, $course курса")
        }

        val foundGroups = linkedSetOf<String>()
        val foundSources = linkedMapOf<String, MutableSet<String>>()
        var readablePdfs = 0

        // Prefer ordinary lesson/exam timetables; practice documents are only a last resort.
        val preferred = sources.filterNot { MgsuSourceRules.isPracticeSource(it.url, it.label) }
        val candidates = (preferred + sources).distinctBy { MgsuSourceRules.canonicalPath(it.url) }

        for (source in candidates.take(MAX_GROUP_SCAN_PDFS)) {
            runCatching {
                val file = downloadCached(source.url, force)
                val text = PDDocument.load(file).use { doc ->
                    PDFTextStripper().apply { sortByPosition = true }.getText(doc)
                }
                readablePdfs++
                ScheduleParsingRules.extractGroups(text).forEach { raw ->
                    val group = ScheduleParsingRules.canonicalGroup(raw) ?: return@forEach
                    if (!ScheduleParsingRules.groupInstitute(group).equals(institute, ignoreCase = true)) return@forEach
                    if (ScheduleParsingRules.groupCourse(group) != course) return@forEach
                    foundGroups += group
                    foundSources.getOrPut(group) { linkedSetOf() }.add(source.url)
                }
            }

            // One normal course PDF usually contains every group for that institute/course. Once
            // we have a healthy set, avoid downloading session/practice duplicates unnecessarily.
            if (foundGroups.size >= MIN_HEALTHY_GROUP_SET && readablePdfs > 0) break
        }

        if (foundGroups.isEmpty()) {
            if (known.isNotEmpty()) return@withContext base
            val suffix = if (readablePdfs == 0) "Файлы найдены, но скачать PDF не удалось." else "PDF прочитаны, но группы не распознаны."
            throw IllegalStateException("Не удалось получить группы $institute, $course курса. $suffix")
        }

        val keepGroups = base.groups.filterNot { isSameSelection(it, institute, course) }
        val keepSources = base.groupSources.filterKeys { !isSameSelection(it, institute, course) }
        val mergedSources = keepSources.toMutableMap().apply {
            foundSources.forEach { (group, urls) -> put(group, urls.distinctBy(MgsuSourceRules::canonicalPath)) }
        }
        val catalog = base.copy(
            schemaVersion = CATALOG_SCHEMA,
            teachers = emptyList(),
            groups = (keepGroups + foundGroups).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            teacherSources = emptyMap(),
            groupSources = mergedSources,
            updatedAtMillis = System.currentTimeMillis()
        )
        store.writeCatalog(catalog)
        catalog
    }

    fun mergeFromSchedule(cache: CachedSchedule): SuggestionCatalog {
        val old = sanitizeCatalog(store.readCatalog())
        val groups = (old.groups + cache.events.mapNotNull { ScheduleParsingRules.canonicalGroup(it.group) })
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val merged = old.copy(
            schemaVersion = CATALOG_SCHEMA,
            teachers = emptyList(),
            groups = groups,
            teacherSources = emptyMap()
        )
        store.writeCatalog(merged)
        return merged
    }

    private fun mergeWithSchedule(base: SuggestionCatalog): SuggestionCatalog {
        val cleanBase = sanitizeCatalog(base)
        val cache = store.readCache()
        if (cache.events.isEmpty()) return cleanBase
        return cleanBase.copy(
            schemaVersion = CATALOG_SCHEMA,
            teachers = emptyList(),
            groups = (cleanBase.groups + cache.events.mapNotNull { ScheduleParsingRules.canonicalGroup(it.group) })
                .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            teacherSources = emptyMap()
        )
    }

    private fun sanitizeCatalog(value: SuggestionCatalog): SuggestionCatalog {
        if (value.schemaVersion < CATALOG_SCHEMA) return SuggestionCatalog(schemaVersion = CATALOG_SCHEMA)
        val groups = value.groups.filter(ScheduleParsingRules::isValidGroup)
            .mapNotNull(ScheduleParsingRules::canonicalGroup)
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val validGroups = groups.toSet()
        return value.copy(
            schemaVersion = CATALOG_SCHEMA,
            teachers = emptyList(),
            groups = groups,
            teacherSources = emptyMap(),
            groupSources = value.groupSources.mapNotNull { (key, urls) ->
                val canonical = ScheduleParsingRules.canonicalGroup(key) ?: return@mapNotNull null
                if (canonical !in validGroups) return@mapNotNull null
                canonical to urls.filter(MgsuSourceRules::isOfficialUrl).distinctBy(MgsuSourceRules::canonicalPath)
            }.toMap(),
            sourceLabels = value.sourceLabels.filterKeys(MgsuSourceRules::isOfficialUrl)
        )
    }

    private fun groupsFor(catalog: SuggestionCatalog, institute: String, course: Int): List<String> =
        catalog.groups.filter { isSameSelection(it, institute, course) }

    private fun isSameSelection(group: String, institute: String, course: Int): Boolean =
        ScheduleParsingRules.groupInstitute(group).equals(institute, ignoreCase = true) &&
            ScheduleParsingRules.groupCourse(group) == course

    private data class Source(val url: String, val label: String)

    private fun sourcesFrom(catalog: SuggestionCatalog): List<Source> =
        catalog.sourceLabels.map { (url, label) -> Source(url, label) }

    private suspend fun discoverAll(): List<Source> {
        val lessons = discoverFirstAvailable(lessonsPath, "Занятия")
        if (lessons.isEmpty()) return emptyList()

        val out = lessons.toMutableList()
        // The main download page currently also contains session sections. Only query the legacy
        // separate exam page when it adds information, and never make it a requirement.
        if (out.none { it.label.startsWith("Экзамены") }) {
            out += discoverFirstAvailable(examsPath, "Экзамены")
        }
        return out.distinctBy { MgsuSourceRules.canonicalPath(it.url) }
    }

    private suspend fun discoverFirstAvailable(path: String, kind: String): List<Source> {
        for (host in hosts) {
            val pageUrl = "https://$host$path"
            val found = runCatching { discover(pageUrl, kind) }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private suspend fun discover(pageUrl: String, defaultKind: String): List<Source> {
        executeWithRetry(pageUrl, "https://mgsu.ru/").use { response ->
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, pageUrl)
            return doc.select("a[href]").mapNotNull { a ->
                val href = MgsuSourceRules.normalizeOfficialUrl(a.absUrl("href"))
                if (!href.substringBefore('?').endsWith(".pdf", ignoreCase = true) || !MgsuSourceRules.isOfficialUrl(href)) {
                    return@mapNotNull null
                }
                val anchor = a.text().ifBlank { href.substringAfterLast('/') }
                val kind = MgsuSourceRules.classifyKind(href, anchor, defaultKind)
                Source(href, "$kind · $anchor")
            }
        }
    }

    private suspend fun downloadCached(url: String, force: Boolean): File {
        val dir = File(context.cacheDir, "mgsu_catalog_pdf").apply { mkdirs() }
        val file = File(dir, sha1(MgsuSourceRules.canonicalPath(url)) + ".pdf")
        if (!force && file.exists() && file.length() > 1024L) return file

        var last: Throwable? = null
        for (candidate in alternateUrls(url)) {
            try {
                executeWithRetry(candidate, "https://${URI(candidate).host}$lessonsPath").use { response ->
                    val bytes = response.body?.bytes() ?: error("Пустой PDF")
                    if (!looksLikePdf(bytes)) error("Сервер вернул не PDF")
                    file.writeBytes(bytes)
                    return file
                }
            } catch (t: Throwable) {
                last = t
            }
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
            } catch (t: Throwable) {
                last = t
            }
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

    private fun looksLikePdf(bytes: ByteArray): Boolean = bytes.size > 5 &&
        bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val CATALOG_SCHEMA = 6
        private const val MAX_GROUP_SCAN_PDFS = 24
        private const val MIN_HEALTHY_GROUP_SET = 2
        private const val INDEX_TTL_MS = 6L * 60L * 60L * 1000L
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
