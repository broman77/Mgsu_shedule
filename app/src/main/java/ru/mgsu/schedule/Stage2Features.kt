@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.mgsu.schedule

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.CalendarContract
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import ru.mgsu.schedule.data.*
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Stage2Blue = Color(0xFF0F3C73)
private val Stage2Ru = Locale("ru", "RU")

@Composable
fun TasksScreen(
    tasks: List<StudyTask>,
    profileId: String,
    onAdd: () -> Unit,
    onEdit: (StudyTask) -> Unit,
    onToggle: (String) -> Unit,
    onDelete: (String) -> Unit
) {
    val context = LocalContext.current
    var showCompleted by remember { mutableStateOf(false) }
    val scoped = remember(tasks, profileId, showCompleted) {
        tasks.filter { it.profileId.isBlank() || it.profileId == profileId }
            .filter { showCompleted || !it.completed }
            .sortedWith(compareBy<StudyTask> { it.completed }.thenBy { it.dueDate }.thenBy { it.dueTime.ifBlank { "99:99" } })
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = Stage2Blue, contentColor = Color.White) {
                Icon(Icons.Default.Add, "Добавить задание")
            }
        }
    ) { pad ->
        LazyColumn(
            Modifier.padding(pad).fillMaxSize(),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Задания и дедлайны", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text("Домашние задания, ссылки, напоминания и сроки", color = Color.Gray)
                    }
                    FilterChip(
                        selected = showCompleted,
                        onClick = { showCompleted = !showCompleted },
                        label = { Text("Выполненные") },
                        leadingIcon = { Icon(Icons.Default.DoneAll, null) }
                    )
                }
            }
            if (scoped.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.TaskAlt, null, Modifier.size(46.dp), tint = Stage2Blue)
                            Spacer(Modifier.height(8.dp))
                            Text(if (showCompleted) "Выполненных заданий пока нет" else "Активных заданий пока нет", fontWeight = FontWeight.Bold)
                            Text("Добавьте дедлайн вручную или создайте задание из карточки пары.", color = Color.Gray)
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Добавить") }
                        }
                    }
                }
            }
            items(scoped, key = { it.id }) { task ->
                TaskCard(
                    task = task,
                    onToggle = { onToggle(task.id) },
                    onEdit = { onEdit(task) },
                    onDelete = { onDelete(task.id) },
                    onCalendar = { addTaskToSystemCalendar(context, task) }
                )
            }
        }
    }
}

@Composable
private fun TaskCard(
    task: StudyTask,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onCalendar: () -> Unit
) {
    val ctx = LocalContext.current
    val due = runCatching { LocalDate.parse(task.dueDate) }.getOrNull()
    val overdue = due != null && due < LocalDate.now() && !task.completed
    Surface(shape = RoundedCornerShape(18.dp), color = if (task.completed) Color(0xFFF1F4F1) else Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Checkbox(checked = task.completed, onCheckedChange = { onToggle() })
                Column(Modifier.weight(1f).padding(top = 3.dp)) {
                    Text(
                        task.title,
                        fontWeight = FontWeight.Bold,
                        textDecoration = if (task.completed) TextDecoration.LineThrough else TextDecoration.None
                    )
                    if (task.relatedEventTitle.isNotBlank()) {
                        Text("К занятию: ${task.relatedEventTitle}", style = MaterialTheme.typography.bodySmall, color = Stage2Blue)
                    }
                    Text(
                        "Срок: ${formatTaskDue(task)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (overdue) Color(0xFFB3261E) else Color.DarkGray,
                        fontWeight = if (overdue) FontWeight.Bold else FontWeight.Normal
                    )
                    if (task.details.isNotBlank()) Text(task.details, modifier = Modifier.padding(top = 5.dp))
                    if (task.link.isNotBlank()) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    val uri = Uri.parse(if (task.link.startsWith("http://") || task.link.startsWith("https://")) task.link else "https://${task.link}")
                                    ctx.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.Link, null)
                            Spacer(Modifier.width(4.dp))
                            Text("Открыть ссылку")
                        }
                    }
                    if (task.attachmentUri.isNotBlank()) {
                        TextButton(
                            onClick = {
                                runCatching {
                                    val uri = Uri.parse(task.attachmentUri)
                                    val mime = ctx.contentResolver.getType(uri) ?: "*/*"
                                    val intent = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    ctx.startActivity(intent)
                                }
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(Icons.Default.AttachFile, null)
                            Spacer(Modifier.width(4.dp))
                            Text(task.attachmentName.ifBlank { "Открыть файл" })
                        }
                    }
                    Text("Напомнить за ${reminderLabel(task.remindMinutesBefore)}", style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = onCalendar) { Icon(Icons.Default.EventAvailable, "Добавить в календарь") }
                IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Изменить") }
                IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Удалить") }
            }
        }
    }
}

