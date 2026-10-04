package ru.mgsu.schedule.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleParsingRulesTest {
    @Test fun groupParserRequiresConcreteGroup() {
        assertEquals("ИПГС 3-18", ScheduleParsingRules.canonicalGroup("ИПГС 3к 18 bo 08.03.01_ПГС"))
        assertTrue(ScheduleParsingRules.isValidGroup("ИПГС 3-18"))
        assertTrue(ScheduleParsingRules.isValidGroup("ИПГС 3к 18"))
        assertFalse(ScheduleParsingRules.isValidGroup("ИПГС 1 курс"))
        assertFalse(ScheduleParsingRules.isValidGroup("ИПГС 1 курс расписание ИПГС 3-18"))
    }

    @Test fun onlyOfficialLessonTimesAreAccepted() {
        assertEquals("08:30" to "09:50", ScheduleParsingRules.normalizeLessonTimeRange("08.30 - 09.50"))
        assertEquals("11:30" to "12:50", ScheduleParsingRules.normalizeLessonTimeRange("11:30–12:50"))
        assertNull(ScheduleParsingRules.normalizeLessonTimeRange("08.30 - 20.03"))
        assertNull(ScheduleParsingRules.normalizeLessonTimeRange("08.03.01"))
    }

    @Test fun parsesWeekRangesAndBoundaries() {
        assertEquals(listOf(1,2,3,5,7,8), ScheduleParsingRules.extractWeekNumbers("1-3, 5, 7-8 нед."))
        assertEquals((1..11).toList(), ScheduleParsingRules.extractWeekNumbers("до 11 нед."))
        assertEquals((3..30).toList(), ScheduleParsingRules.extractWeekNumbers("с 3 нед."))
    }

    @Test fun studentSanitizerKeepsOnlySelectedGroup() {
        val p = UserProfile(
            role = UserRole.STUDENT,
            institute = "ИПГС",
            course = 3,
            group = "ИПГС 3-18"
        )
        val target = ScheduleEvent(
            id = "1",
            title = "Основы аддитивных технологий",
            teacher = "Молоткова П.А.",
            group = "ИПГС 3-18",
            weekday = 2,
            startTime = "11:30",
            endTime = "12:50"
        )
        val otherGroup = target.copy(id = "2", group = "ИПГС 3-20", sourceUrl = "other")

        val out = ScheduleSanitizer.clean(listOf(target, otherGroup), p)

        assertEquals(1, out.size)
        assertEquals("ИПГС 3-18", out.first().group)
    }

    @Test fun duplicateStudentRowsCollapseInsideSameGroup() {
        val p = UserProfile(
            role = UserRole.STUDENT,
            institute = "ИПГС",
            course = 3,
            group = "ИПГС 3-18"
        )
        val base = ScheduleEvent(
            id = "1",
            title = "Железобетонные конструкции",
            group = "ИПГС 3-18",
            weekday = 3,
            startTime = "13:00",
            endTime = "14:20",
            room = "101"
        )
        val mirroredPdfRow = base.copy(id = "2", sourceUrl = "mirror")

        val out = ScheduleSanitizer.clean(listOf(base, mirroredPdfRow), p)

        assertEquals(1, out.size)
        assertEquals("ИПГС 3-18", out.first().group)
    }

    @Test fun invalidStudentProfileNeverShowsUnfilteredUniversitySchedule() {
        val invalid = UserProfile(role = UserRole.STUDENT, group = "ИПГС 1 курс")
        val event = ScheduleEvent(
            id = "1",
            title = "Строительная механика",
            group = "ИПГС 3-18",
            weekday = 1,
            startTime = "08:30",
            endTime = "09:50"
        )

        assertTrue(ScheduleSanitizer.clean(listOf(event), invalid).isEmpty())
    }

    @Test fun recurringStudentLessonsOccurOnMatchingDate() {
        val events = listOf(
            ScheduleEvent("g1", "Основы аддитивных технологий", group = "ИПГС 3-18", weekday = 2, startTime = "11:30", endTime = "12:50"),
            ScheduleEvent("g2", "Строительные конструкции", group = "ИПГС 3-18", weekday = 2, startTime = "13:00", endTime = "14:20"),
            ScheduleEvent("special", "Консультация", group = "ИПГС 3-18", exactDate = "2026-09-29", startTime = "18:10", endTime = "19:30")
        )
        val date = LocalDate.of(2026, 9, 15)
        val actual = events.filter { ScheduleParsingRules.occursOnDate(it, date, "2026-08-31") }

        assertEquals(2, actual.size)
        assertEquals(listOf("11:30", "13:00"), actual.map { it.startTime })
    }
}
