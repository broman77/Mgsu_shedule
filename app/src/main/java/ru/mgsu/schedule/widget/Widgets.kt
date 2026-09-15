package ru.mgsu.schedule.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.mgsu.schedule.R
import ru.mgsu.schedule.data.AppStore
import ru.mgsu.schedule.data.EventOverride
import ru.mgsu.schedule.data.ScheduleEvent
import ru.mgsu.schedule.worker.isOnDate
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

class NextClassWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try { updateInternal(context, manager, ids) } finally { pending.finish() }
        }
    }

    private suspend fun updateInternal(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val store = AppStore(context)
        val profile = store.profileFlow.first()
        val edits = store.editsFlow.first()
        val events = profile?.let { resolvedForDate(store, edits, LocalDate.now(), it.semesterStart) }.orEmpty()
        val now = LocalTime.now()
        val next = events.firstOrNull { e ->
            val start = parseTime(e.startTime)
            val end = parseTime(e.endTime.ifBlank { e.startTime })
            start != null && end != null && !end.isBefore(now)
        }
        ids.forEach { id ->
            val rv = RemoteViews(context.packageName, R.layout.widget_next_class)
            rv.setTextViewText(R.id.widget_title, if (profile == null) "МГСУ Расписание" else if (profile.role.name == "STUDENT") profile.group else profile.teacher)
            if (next == null) {
                rv.setTextViewText(R.id.widget_event, "Сегодня занятий больше нет")
                rv.setTextViewText(R.id.widget_meta, "Откройте приложение для календаря")
            } else {
                rv.setTextViewText(R.id.widget_event, next.title)
                rv.setTextViewText(R.id.widget_meta, listOf(next.startTime, next.room).filter { it.isNotBlank() }.joinToString(" · "))
            }
            manager.updateAppWidget(id, rv)
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, NextClassWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, NextClassWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }
    }
}

class TodayScheduleWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = AppStore(context)
            val profile = store.profileFlow.first()
            val edits = store.editsFlow.first()
            val date = LocalDate.now()
            val events = profile?.let { resolvedForDate(store, edits, date, it.semesterStart) }.orEmpty()
            val body = if (events.isEmpty()) "Занятий нет" else events.take(5).joinToString("\n") { "${it.startTime.ifBlank { "—" }}  ${it.title}" }
            ids.forEach { id ->
                val rv = RemoteViews(context.packageName, R.layout.widget_today_schedule)
                rv.setTextViewText(R.id.widget_day, date.format(DateTimeFormatter.ofPattern("d MMMM")))
                rv.setTextViewText(R.id.widget_count, if (events.isEmpty()) "Свободный день" else "${events.size} событий")
                rv.setTextViewText(R.id.widget_list, body)
                manager.updateAppWidget(id, rv)
            }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        fun updateAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayScheduleWidgetProvider::class.java))
            if (ids.isNotEmpty()) {
                val intent = Intent(context, TodayScheduleWidgetProvider::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids)
                context.sendBroadcast(intent)
            }
        }
    }
}

private suspend fun resolvedForDate(store: AppStore, edits: ru.mgsu.schedule.data.LocalEdits, date: LocalDate, semesterStart: String): List<ScheduleEvent> {
    val official = store.readCache().events.asSequence()
        .filter { isOnDate(it, date, semesterStart) }
        .filter { it.id !in edits.hiddenEventIds && occurrenceKey(it.id, date) !in edits.hiddenOccurrenceIds }
        .map { applyOverride(it, edits.eventOverrides[occurrenceKey(it.id, date)]) }
        .toList()
    val custom = edits.customEvents.asSequence()
        .filter { it.date == date.toString() && it.id !in edits.deletedCustomEventIds }
        .map {
            ScheduleEvent(
                id = it.id, title = it.title, type = it.type, room = it.room,
                exactDate = it.date, startTime = it.startTime, endTime = it.endTime,
                sourceLabel = "Личное событие"
            )
        }.toList()
    return (official + custom).sortedBy { it.startTime.ifBlank { "99:99" } }
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

private fun parseTime(value: String): LocalTime? = runCatching { LocalTime.parse(value, DateTimeFormatter.ofPattern("H:mm")) }.getOrNull()
