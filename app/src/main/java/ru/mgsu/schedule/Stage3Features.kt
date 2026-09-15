@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.mgsu.schedule

import android.content.Context
import android.content.Intent
import android.graphics.Color as AndroidColor
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mgsu.schedule.data.*
import ru.mgsu.schedule.worker.isOnDate
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Stage3Blue = Color(0xFF0F3C73)
private val Stage3Ru = Locale("ru", "RU")

/** Official campus/building information is limited to data published by NIU MGSU. */
data class CampusPlace(
    val code: String,
    val title: String,
    val address: String,
    val description: String,
    val sourceUrl: String
)

object MgsuCampusDirectory {
    const val MAIN_ADDRESS = "Москва, Ярославское шоссе, 26"
    val places = listOf(
        CampusPlace("1", "Учебно-административный корпус", "$MAIN_ADDRESS, корп. 1", "Административный корпус НИУ МГСУ", "https://isa.mgsu.ru/sveden/common/"),
        CampusPlace("2", "Учебный корпус", "$MAIN_ADDRESS, корп. 2", "Учебно-лабораторный корпус", "https://isa.mgsu.ru/sveden/common/"),
        CampusPlace("3", "Корпус поточных аудиторий", "$MAIN_ADDRESS, корп. 3", "Учебный корпус поточных аудиторий", "https://isa.mgsu.ru/sveden/common/"),
        CampusPlace("7", "Учебно-лабораторный блок", "$MAIN_ADDRESS, корп. 7", "Учебно-лабораторный блок старших курсов", "https://isa.mgsu.ru/sveden/common/"),
        CampusPlace("11", "Корпус физвоспитания", "$MAIN_ADDRESS, корп. 11", "Спортивно-оздоровительный учебный корпус", "https://isa.mgsu.ru/sveden/common/"),
        CampusPlace("20", "Учебно-лабораторный корпус", "$MAIN_ADDRESS, корп. 20", "Учебно-лабораторный корпус старших курсов", "https://isa.mgsu.ru/sveden/common/")
    )

    fun resolve(room: String): Pair<String, String> {
        val r = room.uppercase(Stage3Ru).replace('Ё', 'Е').trim()
        return when {
            "КМК" in r -> "КМК" to MAIN_ADDRESS
            "УЛК" in r -> "УЛК" to MAIN_ADDRESS
            "УЛБ" in r || Regex("\\d+\\s*Г\\b").containsMatchIn(r) -> "УЛБ, корпус Г" to MAIN_ADDRESS
            Regex("\\d+\\s*А\\b").containsMatchIn(r) -> "корпус А" to MAIN_ADDRESS
            else -> "Кампус НИУ МГСУ" to MAIN_ADDRESS
        }
    }
}

fun openMgsuRoute(context: Context, room: String = "") {
    val (building, address) = MgsuCampusDirectory.resolve(room)
    val query = if (room.isBlank()) address else "$address, $building, аудитория $room"
    openMapQuery(context, query)
}

private fun openMapQuery(context: Context, query: String) {
    val geo = Uri.parse("geo:0,0?q=${Uri.encode(query)}")
    val intent = Intent(Intent.ACTION_VIEW, geo)
    runCatching { context.startActivity(intent) }.onFailure {
        val web = Uri.parse("https://www.google.com/maps/search/?api=1&query=${Uri.encode(query)}")
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, web)) }
    }
}

