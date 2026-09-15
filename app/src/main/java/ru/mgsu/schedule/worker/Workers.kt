package ru.mgsu.schedule.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.mgsu.schedule.MainActivity
import ru.mgsu.schedule.data.*
import ru.mgsu.schedule.widget.NextClassWidgetProvider
import ru.mgsu.schedule.widget.TodayScheduleWidgetProvider
import java.time.LocalDate

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        val profile = store.profileFlow.first() ?: return Result.success()
        val beforeIds = store.readChanges().map { it.id }.toSet()
        val cache = MgsuRepository(applicationContext).sync(profile)
        val newChanges = store.readChanges().filterNot { it.id in beforeIds }
        if (newChanges.isNotEmpty()) NotificationHelper.notifyChanges(applicationContext, newChanges)
        Stage3NotificationScheduler.reschedule(
            applicationContext,
            profile,
            cache,
            store.editsFlow.first(),
            store.lessonNotificationSettingsFlow.first()
        )
        NextClassWidgetProvider.updateAll(applicationContext)
        TodayScheduleWidgetProvider.updateAll(applicationContext)
        return Result.success()
    }
}

class TaskReminderWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val taskId = inputData.getString("task_id") ?: return Result.success()
        val store = AppStore(applicationContext)
        val task = store.tasksFlow.first().firstOrNull { it.id == taskId } ?: return Result.success()
        if (task.completed) return Result.success()
        NotificationHelper.notifyTask(applicationContext, task)
        return Result.success()
    }
}

object NotificationHelper {
    fun notifyChanges(context: Context, changes: List<ScheduleChange>) {
        if (!canNotify(context) || changes.isEmpty()) return
        val title = if (changes.size == 1) "Расписание изменилось" else "Изменений в расписании: ${changes.size}"
        val text = changes.take(4).joinToString("\n") { c ->
            val prefix = when (c.kind) { "ADDED" -> "Добавлено"; "REMOVED" -> "Отменено"; else -> "Изменено" }
            "$prefix: ${c.title} — ${c.details}"
        }
        notify(context, "schedule_changes", "Изменения расписания", title, text, (System.currentTimeMillis() / 1000L).toInt())
    }

    fun notifyLesson(context: Context, title: String, time: String, room: String) {
        if (!canNotify(context)) return
        val text = buildString {
            if (time.isNotBlank()) append("Начало в $time")
            if (room.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append("ауд. $room")
            }
        }.ifBlank { "Скоро начинается занятие" }
        notify(context, "lesson_reminders", "Напоминания о парах", title, text, title.hashCode() xor time.hashCode())
    }

    fun notifyMorningBrief(context: Context, events: List<ScheduleEvent>, date: LocalDate) {
        if (!canNotify(context)) return
        val timed = events.filter { it.startTime.isNotBlank() }.sortedBy { it.startTime }
        val title = if (events.isEmpty()) "Сегодня занятий нет" else "Сегодня ${events.size} ${pairWord(events.size)}"
        val first = timed.firstOrNull()
        val last = timed.lastOrNull()
        val summary = when {
            first == null -> "Откройте календарь, чтобы посмотреть личные события"
            first == last -> "${first.startTime} · ${first.title}${if (first.room.isNotBlank()) " · ${first.room}" else ""}"
            else -> "Первая ${first.startTime} · последняя до ${last?.let { it.endTime.ifBlank { it.startTime } } ?: "—"}"
        }
        val lines = events.sortedBy { it.startTime.ifBlank { "99:99" } }.take(6).map {
            "${it.startTime.ifBlank { "—" }} ${it.title}${if (it.room.isNotBlank()) " · ${it.room}" else ""}"
        }
        notifyInbox(context, "daily_briefs", "Ежедневные сводки", title, summary, lines, 71001 + date.dayOfYear)
    }

    fun notifyEveningBrief(context: Context, events: List<ScheduleEvent>, date: LocalDate) {
        if (!canNotify(context)) return
        val first = events.firstOrNull { it.startTime.isNotBlank() }
        val title = if (events.isEmpty()) "Завтра занятий нет" else "Завтра ${events.size} ${pairWord(events.size)}"
        val text = first?.let { "Первая в ${it.startTime}: ${it.title}${if (it.room.isNotBlank()) " · ${it.room}" else ""}" } ?: "Можно планировать день свободнее"
        notify(context, "daily_briefs", "Ежедневные сводки", title, text, 72001 + date.dayOfYear)
    }

    fun notifyTask(context: Context, task: StudyTask) {
        if (!canNotify(context)) return
        val whenText = listOf(task.dueDate, task.dueTime).filter { it.isNotBlank() }.joinToString(" ")
        val text = buildString {
            if (task.relatedEventTitle.isNotBlank()) append("${task.relatedEventTitle}. ")
            if (task.details.isNotBlank()) append(task.details)
            if (whenText.isNotBlank()) {
                if (isNotBlank()) append(" · ")
                append("Срок: $whenText")
            }
        }.ifBlank { "Не забудьте выполнить задание" }
        notify(context, "study_tasks", "Учебные задания", task.title, text, task.id.hashCode())
    }

    fun notifyTomorrow(context: Context, events: List<ScheduleEvent>, date: LocalDate) {
        if (!canNotify(context)) return
        val lessons = events.count { !it.type.contains("экзам", true) && !it.type.contains("зач", true) }
        val exams = events.size - lessons
        val title = if (lessons > 0) "Завтра $lessons ${pairWord(lessons)}" else "Завтра ${events.size} событий"
        val text = buildString {
            append(events.take(3).joinToString(" · ") { (it.startTime.ifBlank { "—" }) + " " + it.title })
            if (exams > 0) append(" · Экзаменов/зачётов: $exams")
        }
        notify(context, "tomorrow", "Расписание на завтра", title, text, date.toEpochDay().toInt())
    }

    private fun notify(context: Context, channelId: String, channelName: String, title: String, text: String, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openAppIntent(context, id))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun notifyInbox(context: Context, channelId: String, channelName: String, title: String, text: String, lines: List<String>, id: Int) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT))
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title).setSummaryText(text)
        lines.forEach { style.addLine(it) }
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setContentIntent(openAppIntent(context, id))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    private fun openAppIntent(context: Context, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun canNotify(context: Context): Boolean = android.os.Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun pairWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "пара"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "пары"
        else -> "пар"
    }
}

private fun occurrenceKey(eventId: String, date: LocalDate): String = "$eventId@$date"

private fun applyOverride(base: ScheduleEvent, o: EventOverride?): ScheduleEvent {
    if (o == null) return base
    return base.copy(
        title = o.title ?: base.title,
        type = o.type ?: base.type,
        teacher = o.teacher ?: base.teacher,
        group = o.group ?: base.group,
        room = o.room ?: base.room,
        startTime = o.startTime ?: base.startTime,
        endTime = o.endTime ?: base.endTime
    )
}

fun isOnDate(e: ScheduleEvent, date: LocalDate, semesterStartIso: String): Boolean =
    ScheduleParsingRules.occursOnDate(e, date, semesterStartIso)