@Composable
fun TaskEditorDialog(
    initial: StudyTask?,
    profileId: String,
    relatedOccurrenceKey: String = "",
    relatedEventTitle: String = "",
    onDismiss: () -> Unit,
    onSave: (StudyTask) -> Unit
) {
    val today = LocalDate.now()
    var title by remember(initial, relatedEventTitle) { mutableStateOf(initial?.title.orEmpty()) }
    var details by remember(initial) { mutableStateOf(initial?.details.orEmpty()) }
    var dueDate by remember(initial) { mutableStateOf(initial?.dueDate ?: today.plusDays(1).toString()) }
    var dueTime by remember(initial) { mutableStateOf(initial?.dueTime.orEmpty()) }
    var link by remember(initial) { mutableStateOf(initial?.link.orEmpty()) }
    var reminder by remember(initial) { mutableIntStateOf(initial?.remindMinutesBefore ?: 60) }
    var reminderMenu by remember { mutableStateOf(false) }
    var attachmentUri by remember(initial) { mutableStateOf(initial?.attachmentUri.orEmpty()) }
    var attachmentName by remember(initial) { mutableStateOf(initial?.attachmentName.orEmpty()) }
    val context = LocalContext.current
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            attachmentUri = uri.toString()
            attachmentName = queryDisplayName(context, uri).ifBlank { "Прикреплённый файл" }
        }
    }
    val parsedDate = runCatching { LocalDate.parse(dueDate) }.getOrNull()
    val timeOk = dueTime.isBlank() || runCatching { LocalTime.parse(dueTime) }.isSuccess

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Новое задание" else "Редактировать задание") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val relation = initial?.relatedEventTitle.orEmpty().ifBlank { relatedEventTitle }
                if (relation.isNotBlank()) {
                    Surface(shape = RoundedCornerShape(12.dp), color = Color(0xFFE9F1FA)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.School, null, tint = Stage2Blue)
                            Spacer(Modifier.width(8.dp))
                            Text("К занятию: $relation", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text("Задание") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(details, { details = it }, label = { Text("Описание / что сделать") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(dueDate, { dueDate = it }, label = { Text("Срок") }, placeholder = { Text("2026-09-20") }, modifier = Modifier.weight(1.4f), singleLine = true)
                    OutlinedTextField(dueTime, { dueTime = it }, label = { Text("Время") }, placeholder = { Text("18:00") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                OutlinedTextField(link, { link = it }, label = { Text("Ссылка / материал") }, placeholder = { Text("https://…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedButton(onClick = { fileLauncher.launch(arrayOf("*/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.AttachFile, null)
                    Spacer(Modifier.width(6.dp))
                    Text(if (attachmentName.isBlank()) "Прикрепить файл" else attachmentName, maxLines = 1)
                }
                if (attachmentUri.isNotBlank()) {
                    TextButton(onClick = { attachmentUri = ""; attachmentName = "" }) { Text("Убрать файл") }
                }
                Box {
                    OutlinedButton(onClick = { reminderMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.NotificationsActive, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Напомнить за ${reminderLabel(reminder)}")
                    }
                    DropdownMenu(expanded = reminderMenu, onDismissRequest = { reminderMenu = false }) {
                        listOf(0, 15, 30, 60, 120, 1440).forEach { value ->
                            DropdownMenuItem(
                                text = { Text(reminderLabel(value)) },
                                onClick = { reminder = value; reminderMenu = false }
                            )
                        }
                    }
                }
                if (!timeOk) Text("Время указывается в формате 18:00", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                if (parsedDate == null) Text("Дата указывается в формате ГГГГ-ММ-ДД", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                enabled = title.isNotBlank() && parsedDate != null && timeOk,
                onClick = {
                    val base = initial
                    onSave(
                        StudyTask(
                            id = base?.id ?: UUID.randomUUID().toString(),
                            profileId = base?.profileId?.ifBlank { profileId } ?: profileId,
                            title = title.trim(),
                            details = details.trim(),
                            dueDate = dueDate,
                            dueTime = dueTime.trim(),
                            link = link.trim(),
                            attachmentUri = attachmentUri,
                            attachmentName = attachmentName,
                            relatedOccurrenceKey = base?.relatedOccurrenceKey.orEmpty().ifBlank { relatedOccurrenceKey },
                            relatedEventTitle = base?.relatedEventTitle.orEmpty().ifBlank { relatedEventTitle },
                            remindMinutesBefore = reminder,
                            completed = base?.completed ?: false,
                            createdAtMillis = base?.createdAtMillis ?: System.currentTimeMillis()
                        )
                    )
                }
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
}

@Composable
fun MoreScreen(
    changes: List<ScheduleChange>,
    profiles: ProfileCollection,
    favorites: FavoritesState,
    currentProfileId: String,
    colors: EventColorSettings,
    lessonNotifications: LessonNotificationSettings,
    diagnostics: SyncDiagnostics,
    syncing: Boolean,
    backupStatus: String,
    onSwitchProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onAddProfile: () -> Unit,
    onClearHistory: () -> Unit,
    onToggleTeacherFavorite: (String) -> Unit,
    onToggleGroupFavorite: (String) -> Unit,
    onColors: (EventColorSettings) -> Unit,
    onLessonNotifications: (LessonNotificationSettings) -> Unit,
    onOpenCampus: () -> Unit,
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onSyncNow: () -> Unit,
    onShowTodayNotification: () -> Unit
) {
    var historyExpanded by remember { mutableStateOf(true) }
    var profilesExpanded by remember { mutableStateOf(true) }
    var favoritesExpanded by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
        }
        item {
            SectionHeader("Профили", Icons.Default.SwitchAccount, profilesExpanded) { profilesExpanded = !profilesExpanded }
        }
        if (profilesExpanded) {
            items(profiles.profiles, key = { it.id }) { saved ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (saved.id == currentProfileId) Color(0xFFE9F1FA) else Color.White,
                    modifier = Modifier.fillMaxWidth().clickable { onSwitchProfile(saved.id) }
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(if (saved.profile.role == UserRole.STUDENT) Icons.Default.School else Icons.Default.Person, null, tint = Stage2Blue)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(saved.name, fontWeight = FontWeight.Bold)
                            Text(profileDescription(saved.profile), style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        if (saved.id == currentProfileId) Icon(Icons.Default.CheckCircle, "Текущий", tint = Stage2Blue)
                        if (profiles.profiles.size > 1) {
                            IconButton(onClick = { onDeleteProfile(saved.id) }) { Icon(Icons.Default.DeleteOutline, "Удалить профиль") }
                        }
                    }
                }
            }
            item {
                OutlinedButton(onClick = onAddProfile, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.PersonAdd, null); Spacer(Modifier.width(6.dp)); Text("Добавить профиль")
                }
            }
        }

        item { SectionHeader("Избранное", Icons.Default.Star, favoritesExpanded) { favoritesExpanded = !favoritesExpanded } }
        if (favoritesExpanded) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Преподаватели", fontWeight = FontWeight.Bold)
                        if (favorites.teachers.isEmpty()) Text("Пока нет избранных преподавателей", color = Color.Gray)
                        favorites.teachers.sorted().forEach { value -> FavoriteRow(value) { onToggleTeacherFavorite(value) } }
                        HorizontalDivider()
                        Text("Группы", fontWeight = FontWeight.Bold)
                        if (favorites.groups.isEmpty()) Text("Пока нет избранных групп", color = Color.Gray)
                        favorites.groups.sorted().forEach { value -> FavoriteRow(value) { onToggleGroupFavorite(value) } }
                    }
                }
            }
        }

        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SectionHeader("История изменений", Icons.Default.History, historyExpanded) { historyExpanded = !historyExpanded } }
                if (changes.isNotEmpty()) TextButton(onClick = onClearHistory) { Text("Очистить") }
            }
        }
        if (historyExpanded) {
            if (changes.isEmpty()) {
                item {
                    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                        Text("История пока пуста. После следующего изменения расписания здесь появится сравнение старой и новой версии.", Modifier.padding(14.dp), color = Color.Gray)
                    }
                }
            } else {
                items(changes.take(100), key = { it.id }) { change -> ChangeCard(change) }
            }
        }

        item {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Stage3SettingsBlocks(
                    colors = colors,
                    notifications = lessonNotifications,
                    onColors = onColors,
                    onNotifications = onLessonNotifications,
                    onOpenCampus = onOpenCampus
                )
            }
        }

        item {
            Stage4SettingsBlocks(
                diagnostics = diagnostics,
                syncing = syncing,
                backupStatus = backupStatus,
                onExportBackup = onExportBackup,
                onImportBackup = onImportBackup,
                onSyncNow = onSyncNow,
                onShowTodayNotification = onShowTodayNotification
            )
        }

        item {
            Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFE9F1FA)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.EventAvailable, null, tint = Stage2Blue)
                    Spacer(Modifier.width(10.dp))
                    Column {
                        Text("Системный календарь Android", fontWeight = FontWeight.Bold)
                        Text("В карточках занятий и заданий появилась кнопка календаря. Она открывает стандартное окно добавления события — отдельное разрешение приложению не требуется.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, expanded: Boolean, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = Stage2Blue)
            Spacer(Modifier.width(10.dp))
            Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
    }
}