@Composable
fun CampusDirectoryDialog(room: String, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val (resolved, address) = MgsuCampusDirectory.resolve(room)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (room.isBlank()) "Корпуса НИУ МГСУ" else "Где находится аудитория $room") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (room.isNotBlank()) {
                    item {
                        Surface(shape = RoundedCornerShape(14.dp), color = Color(0xFFE9F1FA)) {
                            Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                Text(resolved, fontWeight = FontWeight.Bold, color = Stage3Blue)
                                Text(address)
                                Text("Если в обозначении аудитории нет корпуса, приложение показывает основной кампус. Точный корпус можно уточнить по подписи в расписании.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                Spacer(Modifier.height(6.dp))
                                Button(onClick = { openMgsuRoute(context, room) }) {
                                    Icon(Icons.Default.Directions, null); Spacer(Modifier.width(6.dp)); Text("Открыть маршрут")
                                }
                            }
                        }
                    }
                }
                item { Text("Официальные корпуса по адресу Ярославское шоссе, 26", fontWeight = FontWeight.Bold) }
                items(MgsuCampusDirectory.places, key = { it.code }) { place ->
                    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, shadowElevation = 1.dp) {
                        Column(Modifier.fillMaxWidth().padding(11.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Domain, null, tint = Stage3Blue)
                                Spacer(Modifier.width(8.dp))
                                Column(Modifier.weight(1f)) {
                                    Text("Корп. ${place.code} · ${place.title}", fontWeight = FontWeight.Bold)
                                    Text(place.address, style = MaterialTheme.typography.bodySmall)
                                    Text(place.description, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                }
                                IconButton(onClick = { openMapQuery(context, place.address) }) { Icon(Icons.Default.Map, "Маршрут") }
                            }
                            TextButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(place.sourceUrl))) } }) {
                                Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(4.dp)); Text("Официальный источник МГСУ")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private val palette = listOf(
    "#E8F1FB" to "Голубой", "#E8F5EC" to "Зелёный", "#FFF3D9" to "Жёлтый",
    "#FFE7E7" to "Красный", "#F2ECF8" to "Фиолетовый", "#E9F7F7" to "Бирюзовый",
    "#F4F4F4" to "Серый", "#FCEAF4" to "Розовый"
)

@Composable
fun Stage3SettingsBlocks(
    colors: EventColorSettings,
    notifications: LessonNotificationSettings,
    onColors: (EventColorSettings) -> Unit,
    onNotifications: (LessonNotificationSettings) -> Unit,
    onOpenCampus: () -> Unit
) {
    var colorsOpen by remember { mutableStateOf(false) }
    var notifyOpen by remember { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { colorsOpen = !colorsOpen }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Palette, null, tint = Stage3Blue); Spacer(Modifier.width(9.dp))
                Text("Цвета занятий", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (colorsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (colorsOpen) {
                Spacer(Modifier.height(8.dp))
                ColorSettingRow("Лекции", colors.lectureHex) { onColors(colors.copy(lectureHex = it)) }
                ColorSettingRow("Практики / лабораторные", colors.practiceHex) { onColors(colors.copy(practiceHex = it)) }
                ColorSettingRow("Экзамены / зачёты", colors.examHex) { onColors(colors.copy(examHex = it)) }
                ColorSettingRow("Личные события", colors.personalHex) { onColors(colors.copy(personalHex = it)) }
                TextButton(onClick = { onColors(EventColorSettings()) }) { Text("Вернуть стандартные цвета") }
            }
        }
    }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(Modifier.fillMaxWidth().clickable { notifyOpen = !notifyOpen }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.NotificationsActive, null, tint = Stage3Blue); Spacer(Modifier.width(9.dp))
                Text("Уведомления о парах", fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Icon(if (notifyOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (notifyOpen) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Перед каждой парой", modifier = Modifier.weight(1f))
                    Switch(notifications.classRemindersEnabled, { onNotifications(notifications.copy(classRemindersEnabled = it)) })
                }
                if (notifications.classRemindersEnabled) {
                    Text("Напоминать за", style = MaterialTheme.typography.labelMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(10, 15, 30, 60).forEach { min ->
                            FilterChip(
                                selected = notifications.minutesBefore == min,
                                onClick = { onNotifications(notifications.copy(minutesBefore = min)) },
                                label = { Text(if (min == 60) "1 час" else "$min мин") }
                            )
                        }
                    }
                }
                HorizontalDivider(Modifier.padding(vertical = 6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Утренняя сводка", fontWeight = FontWeight.SemiBold); Text("Первая пара и план на сегодня", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    Switch(notifications.morningBriefEnabled, { onNotifications(notifications.copy(morningBriefEnabled = it)) })
                }
                if (notifications.morningBriefEnabled) HourChips(notifications.morningHour, listOf(6,7,8,9)) { onNotifications(notifications.copy(morningHour = it)) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) { Text("Вечерняя сводка", fontWeight = FontWeight.SemiBold); Text("Сколько пар завтра и во сколько первая", style = MaterialTheme.typography.bodySmall, color = Color.Gray) }
                    Switch(notifications.eveningBriefEnabled, { onNotifications(notifications.copy(eveningBriefEnabled = it)) })
                }
                if (notifications.eveningBriefEnabled) HourChips(notifications.eveningHour, listOf(18,19,20,21,22)) { onNotifications(notifications.copy(eveningHour = it)) }
            }
        }
    }
    OutlinedButton(onClick = onOpenCampus, modifier = Modifier.fillMaxWidth()) {
        Icon(Icons.Default.Map, null); Spacer(Modifier.width(6.dp)); Text("Карта корпусов и маршруты")
    }
}

