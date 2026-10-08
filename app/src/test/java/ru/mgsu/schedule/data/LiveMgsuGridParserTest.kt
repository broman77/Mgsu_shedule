package ru.mgsu.schedule.data

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Optional live regression suite.
 *
 * Normal local unit tests skip this class. CI sets MGSU_LIVE_DIR after downloading the current
 * official PDFs from mgsu.ru. The same structural core used by Android is then fed real glyph
 * coordinates through desktop PDFBox.
 */
class LiveMgsuGridParserTest {
    @Test
    fun `current first-course PDFs never invent evening pair rows`() {
        val dir = liveDirOrSkip()
        val samples = listOf(
            Sample("IPGSb_1k_0610.pdf", "ИПГС", "ИПГС 1-1"),
            Sample("IGES_1k.pdf", "ИГЭС", "ИГЭС 1-1"),
            Sample("IIESM_1k_0210.pdf", "ИИЭСМ", "ИИЭСМ 1-10")
        )

        for (sample in samples) {
            val file = File(dir, sample.fileName)
            assertTrue("Live fixture missing: ${sample.fileName}", file.isFile && file.length() > 10_000)
            val events = parse(file, sample.institute, sample.group)
            assertTrue("${sample.group}: parser returned no lessons", events.isNotEmpty())
            assertTrue("${sample.group}: invalid weekday", events.all { it.weekday in 1..6 })
            assertTrue("${sample.group}: more than one structural event per official pair row", events.groupingBy { it.weekday }.eachCount().values.all { it <= 8 })
            assertFalse(
                "${sample.group}: fabricated late pair found: ${events.filter { it.startTime in LATE_STARTS }}",
                events.any { it.startTime in LATE_STARTS }
            )
        }
    }

    @Test
    fun `current IAG 1-41 Monday ends at 13-00`() {
        val dir = liveDirOrSkip()
        val file = File(dir, "IAG_1k_0826_20.pdf")
        assertTrue("Live IAG fixture missing", file.isFile && file.length() > 10_000)

        val events = parse(file, "ИАГ", "ИАГ 1-41")
        assertTrue("ИАГ 1-41: parser returned no lessons", events.isNotEmpty())
        val monday = events.filter { it.weekday == 1 }.sortedBy { it.startTime }
        assertTrue("ИАГ 1-41: Monday is empty", monday.isNotEmpty())
        assertEquals("ИАГ 1-41: wrong final Monday pair", "13:00", monday.last().startTime)
        assertFalse("ИАГ 1-41: false late Monday lesson", monday.any { it.startTime in setOf("14:30", "16:00", "18:10", "19:40") })
    }

    private fun parse(file: File, institute: String, group: String): List<ScheduleEvent> {
        val profile = UserProfile(
            role = UserRole.STUDENT,
            institute = institute,
            course = 1,
            studyForm = "Очная",
            group = group,
            semesterStart = "2026-08-31"
        )
        return extractPages(file).flatMap { page ->
            MgsuGridParserCore.parseLessons(page, profile, file.toURI().toString(), "$institute 1 курс live")
        }.distinctBy { it.id }
    }

    private fun extractPages(file: File): List<MgsuGridParserCore.Page> {
        PDDocument.load(file).use { document ->
            val stripper = DesktopStripper()
            stripper.sortByPosition = true
            stripper.getText(document)
            return stripper.glyphs.groupBy { it.page }.map { (pageNo, glyphs) ->
                val page = document.getPage(pageNo - 1)
                val rotated = ((page.rotation % 180) + 180) % 180 == 90
                val visualWidth = if (rotated) page.mediaBox.height else page.mediaBox.width
                val visualHeight = if (rotated) page.mediaBox.width else page.mediaBox.height
                MgsuGridParserCore.Page(pageNo, visualWidth, visualHeight, glyphs)
            }
        }
    }

    private fun liveDirOrSkip(): File {
        val raw = System.getenv("MGSU_LIVE_DIR").orEmpty()
        assumeTrue("MGSU_LIVE_DIR is not set; live parser verification skipped", raw.isNotBlank())
        val dir = File(raw)
        assumeTrue("MGSU_LIVE_DIR does not exist", dir.isDirectory)
        return dir
    }

    private data class Sample(val fileName: String, val institute: String, val group: String)

    private class DesktopStripper : PDFTextStripper() {
        val glyphs = mutableListOf<MgsuGridParserCore.Glyph>()
        private var pageNo = 0

        override fun startPage(page: PDPage?) {
            pageNo++
            super.startPage(page)
        }

        override fun processTextPosition(text: TextPosition) {
            val unicode = text.unicode ?: return
            if (unicode.isBlank()) return
            glyphs += MgsuGridParserCore.Glyph(
                page = pageNo,
                x = text.xDirAdj,
                y = text.yDirAdj,
                width = text.widthDirAdj,
                height = text.heightDir,
                text = unicode
            )
        }
    }

    companion object {
        private val LATE_STARTS = setOf("18:10", "19:40")
    }
}
