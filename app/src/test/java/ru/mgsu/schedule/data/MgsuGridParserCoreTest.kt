package ru.mgsu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MgsuGridParserCoreTest {
    @Test
    fun `pair cycles are split by pair-number reset`() {
        val anchors = buildList {
            repeat(6) { day ->
                repeat(6) { pair ->
                    add(MgsuGridParserCore.PairAnchor(pair + 1, 100f + day * 180f + pair * 24f))
                }
            }
        }

        val cycles = MgsuGridParserCore.splitPairCycles(anchors)
        assertEquals(6, cycles.size)
        cycles.forEach { cycle ->
            assertEquals(listOf(1, 2, 3, 4, 5, 6), cycle.map { it.pairNo })
        }
    }

    @Test
    fun `row bands never cross into next weekday`() {
        val cycles = (0 until 6).map { day ->
            (1..6).map { pair ->
                MgsuGridParserCore.PairAnchor(pair, 120f + day * 190f + (pair - 1) * 26f)
            }
        }
        val bounded = MgsuGridParserCore.assignAndBoundCycles(cycles, emptyMap(), 70f, 1400f)

        assertEquals(6, bounded.size)
        for (i in 0 until bounded.lastIndex) {
            assertTrue(bounded[i].bottom <= bounded[i + 1].top + 0.01f)
            assertTrue(bounded[i].anchors.last().y < bounded[i].bottom)
            assertTrue(bounded[i + 1].anchors.first().y > bounded[i + 1].top)
        }
    }

    @Test
    fun `actual grid rules bound the exact group cell instead of text midpoints`() {
        val column = MgsuGridParserCore.Column(
            group = "ИАГ 1-41",
            header = "ИАГ 1к 41",
            center = 250f,
            left = 200f,
            right = 300f,
            headerY = 40f
        )
        val rules = listOf(
            MgsuGridParserCore.Rule(180f, 100f, 320f, 100f),
            MgsuGridParserCore.Rule(180f, 130f, 320f, 130f),
            MgsuGridParserCore.Rule(180f, 160f, 320f, 160f),
            // A neighboring column's line must not affect this group's cell bounds.
            MgsuGridParserCore.Rule(20f, 118f, 150f, 118f)
        )

        assertEquals(100f to 130f, MgsuGridParserCore.gridCellBounds(rules, column, 112f, 90f, 170f))
        assertEquals(130f to 160f, MgsuGridParserCore.gridCellBounds(rules, column, 142f, 90f, 170f))
        assertTrue(MgsuGridParserCore.hasHorizontalGrid(rules, column, 90f, 170f))
        assertEquals(null, MgsuGridParserCore.gridCellBounds(rules, column, 175f, 90f, 170f))
    }

    @Test
    fun `synthetic IAG 1-41 Monday stops at 13-00`() {
        val glyphs = mutableListOf<MgsuGridParserCore.Glyph>()
        fun add(x: Float, y: Float, text: String, width: Float = 12f) {
            glyphs += MgsuGridParserCore.Glyph(1, x, y, width, 8f, text)
        }

        // Header: dedicated pair column + two concrete student groups.
        add(94f, 44f, "Пара", 26f)
        add(230f, 44f, "ИАГ 1к 41", 70f)
        add(530f, 44f, "ИАГ 1к 42", 70f)

        val firstPairY = 92f
        val dayHeight = 180f
        val rowGap = 25f
        repeat(6) { day ->
            repeat(6) { pairIndex ->
                val y = firstPairY + day * dayHeight + pairIndex * rowGap
                add(100f, y, (pairIndex + 1).toString(), 5f)
            }
        }

        // Monday: only pairs 1, 2 and 4 are occupied; pair 4 (13:00) is the final lesson.
        add(235f, firstPairY, "л.Математика")
        add(235f, firstPairY + rowGap, "пр.Архитектурное проектирование")
        add(235f, firstPairY + rowGap * 3, "пр.Начертательная геометрия")

        // Other weekdays contain a few ordinary rows so all six day cycles are realistic.
        add(235f, firstPairY + dayHeight + rowGap * 2, "пр.История России")
        add(235f, firstPairY + dayHeight * 2 + rowGap * 5, "л.Физика")
        add(235f, firstPairY + dayHeight * 3 + rowGap, "пр.Иностранный язык")
        add(235f, firstPairY + dayHeight * 4 + rowGap * 4, "лаб.Материаловедение")

        val page = MgsuGridParserCore.Page(1, 900f, 1300f, glyphs)
        val profile = UserProfile(
            role = UserRole.STUDENT,
            institute = "ИАГ",
            course = 1,
            studyForm = "Очная",
            group = "ИАГ 1-41"
        )

        val events = MgsuGridParserCore.parseLessons(page, profile, "https://mgsu.ru/test.pdf", "ИАГ 1 курс")
        val monday = events.filter { it.weekday == 1 }.sortedBy { it.startTime }

        assertEquals(listOf("08:30", "10:00", "13:00"), monday.map { it.startTime })
        assertFalse(monday.any { it.startTime in setOf("14:30", "16:00", "18:10", "19:40") })
        assertEquals("13:00", monday.last().startTime)
        assertTrue(events.all { it.startTime in ScheduleParsingRules.officialPairTimes.values.map { time -> time.first } })
    }
}
