package ru.mgsu.schedule.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
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

class MgsuRepository(private val context: Context) {
    private val store = AppStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(25, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)
        .callTimeout(90, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val scheduleHosts = listOf(
        "mgsu.ru",
        "iges.mgsu.ru",
        "isa.mgsu.ru"
    )

    private val lessonsPath = "/student/Raspisanie_zanyatii_i_ekzamenov/fayly-raspisaniya-dlya-skachivaniya/"
    private val examsPath = "/student/Raspisanie_zanyatii_i_ekzamenov/raspisanie-ekzamenov/"

    init { PDFBoxResourceLoader.init(context) }

    suspend fun sync(profile: UserProfile): CachedSchedule = withContext(Dispatchers.IO) {
        val old = store.readCache()
        val previousDiagnostics = store.readDiagnostics()
        val attemptAt = System.currentTimeMillis()
        var activeHost = ""
        var discoveredCount = 0
        var matchedCount = 0
        var checked = 0
        var failedPdfs = 0
        store.writeDiagnostics(
            previousDiagnostics.copy(
                lastAttemptMillis = attemptAt,
                activeHost = "",
                sourcesDiscovered = 0,
                sourcesMatched = 0,
                sourcesChecked = 0,
                parsedEvents = old.events.size,
                failedPdfs = 0,
                usedOfflineCache = false,
                lastError = ""
            )
        )
        try {
            val discoveryErrors = mutableListOf<String>()
            val discovered = mutableListOf<Source>()

            // The main MGSU host sometimes responds with 502. All addresses below are
            // official MGSU subdomains and are used only as fallbacks for the same public data.
            val indexPages = buildList {
                scheduleHosts.forEach { host -> add("https://$host$lessonsPath" to "Занятия") }
                scheduleHosts.forEach { host -> add("https://$host$examsPath" to "Экзамены") }
            }

            for ((pageUrl, kind) in indexPages) {
                runCatching { discover(pageUrl, kind) }
                    .onSuccess { found ->
                        if (found.isNotEmpty()) {
                            discovered += found
                            if (activeHost.isBlank()) activeHost = runCatching { URI(pageUrl).host.orEmpty() }.getOrDefault("")
                        }
                    }
                    .onFailure { discoveryErrors += "${URI(pageUrl).host}: ${it.message.orEmpty()}" }

                if (discovered.distinctBy { it.url }.size >= 12 && kind == "Экзамены") break
            }

            discoveredCount = discovered.distinctBy { canonicalPath(it.url) }.size
            val sources = discovered
                .distinctBy { canonicalPath(it.url) }
                .filter { isRelevant(it, profile) }
                .take(if (profile.role == UserRole.TEACHER) 180 else 40)
            matchedCount = sources.size

            if (sources.isEmpty()) {
                val detail = discoveryErrors.firstOrNull().orEmpty()
                error(if (detail.isBlank()) "Не удалось найти файлы расписания МГСУ" else "Сайт МГСУ временно недоступен ($detail)")
            }

            val parser = PdfScheduleParser(profile)
            val events = mutableListOf<ScheduleEvent>()

            for (source in sources) {
                runCatching {
                    val file = download(source.url)
                    events += parser.parse(file, source.url, source.label)
                    checked++
                }.onFailure { failedPdfs++ }
            }

            val unique = events.distinctBy { it.id }
                .sortedWith(compareBy<ScheduleEvent>({ it.exactDate ?: "9999" }, { it.weekday ?: 9 }, { it.startTime }))

            if (unique.isEmpty() && old.events.isNotEmpty() && (checked == 0 || failedPdfs > 0)) {
                val kept = old.copy(
                    lastError = "Не удалось получить свежие данные. Показываю последнее сохранённое расписание.",
                    sourcesChecked = checked
                )
                store.writeCache(kept)
                store.writeDiagnostics(
                    SyncDiagnostics(
                        lastAttemptMillis = attemptAt,
                        lastSuccessMillis = previousDiagnostics.lastSuccessMillis.takeIf { it > 0 } ?: old.lastSyncMillis,
                        activeHost = activeHost,
                        sourcesDiscovered = discoveredCount,
                        sourcesMatched = matchedCount,
                        sourcesChecked = checked,
                        parsedEvents = old.events.size,
                        failedPdfs = failedPdfs,
                        usedOfflineCache = true,
                        lastError = kept.lastError
                    )
                )
                return@withContext kept
            }

            val successAt = System.currentTimeMillis()
            val cache = CachedSchedule(
                events = unique,
                lastSyncMillis = successAt,
                lastError = if (checked == 0) "Не удалось прочитать файлы расписания" else "",
                sourcesChecked = checked
            )
            store.writeCache(cache)
            if (unique.isNotEmpty()) store.appendChanges(ChangeDetector.detect(old.events, unique, successAt))
            store.writeDiagnostics(
                SyncDiagnostics(
                    lastAttemptMillis = attemptAt,
                    lastSuccessMillis = if (cache.lastError.isBlank()) successAt else previousDiagnostics.lastSuccessMillis,
                    activeHost = activeHost,
                    sourcesDiscovered = discoveredCount,
                    sourcesMatched = matchedCount,
                    sourcesChecked = checked,
                    parsedEvents = unique.size,
                    failedPdfs = failedPdfs,
                    usedOfflineCache = false,
                    lastError = cache.lastError
                )
            )
            cache
        } catch (t: Throwable) {
            val error = humanError(t)
            val cache = old.copy(lastError = error)
            store.writeCache(cache)
            store.writeDiagnostics(
                SyncDiagnostics(
                    lastAttemptMillis = attemptAt,
                    lastSuccessMillis = previousDiagnostics.lastSuccessMillis.takeIf { it > 0 } ?: old.lastSyncMillis,
                    activeHost = activeHost,
                    sourcesDiscovered = discoveredCount,
                    sourcesMatched = matchedCount,
                    sourcesChecked = checked,
                    parsedEvents = old.events.size,
                    failedPdfs = failedPdfs,
                    usedOfflineCache = old.events.isNotEmpty(),
                    lastError = error
                )
            )
            cache
        }
    }

    private data class Source(val url: String, val label: String)

    private suspend fun discover(pageUrl: String, kind: String): List<Source> {
        val response = executeWithRetry(pageUrl, referer = "https://mgsu.ru/")
        response.use {
            val html = it.body?.string().orEmpty()
            if (html.isBlank()) error("пустой ответ")
            val doc = Jsoup.parse(html, pageUrl)
            return doc.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href")
                if (!href.substringBefore('?').endsWith(".pdf", true)) return@mapNotNull null

                val anchor = a.text().ifBlank { href.substringAfterLast('/') }
                val marker = "$href $anchor".lowercase()
                val actualKind = when {
                    marker.contains("raspisanie-ekzamenov") || marker.contains("экзам") || marker.contains("сесси") -> "Экзамены"
                    else -> kind
                }
                Source(href, "$actualKind · $anchor")
            }
        }
    }

