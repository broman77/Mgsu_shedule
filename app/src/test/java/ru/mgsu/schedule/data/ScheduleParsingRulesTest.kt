package ru.mgsu.schedule.data

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class ScheduleParsingRulesTest {
    @Test fun strictTeacherMatchWorksBothOrders() {
        assertTrue(ScheduleParsingRules.matchesTeacher("Молоткова П.А. Основы аддитивных технологий", "Молоткова П.А."))
        assertTrue(ScheduleParsingRules.matchesTeacher("П.А. Молоткова Основы аддитивных технологий", "Молоткова П.А."))
        assertTrue(ScheduleParsingRules.matchesTeacher("доц.МОЛОТКОВА П.А. Основы аддитивных технологий", "Молоткова П.А."))
        assertFalse(ScheduleParsingRules.matchesTeacher("Молоткова И.В. Архитектура", "Молоткова П.А."))
        assertTrue(ScheduleParsingRules.isValidTeacher("Молоткова П.А."))
        assertFalse(ScheduleParsingRules.isValidTeacher("В-молоткова"))
        assertFalse(ScheduleParsingRules.isValidTeacher("кафедра Молоткова П.А. расписание"))
    }

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

    @Test fun teacherDuplicatesCollapseBySlot() {
        val p = UserProfile(role = UserRole.TEACHER, teacher = "Молоткова П.А.")
        val base = ScheduleEvent(
            id="1", title="Основы аддитивных технологий", teacher="Молоткова П.А.",
            group="ИПГС 3-18", weekday=2, startTime="11:30", endTime="12:50"
        )
        val copy = base.copy(id="2", group="ИПГС 3-20", sourceUrl="other")
        val out = ScheduleSanitizer.clean(listOf(base, copy), p)
        assertEquals(1, out.size)
        assertTrue(out.first().group.contains("ИПГС 3-18"))
        assertTrue(out.first().group.contains("ИПГС 3-20"))
    }

    @Test fun molotkovaReferenceForTuesday15SeptemberHasTwoRecurringLessons() {
        // Regression fixture transcribed from the Excel reference supplied by the user.
        // It validates recurrence/date filtering; runtime data is still read from official MGSU PDFs.
        val events = listOf(
            ScheduleEvent("t3", "Основы аддитивных технологий", teacher="Молоткова П.А.", group="ИПГС 3-18", room="622а КМК", weekday=2, startTime="11:30", endTime="12:50"),
            ScheduleEvent("t4", "Основы аддитивных технологий", teacher="Молоткова П.А.", group="ИПГС 3-20", room="622а КМК", weekday=2, startTime="13:00", endTime="14:20"),
            ScheduleEvent("ido7", "Основы организации строительного производства ИДО", teacher="Молоткова П.А.", group="ИДО 4-53", exactDate="2026-09-29", startTime="18:10", endTime="19:30"),
            ScheduleEvent("ido8", "Основы организации строительного производства ИДО", teacher="Молоткова П.А.", group="ИДО 4-53", exactDate="2026-09-29", startTime="19:40", endTime="21:00")
        )
        val date = LocalDate.of(2026, 9, 15)
        val actual = events.filter { ScheduleParsingRules.occursOnDate(it, date, "2026-08-31") }
        assertEquals(2, actual.size)
        assertEquals(listOf("11:30", "13:00"), actual.map { it.startTime })
    }
}
