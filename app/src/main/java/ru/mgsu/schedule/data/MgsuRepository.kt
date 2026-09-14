package ru.mgsu.schedule.data

import android.content.Context
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class MgsuRepository(private val context: Context) {
    private val store = AppStore(context)
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val lessonsPage = "https://mgsu.ru/student/Raspisanie_zanyatii_i_ekzamenov/fayly-raspisaniya-dlya-skachivaniya/"
    private val examsPage = "https://mgsu.ru/student/Raspisanie_zanyatii_i_ekzamenov/raspisanie-ekzamenov/"

    init { PDFBoxResourceLoader.init(context) }

    suspend fun sync(profile: UserProfile): CachedSchedule = withContext(Dispatchers.IO) {
        try {
            val sources = (discover(lessonsPage, "Занятия") + discover(examsPage, "Экзамены"))
                .distinctBy { it.url }
                .filter { isRelevant(it, profile) }
                .take(if (profile.role == UserRole.TEACHER) 80 else 28)

            val parser = PdfScheduleParser(profile)
            val events = mutableListOf<ScheduleEvent>()
            var checked = 0
            for (source in sources) {
                runCatching {
                    val file = download(source.url)
                    events += parser.parse(file, source.url, source.label)
                    checked++
                }
            }
            val unique = events.distinctBy { it.id }
                .sortedWith(compareBy<ScheduleEvent>({ it.exactDate ?: "9999" }, { it.weekday ?: 9 }, { it.startTime }))
            val cache = CachedSchedule(unique, System.currentTimeMillis(), "", checked)
            store.writeCache(cache)
            cache
        } catch (t: Throwable) {
            val old = store.readCache()
            val cache = old.copy(lastError = t.message ?: "Не удалось обновить расписание")
            store.writeCache(cache)
            cache
        }
    }

    private data class Source(val url: String, val label: String)

    private fun discover(pageUrl: String, kind: String): List<Source> {
        val req = Request.Builder().url(pageUrl).header("User-Agent", "MGSU-Schedule/1.0 Android").build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("MGSU HTTP ${response.code}")
            val html = response.body?.string().orEmpty()
            val doc = Jsoup.parse(html, pageUrl)
            return doc.select("a[href]").mapNotNull { a ->
                val href = a.absUrl("href")
                if (!href.substringBefore('?').endsWith(".pdf", true)) null
                else Source(href, "$kind · ${a.text().ifBlank { href.substringAfterLast('/') }}")
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

    private fun download(url: String): File {
        val name = sha1(url) + ".pdf"
        val dir = File(context.cacheDir, "mgsu_pdf").apply { mkdirs() }
        val file = File(dir, name)
        val req = Request.Builder().url(url).header("User-Agent", "MGSU-Schedule/1.0 Android").build()
        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) error("PDF HTTP ${response.code}")
            file.outputStream().use { out -> response.body?.byteStream()?.copyTo(out) ?: error("Пустой PDF") }
        }
        return file
    }

    private fun sha1(s: String): String = MessageDigest.getInstance("SHA-1")
        .digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
