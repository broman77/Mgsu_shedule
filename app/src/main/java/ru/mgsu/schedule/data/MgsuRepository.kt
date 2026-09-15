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
import java.time.LocalDate
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
        "www.mgsu.ru",
        "www-20.mgsu.ru",
        "iges.mgsu.ru",
        "isa.mgsu.ru",
        "euis.mgsu.ru"
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
            val catalog = store.readCatalog()
            val targetTeacher = if (profile.role == UserRole.TEACHER) ScheduleParsingRules.canonicalTeacher(profile.teacher) else null
            val targetGroup = if (profile.role == UserRole.STUDENT) ScheduleParsingRules.canonicalGroup(profile.group) else null
            val hintedUrls = when (profile.role) {
                UserRole.TEACHER -> targetTeacher?.let { catalog.teacherSources[it] }.orEmpty()
                UserRole.STUDENT -> targetGroup?.let { catalog.groupSources[it] }.orEmpty()
            }
            val hintedSources = hintedUrls.map { url ->
                Source(url, catalog.sourceLabels[url] ?: inferSourceLabel(url))
            }

            val discovered = mutableListOf<Source>()
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

                // Once one official host produced a useful index for each section, mirrors only
                // duplicate the same PDF paths. Keep discovery quick on a phone.
                val lessonFound = discovered.any { !it.label.startsWith("Экзамены") }
                val examFound = discovered.any { it.label.startsWith("Экзамены") }
                if (lessonFound && examFound) break
            }

            val allDiscovered = discovered.distinctBy { canonicalPath(it.url) }
            discoveredCount = allDiscovered.size

            // First preference: the source index built on first launch. If a timetable file was
            // renamed or the index is stale, we still fall back to current official links.
            val primary = (hintedSources + allDiscovered.filter { isRelevant(it, profile) })
                .distinctBy { canonicalPath(it.url) }
            val fallback = allDiscovered.filter { src -> primary.none { canonicalPath(it.url) == canonicalPath(src.url) } }
            matchedCount = primary.size

            if (primary.isEmpty() && fallback.isEmpty()) {
                val detail = discoveryErrors.firstOrNull().orEmpty()
                error(if (detail.isBlank()) "Не удалось найти файлы расписания МГСУ" else "Сайт МГСУ временно недоступен ($detail)")
            }

            val parser = PdfScheduleParser(profile)
            val events = mutableListOf<ScheduleEvent>()
            val seenPdfDigests = mutableSetOf<String>()
            val seenPaths = mutableSetOf<String>()

            suspend fun parseSources(sources: List<Source>, limit: Int) {
                for (source in sources.take(limit)) {
                    val pathKey = canonicalPath(source.url)
                    if (!seenPaths.add(pathKey)) continue
                    runCatching {
                        val file = download(source.url)
                        checked++
                        val digest = sha256(file)
                        if (seenPdfDigests.add(digest)) {
                            events += parser.parse(file, source.url, source.label)
                        }
                    }.onFailure { failedPdfs++ }
                }
            }

            // Parse exact indexed PDFs first. This is normally just a handful of files.
            parseSources(primary, if (hintedSources.isNotEmpty()) 80 else 140)

            // If strict coordinate parsing found nothing, do not return an empty schedule just
            // because MGSU renamed a PDF or used an unexpected filename. Broaden once.
            if (events.isEmpty()) parseSources(fallback, 260)

            val unique = ScheduleSanitizer.clean(events, profile)
            val plausibilityError = schedulePlausibilityError(unique, profile)
            if (plausibilityError.isNotBlank()) {
                throw IllegalStateException(plausibilityError)
            }

            if (unique.isEmpty() && old.events.isNotEmpty()) {
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

            if (unique.isEmpty()) {
                throw IllegalStateException(
                    "PDF прочитаны, но занятия для выбранного профиля не распознаны. " +
                        "Проверьте выбранную группу/преподавателя и повторите обновление. " +
                        "Прочитано PDF: $checked, ошибок PDF: $failedPdfs."
                )
            }

            val successAt = System.currentTimeMillis()
            val cache = CachedSchedule(
                events = unique,
                parserVersion = AppStore.CURRENT_PARSER_VERSION,
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
                val href = normalizeOfficialUrl(a.absUrl("href"))
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

    private fun inferSourceLabel(url: String): String {
        val marker = url.lowercase()
        return if (marker.contains("raspisanie-ekzamenov") || marker.contains("sez") || marker.contains("exam")) {
            "Экзамены · ${url.substringAfterLast('/')}"
        } else {
            "Занятия · ${url.substringAfterLast('/')}"
        }
    }

    /**
     * Last-resort guard against catastrophic table parsing. A selected student/teacher cannot
     * physically have dozens of distinct classes on one calendar day. If a future PDF layout
     * breaks parsing, keep the previous cache instead of showing nonsense such as 61/94 classes.
     */
    private fun schedulePlausibilityError(events: List<ScheduleEvent>, profile: UserProfile): String {
        if (events.isEmpty()) return ""
        val semesterStart = runCatching { LocalDate.parse(profile.semesterStart) }.getOrElse { LocalDate.now().withDayOfMonth(1) }
        val from = maxOf(semesterStart, LocalDate.now().minusDays(14))
        for (offset in 0L..180L) {
            val date = from.plusDays(offset)
            val count = events.count { ScheduleParsingRules.occursOnDate(it, date, profile.semesterStart) }
            if (count > 12) {
                return "Проверка расписания отклонила некорректный результат ($count занятий на $date). Старые данные сохранены."
            }
        }
        return ""
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

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(32 * 1024)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
        private val RETRY_CODES = setOf(408, 425, 429, 500, 502, 503, 504)
    }
}
