package ru.mgsu.schedule.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Downloads and parses only the official timetable PDFs known to contain the selected student
 * group. Source discovery/indexing lives in [MgsuCatalogRepository].
 */
class MgsuRepository(private val context: Context) {
    private val store = AppStore(context)
    private val catalogRepository = MgsuCatalogRepository(context)
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

    init { PDFBoxResourceLoader.init(context) }

    suspend fun sync(profile: UserProfile): CachedSchedule = withContext(Dispatchers.IO) {
        val old = store.readCache()
        val previousDiagnostics = store.readDiagnostics()
        val attemptAt = System.currentTimeMillis()
        var checked = 0
        var failedPdfs = 0
        var matchedCount = 0
        var discoveredCount = 0
        var activeHost = ""

        fun writeDiagnostics(error: String, parsed: Int, offline: Boolean, successAt: Long = 0L) {
            store.writeDiagnostics(
                SyncDiagnostics(
                    lastAttemptMillis = attemptAt,
                    lastSuccessMillis = when {
                        successAt > 0L -> successAt
                        previousDiagnostics.lastSuccessMillis > 0L -> previousDiagnostics.lastSuccessMillis
                        else -> old.lastSyncMillis
                    },
                    activeHost = activeHost,
                    sourcesDiscovered = discoveredCount,
                    sourcesMatched = matchedCount,
                    sourcesChecked = checked,
                    parsedEvents = parsed,
                    failedPdfs = failedPdfs,
                    usedOfflineCache = offline,
                    lastError = error
                )
            )
        }

        try {
            if (profile.role != UserRole.STUDENT) {
                error("Эта версия приложения предназначена только для студентов")
            }
            val targetGroup = profile.group.takeIf(ScheduleParsingRules::isValidGroup)
                ?.let(ScheduleParsingRules::canonicalGroup)
                ?: error("Не выбрана корректная учебная группа")

            suspend fun indexedSources(force: Boolean): Pair<SuggestionCatalog, List<Source>> {
                val catalog = catalogRepository.refreshGroups(profile.institute, profile.course, force)
                discoveredCount = catalog.sourceLabels.size
                val urls = catalog.groupSources[targetGroup].orEmpty()
                val sources = urls.map { url -> Source(url, catalog.sourceLabels[url] ?: inferSourceLabel(url)) }
                    .distinctBy { MgsuSourceRules.canonicalPath(it.url) }
                    .sortedWith(compareBy<Source> { MgsuSourceRules.sourcePriority(it.url, it.label) }.thenBy { it.label })
                matchedCount = sources.size
                return catalog to sources
            }

            suspend fun parse(sources: List<Source>): List<ScheduleEvent> {
                // Parser v9 is a complete structural rewrite. The legacy PdfScheduleParser remains
                // in the repository only for rollback/reference and is no longer used at runtime.
                val parser = GridPdfScheduleParser(profile)
                val events = mutableListOf<ScheduleEvent>()
                val seenDigests = mutableSetOf<String>()
                for (source in sources.take(MAX_EXACT_SOURCES)) {
                    runCatching {
                        val file = download(source.url)
                        checked++
                        if (activeHost.isBlank()) activeHost = runCatching { URI(source.url).host.orEmpty() }.getOrDefault("")
                        val digest = sha256(file)
                        if (seenDigests.add(digest)) {
                            events += parser.parse(file, source.url, source.label)
                        }
                    }.onFailure { failedPdfs++ }
                }
                return ScheduleSanitizer.clean(events, profile)
            }

            var sources = indexedSources(force = false).second
            if (sources.isEmpty()) {
                sources = indexedSources(force = true).second
            }
            if (sources.isEmpty()) error("Для группы $targetGroup не найден официальный файл расписания")

            var unique = parse(sources)

            // A semester update can rename the same institute/course PDF. If the previously indexed
            // exact URL no longer produces the group, refresh the official page and rebuild just
            // this student's group index once, then retry. Never scan unrelated university PDFs.
            if (unique.isEmpty()) {
                val refreshed = indexedSources(force = true).second
                val oldPaths = sources.map { MgsuSourceRules.canonicalPath(it.url) }.toSet()
                val changed = refreshed.any { MgsuSourceRules.canonicalPath(it.url) !in oldPaths }
                if (changed || failedPdfs > 0) {
                    sources = refreshed
                    unique = parse(sources)
                }
            }

            schedulePlausibilityError(unique)?.let { error(it) }

            if (unique.isEmpty() && old.events.isNotEmpty()) {
                val message = "Не удалось получить свежие данные. Показываю последнее сохранённое расписание."
                val kept = old.copy(lastError = message, sourcesChecked = checked)
                store.writeCache(kept)
                writeDiagnostics(message, old.events.size, offline = true)
                return@withContext kept
            }

            if (unique.isEmpty()) {
                error(
                    "PDF МГСУ получен, но занятия группы $targetGroup не распознаны. " +
                        "Проверено PDF: $checked, ошибок загрузки/чтения: $failedPdfs."
                )
            }

            val successAt = System.currentTimeMillis()
            val cache = CachedSchedule(
                events = unique,
                parserVersion = AppStore.CURRENT_PARSER_VERSION,
                lastSyncMillis = successAt,
                lastError = "",
                sourcesChecked = checked
            )
            store.writeCache(cache)
            store.appendChanges(ChangeDetector.detect(old.events, unique, successAt))
            writeDiagnostics("", unique.size, offline = false, successAt = successAt)
            catalogRepository.mergeFromSchedule(cache)
            cache
        } catch (t: Throwable) {
            val error = humanError(t)
            val cache = if (old.events.isNotEmpty()) {
                old.copy(lastError = error)
            } else {
                CachedSchedule(
                    events = emptyList(),
                    parserVersion = AppStore.CURRENT_PARSER_VERSION,
                    lastSyncMillis = 0L,
                    lastError = error,
                    sourcesChecked = checked
                )
            }
            store.writeCache(cache)
            writeDiagnostics(error, cache.events.size, offline = cache.events.isNotEmpty())
            cache
        }
    }