@Composable
private fun HourChips(selected: Int, values: List<Int>, onSelect: (Int) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        values.forEach { hour ->
            FilterChip(selected = selected == hour, onClick = { onSelect(hour) }, label = { Text(String.format(Stage3Ru, "%02d:00", hour)) })
        }
    }
}

@Composable
private fun ColorSettingRow(label: String, selected: String, onSelected: (String) -> Unit) {
    Column(Modifier.padding(vertical = 5.dp)) {
        Text(label, fontWeight = FontWeight.SemiBold)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            palette.forEach { (hex, name) ->
                val c = colorFromHex(hex, Color.LightGray)
                Box(
                    Modifier.size(28.dp)
                        .background(c, CircleShape)
                        .clickable { onSelected(hex) }
                        .padding(3.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected.equals(hex, true)) Icon(Icons.Default.Check, name, tint = Color.Black, modifier = Modifier.size(17.dp))
                }
            }
        }
    }
}

fun eventBackgroundColor(settings: EventColorSettings, type: String, custom: Boolean): Color {
    val t = type.uppercase(Stage3Ru)
    val hex = when {
        custom || "ЛИЧ" in t || "ЗАМЕТ" in t -> settings.personalHex
        "ЭКЗ" in t || "ЗАЧ" in t -> settings.examHex
        "ПРАК" in t || "СЕМИН" in t || "ЛАБ" in t -> settings.practiceHex
        else -> settings.lectureHex
    }
    return colorFromHex(hex, Color(0xFFE8F1FB))
}

fun eventAccentColor(type: String, custom: Boolean): Color {
    val t = type.uppercase(Stage3Ru)
    return when {
        custom || "ЛИЧ" in t || "ЗАМЕТ" in t -> Color(0xFFE29A16)
        "ЭКЗ" in t || "ЗАЧ" in t -> Color(0xFFB3261E)
        "ПРАК" in t || "СЕМИН" in t || "ЛАБ" in t -> Color(0xFF2F7D4A)
        else -> Stage3Blue
    }
}

private fun colorFromHex(hex: String, fallback: Color): Color = runCatching {
    Color(AndroidColor.parseColor(hex))
}.getOrDefault(fallback)

data class GlobalSearchHit(val event: ScheduleEvent, val date: LocalDate, val subtitle: String)

