package ru.mgsu.schedule.data;

import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JVM-only counterpart of PdfGridRuleExtractor. Keeping this helper in Java avoids Android's
 * Kotlin compile classpath hiding java.awt.geom while still exercising desktop PDFBox in CI.
 */
public final class DesktopPdfGridRuleExtractor extends PDFGraphicsStreamEngine {
    private final PDPage page;
    private final List<MgsuGridParserCore.Rule> rules = new ArrayList<>();
    private Point2D current;
    private Point2D subPathStart;

    public DesktopPdfGridRuleExtractor(PDPage page) {
        super(page);
        this.page = page;
    }

    public List<MgsuGridParserCore.Rule> extract() throws IOException {
        processPage(page);
        Map<String, MgsuGridParserCore.Rule> unique = new LinkedHashMap<>();
        for (MgsuGridParserCore.Rule rule : rules) {
            float length = Math.max(Math.abs(rule.getX2() - rule.getX1()), Math.abs(rule.getY2() - rule.getY1()));
            if (length < 8f) continue;
            String key = Math.round(rule.getX1() * 2f) + ":" + Math.round(rule.getY1() * 2f) + ":" +
                Math.round(rule.getX2() * 2f) + ":" + Math.round(rule.getY2() * 2f);
            unique.putIfAbsent(key, rule);
        }
        return new ArrayList<>(unique.values());
    }

    @Override
    public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
        addSegment(p0, p1);
        addSegment(p1, p2);
        addSegment(p2, p3);
        addSegment(p3, p0);
        current = new Point2D.Float((float) p0.getX(), (float) p0.getY());
        subPathStart = current;
    }

    @Override
    public void moveTo(float x, float y) {
        current = new Point2D.Float(x, y);
        subPathStart = current;
    }

    @Override
    public void lineTo(float x, float y) {
        Point2D next = new Point2D.Float(x, y);
        if (current != null) addSegment(current, next);
        current = next;
    }

    @Override
    public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
        current = new Point2D.Float(x3, y3);
    }

    @Override
    public Point2D getCurrentPoint() {
        return current;
    }

    @Override
    public void closePath() {
        if (subPathStart != null && current != null) addSegment(current, subPathStart);
        current = subPathStart;
    }

    @Override
    public void endPath() {
        current = null;
        subPathStart = null;
    }

    @Override public void strokePath() { endPath(); }
    @Override public void fillPath(int windingRule) { endPath(); }
    @Override public void fillAndStrokePath(int windingRule) { endPath(); }
    @Override public void clip(int windingRule) { }
    @Override public void drawImage(PDImage pdImage) { }
    @Override public void shadingFill(COSName shadingName) { }

    private void addSegment(Point2D a, Point2D b) {
        float[] av = toVisual(a);
        float[] bv = toVisual(b);
        float dx = Math.abs(av[0] - bv[0]);
        float dy = Math.abs(av[1] - bv[1]);
        if (dx < 1.8f || dy < 1.8f) {
            rules.add(new MgsuGridParserCore.Rule(av[0], av[1], bv[0], bv[1]));
        }
    }

    private float[] toVisual(Point2D point) {
        PDRectangle box = page.getMediaBox();
        float x = (float) point.getX() - box.getLowerLeftX();
        float y = (float) point.getY() - box.getLowerLeftY();
        int rotation = ((page.getRotation() % 360) + 360) % 360;
        switch (rotation) {
            case 90: return new float[] { y, x };
            case 180: return new float[] { box.getWidth() - x, y };
            case 270: return new float[] { box.getHeight() - y, box.getWidth() - x };
            default: return new float[] { x, box.getHeight() - y };
        }
    }
}
