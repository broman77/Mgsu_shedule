package ru.mgsu.schedule.data

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage3ModelsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun eventColorSettings_roundTrip() {
        val value = EventColorSettings(
            lectureHex = "#E8F1FB",
            practiceHex = "#E8F5EC",
            examHex = "#FFE7E7",
            personalHex = "#FFF3D9"
        )
        val restored = json.decodeFromString<EventColorSettings>(json.encodeToString(value))
        assertEquals(value, restored)
    }

    @Test
    fun lessonNotificationSettings_roundTrip_andSupportedReminder() {
        val value = LessonNotificationSettings(minutesBefore = 30, morningHour = 8, eveningHour = 21)
        val restored = json.decodeFromString<LessonNotificationSettings>(json.encodeToString(value))
        assertEquals(value, restored)
        assertTrue(restored.minutesBefore in setOf(10, 15, 30, 60))
        assertTrue(restored.morningHour in 0..23)
        assertTrue(restored.eveningHour in 0..23)
    }
}
