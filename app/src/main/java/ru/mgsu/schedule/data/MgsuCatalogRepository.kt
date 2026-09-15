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

    fun cached(): SuggestionCatalog = mergeWithSchedule(store.readCatalog())

    suspend fun refresh(force: Boolean = false): SuggestionCatalog = withContext(Dispatchers.IO) {
        val current = mergeWithSchedule(store.readCatalog())
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
            teachers = teachers.map(::cleanTeacher).filter { it.length >= 5 }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            groups = groups.map(::cleanGroup).filter { it.length >= 3 }.distinct().sortedWith(String.CASE_INSENSITIVE_ORDER),
            updatedAtMillis = System.currentTimeMillis()
        )
        store.writeCatalog(catalog)
        catalog
    }

    fun mergeFromSchedule(cache: CachedSchedule): SuggestionCatalog {
        val old = store.readCatalog()
        val teachers = (old.teachers + cache.events.map { it.teacher }.filter { it.isNotBlank() }).map(::cleanTeacher).distinct().sorted()
        val groups = (old.groups + cache.events.map { it.group }.filter { it.isNotBlank() }).map(::cleanGroup).distinct().sorted()
        val merged = old.copy(teachers = teachers, groups = groups)
        store.writeCatalog(merged)
        return merged
    }

    private fun mergeWithSchedule(base: SuggestionCatalog): SuggestionCatalog {
        val cache = store.readCache()
        if (cache.events.isEmpty()) return base
        val teachers = (base.teachers + cache.events.map { it.teacher }.filter { it.isNotBlank() }).map(::cleanTeacher).distinct().sorted()
        val groups = (base.groups + cache.events.map { it.group }.filter { it.isNotBlank() }).map(::cleanGroup).distinct().sorted()
        return base.copy(teachers = teachers, groups = groups)
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

    private fun extractTeachers(text: String): Set<String> {
        val result = mutableSetOf<String>()
        surnameFirst.findAll(text).forEach { result += cleanTeacher(it.value) }
        initialsFirst.findAll(text).forEach { m ->
            val v = m.value.replace(rolePrefix, "").trim()
            val mm = Regex("([А-ЯЁ])\\.?\\s*([А-ЯЁ])\\.?\\s+([А-ЯЁ][а-яё-]{2,})", RegexOption.IGNORE_CASE).find(v)
            if (mm != null) result += "${capitalizeRu(mm.groupValues[3])} ${mm.groupValues[1].uppercase()}.${mm.groupValues[2].uppercase()}."
        }
        return result.filter { it.count(Char::isLetter) >= 5 }.toSet()
    }

    private fun extractGroups(text: String): Set<String> {
        val compact = text.replace('\u00A0', ' ')
        val result = mutableSetOf<String>()
        // Common forms seen in MGSU PDFs: "ИАГ-1-12", "ИИЭСМ 2к 30", "ИДО 1к 40 boz".
        groupRegex.findAll(compact).forEach { result += cleanGroup(it.value) }
        return result
    }

    private fun cleanTeacher(raw: String): String {
        var s = raw.replace(rolePrefix, "").replace(Regex("\\s+"), " ").trim(' ', ',', ';')
        val m = Regex("([А-ЯЁ][а-яё-]{2,})\\s+([А-ЯЁ])\\.?\\s*([А-ЯЁ])\\.?", RegexOption.IGNORE_CASE).find(s)
        if (m != null) s = "${capitalizeRu(m.groupValues[1])} ${m.groupValues[2].uppercase()}.${m.groupValues[3].uppercase()}."
        return s
    }

    private fun cleanGroup(raw: String): String = raw.replace(Regex("\\s+"), " ").trim(' ', ',', ';', '|')

    private fun capitalizeRu(s: String): String = s.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

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
        private val rolePrefix = Regex("(?i)(доц\\.|проф\\.|ст\\.пр\\.|преп\\.|пр\\.|асс\\.|зав\\.каф\\.)\\s*")
        private val surnameFirst = Regex("(?i)(?:доц\\.|проф\\.|ст\\.пр\\.|преп\\.|пр\\.|асс\\.|зав\\.каф\\.)?\\s*[А-ЯЁ][а-яё-]{2,}\\s+[А-ЯЁ]\\.?\\s*[А-ЯЁ]\\.?")
        private val initialsFirst = Regex("(?i)(?:доц\\.|проф\\.|ст\\.пр\\.|преп\\.|пр\\.|асс\\.|зав\\.каф\\.)?\\s*[А-ЯЁ]\\.?\\s*[А-ЯЁ]\\.?\\s+[А-ЯЁ][а-яё-]{2,}")
        private val groupRegex = Regex("(?i)\\b(?:ИАГ|ИПГС|ИГЭС|ИИЭСМ|ИЦТМС|ИЭУКСН|ИИС(?:\\s+ОИАЭ)?|ИДО)[-\\s]*[1-6](?:\\s*[кk])?(?:[-\\s]+[0-9А-ЯA-Zа-яa-z./_]{1,18}){1,3}")
    }
}
