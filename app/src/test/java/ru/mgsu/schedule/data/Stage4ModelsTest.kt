package ru.mgsu.schedule.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage4ModelsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun backup_roundTrip_preservesUserData() {
        val profile = UserProfile(role = UserRole.STUDENT, institute = "ИАГ", course = 1, group = "ИАГ-1-1")
        val backup = AppBackup(
            exportedAtMillis = 123456L,
            profiles = ProfileCollection("p1", listOf(SavedProfile("p1", "ИАГ-1-1", profile, 1L))),
            edits = LocalEdits(notes = mapOf("lesson@2026-09-15" to "Взять линейку")),
            favorites = FavoritesState(teachers = setOf("Синенко С.А."), groups = setOf("ИАГ-1-1")),
            tasks = listOf(StudyTask(id = "t1", title = "Подготовиться", dueDate = "2026-09-20")),
            colors = EventColorSettings(lectureHex = "#E8F1FB"),
            notifications = LessonNotificationSettings(minutesBefore = 30),
            recentSelections = RecentSelections(teachers = listOf("Синенко С.А."), groups = listOf("ИАГ-1-1")),
            cache = CachedSchedule(events = listOf(ScheduleEvent(id = "e1", title = "Математика")), lastSyncMillis = 77L),
            changes = listOf(ScheduleChange("c1", 5L, "CHANGED", "Математика", "Аудитория"))
        )

        val restored = json.decodeFromString<AppBackup>(json.encodeToString(backup))
        assertEquals("ИАГ-1-1", restored.profiles.profiles.single().profile.group)
        assertEquals("Взять линейку", restored.edits.notes["lesson@2026-09-15"])
        assertEquals(30, restored.notifications.minutesBefore)
        assertEquals("Синенко С.А.", restored.recentSelections.teachers.first())
        assertEquals("Математика", restored.cache.events.single().title)
        assertTrue(restored.changes.isNotEmpty())
    }

    @Test
    fun diagnostics_roundTrip_preservesOfflineState() {
        val value = SyncDiagnostics(
            lastAttemptMillis = 10L,
            lastSuccessMillis = 5L,
            activeHost = "mgsu.ru",
            sourcesDiscovered = 42,
            sourcesMatched = 8,
            sourcesChecked = 7,
            parsedEvents = 33,
            failedPdfs = 1,
            usedOfflineCache = true,
            lastError = "HTTP 502"
        )
        val restored = json.decodeFromString<SyncDiagnostics>(json.encodeToString(value))
        assertEquals(value, restored)
    }
}
