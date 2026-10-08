package ru.mgsu.schedule.data

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.graphics.image.PDImage
import java.awt.geom.Point2D

/** Desktop counterpart of PdfGridRuleExtractor used only by JVM/live official-PDF tests. */
internal class DesktopPdfGridRuleExtractor(private val page: PDPage) : PDFGraphicsStreamEngine(page) {
    private val rules = mutableListOf<MgsuGridParserCore.Rule>()
    private var current: Point2D? = null
    private var subPathStart: Point2D? = null

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

    override fun appendRectangle(p0: Point2D, p1: Point2D, p2: Point2D, p3: Point2D) {
        addSegment(p0, p1)
        addSegment(p1, p2)
        addSegment(p2, p3)
        addSegment(p3, p0)
        current = Point2D.Float(p0.x.toFloat(), p0.y.toFloat())
        subPathStart = current
    }

    override fun moveTo(x: Float, y: Float) {
        current = Point2D.Float(x, y)
        subPathStart = current
    }

    override fun lineTo(x: Float, y: Float) {
        val next = Point2D.Float(x, y)
        current?.let { addSegment(it, next) }
        current = next
    }

    override fun curveTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        current = Point2D.Float(x3, y3)
    }

    override fun getCurrentPoint(): Point2D? = current

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
    override fun fillPath(windingRule: Int) = endPath()
    override fun fillAndStrokePath(windingRule: Int) = endPath()
    override fun clip(windingRule: Int) = Unit
    override fun drawImage(pdImage: PDImage) = Unit
    override fun shadingFill(shadingName: COSName) = Unit

    private fun addSegment(a: Point2D, b: Point2D) {
        val av = toVisual(a)
        val bv = toVisual(b)
        val dx = kotlin.math.abs(av.first - bv.first)
        val dy = kotlin.math.abs(av.second - bv.second)
        if (dx < 1.8f || dy < 1.8f) {
            rules += MgsuGridParserCore.Rule(av.first, av.second, bv.first, bv.second)
        }
    }

    private fun toVisual(point: Point2D): Pair<Float, Float> {
        val box = page.mediaBox
        val x = point.x.toFloat() - box.lowerLeftX
        val y = point.y.toFloat() - box.lowerLeftY
        val rotation = ((page.rotation % 360) + 360) % 360
        return when (rotation) {
            90 -> y to x
            180 -> (box.width - x) to y
            270 -> (box.height - y) to (box.width - x)
            else -> x to (box.height - y)
        }
    }
}
