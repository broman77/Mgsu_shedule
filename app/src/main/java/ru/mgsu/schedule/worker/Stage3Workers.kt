package ru.mgsu.schedule.worker

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.flow.first
import ru.mgsu.schedule.data.*
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

class LessonReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val title = inputData.getString("title") ?: return Result.success()
        val time = inputData.getString("time").orEmpty()
        val room = inputData.getString("room").orEmpty()
        NotificationHelper.notifyLesson(applicationContext, title, time, room)
        return Result.success()
    }
}

class DailyScheduleBriefWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        val profile = store.profileFlow.first() ?: return Result.success()
        if (!profile.notificationsEnabled) return Result.success()
        val settings = store.lessonNotificationSettingsFlow.first()
        val mode = inputData.getString("mode") ?: return Result.success()
        if (mode == "morning" && !settings.morningBriefEnabled) return Result.success()
        if (mode == "evening" && !settings.eveningBriefEnabled) return Result.success()
        val date = if (mode == "morning") LocalDate.now() else LocalDate.now().plusDays(1)
        val events = Stage3NotificationScheduler.eventsForDate(store.readCache(), store.editsFlow.first(), profile, date)
        if (mode == "morning") NotificationHelper.notifyMorningBrief(applicationContext, events, date)
        else NotificationHelper.notifyEveningBrief(applicationContext, events, date)
        return Result.success()
    }
}

object Stage3NotificationScheduler {
    fun reschedule(
        context: Context,
        profile: UserProfile,
        cache: CachedSchedule,
        edits: LocalEdits,
        settings: LessonNotificationSettings
    ) {
        val wm = WorkManager.getInstance(context)
        wm.cancelAllWorkByTag("lesson_reminder")
        if (profile.notificationsEnabled && settings.classRemindersEnabled) {
            val now = ZonedDateTime.now()
            for (offset in 0L..14L) {
                val date = LocalDate.now().plusDays(offset)
                eventsForDate(cache, edits, profile, date).forEach { event ->
                    val time = runCatching { LocalTime.parse(event.startTime) }.getOrNull() ?: return@forEach
                    val trigger = LocalDateTime.of(date, time)
                        .atZone(ZoneId.systemDefault())
                        .minusMinutes(settings.minutesBefore.toLong())
                    val delay = Duration.between(now, trigger).toMillis()
                    if (delay <= 0) return@forEach
                    val key = "${event.id}@$date@${event.startTime}"
                    val request = OneTimeWorkRequestBuilder<LessonReminderWorker>()
                        .setInitialDelay(delay, TimeUnit.MILLISECONDS)
                        .setInputData(workDataOf("title" to event.title, "time" to event.startTime, "room" to event.room))
                        .addTag("lesson_reminder")
                        .build()
                    wm.enqueueUniqueWork("lesson_${key.hashCode()}", ExistingWorkPolicy.REPLACE, request)
                }
            }
        }
        scheduleDailyBrief(wm, "morning_schedule_brief", "morning", settings.morningBriefEnabled && profile.notificationsEnabled, settings.morningHour)
        scheduleDailyBrief(wm, "evening_schedule_brief", "evening", settings.eveningBriefEnabled && profile.notificationsEnabled, settings.eveningHour)
    }

    private fun scheduleDailyBrief(wm: WorkManager, name: String, mode: String, enabled: Boolean, hour: Int) {
        if (!enabled) {
            wm.cancelUniqueWork(name)
            return
        }
        val now = ZonedDateTime.now()
        var next = now.toLocalDate().atTime(hour.coerceIn(0, 23), 0).atZone(now.zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val delay = Duration.between(now, next).toMillis().coerceAtLeast(0L)
        val request = PeriodicWorkRequestBuilder<DailyScheduleBriefWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("mode" to mode))
            .build()
        wm.enqueueUniquePeriodicWork(name, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun eventsForDate(cache: CachedSchedule, edits: LocalEdits, profile: UserProfile, date: LocalDate): List<ScheduleEvent> {
        val official = cache.events.filter { base ->
            val key = "${base.id}@$date"
            isOnDate(base, date, profile.semesterStart) && base.id !in edits.hiddenEventIds && key !in edits.hiddenOccurrenceIds
        }.map { base ->
            val key = "${base.id}@$date"
            val o = edits.eventOverrides[key]
            if (o == null) base else base.copy(
                title = o.title ?: base.title,
                type = o.type ?: base.type,
                teacher = o.teacher ?: base.teacher,
                group = o.group ?: base.group,
                room = o.room ?: base.room,
                startTime = o.startTime ?: base.startTime,
                endTime = o.endTime ?: base.endTime
            )
        }
        val custom = edits.customEvents.filter { it.date == date.toString() && it.id !in edits.deletedCustomEventIds }.map {
            ScheduleEvent(
                id = it.id, title = it.title, type = it.type, room = it.room,
                exactDate = it.date, startTime = it.startTime, endTime = it.endTime, sourceLabel = "Личное событие"
            )
        }
        return (official + custom).sortedBy { it.startTime.ifBlank { "99:99" } }
    }
}
