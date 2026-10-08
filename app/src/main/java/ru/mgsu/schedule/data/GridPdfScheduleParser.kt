package ru.mgsu.schedule.data

import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import java.io.File

/**
 * Thin PDFBox adapter for the new structural parser.
 * Text glyphs and drawn table borders are extracted here; all timetable decisions live in
 * [MgsuGridParserCore].
 */
class GridPdfScheduleParser(private val profile: UserProfile) {
    fun parse(file: File, sourceUrl: String, sourceLabel: String): List<ScheduleEvent> {
        PDDocument.load(file).use { document ->
            val stripper = CoordinateStripper()
            stripper.sortByPosition = true
            stripper.getText(document)

            val pages = stripper.glyphs
                .groupBy { it.page }
                .mapNotNull { (pageNo, glyphs) ->
                    val page = document.getPage(pageNo - 1)
                    val rotated = ((page.rotation % 180) + 180) % 180 == 90
                    val visualWidth = if (rotated) page.mediaBox.height else page.mediaBox.width
                    val visualHeight = if (rotated) page.mediaBox.width else page.mediaBox.height
                    val rules = runCatching { PdfGridRuleExtractor(page).extract() }.getOrDefault(emptyList())
                    MgsuGridParserCore.Page(
                        number = pageNo,
                        width = visualWidth,
                        height = visualHeight,
                        glyphs = glyphs,
                        rules = rules
                    )
                }

            val marker = "$sourceLabel $sourceUrl".lowercase()
            val exam = marker.contains("экзам") || marker.contains("сесси") ||
                marker.contains("raspisanie-ekzamenov") || marker.contains("_sess") || marker.contains("sez")

            return pages.flatMap { page ->
                if (exam) {
                    MgsuGridParserCore.parseExams(page, profile, sourceUrl, sourceLabel)
                } else {
                    MgsuGridParserCore.parseLessons(page, profile, sourceUrl, sourceLabel)
                }
            }.distinctBy { it.id }
        }
    }

    private class CoordinateStripper : PDFTextStripper() {
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
}
