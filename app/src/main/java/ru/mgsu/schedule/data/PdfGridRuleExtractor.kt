package ru.mgsu.schedule.data

import android.graphics.Path
import android.graphics.PointF
import com.tom_roush.pdfbox.contentstream.PDFGraphicsStreamEngine
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImage

/**
 * Extracts visible table-rule geometry from an official MGSU PDF page.
 *
 * Text coordinates alone are not sufficient for MGSU schedules: several spreadsheets use
 * unequal-height pair rows and merged sub-rows.  The drawn table borders are the authoritative
 * cell boundaries, so parser v9 reads them directly and uses text midpoints only as a fallback.
 */
internal class PdfGridRuleExtractor(private val page: PDPage) : PDFGraphicsStreamEngine(page) {
    private val rules = mutableListOf<MgsuGridParserCore.Rule>()
    private var current: PointF? = null
    private var subPathStart: PointF? = null

    fun extract(): List<MgsuGridParserCore.Rule> {
        processPage(page)
        return rules
            .filter { it.length >= 8f }
            .distinctBy {
                listOf(it.x1, it.y1, it.x2, it.y2).joinToString(":") { value ->
                    (value * 2f).toInt().toString()
                }
            }
    }

    override fun appendRectangle(p0: PointF, p1: PointF, p2: PointF, p3: PointF) {
        addSegment(p0, p1)
        addSegment(p1, p2)
        addSegment(p2, p3)
        addSegment(p3, p0)
        current = PointF(p0.x, p0.y)
        subPathStart = PointF(p0.x, p0.y)
    }

    override fun moveTo(x: Float, y: Float) {
        current = PointF(x, y)
        subPathStart = PointF(x, y)
    }

    override fun lineTo(x: Float, y: Float) {
        val next = PointF(x, y)
        current?.let { addSegment(it, next) }
        current = next
    }

    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        current = PointF(x3, y3)
    }

    override fun getCurrentPoint(): PointF? = current

    override fun closePath() {
        val start = subPathStart
        val end = current
        if (start != null && end != null) addSegment(end, start)
        current = start
    }

    override fun endPath() {
        current = null
        subPathStart = null
    }

    override fun strokePath() = endPath()
    override fun fillPath(windingRule: Path.FillType) = endPath()
    override fun fillAndStrokePath(windingRule: Path.FillType) = endPath()
    override fun clip(windingRule: Path.FillType) = Unit
    override fun drawImage(pdImage: PDImage) = Unit
    override fun shadingFill(shadingName: COSName) = Unit

    private fun addSegment(a: PointF, b: PointF) {
        val av = toVisual(a)
        val bv = toVisual(b)
        val dx = kotlin.math.abs(av.x - bv.x)
        val dy = kotlin.math.abs(av.y - bv.y)
        // Timetable borders are axis-aligned. Ignore curves/diagonals and tiny decorative marks.
        if (dx < 1.8f || dy < 1.8f) {
            rules += MgsuGridParserCore.Rule(av.x, av.y, bv.x, bv.y)
        }
    }

    private fun toVisual(point: PointF): PointF {
        val box = page.mediaBox
        val x = point.x - box.lowerLeftX
        val y = point.y - box.lowerLeftY
        val rotation = ((page.rotation % 360) + 360) % 360
        return when (rotation) {
            90 -> PointF(y, x)
            180 -> PointF(box.width - x, y)
            270 -> PointF(box.height - y, box.width - x)
            else -> PointF(x, box.height - y)
        }
    }
}