    private data class Source(val url: String, val label: String)

    private suspend fun download(url: String): File {
        val name = sha1(MgsuSourceRules.canonicalPath(url)) + ".pdf"
        val dir = File(context.cacheDir, "mgsu_pdf").apply { mkdirs() }
        val file = File(dir, name)

        var last: Throwable? = null
        for (candidate in alternateOfficialUrls(url)) {
            try {
                executeWithRetry(candidate, referer = "https://${URI(candidate).host}$lessonsPath").use { response ->
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
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", BROWSER_UA)
                    .header("Accept", "application/pdf,text/html;q=0.8,*/*;q=0.5")
                    .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.6")
                    .header("Cache-Control", "no-cache")
                    .header("Referer", referer)
                    .build()
                val response = client.newCall(req).execute()
                if (response.isSuccessful) return response
                val code = response.code
                response.close()
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

    private fun inferSourceLabel(url: String): String {
        val kind = MgsuSourceRules.classifyKind(url, "", "Занятия")
        return "$kind · ${url.substringAfterLast('/')}"
    }

    /**
     * One structural weekly table cannot contain more than the eight official MGSU pair slots
     * for a student on one weekday. Reject anything beyond that instead of ever showing a
     * fabricated late-evening lesson.
     */
    private fun schedulePlausibilityError(events: List<ScheduleEvent>): String? {
        if (events.isEmpty()) return null
        val worstWeekday = events.filter { it.exactDate == null }.groupingBy { it.weekday }.eachCount().values.maxOrNull() ?: 0
        val worstDate = events.mapNotNull { e -> e.exactDate?.let { it to e } }
            .groupingBy { it.first }.eachCount().values.maxOrNull() ?: 0
        return if (worstWeekday > 8 || worstDate > 14 || events.size > 180) {
            "Формат PDF МГСУ изменился: распознано неправдоподобно много занятий. Старый кэш сохранён."
        } else null
    }

    private fun humanError(t: Throwable): String {
        val raw = t.message?.trim().orEmpty()
        return when {
            raw.isBlank() -> "Не удалось обновить расписание МГСУ"
            raw.contains("timeout", ignoreCase = true) -> "Сайт МГСУ отвечает слишком долго. Показываю сохранённые данные, если они есть."
            raw.contains("Unable to resolve host", ignoreCase = true) || raw.contains("Failed to connect", ignoreCase = true) ->
                "Нет соединения с сайтом МГСУ. Показываю сохранённые данные, если они есть."
            else -> raw
        }
    }

    private fun looksLikePdf(bytes: ByteArray): Boolean = bytes.size > 5 &&
        bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
        bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte()

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_EXACT_SOURCES = 16
        private const val BROWSER_UA = "Mozilla/5.0 (Linux; Android 16; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"
    }
}
