package ru.mgsu.schedule.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChangeDetectorTest {
    private fun event(room: String = "101", start: String = "08:30") = ScheduleEvent(
        id = "id-1",
        title = "Математика",
        group = "ИАГ-1-1",
        weekday = 1,
        startTime = start,
        endTime = "10:00",
        room = room,
        weekParity = "ANY"
    )

    @Test
    fun detectsRoomChange() {
        val changes = ChangeDetector.detect(listOf(event()), listOf(event(room = "202")), now = 1L)
        assertEquals(1, changes.size)
        assertEquals("CHANGED", changes.first().kind)
        assertTrue(changes.first().details.contains("101"))
        assertTrue(changes.first().details.contains("202"))
    }

    @Test
    fun detectsRemovedEvent() {
        val changes = ChangeDetector.detect(listOf(event()), emptyList(), now = 1L)
        assertEquals(1, changes.size)
        assertEquals("REMOVED", changes.first().kind)
    }

    @Test
    fun firstSyncDoesNotGenerateNoise() {
        val changes = ChangeDetector.detect(emptyList(), listOf(event()), now = 1L)
        assertTrue(changes.isEmpty())
    }
}
