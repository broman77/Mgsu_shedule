package ru.mgsu.schedule.worker

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import ru.mgsu.schedule.data.AppStore
import ru.mgsu.schedule.data.MgsuRepository
import ru.mgsu.schedule.data.ScheduleEvent
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        val profile = store.profileFlow.first() ?: return Result.success()
        MgsuRepository(applicationContext).sync(profile)
        return Result.success()
    }
}

class TomorrowWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val store = AppStore(applicationContext)
        val profile = store.profileFlow.first() ?: return Result.success()
        if (!profile.notificationsEnabled) return Result.success()
        val edits = store.editsFlow.first()
        val cache = store.readCache()
        val tomorrow = LocalDate.now().plusDays(1)
        val events = cache.events.filter { e -> isOnDate(e, tomorrow, profile.semesterStart) && e.id !in edits.hiddenEventIds }
        val state = applicationContext.getSharedPreferences("notify_state", Context.MODE_PRIVATE)
        if (state.getString("last_tomorrow", "") == tomorrow.toString()) return Result.success()
        if (events.isNotEmpty()) {
            notify(events, tomorrow)
            state.edit().putString("last_tomorrow", tomorrow.toString()).apply()
        }
        return Result.success()
    }

    private fun notify(events: List<ScheduleEvent>, date: LocalDate) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val nm = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel("tomorrow", "Расписание на завтра", NotificationManager.IMPORTANCE_DEFAULT)
        nm.createNotificationChannel(channel)
        val pairs = events.count { it.type !in setOf("Экзамен", "Зачёт", "Экзамен/зачёт") }
        val exams = events.size - pairs
        val title = if (pairs > 0) "Завтра $pairs ${pairWord(pairs)}" else "Завтра ${events.size} событий"
        val text = buildString {
            append(events.take(3).joinToString(" · ") { (it.startTime.ifBlank { "—" }) + " " + it.title })
            if (exams > 0) append(" · Экзаменов/зачётов: $exams")
        }
        val notification = NotificationCompat.Builder(applicationContext, "tomorrow")
            .setSmallIcon(android.R.drawable.ic_menu_today)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(applicationContext).notify(date.toEpochDay().toInt(), notification)
    }

    private fun pairWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "пара"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "пары"
        else -> "пар"
    }
}

fun isOnDate(e: ScheduleEvent, date: LocalDate, semesterStartIso: String): Boolean {
    e.exactDate?.let { return runCatching { LocalDate.parse(it) == date }.getOrDefault(false) }
    if (e.weekday != date.dayOfWeek.value) return false
    val start = runCatching { LocalDate.parse(semesterStartIso) }.getOrElse { LocalDate.of(date.year, 9, 1) }
    val monday = start.minusDays((start.dayOfWeek.value - 1).toLong())
    val week = ChronoUnit.WEEKS.between(monday, date) + 1
    if (week < 1) return false
    if (e.weekNumbers.isNotEmpty() && week.toInt() !in e.weekNumbers) return false
    return when (e.weekParity) {
        "EVEN" -> week % 2L == 0L
        "ODD" -> week % 2L == 1L
        else -> true
    }
}