@Composable
fun GlobalSearchDialog(
    cache: CachedSchedule,
    edits: LocalEdits,
    profile: UserProfile,
    onOpenDay: (LocalDate) -> Unit,
    onCampus: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, cache, edits, profile) { globalSearch(cache, edits, profile, query) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Поиск по расписанию") },
        text = {
            Column(Modifier.fillMaxWidth().heightIn(min = 280.dp, max = 580.dp)) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Default.Close, "Очистить") } },
                    placeholder = { Text("Предмет, преподаватель, аудитория…") }
                )
                Spacer(Modifier.height(8.dp))
                if (query.trim().length < 2) {
                    Text("Введите минимум 2 символа. Можно искать, например, «Синенко», «4101» или название предмета.", color = Color.Gray)
                } else if (results.isEmpty()) {
                    Text("Ничего не найдено", color = Color.Gray)
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        items(results, key = { "${it.event.id}@${it.date}" }) { hit ->
                            Surface(shape = RoundedCornerShape(13.dp), color = Color(0xFFF7F9FC), modifier = Modifier.fillMaxWidth().clickable { onOpenDay(hit.date); onDismiss() }) {
                                Column(Modifier.padding(10.dp)) {
                                    Text(hit.event.title, fontWeight = FontWeight.Bold)
                                    Text(hit.subtitle, style = MaterialTheme.typography.bodySmall)
                                    val who = listOf(hit.event.teacher, hit.event.group).filter { it.isNotBlank() }.joinToString(" · ")
                                    if (who.isNotBlank()) Text(who, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                                    if (hit.event.room.isNotBlank()) {
                                        TextButton(onClick = { onCampus(hit.event.room) }, contentPadding = PaddingValues(0.dp)) {
                                            Icon(Icons.Default.LocationOn, null); Spacer(Modifier.width(4.dp)); Text(hit.event.room)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
}

private fun globalSearch(cache: CachedSchedule, edits: LocalEdits, profile: UserProfile, rawQuery: String): List<GlobalSearchHit> {
    val q = normalizeStage3(rawQuery)
    if (q.length < 2) return emptyList()
    val start = LocalDate.now().minusDays(30)
    val end = LocalDate.now().plusDays(150)
    val hits = mutableListOf<GlobalSearchHit>()
    cache.events.forEach { base ->
        val hay = normalizeStage3(listOf(base.title, base.type, base.teacher, base.group, base.room).joinToString(" "))
        if (!hay.contains(q)) return@forEach
        var date = start
        var added = 0
        while (!date.isAfter(end) && added < 4) {
            if (isOnDate(base, date, profile.semesterStart)) {
                val key = "${base.id}@$date"
                if (base.id !in edits.hiddenEventIds && key !in edits.hiddenOccurrenceIds) {
                    val o = edits.eventOverrides[key]
                    val event = if (o == null) base else base.copy(
                        title = o.title ?: base.title, type = o.type ?: base.type, teacher = o.teacher ?: base.teacher,
                        group = o.group ?: base.group, room = o.room ?: base.room, startTime = o.startTime ?: base.startTime, endTime = o.endTime ?: base.endTime
                    )
                    val subtitle = "${date.format(DateTimeFormatter.ofPattern("d MMM yyyy", Stage3Ru))} · ${event.startTime.ifBlank { "без времени" }} · ${event.type}"
                    hits += GlobalSearchHit(event, date, subtitle)
                    added++
                }
            }
            date = date.plusDays(1)
        }
    }
    edits.customEvents.filterNot { it.id in edits.deletedCustomEventIds }.forEach { c ->
        val hay = normalizeStage3(listOf(c.title, c.type, c.room, c.note).joinToString(" "))
        if (hay.contains(q)) {
            val d = runCatching { LocalDate.parse(c.date) }.getOrNull() ?: return@forEach
            hits += GlobalSearchHit(
                ScheduleEvent(c.id, c.title, c.type, room = c.room, exactDate = c.date, startTime = c.startTime, endTime = c.endTime, sourceLabel = "Личное событие"),
                d,
                "${d.format(DateTimeFormatter.ofPattern("d MMM yyyy", Stage3Ru))} · ${c.startTime.ifBlank { "без времени" }} · ${c.type}"
            )
        }
    }
    return hits.sortedWith(compareBy<GlobalSearchHit> { it.date }.thenBy { it.event.startTime.ifBlank { "99:99" } }).take(80)
}

private fun normalizeStage3(value: String): String = value.uppercase(Stage3Ru).replace('Ё', 'Е').replace(Regex("[^А-ЯA-Z0-9]+"), " ").trim()
