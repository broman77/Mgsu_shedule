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
data class LocalEdits(
    val notes: Map<String, String> = emptyMap(),
    val hiddenEventIds: Set<String> = emptySet()
)

@Serializable
data class CachedSchedule(
    val events: List<ScheduleEvent> = emptyList(),
    val lastSyncMillis: Long = 0L,
    val lastError: String = "",
    val sourcesChecked: Int = 0
)