@Composable
private fun FavoriteRow(value: String, onRemove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Star, null, tint = Color(0xFFFFB300))
        Spacer(Modifier.width(8.dp))
        Text(value, Modifier.weight(1f))
        IconButton(onClick = onRemove) { Icon(Icons.Default.Close, "Убрать из избранного") }
    }
}

@Composable
private fun ChangeCard(change: ScheduleChange) {
    val icon = when (change.kind) {
        "ADDED" -> Icons.Default.AddCircle
        "REMOVED" -> Icons.Default.Cancel
        else -> Icons.Default.EditCalendar
    }
    val tint = when (change.kind) {
        "ADDED" -> Color(0xFF2E7D32)
        "REMOVED" -> Color(0xFFB3261E)
        else -> Stage2Blue
    }
    var expanded by remember(change.id) { mutableStateOf(false) }
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(13.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = tint)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(change.title, fontWeight = FontWeight.Bold)
                    Text(change.details, style = MaterialTheme.typography.bodySmall)
                    Text(DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Stage2Ru).format(java.time.Instant.ofEpochMilli(change.timestampMillis).atZone(ZoneId.systemDefault())), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    if (change.oldValue.isNotBlank()) Text("Было: ${change.oldValue}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    if (change.newValue.isNotBlank()) Text("Стало: ${change.newValue}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

fun addScheduleEventToSystemCalendar(context: Context, event: ScheduleEvent, date: LocalDate) {
    val start = parseDateTime(date, event.startTime, LocalTime.of(9, 0))
    val end = parseDateTime(date, event.endTime, start.toLocalTime().plusMinutes(90))
    val description = buildString {
        if (event.type.isNotBlank()) append(event.type)
        if (event.teacher.isNotBlank()) append(if (isBlank()) event.teacher else "\nПреподаватель: ${event.teacher}")
        if (event.group.isNotBlank()) append(if (isBlank()) event.group else "\nГруппа: ${event.group}")
        if (event.sourceUrl.isNotBlank()) append(if (isBlank()) event.sourceUrl else "\nИсточник: ${event.sourceUrl}")
    }
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, event.title)
        putExtra(CalendarContract.Events.EVENT_LOCATION, event.room)
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        if (event.startTime.isBlank()) putExtra(CalendarContract.Events.ALL_DAY, true)
    }
    runCatching { context.startActivity(intent) }
}

