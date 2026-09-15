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
 * Builds a local autocomplete dictionary from the same official MGSU schedule PDFs.
 * The dictionary is cached, so typing works instantly after the first successful load.
 */
class MgsuCatalogRepository(private val context: Context) {
    private val store = AppStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(55, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val hosts = listOf("mgsu.ru", "iges.mgsu.ru", "isa.mgsu.ru")
    private val lessonsPath = "/student/Raspisanie_zanyatii_i_ekzamenov/fayly-raspisaniya-dlya-skachivaniya/"
    private val examsPath = "/student/Raspisanie_zanyatii_i_ekzamenov/raspisanie-ekzamenov/"

    init { PDFBoxResourceLoader.init(context) }

    fun cached(): SuggestionCatalog = mergeWithSchedule(sanitizeCatalog(store.readCatalog()))

    suspend fun refresh(force: Boolean = false): SuggestionCatalog = withContext(Dispatchers.IO) {
        val current = mergeWithSchedule(sanitizeCatalog(store.readCatalog()))
        val fresh = current.updatedAtMillis > 0L && System.currentTimeMillis() - current.updatedAtMillis < 24L * 60L * 60L * 1000L
        if (!force && fresh && current.teachers.isNotEmpty() && current.groups.isNotEmpty()) return@withContext current

        val sources = discoverAll()
        if (sources.isEmpty()) {
            store.writeCatalog(current)
            return@withContext current
        }

        val teachers = current.teachers.toMutableSet()
        val groups = current.groups.toMutableSet()

        // Link labels and filenames already contain many group names, so suggestions can improve
        // before any PDF parsing succeeds.
        sources.forEach { src ->
            groups += extractGroups(src.label + " " + src.url)
        }

        // Scan current lesson/exam PDFs once. Files are cached locally and future refreshes are cheap.
        // Keep the upper bound to avoid excessive traffic if the university publishes old archives.
        for (source in sources.take(180)) {
            runCatching {
                val file = downloadCached(source.url)
                val text = PDDocument.load(file).use { PDFTextStripper().getText(it) }
                teachers += extractTeachers(text)
                groups += extractGroups(text)
            }
        }

        val catalog = SuggestionCatalog(
            schemaVersion = 3,
            teachers = teachers.mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            groups = groups.mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
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
        val merged = old.copy(schemaVersion = 3, teachers = teachers, groups = groups)
        store.writeCatalog(merged)
        return merged
    }

    private fun mergeWithSchedule(base: SuggestionCatalog): SuggestionCatalog {
        val cleanBase = sanitizeCatalog(base)
        val cache = store.readCache()
        if (cache.events.isEmpty()) return cleanBase
        val teachers = (cleanBase.teachers + cache.events.mapNotNull { ScheduleParsingRules.canonicalTeacher(it.teacher) })
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        val groups = (cleanBase.groups + cache.events.mapNotNull { ScheduleParsingRules.canonicalGroup(it.group) })
            .distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        return cleanBase.copy(schemaVersion = 3, teachers = teachers, groups = groups)
    }

    private fun sanitizeCatalog(value: SuggestionCatalog): SuggestionCatalog {
        // schemaVersion < 3 contained permissive regex results such as "В-молоткова" and
        // broad pseudo-groups such as "ИПГС 1 курс". Do not let them survive an upgrade.
        if (value.schemaVersion < 3) return SuggestionCatalog(schemaVersion = 3)
        return value.copy(
            schemaVersion = 3,
            teachers = value.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            groups = value.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().sortedWith(String.CASE_INSENSITIVE_ORDER)
        )
    }

    private data class Source(val url: String, val label: String)

    private suspend fun discoverAll(): List<Source> {
        val out = mutableListOf<Source>()
        val paths = listOf(lessonsPath, examsPath)
        for (path in paths) {
            for (host in hosts) {
                val url = "https://$host$path"
                val found = runCatching { discover(url) }.getOrDefault(emptyList())
                out += found
                if (found.size >= 8) break
            }
        }
        return out.distinctBy { canonicalPath(it.url) }
    }

    private suspend fun discover(pageUrl: String): List<Source> {
        executeWithRetry(pageUrl, "https://mgsu.ru/").use { response ->
            val html = response.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            val doc = Jsoup.parse(html, pageUrl)
            return doc.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href")
                if (!href.substringBefore('?').endsWith(".pdf", ignoreCase = true)) null
                else Source(href, a.text().ifBlank { href.substringAfterLast('/') })
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
                    if (bytes.size < 5 || bytes[0] != '%'.code.toByte() || bytes[1] != 'P'.code.toByte() || bytes[2] != 'D'.code.toByte() || bytes[3] != 'F'.code.toByte()) error("Сервер вернул не PDF")
                    file.writeBytes(bytes)
                    return file
                }
            } catch (t: Throwable) { last = t }
        }
        throw last ?: IllegalStateException("Не удалось скачать PDF")
    }

    private suspend fun executeWithRetry(url: String, referer: String): Response {
        var last: Throwable? = null
        repeat(2) { attempt ->
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
            if (attempt == 0) delay(700L)
        }
        throw last ?: IllegalStateException("Ошибка сети")
    }

    private fun extractTeachers(text: String): Set<String> = ScheduleParsingRules.extractTeachers(text).toSet()

    private fun extractGroups(text: String): Set<String> = ScheduleParsingRules.extractGroups(text).toSet()

    private fun alternateUrls(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val host = uri.host ?: return listOf(url)
        if (host !in hosts) return listOf(url)
        return (listOf(host) + hosts).distinct().mapNotNull { newHost ->
            runCatching { URI(uri.scheme ?: "https", uri.userInfo, newHost, uri.port, uri.path, uri.query, uri.fragment).toString() }.getOrNull()
        }
    }

    private fun canonicalPath(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return (uri.path ?: url) + (uri.query?.let { "?$it" } ?: "")
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