    private fun isRelevant(source: Source, p: UserProfile): Boolean {
        if (p.role == UserRole.TEACHER) return true
        val s = (source.url + " " + source.label).uppercase()
        val aliases = mapOf(
            "ИАГ" to listOf("ИАГ", "IAG"),
            "ИПГС" to listOf("ИПГС", "IPGS"),
            "ИГЭС" to listOf("ИГЭС", "IGES", "GES"),
            "ИИЭСМ" to listOf("ИИЭСМ", "IIESM"),
            "ИЦТМС" to listOf("ИЦТМС", "ICTMS", "IZTMS"),
            "ИЭУКСН" to listOf("ИЭУКСН", "IEUKSN", "EUIS"),
            "ИИС ОИАЭ" to listOf("ИИС", "ОИАЭ", "OIAE"),
            "ИДО" to listOf("ИДО", "IDO")
        )
        val instituteOk = aliases[p.institute].orEmpty().any { s.contains(it) } || s.contains(p.institute.uppercase())
        if (!instituteOk) return false
        val c = p.course
        return s.contains("${c}К") || s.contains("${c}K") || s.contains("${c} K") || s.contains("${c} К") || !Regex("[1-6][КK]").containsMatchIn(s)
    }

    private suspend fun download(url: String): File {
        val name = sha1(canonicalPath(url)) + ".pdf"
        val dir = File(context.cacheDir, "mgsu_pdf").apply { mkdirs() }
        val file = File(dir, name)

        var last: Throwable? = null
        for (candidate in alternateOfficialUrls(url)) {
            try {
                val response = executeWithRetry(candidate, referer = "https://${URI(candidate).host}$lessonsPath")
                response.use {
                    val bytes = it.body?.bytes() ?: error("Пустой PDF")
                    if (bytes.size < 5 || bytes[0] != '%'.code.toByte() || bytes[1] != 'P'.code.toByte() || bytes[2] != 'D'.code.toByte() || bytes[3] != 'F'.code.toByte()) {
                        error("Сервер вернул не PDF")
                    }
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
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "text/html,application/xhtml+xml,application/pdf;q=0.9,*/*;q=0.8")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.6")
                    .header("Cache-Control", "no-cache")
                    .header("Referer", referer)
                    .build()
                val response = client.newCall(req).execute()
                if (response.isSuccessful) return response

                val code = response.code
                response.close()
                if (code !in RETRY_CODES) error("HTTP $code")
                lastError = IllegalStateException("HTTP $code")
            } catch (t: Throwable) {
                lastError = t
            }
            if (attempt < 2) delay((attempt + 1) * 900L)
        }
        throw lastError ?: IllegalStateException("Ошибка сети")
    }

    private fun alternateOfficialUrls(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        val host = uri.host ?: return listOf(url)
        if (host !in scheduleHosts) return listOf(url)

        return (listOf(host) + scheduleHosts).distinct().mapNotNull { newHost ->
            runCatching {
                URI(uri.scheme ?: "https", uri.userInfo, newHost, uri.port, uri.path, uri.query, uri.fragment).toString()
            }.getOrNull()
        }
    }

    private fun canonicalPath(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return url
        return (uri.path ?: url) + (uri.query?.let { "?$it" } ?: "")
    }

    private fun humanError(t: Throwable): String {
        val msg = t.message.orEmpty()
        return when {
            msg.contains("HTTP 502") -> "Сервер МГСУ ответил 502. Приложение попробовало официальные зеркала, но они тоже временно недоступны. Нажмите обновить позже."
            msg.contains("timeout", true) || msg.contains("timed out", true) -> "Сайт МГСУ отвечает слишком долго. Нажмите обновить ещё раз через минуту."
            msg.isBlank() -> "Не удалось обновить расписание"
            else -> msg
        }
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private val RETRY_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
