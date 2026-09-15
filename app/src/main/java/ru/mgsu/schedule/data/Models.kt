package ru.mgsu.schedule.data

import kotlinx.serialization.Serializable

@Serializable
enum class UserRole { STUDENT, TEACHER }

@Serializable
data class UserProfile(
    val role: UserRole = UserRole.STUDENT,
    val institute: String = "ИАГ",
    val course: Int = 1,
    val studyForm: String = "Очная",
    val group: String = "",
    val teacher: String = "",
    val semesterStart: String = "2026-08-31",
    val notificationsEnabled: Boolean = true
)

@Serializable
data class ScheduleEvent(
    val id: String,
    val title: String,
    val type: String = "Занятие",
    val teacher: String = "",
    val group: String = "",
    val room: String = "",
    val weekday: Int? = null,
    val exactDate: String? = null,
    val startTime: String = "",
    val endTime: String = "",
    val weekNumbers: List<Int> = emptyList(),
    val weekParity: String = "ANY",
    val sourceUrl: String = "",
    val sourceLabel: String = ""
)

@Serializable
data class EventOverride(
    val title: String? = null,
    val type: String? = null,
    val teacher: String? = null,
    val group: String? = null,
    val room: String? = null,
    val startTime: String? = null,
    val endTime: String? = null
)

@Serializable
data class CustomCalendarEvent(
    val id: String,
    val date: String,
    val title: String,
    val type: String = "Личное",
    val startTime: String = "",
    val endTime: String = "",
    val room: String = "",
    val note: String = ""
)

@Serializable
data class LocalEdits(
    // Legacy global edits are kept for compatibility with versions <= 1.0.3.
    val notes: Map<String, String> = emptyMap(),
    val hiddenEventIds: Set<String> = emptySet(),
    // New calendar-like per-day edits.
    val hiddenOccurrenceIds: Set<String> = emptySet(),
    val eventOverrides: Map<String, EventOverride> = emptyMap(),
    val customEvents: List<CustomCalendarEvent> = emptyList(),
    val deletedCustomEventIds: Set<String> = emptySet()
)

@Serializable
data class CachedSchedule(
    val events: List<ScheduleEvent> = emptyList(),
    val parserVersion: Int = 0,
    val lastSyncMillis: Long = 0L,
    val lastError: String = "",
    val sourcesChecked: Int = 0
)

@Serializable
data class ScheduleChange(
    val id: String,
    val timestampMillis: Long,
    val kind: String,
    val title: String,
    val details: String,
    val oldValue: String = "",
    val newValue: String = ""
)

@Serializable
data class SuggestionCatalog(
    val schemaVersion: Int = 3,
    val teachers: List<String> = emptyList(),
    val groups: List<String> = emptyList(),
    val updatedAtMillis: Long = 0L
)

@Serializable
data class SavedProfile(
    val id: String,
    val name: String,
    val profile: UserProfile,
    val createdAtMillis: Long = 0L
)

@Serializable
data class ProfileCollection(
    val currentId: String = "",
    val profiles: List<SavedProfile> = emptyList()
)

@Serializable
data class FavoritesState(
    val teachers: Set<String> = emptySet(),
    val groups: Set<String> = emptySet()
)

@Serializable
data class StudyTask(
    val id: String,
    val profileId: String = "",
    val title: String,
    val details: String = "",
    val dueDate: String,
    val dueTime: String = "",
    val link: String = "",
    val attachmentUri: String = "",
    val attachmentName: String = "",
    val relatedOccurrenceKey: String = "",
    val relatedEventTitle: String = "",
    val remindMinutesBefore: Int = 60,
    val completed: Boolean = false,
    val createdAtMillis: Long = 0L
)


@Serializable
data class EventColorSettings(
    val lectureHex: String = "#E8F1FB",
    val practiceHex: String = "#E8F5EC",
    val examHex: String = "#FFE7E7",
    val personalHex: String = "#FFF3D9"
)

@Serializable
data class LessonNotificationSettings(
    val classRemindersEnabled: Boolean = true,
    val minutesBefore: Int = 15,
    val morningBriefEnabled: Boolean = true,
    val morningHour: Int = 7,
    val eveningBriefEnabled: Boolean = true,
    val eveningHour: Int = 20
)

@Serializable
data class RecentSelections(
    val teachers: List<String> = emptyList(),
    val groups: List<String> = emptyList()
)

@Serializable
data class SyncDiagnostics(
    val lastAttemptMillis: Long = 0L,
    val lastSuccessMillis: Long = 0L,
    val activeHost: String = "",
    val sourcesDiscovered: Int = 0,
    val sourcesMatched: Int = 0,
    val sourcesChecked: Int = 0,
    val parsedEvents: Int = 0,
    val failedPdfs: Int = 0,
    val usedOfflineCache: Boolean = false,
    val lastError: String = ""
)

@Serializable
data class AppBackup(
    val schemaVersion: Int = 1,
    val exportedAtMillis: Long = 0L,
    val profiles: ProfileCollection = ProfileCollection(),
    val edits: LocalEdits = LocalEdits(),
    val favorites: FavoritesState = FavoritesState(),
    val tasks: List<StudyTask> = emptyList(),
    val colors: EventColorSettings = EventColorSettings(),
    val notifications: LessonNotificationSettings = LessonNotificationSettings(),
    val recentSelections: RecentSelections = RecentSelections(),
    val cache: CachedSchedule = CachedSchedule(),
    val changes: List<ScheduleChange> = emptyList()
)
