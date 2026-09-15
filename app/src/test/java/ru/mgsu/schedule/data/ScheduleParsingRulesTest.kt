package ru.mgsu.schedule.data

import org.junit.Assert.*
import org.junit.Test

class ScheduleParsingRulesTest {
    @Test fun strictTeacherMatchWorksBothOrders() {
        assertTrue(ScheduleParsingRules.matchesTeacher("Молоткова П.А. Архитектура", "Молоткова П.А."))
        assertTrue(ScheduleParsingRules.matchesTeacher("П.А. Молоткова Архитектура", "Молоткова П.А."))
        assertFalse(ScheduleParsingRules.matchesTeacher("Молоткова И.В. Архитектура", "Молоткова П.А."))
    }

    @Test fun parsesWeekRanges() {
        assertEquals(listOf(1,2,3,5,7,8), ScheduleParsingRules.extractWeekNumbers("1-3, 5, 7-8 нед."))
    }

    @Test fun teacherDuplicatesCollapseBySlot() {
        val p = UserProfile(role = UserRole.TEACHER, teacher = "Молоткова П.А.")
        val base = ScheduleEvent("1", "Архитектура", teacher="Молоткова П.А.", group="ИАГ-1", weekday=2, startTime="10:10", endTime="11:40")
        val copy = base.copy(id="2", group="ИАГ-2", sourceUrl="other")
        val out = ScheduleSanitizer.clean(listOf(base, copy), p)
        assertEquals(1, out.size)
        assertTrue(out.first().group.contains("ИАГ-1"))
        assertTrue(out.first().group.contains("ИАГ-2"))
    }
}
