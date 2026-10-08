package ru.mgsu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LessonRowGeometryTest {
    @Test
    fun splitStartTimeUsesLeadingEdgeInsteadOfMidpoint() {
        val anchors = (0..5).map { index ->
            LessonRowGeometry.Anchor(y = 100f + index * 30f, startsAtTop = true)
        }

        val tops = LessonRowGeometry.estimatedTops(anchors)
        // Old midpoint logic would put the third-row top at 145, inside the previous cell.
        // For split 08.30 / 09.50 labels the real boundary must stay close to the 160 baseline.
        assertTrue(tops[2] > 150f)
        assertTrue(tops[2] < 160f)

        val band = LessonRowGeometry.band(anchors, 2, 400f)!!
        assertEquals(tops[2], band.first, 0.01f)
        assertEquals(tops[3], band.second, 0.01f)
    }

    @Test
    fun oneLineFullRangeKeepsMidpointGeometry() {
        val anchors = listOf(
            LessonRowGeometry.Anchor(100f, startsAtTop = false),
            LessonRowGeometry.Anchor(130f, startsAtTop = false),
            LessonRowGeometry.Anchor(160f, startsAtTop = false)
        )

        val tops = LessonRowGeometry.estimatedTops(anchors)
        assertEquals(85f, tops[0], 0.01f)
        assertEquals(115f, tops[1], 0.01f)
        assertEquals(145f, tops[2], 0.01f)
    }

    @Test
    fun lastRowUsesOneLocalRowHeightInsteadOfRunningToPageBottom() {
        val anchors = (0..5).map { index ->
            LessonRowGeometry.Anchor(y = 100f + index * 30f, startsAtTop = true)
        }

        val band = LessonRowGeometry.band(anchors, 5, 1000f)!!
        assertTrue(band.second - band.first in 29f..31f)
        assertTrue(band.second < 300f)
    }

    @Test
    fun `last row of one day is bounded using only that day's anchors`() {
        // Monday has pairs 1..8; Tuesday starts immediately below. The old page-global geometry
        // used Tuesday's first anchor as the bottom of Monday pair 8, allowing Tuesday text to
        // become a fake Monday 19:40 lesson. Per-day geometry must end pair 8 by Monday's own gap.
        val monday = listOf(
            LessonRowGeometry.Anchor(100f, true),
            LessonRowGeometry.Anchor(132f, true),
            LessonRowGeometry.Anchor(164f, true),
            LessonRowGeometry.Anchor(196f, true),
            LessonRowGeometry.Anchor(228f, true),
            LessonRowGeometry.Anchor(260f, true),
            LessonRowGeometry.Anchor(292f, true),
            LessonRowGeometry.Anchor(324f, true)
        )
        val pair8 = LessonRowGeometry.band(monday, 7, 700f)!!
        assertTrue(pair8.second < 356f)
        assertTrue(pair8.second > 324f)
    }

}
