package ru.mgsu.schedule.data

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Stage2ModelsTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

    @Test
    fun profilesFavoritesAndTasksRoundTrip() {
        val profile = UserProfile(
            role = UserRole.STUDENT,
            institute = "ИАГ",
            course = 2,
            studyForm = "Очная",
            group = "ИАГ-2-1"
        )
        val collection = ProfileCollection(
            currentId = "p1",
            profiles = listOf(SavedProfile("p1", "ИАГ-2-1", profile, 123L))
        )
        val favorites = FavoritesState(
            teachers = setOf("Синенко С.А."),
            groups = setOf("ИАГ-2-1")
        )
        val task = StudyTask(
            id = "t1",
            profileId = "p1",
            title = "Подготовиться к зачёту",
            dueDate = "2026-09-20",
            dueTime = "18:00",
            relatedOccurrenceKey = "lesson@2026-09-19",
            relatedEventTitle = "Высшая математика",
            remindMinutesBefore = 60
        )

        val decodedCollection = json.decodeFromString<ProfileCollection>(json.encodeToString(collection))
        val decodedFavorites = json.decodeFromString<FavoritesState>(json.encodeToString(favorites))
        val decodedTask = json.decodeFromString<StudyTask>(json.encodeToString(task))

        assertEquals("ИАГ-2-1", decodedCollection.profiles.single().profile.group)
        assertTrue("Синенко С.А." in decodedFavorites.teachers)
        assertEquals("Высшая математика", decodedTask.relatedEventTitle)
        assertEquals(60, decodedTask.remindMinutesBefore)
    }
}