fun addTaskToSystemCalendar(context: Context, task: StudyTask) {
    val date = runCatching { LocalDate.parse(task.dueDate) }.getOrNull() ?: return
    val start = parseDateTime(date, task.dueTime, LocalTime.of(9, 0))
    val end = start.plusMinutes(30)
    val description = listOf(
        task.details,
        task.relatedEventTitle.takeIf { it.isNotBlank() }?.let { "К занятию: $it" }.orEmpty(),
        task.link,
        task.attachmentName.takeIf { it.isNotBlank() }?.let { "Файл: $it" }.orEmpty()
    )
        .filter { it.isNotBlank() }.joinToString("\n")
    val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI).apply {
        putExtra(CalendarContract.Events.TITLE, "Дедлайн: ${task.title}")
        putExtra(CalendarContract.Events.DESCRIPTION, description)
        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        if (task.dueTime.isBlank()) putExtra(CalendarContract.Events.ALL_DAY, true)
    }
    runCatching { context.startActivity(intent) }
}

private fun parseDateTime(date: LocalDate, time: String, fallback: LocalTime): LocalDateTime {
    val parsed = runCatching { LocalTime.parse(time) }.getOrElse { fallback }
    return LocalDateTime.of(date, parsed)
}

private fun formatTaskDue(task: StudyTask): String {
    val date = runCatching { LocalDate.parse(task.dueDate) }.getOrNull()
    val dateText = date?.format(DateTimeFormatter.ofPattern("d MMMM yyyy", Stage2Ru)) ?: task.dueDate
    return listOf(dateText, task.dueTime).filter { it.isNotBlank() }.joinToString(" · ")
}

private fun reminderLabel(minutes: Int): String = when (minutes) {
    0 -> "в момент срока"
    15 -> "15 минут"
    30 -> "30 минут"
    60 -> "1 час"
    120 -> "2 часа"
    1440 -> "1 день"
    else -> "$minutes мин"
}

private fun queryDisplayName(context: Context, uri: Uri): String = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
        if (cursor.moveToFirst()) cursor.getString(0).orEmpty() else ""
    }.orEmpty()
}.getOrDefault("")

private fun profileDescription(profile: UserProfile): String = when (profile.role) {
    UserRole.STUDENT -> "${profile.institute} · ${profile.course} курс · ${profile.studyForm}"
    UserRole.TEACHER -> "Преподаватель"
}
