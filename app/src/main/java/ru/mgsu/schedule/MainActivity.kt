@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package ru.mgsu.schedule

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import coil.compose.AsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.mgsu.schedule.data.*
import ru.mgsu.schedule.worker.NotificationHelper
import ru.mgsu.schedule.worker.SyncWorker
import ru.mgsu.schedule.worker.TaskReminderWorker
import ru.mgsu.schedule.worker.Stage3NotificationScheduler
import ru.mgsu.schedule.worker.isOnDate
import ru.mgsu.schedule.widget.NextClassWidgetProvider
import ru.mgsu.schedule.widget.TodayScheduleWidgetProvider
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

private val MgsuBlue = Color(0xFF0F3C73)
private val Ru = Locale("ru", "RU")
private const val OfficialMgsuLogoUrl = "https://api-loft.mgsu.ru/storage/files/1/9/3/193.png"
private const val OfficialMgsuCampusUrl = "https://mgsu.ru/news/2025/02-09-2025-86800648.jpg"
private const val OfficialMgsuSiteUrl = "https://mgsu.ru/"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MgsuTheme { MgsuApp() } }
    }
}

class MainVm(private val context: android.app.Application) : AndroidViewModel(context) {
    private val store = AppStore(context)
    private val catalogRepository = MgsuCatalogRepository(context)

    var profile by mutableStateOf<UserProfile?>(null); private set
    var profiles by mutableStateOf(ProfileCollection()); private set
    var favorites by mutableStateOf(FavoritesState()); private set
    var tasks by mutableStateOf<List<StudyTask>>(emptyList()); private set
    var colorSettings by mutableStateOf(EventColorSettings()); private set
    var notificationSettings by mutableStateOf(LessonNotificationSettings()); private set
    var recentSelections by mutableStateOf(RecentSelections()); private set
    var diagnostics by mutableStateOf(store.readDiagnostics()); private set
    var backupStatus by mutableStateOf(""); private set
    var changes by mutableStateOf(store.readChanges()); private set
    var cache by mutableStateOf(store.readCache()); private set
    var edits by mutableStateOf(LocalEdits()); private set
    var catalog by mutableStateOf(catalogRepository.cached()); private set
    var syncing by mutableStateOf(false); private set
    var catalogLoading by mutableStateOf(false); private set
    var catalogError by mutableStateOf(""); private set
    var bootstrapReady by mutableStateOf(false); private set
    var bootstrapError by mutableStateOf(""); private set
    var bootstrapMessage by mutableStateOf("Подождите, загружаем официальное расписание НИУ МГСУ…"); private set
    var loaded by mutableStateOf(false); private set
    private var catalogJob: Job? = null

    val currentProfileId: String get() = profiles.currentId

    init {
        viewModelScope.launch {
            profiles = store.profileCollectionFlow.first()
            profile = profiles.profiles.firstOrNull { it.id == profiles.currentId }?.profile
                ?: profiles.profiles.firstOrNull()?.profile
            edits = store.editsFlow.first()
            val loadedFavorites = store.favoritesFlow.first()
            favorites = loadedFavorites.copy(
                teachers = loadedFavorites.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).toSet(),
                groups = loadedFavorites.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).toSet()
            )
            tasks = store.tasksFlow.first()
            colorSettings = store.eventColorSettingsFlow.first()
            notificationSettings = store.lessonNotificationSettingsFlow.first()
            val loadedRecent = store.recentSelectionsFlow.first()
            recentSelections = loadedRecent.copy(
                teachers = loadedRecent.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct().take(8),
                groups = loadedRecent.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().take(10)
            )
            diagnostics = store.readDiagnostics()
            changes = store.readChanges()
            loaded = true
            if (profiles.profiles.isEmpty()) {
                prepareFirstLaunchCatalog()
            } else {
                bootstrapReady = true
                ensureCatalog()
            }
            scheduleWorkers()
            rescheduleLessonNotifications()
        }
    }

    fun saveProfile(p: UserProfile) = viewModelScope.launch {
        val normalized = if (p.role == UserRole.STUDENT && ScheduleParsingRules.isValidGroup(p.group)) {
            p.copy(group = ScheduleParsingRules.canonicalGroup(p.group).orEmpty(), teacher = "")
        } else if (p.role == UserRole.TEACHER && ScheduleParsingRules.isValidTeacher(p.teacher)) {
            p.copy(teacher = ScheduleParsingRules.canonicalTeacher(p.teacher).orEmpty(), group = "")
        } else return@launch
        profiles = store.createProfile(normalized)
        profile = normalized
        store.clearCache()
        cache = CachedSchedule(parserVersion = AppStore.CURRENT_PARSER_VERSION)
        scheduleWorkers()
        sync()
    }

    fun startAddProfile() {
        profile = null
    }

    fun switchProfile(id: String) = viewModelScope.launch {
        val updated = store.switchProfile(id)
        profiles = updated
        profile = updated.profiles.firstOrNull { it.id == updated.currentId }?.profile
        store.clearCache()
        cache = CachedSchedule(parserVersion = AppStore.CURRENT_PARSER_VERSION)
        updateWidgets()
        scheduleWorkers()
        sync()
    }

    fun deleteProfile(id: String) = viewModelScope.launch {
        val wasCurrent = id == profiles.currentId
        profiles = store.deleteProfile(id)
        if (wasCurrent) {
            profile = profiles.profiles.firstOrNull { it.id == profiles.currentId }?.profile
            store.clearCache()
            cache = CachedSchedule(parserVersion = AppStore.CURRENT_PARSER_VERSION)
            updateWidgets()
            if (profile != null) sync()
        }
    }

    fun resetAllProfiles() = viewModelScope.launch {
        store.clearProfile()
        profiles = ProfileCollection()
        profile = null
        cache = CachedSchedule(parserVersion = AppStore.CURRENT_PARSER_VERSION)
    }

    fun sync() = viewModelScope.launch {
        val p = profile ?: return@launch
        syncing = true
        try {
            val knownChangeIds = store.readChanges().map { it.id }.toSet()
            cache = MgsuRepository(context).sync(p)
            diagnostics = store.readDiagnostics()
            catalog = catalogRepository.mergeFromSchedule(cache)
            changes = store.readChanges()
            val newChanges = changes.filterNot { it.id in knownChangeIds }
            if (newChanges.isNotEmpty()) NotificationHelper.notifyChanges(context, newChanges)
            updateWidgets()
            rescheduleLessonNotifications()
        } finally {
            diagnostics = store.readDiagnostics()
            syncing = false
        }
    }

    fun clearHistory() {
        store.clearChanges()
        changes = emptyList()
    }

    fun prepareFirstLaunchCatalog() {
        if (catalogJob?.isActive == true) return
        catalogJob = viewModelScope.launch {
            bootstrapReady = false
            bootstrapError = ""
            catalogError = ""
            catalogLoading = true
            bootstrapMessage = "Подождите, получаем список официальных файлов НИУ МГСУ…"
            try {
                catalog = catalogRepository.refresh(force = true)
                bootstrapMessage = "Проверяем группы и преподавателей…"
                if (catalog.groups.isEmpty() || catalog.teachers.isEmpty()) {
                    bootstrapError = "Не удалось полностью обработать официальное расписание. Проверьте интернет и повторите загрузку."
                } else {
                    bootstrapReady = true
                }
            } catch (t: Throwable) {
                bootstrapError = t.message ?: "Не удалось загрузить расписание НИУ МГСУ"
            } finally {
                catalogLoading = false
            }
        }
    }

    fun ensureCatalog(force: Boolean = false) {
        if (catalogJob?.isActive == true) return
        if (!force && catalog.teachers.isNotEmpty() && catalog.groups.isNotEmpty()) return
        catalogJob = viewModelScope.launch {
            catalogLoading = true
            catalogError = ""
            try {
                catalog = catalogRepository.refresh(force)
                if (catalog.teachers.isEmpty() && catalog.groups.isEmpty()) catalogError = "Не удалось загрузить справочник с сайта МГСУ"
            } catch (t: Throwable) {
                catalogError = t.message ?: "Не удалось обновить справочник"
            } finally {
                catalogLoading = false
            }
        }
    }

    fun teacherSuggestions(query: String): List<String> {
        val validCatalog = catalog.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct()
        val validRecent = recentSelections.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct()
        val validFavorites = favorites.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).toSet()
        val q = normalizeSearch(query)
        if (q.isBlank()) return (validRecent + validFavorites.sorted()).distinct().take(8)
        return rankSuggestions(validCatalog, query, validFavorites, validRecent).take(8)
    }

    fun groupSuggestions(query: String, institute: String, course: Int): List<String> {
        val base = catalog.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().filter { g ->
            ScheduleParsingRules.groupInstitute(g).equals(institute, ignoreCase = true) &&
                ScheduleParsingRules.groupCourse(g) == course
        }
        val validRecent = recentSelections.groups.mapNotNull(ScheduleParsingRules::canonicalGroup).filter { it in base }.distinct()
        val validFavorites = favorites.groups.mapNotNull(ScheduleParsingRules::canonicalGroup).filter { it in base }.toSet()
        val q = normalizeSearch(query)
        if (q.isBlank()) return (validRecent + validFavorites.sorted()).distinct().take(10)
        return rankSuggestions(base, query, validFavorites, validRecent).take(10)
    }

    fun recordTeacherSelection(value: String) = viewModelScope.launch {
        val canonical = ScheduleParsingRules.canonicalTeacher(value) ?: return@launch
        recentSelections = recentSelections.copy(teachers = (listOf(canonical) + recentSelections.teachers.mapNotNull(ScheduleParsingRules::canonicalTeacher)).distinct().take(8))
        store.saveRecentSelections(recentSelections)
    }

    fun recordGroupSelection(value: String) = viewModelScope.launch {
        val canonical = ScheduleParsingRules.canonicalGroup(value) ?: return@launch
        recentSelections = recentSelections.copy(groups = (listOf(canonical) + recentSelections.groups.mapNotNull(ScheduleParsingRules::canonicalGroup)).distinct().take(10))
        store.saveRecentSelections(recentSelections)
    }

    fun toggleFavoriteTeacher(value: String) = viewModelScope.launch {
        val canonical = ScheduleParsingRules.canonicalTeacher(value) ?: return@launch
        favorites = favorites.copy(teachers = favorites.teachers.mapNotNull(ScheduleParsingRules::canonicalTeacher).toMutableSet().apply { if (!add(canonical)) remove(canonical) })
        store.saveFavorites(favorites)
    }

    fun toggleFavoriteGroup(value: String) = viewModelScope.launch {
        val canonical = ScheduleParsingRules.canonicalGroup(value) ?: return@launch
        favorites = favorites.copy(groups = favorites.groups.mapNotNull(ScheduleParsingRules::canonicalGroup).toMutableSet().apply { if (!add(canonical)) remove(canonical) })
        store.saveFavorites(favorites)
    }

    fun note(key: String, note: String) = viewModelScope.launch {
        edits = edits.copy(notes = edits.notes.toMutableMap().apply { if (note.isBlank()) remove(key) else put(key, note) })
        store.saveEdits(edits)
        updateWidgets()
    }

    fun hideOccurrence(eventId: String, date: LocalDate) = viewModelScope.launch {
        val key = occurrenceKey(eventId, date)
        edits = edits.copy(hiddenOccurrenceIds = edits.hiddenOccurrenceIds + key)
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun restoreOccurrence(eventId: String, date: LocalDate) = viewModelScope.launch {
        val key = occurrenceKey(eventId, date)
        edits = edits.copy(
            hiddenOccurrenceIds = edits.hiddenOccurrenceIds - key,
            hiddenEventIds = edits.hiddenEventIds - eventId
        )
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun resetOccurrenceToOfficial(eventId: String, date: LocalDate) = viewModelScope.launch {
        val key = occurrenceKey(eventId, date)
        edits = edits.copy(
            hiddenOccurrenceIds = edits.hiddenOccurrenceIds - key,
            hiddenEventIds = edits.hiddenEventIds - eventId,
            eventOverrides = edits.eventOverrides - key
        )
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun editOccurrence(eventId: String, date: LocalDate, override: EventOverride) = viewModelScope.launch {
        val key = occurrenceKey(eventId, date)
        edits = edits.copy(eventOverrides = edits.eventOverrides.toMutableMap().apply { put(key, override) })
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun addCustomEvent(event: CustomCalendarEvent) = viewModelScope.launch {
        edits = edits.copy(customEvents = edits.customEvents + event)
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun updateCustomEvent(event: CustomCalendarEvent) = viewModelScope.launch {
        edits = edits.copy(customEvents = edits.customEvents.map { if (it.id == event.id) event else it })
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun deleteCustomEvent(id: String) = viewModelScope.launch {
        edits = edits.copy(deletedCustomEventIds = edits.deletedCustomEventIds + id)
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun restoreCustomEvent(id: String) = viewModelScope.launch {
        edits = edits.copy(deletedCustomEventIds = edits.deletedCustomEventIds - id)
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun restoreAll() = viewModelScope.launch {
        edits = edits.copy(hiddenEventIds = emptySet(), hiddenOccurrenceIds = emptySet(), deletedCustomEventIds = emptySet())
        store.saveEdits(edits)
        updateWidgets()
        rescheduleLessonNotifications()
    }

    fun addTask(task: StudyTask) = viewModelScope.launch {
        val scoped = task.copy(profileId = currentProfileId, createdAtMillis = if (task.createdAtMillis == 0L) System.currentTimeMillis() else task.createdAtMillis)
        tasks = (tasks + scoped).distinctBy { it.id }
        store.saveTasks(tasks)
        scheduleTaskReminder(scoped)
    }

    fun updateTask(task: StudyTask) = viewModelScope.launch {
        tasks = tasks.map { if (it.id == task.id) task else it }
        store.saveTasks(tasks)
        scheduleTaskReminder(task)
    }

    fun toggleTask(id: String) = viewModelScope.launch {
        tasks = tasks.map { if (it.id == id) it.copy(completed = !it.completed) else it }
        store.saveTasks(tasks)
        tasks.firstOrNull { it.id == id }?.let { scheduleTaskReminder(it) }
    }

    fun deleteTask(id: String) = viewModelScope.launch {
        tasks = tasks.filterNot { it.id == id }
        store.saveTasks(tasks)
        WorkManager.getInstance(context).cancelUniqueWork("study_task_$id")
    }

    private fun scheduleTaskReminder(task: StudyTask) {
        val wm = WorkManager.getInstance(context)
        val name = "study_task_${task.id}"
        wm.cancelUniqueWork(name)
        if (task.completed) return
        val dueDate = runCatching { LocalDate.parse(task.dueDate) }.getOrNull() ?: return
        val dueTime = runCatching { java.time.LocalTime.parse(task.dueTime.ifBlank { "09:00" }) }.getOrNull() ?: return
        val due = java.time.LocalDateTime.of(dueDate, dueTime).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val at = due - task.remindMinutesBefore.coerceAtLeast(0) * 60_000L
        val delay = at - System.currentTimeMillis()
        if (delay <= 0L) return
        val request = OneTimeWorkRequestBuilder<TaskReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf("task_id" to task.id))
            .build()
        wm.enqueueUniqueWork(name, ExistingWorkPolicy.REPLACE, request)
    }

    fun exportBackupToUri(uri: Uri) = viewModelScope.launch {
        backupStatus = ""
        runCatching {
            val backup = AppBackup(
                schemaVersion = 1,
                exportedAtMillis = System.currentTimeMillis(),
                profiles = profiles,
                edits = edits,
                favorites = favorites,
                tasks = tasks,
                colors = colorSettings,
                notifications = notificationSettings,
                recentSelections = recentSelections,
                cache = cache,
                changes = changes
            )
            val text = store.encodeBackup(backup)
            context.contentResolver.openOutputStream(uri, "wt")?.bufferedWriter()?.use { it.write(text) }
                ?: error("Не удалось открыть файл для записи")
        }.onSuccess { backupStatus = "Резервная копия сохранена" }
            .onFailure { backupStatus = "Ошибка экспорта: ${it.message ?: "неизвестная ошибка"}" }
    }

    fun importBackupFromUri(uri: Uri) = viewModelScope.launch {
        backupStatus = ""
        runCatching {
            val raw = context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                ?: error("Не удалось прочитать файл")
            val backup = store.decodeBackup(raw)
            store.applyBackup(backup)
            profiles = backup.profiles
            profile = backup.profiles.profiles.firstOrNull { it.id == backup.profiles.currentId }?.profile
                ?: backup.profiles.profiles.firstOrNull()?.profile
            edits = backup.edits
            favorites = backup.favorites
            tasks = backup.tasks
            colorSettings = backup.colors
            notificationSettings = backup.notifications
            recentSelections = backup.recentSelections
            cache = backup.cache
            changes = backup.changes
            diagnostics = store.readDiagnostics()
            updateWidgets()
            scheduleWorkers()
            tasks.filterNot { it.completed }.forEach { scheduleTaskReminder(it) }
            rescheduleLessonNotifications()
        }.onSuccess {
            backupStatus = "Резервная копия восстановлена"
            if (profile != null) sync()
        }.onFailure { backupStatus = "Ошибка импорта: ${it.message ?: "неизвестная ошибка"}" }
    }

    fun showTodayNotification() {
        val p = profile ?: return
        val events = Stage3NotificationScheduler.eventsForDate(cache, edits, p, LocalDate.now())
        NotificationHelper.notifyMorningBrief(context, events, LocalDate.now())
    }

    fun saveColorSettings(settings: EventColorSettings) = viewModelScope.launch {
        colorSettings = settings
        store.saveEventColorSettings(settings)
    }

    fun saveNotificationSettings(settings: LessonNotificationSettings) = viewModelScope.launch {
        notificationSettings = settings
        store.saveLessonNotificationSettings(settings)
        rescheduleLessonNotifications()
    }

    private fun rescheduleLessonNotifications() {
        val p = profile ?: return
        Stage3NotificationScheduler.reschedule(context, p, cache, edits, notificationSettings)
    }

    private fun updateWidgets() {
        NextClassWidgetProvider.updateAll(context)
        TodayScheduleWidgetProvider.updateAll(context)
    }

    private fun scheduleWorkers() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork(
            "mgsu_sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
        )
        // Configurable morning/evening briefs replace the legacy 12-hour tomorrow worker.
        wm.cancelUniqueWork("mgsu_tomorrow")
    }

    private fun rankSuggestions(values: List<String>, query: String, favoriteValues: Set<String>, recentValues: List<String> = emptyList()): List<String> {
        val q = normalizeSearch(query)
        val recentRank = recentValues.withIndex().associate { it.value to it.index }
        val filtered = if (q.length < 2) values else values.filter { normalizeSearch(it).contains(q) }
        return filtered.sortedWith(
            compareBy<String> { recentRank[it] ?: Int.MAX_VALUE }
                .thenByDescending { it in favoriteValues }
                .thenBy { if (q.isNotBlank() && normalizeSearch(it).startsWith(q)) 0 else 1 }
                .thenBy { it.length }
                .thenBy { it }
        )
    }
}
@Composable
fun MgsuApp(vm: MainVm = viewModel()) {
    if (!vm.loaded) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MgsuBlue) }
        return
    }
    val p = vm.profile
    if (p == null && !vm.bootstrapReady) {
        FirstLaunchLoadingScreen(vm)
    } else if (p == null) {
        ProfileSetup(vm, onSave = vm::saveProfile)
    } else {
        ScheduleHome(vm, p)
    }
}

@Composable
private fun FirstLaunchLoadingScreen(vm: MainVm) {
    val ctx = LocalContext.current
    Column(
        Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(ctx).data(OfficialMgsuLogoUrl).crossfade(true).build(),
            contentDescription = "Официальный логотип НИУ МГСУ",
            modifier = Modifier.height(110.dp).fillMaxWidth(.75f),
            contentScale = ContentScale.Fit
        )
        Spacer(Modifier.height(28.dp))
        Text("Расписание МГСУ", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, color = MgsuBlue)
        Spacer(Modifier.height(10.dp))
        if (vm.bootstrapError.isBlank()) {
            CircularProgressIndicator(color = MgsuBlue)
            Spacer(Modifier.height(18.dp))
            Text(vm.bootstrapMessage, textAlign = TextAlign.Center, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            Text(
                "На первом запуске приложение загружает и обрабатывает официальные файлы занятий и экзаменов. Это может занять несколько минут.",
                textAlign = TextAlign.Center,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        } else {
            Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(42.dp))
            Spacer(Modifier.height(12.dp))
            Text(vm.bootstrapError, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(16.dp))
            Button(onClick = vm::prepareFirstLaunchCatalog) {
                Icon(Icons.Default.Refresh, null); Spacer(Modifier.width(8.dp)); Text("Повторить загрузку")
            }
        }
        Spacer(Modifier.height(20.dp))
        TextButton(onClick = {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(OfficialMgsuSiteUrl))) }
        }) { Text("Источник: официальный сайт НИУ МГСУ") }
    }
}

@Composable
private fun ProfileSetup(vm: MainVm, onSave: (UserProfile) -> Unit) {
    var role by remember { mutableStateOf<UserRole?>(null) }
    var institute by remember { mutableStateOf("ИАГ") }
    var course by remember { mutableIntStateOf(1) }
    var form by remember { mutableStateOf("Очная") }
    var group by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("2026-08-31") }
    val institutes = listOf("ИАГ", "ИПГС", "ИГЭС", "ИИЭСМ", "ИЦТМС", "ИЭУКСН", "ИИС ОИАЭ", "ИДО")

    LaunchedEffect(role) { if (role != null) vm.ensureCatalog() }

    Column(
        Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MgsuBlue, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(230.dp)) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current).data(OfficialMgsuCampusUrl).crossfade(true).build(),
                    contentDescription = "Кампус НИУ МГСУ",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .46f)))
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.SpaceBetween) {
                    AsyncImage(
                        model = ImageRequest.Builder(LocalContext.current).data(OfficialMgsuLogoUrl).crossfade(true).build(),
                        contentDescription = "Официальный логотип НИУ МГСУ",
                        modifier = Modifier.height(88.dp).widthIn(max = 170.dp),
                        contentScale = ContentScale.Fit
                    )
                    Column {
                        Text("Расписание МГСУ", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                        Text("Занятия, экзамены и личный календарь", color = Color.White.copy(alpha = .94f), modifier = Modifier.padding(top = 5.dp))
                        Text("Фото и символика: официальный сайт НИУ МГСУ", color = Color.White.copy(alpha = .78f), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        if (vm.profiles.profiles.isNotEmpty()) {
            Text("Сохранённые профили", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.profiles.profiles.forEach { saved ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (saved.id == vm.profiles.currentId) Color(0xFFE9F1FA) else Color.White,
                        modifier = Modifier.fillMaxWidth().clickable { vm.switchProfile(saved.id) }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (saved.profile.role == UserRole.STUDENT) Icons.Default.School else Icons.Default.Person, null, tint = MgsuBlue)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(saved.name, fontWeight = FontWeight.Bold)
                                Text(if (saved.profile.role == UserRole.STUDENT) "${saved.profile.institute} · ${saved.profile.studyForm}" else "Преподаватель", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("Добавить ещё один профиль", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        Text("Кто вы?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleCard("Студент", Icons.Default.School, role == UserRole.STUDENT) { role = UserRole.STUDENT }
            RoleCard("Преподаватель", Icons.Default.Person, role == UserRole.TEACHER) { role = UserRole.TEACHER }
        }
        AnimatedVisibility(role != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (role == UserRole.STUDENT) {
                    DropField("Институт", institute, institutes) { institute = it; group = "" }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropField("Курс", "$course", (1..6).map { "$it" }, Modifier.weight(1f)) { course = it.toInt(); group = "" }
                        DropField("Форма", form, listOf("Очная", "Очно-заочная", "Заочная"), Modifier.weight(2f)) { form = it }
                    }
                    AutoCompleteField(
                        label = "Группа",
                        value = group,
                        placeholder = "Например: ИПГС 3-18",
                        suggestions = vm.groupSuggestions(group, institute, course),
                        loading = vm.catalogLoading,
                        favorites = vm.favorites.groups,
                        onToggleFavorite = vm::toggleFavoriteGroup,
                        onValueChange = { group = it },
                        onSelected = { group = it; vm.recordGroupSelection(it) }
                    )
                } else if (role == UserRole.TEACHER) {
                    AutoCompleteField(
                        label = "Преподаватель",
                        value = teacher,
                        placeholder = "Например: Молоткова П.А.",
                        suggestions = vm.teacherSuggestions(teacher),
                        loading = vm.catalogLoading,
                        favorites = vm.favorites.teachers,
                        onToggleFavorite = vm::toggleFavoriteTeacher,
                        onValueChange = { teacher = it },
                        onSelected = { teacher = it; vm.recordTeacherSelection(it) }
                    )
                }
                if (vm.catalogError.isNotBlank()) {
                    AssistChip(onClick = { vm.ensureCatalog(true) }, label = { Text("Не удалось обновить подсказки. Повторить") }, leadingIcon = { Icon(Icons.Default.Refresh, null) })
                }
                OutlinedTextField(
                    start, { start = it }, label = { Text("Понедельник 1-й учебной недели") },
                    supportingText = { Text("Для чётных/нечётных недель и номеров учебных недель") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
                val canonicalGroup = if (role == UserRole.STUDENT && ScheduleParsingRules.isValidGroup(group)) ScheduleParsingRules.canonicalGroup(group) else null
                val canonicalTeacher = if (role == UserRole.TEACHER && ScheduleParsingRules.isValidTeacher(teacher)) ScheduleParsingRules.canonicalTeacher(teacher) else null
                if (role == UserRole.STUDENT && group.isNotBlank() && canonicalGroup == null) {
                    Text("Выберите конкретную группу, например ИПГС 3-18. Значение вроде «ИПГС 1 курс» больше не принимается.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (role == UserRole.TEACHER && teacher.isNotBlank() && canonicalTeacher == null) {
                    Text("Введите фамилию и два инициала, например Молоткова П.А. Случайные фрагменты PDF больше не принимаются как преподаватели.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (inputError.isNotBlank()) {
                    Text(inputError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Button(
                    onClick = {
                        val r = role ?: return@Button
                        if (r == UserRole.STUDENT) {
                            val g = group.takeIf(ScheduleParsingRules::isValidGroup)?.let(ScheduleParsingRules::canonicalGroup)
                            if (g == null) { inputError = "Нужно выбрать точную группу из подсказки."; return@Button }
                            inputError = ""
                            group = g
                            vm.recordGroupSelection(g)
                            onSave(UserProfile(r, institute, course, form, g, "", start, true))
                        } else {
                            val t = teacher.takeIf(ScheduleParsingRules::isValidTeacher)?.let(ScheduleParsingRules::canonicalTeacher)
                            if (t == null) { inputError = "Нужно выбрать преподавателя в формате Фамилия И.О."; return@Button }
                            inputError = ""
                            teacher = t
                            vm.recordTeacherSelection(t)
                            onSave(UserProfile(r, institute, course, form, "", t, start, true))
                        }
                    },
                    enabled = (role == UserRole.STUDENT && canonicalGroup != null) || (role == UserRole.TEACHER && canonicalTeacher != null),
                    modifier = Modifier.fillMaxWidth().height(52.dp), colors = ButtonDefaults.buttonColors(containerColor = MgsuBlue)
                ) { Text("Открыть календарь") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("Неофициальное приложение. Расписание, фото и символика — из официальных ресурсов НИУ МГСУ (mgsu.ru).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

@Composable
private fun AutoCompleteField(
    label: String,
    value: String,
    placeholder: String,
    suggestions: List<String>,
    loading: Boolean,
    favorites: Set<String> = emptySet(),
    onToggleFavorite: (String) -> Unit = {},
    onValueChange: (String) -> Unit,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(suggestions, value) {
        if (value.isNotBlank() && suggestions.isNotEmpty()) expanded = true
    }
    Box(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = value,
                onValueChange = { onValueChange(it); expanded = true },
                label = { Text(label) }, placeholder = { Text(placeholder) },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (loading) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else IconButton(onClick = { expanded = suggestions.isNotEmpty() }) { Icon(Icons.Default.ArrowDropDown, "Показать варианты") }
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { state -> if (state.isFocused && suggestions.isNotEmpty()) expanded = true },
                singleLine = true
            )
            DropdownMenu(expanded = expanded && suggestions.isNotEmpty(), onDismissRequest = { expanded = false }, modifier = Modifier.fillMaxWidth(.92f)) {
                if (value.isBlank()) {
                    Text("Недавние и избранные", modifier = Modifier.padding(horizontal = 12.dp, vertical = 5.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                }
                suggestions.forEach { item ->
                    DropdownMenuItem(
                        text = { HighlightedSuggestion(item, value) },
                        leadingIcon = { Icon(if (label == "Преподаватель") Icons.Default.Person else Icons.Default.Groups, null) },
                        trailingIcon = {
                            IconButton(onClick = { onToggleFavorite(item) }, modifier = Modifier.size(32.dp)) {
                                Icon(if (item in favorites) Icons.Default.Star else Icons.Default.StarBorder, if (item in favorites) "Убрать из избранного" else "В избранное", tint = if (item in favorites) Color(0xFFFFB300) else Color.Gray)
                            }
                        },
                        onClick = { onSelected(item); expanded = false }
                    )
                }
            }
            if (loading && value.length >= 2 && suggestions.isEmpty()) Text("Загружаю варианты с расписания МГСУ…", style = MaterialTheme.typography.bodySmall, color = Color.Gray, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
        }
    }
}

@Composable
private fun HighlightedSuggestion(text: String, query: String) {
    val q = query.trim()
    val index = if (q.isBlank()) -1 else text.indexOf(q, ignoreCase = true)
    if (index < 0) {
        Text(text, maxLines = 1)
        return
    }
    val annotated: AnnotatedString = buildAnnotatedString {
        append(text.substring(0, index))
        withStyle(SpanStyle(fontWeight = FontWeight.Black, color = MgsuBlue)) {
            append(text.substring(index, index + q.length))
        }
        append(text.substring(index + q.length))
    }
    Text(annotated, maxLines = 1)
}

@Composable
private fun RowScope.RoleCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(modifier = Modifier.weight(1f).clickable(onClick = onClick), shape = RoundedCornerShape(18.dp), color = if (selected) MgsuBlue else Color.White, tonalElevation = 2.dp) {
        Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = if (selected) Color.White else MgsuBlue)
            Text(title, color = if (selected) Color.White else Color.DarkGray, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DropField(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onValue: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(
            value, {}, readOnly = true, label = { Text(label) },
            trailingIcon = { Icon(Icons.Default.ArrowDropDown, null, Modifier.clickable { expanded = true }) },
            modifier = Modifier.fillMaxWidth().clickable { expanded = true }
        )
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { DropdownMenuItem({ Text(it) }, { onValue(it); expanded = false }) }
        }
    }
}

private data class DayEventItem(
    val key: String,
    val event: ScheduleEvent,
    val hidden: Boolean,
    val customId: String? = null,
    val customNote: String = "",
    val locallyModified: Boolean = false
)

private enum class HomeTab { TODAY, WEEK, MONTH, TASKS, SETTINGS }

@Composable
private fun BottomNavLabel(text: String) {
    Text(text, maxLines = 1, softWrap = false, fontSize = 9.sp, textAlign = TextAlign.Center)
}

@Composable
private fun ScheduleHome(vm: MainVm, profile: UserProfile) {
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var showHidden by remember { mutableStateOf(false) }
    var timelineOpen by remember { mutableStateOf(false) }
    var editItem by remember { mutableStateOf<DayEventItem?>(null) }
    var addOpen by remember { mutableStateOf(false) }
    var tab by remember { mutableStateOf(HomeTab.TODAY) }
    var weekAnchor by remember { mutableStateOf(LocalDate.now()) }
    var taskEditor by remember { mutableStateOf<StudyTask?>(null) }
    var taskEditorOpen by remember { mutableStateOf(false) }
    var taskRelatedKey by remember { mutableStateOf("") }
    var taskRelatedTitle by remember { mutableStateOf("") }
    var searchOpen by remember { mutableStateOf(false) }
    var campusRoom by remember { mutableStateOf<String?>(null) }
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (vm.cache.lastSyncMillis == 0L || vm.cache.events.isEmpty() || vm.cache.lastError.isNotBlank()) vm.sync()
    }

    val week = academicWeek(selected, profile.semesterStart)
    val allDayItems = remember(vm.cache, vm.edits, selected) {
        buildDayItems(vm.cache, vm.edits, selected, profile.semesterStart, showHidden = true)
    }
    val dayItems = remember(allDayItems, showHidden) { if (showHidden) allDayItems else allDayItems.filterNot { it.hidden } }
    val hiddenForDay = allDayItems.count { it.hidden }

    fun openDay(date: LocalDate) {
        selected = date
        month = YearMonth.from(date)
        showHidden = false
        timelineOpen = true
    }

    if (timelineOpen) {
        DayTimelineScreen(
            date = selected,
            week = week,
            items = allDayItems.filterNot { it.hidden },
            colors = vm.colorSettings,
            onBack = { timelineOpen = false },
            onPreviousDay = {
                selected = selected.minusDays(1)
                month = YearMonth.from(selected)
                showHidden = false
            },
            onNextDay = {
                selected = selected.plusDays(1)
                month = YearMonth.from(selected)
                showHidden = false
            },
            onToday = {
                selected = LocalDate.now()
                month = YearMonth.from(selected)
                showHidden = false
            },
            onAdd = { addOpen = true },
            onEdit = { editItem = it },
            onDelete = { item ->
                if (item.customId == null) vm.hideOccurrence(item.event.id, selected) else vm.deleteCustomEvent(item.customId)
            },
            onMove = { item, newStart, newEnd ->
                if (item.customId == null) {
                    val old = vm.edits.eventOverrides[item.key] ?: EventOverride()
                    vm.editOccurrence(item.event.id, selected, old.copy(startTime = newStart, endTime = newEnd))
                } else {
                    vm.edits.customEvents.firstOrNull { it.id == item.customId }?.let {
                        vm.updateCustomEvent(it.copy(startTime = newStart, endTime = newEnd))
                    }
                }
            },
            onTask = { item ->
                taskEditor = null
                taskRelatedKey = item.key
                taskRelatedTitle = item.event.title
                taskEditorOpen = true
            },
            onCalendar = { item -> addScheduleEventToSystemCalendar(ctx, item.event, selected) },
            onCampus = { item -> campusRoom = item.event.room }
        )
    } else {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(if (profile.role == UserRole.STUDENT) profile.group else profile.teacher, fontWeight = FontWeight.Bold, maxLines = 1)
                            val currentWeek = academicWeek(LocalDate.now(), profile.semesterStart)
                            Text(
                                if (currentWeek > 0) "Учебная неделя №$currentWeek · ${if (currentWeek % 2 == 0L) "чётная" else "нечётная"}" else "Вне учебного семестра",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    },
                    actions = {
                        IconButton({ searchOpen = true }) { Icon(Icons.Default.Search, "Поиск по расписанию") }
                        IconButton({ campusRoom = "" }) { Icon(Icons.Default.Map, "Корпуса МГСУ") }
                        IconButton({ vm.sync() }) {
                            if (vm.syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(Icons.Default.Refresh, "Обновить")
                        }
                        IconButton({ tab = HomeTab.SETTINGS }) { Icon(Icons.Default.Settings, "Настройки") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MgsuBlue,
                        titleContentColor = Color.White,
                        actionIconContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(modifier = Modifier.height(72.dp)) {
                    NavigationBarItem(
                        selected = tab == HomeTab.TODAY,
                        onClick = { tab = HomeTab.TODAY },
                        icon = { Icon(Icons.Default.Today, null) },
                        label = { BottomNavLabel("Сегодня") }
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.WEEK,
                        onClick = { tab = HomeTab.WEEK },
                        icon = { Icon(Icons.Default.ViewWeek, null) },
                        label = { BottomNavLabel("Неделя") }
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.MONTH,
                        onClick = { tab = HomeTab.MONTH },
                        icon = { Icon(Icons.Default.CalendarMonth, null) },
                        label = { BottomNavLabel("Месяц") }
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.TASKS,
                        onClick = { tab = HomeTab.TASKS },
                        icon = { Icon(Icons.Default.TaskAlt, null) },
                        label = { BottomNavLabel("Задания") }
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.SETTINGS,
                        onClick = { tab = HomeTab.SETTINGS },
                        icon = { Icon(Icons.Default.Settings, null) },
                        label = { BottomNavLabel("Настройки") }
                    )
                }
            }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize().background(Color(0xFFF5F7FA))) {
                when (tab) {
                    HomeTab.TODAY -> TodayDashboard(vm, profile, onOpenDay = ::openDay)
                    HomeTab.WEEK -> WeekCalendarScreen(
                        vm = vm,
                        profile = profile,
                        anchor = weekAnchor,
                        onAnchor = { weekAnchor = it },
                        onOpenDay = ::openDay
                    )
                    HomeTab.MONTH -> LazyColumn(
                        Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        item {
                            MonthCalendar(
                                month = month,
                                selected = selected,
                                allEvents = vm.cache.events,
                                edits = vm.edits,
                                semesterStart = profile.semesterStart,
                                onDate = ::openDay,
                                prev = { month = month.minusMonths(1) },
                                next = { month = month.plusMonths(1) }
                            )
                        }
                        item {
                            Surface(shape = RoundedCornerShape(16.dp), color = if (week % 2 == 0L) Color(0xFFE9F1FA) else Color(0xFFF2ECF8)) {
                                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CalendarMonth, null, tint = MgsuBlue)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Ru)).replaceFirstChar { it.uppercase() },
                                            fontWeight = FontWeight.Bold
                                        )
                                        Text(if (week > 0) "Учебная неделя №$week · ${if (week % 2 == 0L) "чётная" else "нечётная"}" else "Дата вне рассчитанного учебного периода")
                                    }
                                    TextButton(onClick = { timelineOpen = true }) {
                                        Icon(Icons.Default.ViewDay, null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("День")
                                    }
                                    IconButton(onClick = { addOpen = true }) { Icon(Icons.Default.AddCircle, "Добавить", tint = MgsuBlue) }
                                }
                            }
                        }
                        if (vm.cache.lastError.isNotBlank()) item { OfflineBanner(vm.cache, vm::sync) }
                        if (dayItems.isEmpty()) item { EmptyDay(vm.syncing, onAdd = { addOpen = true }) }
                        items(dayItems, key = { it.key }) { item ->
                            val e = item.event
                            val noteKey = if (item.customId == null) item.key else "custom:${item.customId}"
                            val initialNote = if (item.customId == null) vm.edits.notes[noteKey].orEmpty() else item.customNote
                            EventCard(
                                e = e,
                                initialNote = initialNote,
                                hidden = item.hidden,
                                official = item.customId == null,
                                locallyModified = item.locallyModified,
                                colors = vm.colorSettings,
                                onNote = { newNote ->
                                    if (item.customId == null) vm.note(noteKey, newNote)
                                    else vm.edits.customEvents.firstOrNull { it.id == item.customId }?.let { vm.updateCustomEvent(it.copy(note = newNote)) }
                                },
                                onEdit = { editItem = item },
                                onDelete = {
                                    if (item.customId == null) vm.hideOccurrence(e.id, selected) else vm.deleteCustomEvent(item.customId)
                                },
                                onRestore = {
                                    if (item.customId == null) vm.restoreOccurrence(e.id, selected) else vm.restoreCustomEvent(item.customId)
                                },
                                onResetOfficial = { if (item.customId == null) vm.resetOccurrenceToOfficial(e.id, selected) },
                                onTask = {
                                    taskEditor = null
                                    taskRelatedKey = item.key
                                    taskRelatedTitle = e.title
                                    taskEditorOpen = true
                                },
                                onCalendar = { addScheduleEventToSystemCalendar(ctx, e, selected) },
                                onCampus = { campusRoom = e.room },
                                onOpen = { if (e.sourceUrl.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.sourceUrl))) }
                            )
                        }
                        val hasAnyDeleted = vm.edits.hiddenOccurrenceIds.isNotEmpty() || vm.edits.hiddenEventIds.isNotEmpty() || vm.edits.deletedCustomEventIds.isNotEmpty()
                        if (hasAnyDeleted) item {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                TextButton({ showHidden = !showHidden }) {
                                    Icon(if (showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility, null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (showHidden) "Скрыть удалённые" else "Показать удалённые${if (hiddenForDay > 0) " ($hiddenForDay)" else ""}")
                                }
                                TextButton({ vm.restoreAll() }) { Text("Восстановить все") }
                            }
                        }
                        item { LastSyncText(vm.cache) }
                    }
                    HomeTab.TASKS -> TasksScreen(
                        tasks = vm.tasks,
                        profileId = vm.currentProfileId,
                        onAdd = {
                            taskEditor = null
                            taskRelatedKey = ""
                            taskRelatedTitle = ""
                            taskEditorOpen = true
                        },
                        onEdit = { task -> taskEditor = task; taskRelatedKey = task.relatedOccurrenceKey; taskRelatedTitle = task.relatedEventTitle; taskEditorOpen = true },
                        onToggle = vm::toggleTask,
                        onDelete = vm::deleteTask
                    )
                    HomeTab.SETTINGS -> MoreScreen(
                        changes = vm.changes,
                        profiles = vm.profiles,
                        favorites = vm.favorites,
                        currentProfileId = vm.currentProfileId,
                        colors = vm.colorSettings,
                        lessonNotifications = vm.notificationSettings,
                        diagnostics = vm.diagnostics,
                        syncing = vm.syncing,
                        backupStatus = vm.backupStatus,
                        onSwitchProfile = vm::switchProfile,
                        onDeleteProfile = vm::deleteProfile,
                        onAddProfile = vm::startAddProfile,
                        onClearHistory = vm::clearHistory,
                        onToggleTeacherFavorite = vm::toggleFavoriteTeacher,
                        onToggleGroupFavorite = vm::toggleFavoriteGroup,
                        onColors = vm::saveColorSettings,
                        onLessonNotifications = vm::saveNotificationSettings,
                        onOpenCampus = { campusRoom = "" },
                        onExportBackup = vm::exportBackupToUri,
                        onImportBackup = vm::importBackupFromUri,
                        onSyncNow = vm::sync,
                        onShowTodayNotification = vm::showTodayNotification
                    )
                }
            }
        }
    }

    if (searchOpen) {
        GlobalSearchDialog(
            cache = vm.cache,
            edits = vm.edits,
            profile = profile,
            onOpenDay = ::openDay,
            onCampus = { campusRoom = it },
            onDismiss = { searchOpen = false }
        )
    }

    campusRoom?.let { room ->
        CampusDirectoryDialog(room = room, onDismiss = { campusRoom = null })
    }

    if (addOpen) {
        EventEditorDialog(
            title = "Новое событие",
            date = selected,
            initial = ScheduleEvent(id = "", title = "", exactDate = selected.toString()),
            initialNote = "",
            custom = true,
            onDismiss = { addOpen = false },
            onSave = { date, title, type, startTime, endTime, room, teacher, group, note ->
                vm.addCustomEvent(
                    CustomCalendarEvent(
                        id = UUID.randomUUID().toString(), date = date.toString(), title = title, type = type,
                        startTime = startTime, endTime = endTime, room = room, note = note
                    )
                )
                addOpen = false
            }
        )
    }

    editItem?.let { item ->
        val custom = item.customId != null
        val noteKey = if (!custom) item.key else "custom:${item.customId}"
        val initialNote = if (!custom) vm.edits.notes[noteKey].orEmpty() else item.customNote
        EventEditorDialog(
            title = if (custom) "Редактировать событие" else "Изменить занятие локально",
            date = selected,
            initial = item.event,
            initialNote = initialNote,
            custom = custom,
            onDismiss = { editItem = null },
            onSave = { newDate, title, type, startTime, endTime, room, teacher, group, note ->
                if (custom) {
                    vm.edits.customEvents.firstOrNull { it.id == item.customId }?.let { old ->
                        vm.updateCustomEvent(old.copy(date = newDate.toString(), title = title, type = type, startTime = startTime, endTime = endTime, room = room, note = note))
                    }
                } else {
                    vm.editOccurrence(item.event.id, selected, EventOverride(title, type, teacher, group, room, startTime, endTime))
                    vm.note(item.key, note)
                }
                editItem = null
            }
        )
    }

    if (taskEditorOpen) {
        TaskEditorDialog(
            initial = taskEditor,
            profileId = vm.currentProfileId,
            relatedOccurrenceKey = taskRelatedKey,
            relatedEventTitle = taskRelatedTitle,
            onDismiss = { taskEditorOpen = false; taskEditor = null; taskRelatedKey = ""; taskRelatedTitle = "" },
            onSave = { task ->
                if (taskEditor == null) vm.addTask(task) else vm.updateTask(task)
                taskEditorOpen = false
                taskEditor = null
                taskRelatedKey = ""
                taskRelatedTitle = ""
            }
        )
    }

}

@Composable
private fun TodayDashboard(vm: MainVm, profile: UserProfile, onOpenDay: (LocalDate) -> Unit) {
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    val todayItems = remember(vm.cache, vm.edits, today) { buildDayItems(vm.cache, vm.edits, today, profile.semesterStart, false) }
    val tomorrowItems = remember(vm.cache, vm.edits, tomorrow) { buildDayItems(vm.cache, vm.edits, tomorrow, profile.semesterStart, false) }
    val now = java.time.LocalTime.now()
    val nowMinutes = now.hour * 60 + now.minute
    val next = todayItems.firstOrNull { item ->
        val start = timeToMinutes(item.event.startTime)
        val end = timeToMinutes(item.event.endTime.ifBlank { item.event.startTime })
        start != null && end != null && end >= nowMinutes
    }
    val lastEnd = todayItems.mapNotNull { timeToMinutes(it.event.endTime.ifBlank { it.event.startTime }) }.maxOrNull()
    val windows = remember(todayItems) { calculateWindows(todayItems) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Surface(shape = RoundedCornerShape(24.dp), color = MgsuBlue, shadowElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(18.dp)) {
                    Text(today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Ru)).replaceFirstChar { it.uppercase() }, color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (todayItems.isEmpty()) "Сегодня занятий нет" else "Сегодня ${todayItems.size} ${eventWord(todayItems.size)}",
                        color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp
                    )
                    if (lastEnd != null) Text("Учебный день до ${minutesToTime(lastEnd)}", color = Color.White.copy(alpha = 0.85f))
                }
            }
        }
        if (vm.cache.lastError.isNotBlank()) item { OfflineBanner(vm.cache, vm::sync) }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Сегодня", todayItems.size.toString(), Icons.Default.Today, Modifier.weight(1f))
                StatCard("Завтра", tomorrowItems.size.toString(), Icons.Default.EventAvailable, Modifier.weight(1f))
                StatCard("Окна", windows.size.toString(), Icons.Default.Schedule, Modifier.weight(1f))
            }
        }
        if (next != null) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = eventBackgroundColor(vm.colorSettings, next.event.type, next.customId != null)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text("Ближайшее занятие", color = eventAccentColor(next.event.type, next.customId != null), fontWeight = FontWeight.Bold)
                        Text(next.event.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                        Text(listOf(next.event.startTime + if (next.event.endTime.isNotBlank()) "–${next.event.endTime}" else "", next.event.room, next.event.teacher).filter { it.isNotBlank() }.joinToString(" · "))
                        val start = timeToMinutes(next.event.startTime)
                        if (start != null && start > nowMinutes) Text("До начала: ${formatDuration(start - nowMinutes)}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (windows.isNotEmpty()) {
            item {
                Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        Text("Окна между парами", fontWeight = FontWeight.Bold)
                        windows.forEach { (from, to) -> Text("${minutesToTime(from)}–${minutesToTime(to)} · ${formatDuration(to - from)}") }
                    }
                }
            }
        }
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Расписание на сегодня", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                TextButton(onClick = { onOpenDay(today) }) { Text("Открыть день") }
            }
        }
        if (todayItems.isEmpty()) item { EmptyDay(vm.syncing, onAdd = { onOpenDay(today) }) }
        items(todayItems, key = { it.key }) { item ->
            Surface(shape = RoundedCornerShape(16.dp), color = Color.White, modifier = Modifier.fillMaxWidth().clickable { onOpenDay(today) }) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.width(5.dp).height(54.dp).background(eventAccentColor(item.event.type, item.customId != null), RoundedCornerShape(3.dp)))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.event.title, fontWeight = FontWeight.Bold)
                        val sourceMark = if (item.customId != null) "Личное" else if (item.locallyModified) "Локально изменено" else "МГСУ"
                        Text(listOf(sourceMark, item.event.startTime, item.event.room, item.event.type).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        item { LastSyncText(vm.cache) }
    }
}

@Composable
private fun StatCard(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
    Surface(modifier = modifier, shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MgsuBlue)
            Text(value, fontWeight = FontWeight.Black, fontSize = 24.sp)
            Text(label, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun OfflineBanner(cache: CachedSchedule, onRetry: () -> Unit) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFFFE8E8)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.CloudOff, null, tint = Color(0xFF9B1C1C))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Офлайн-режим", fontWeight = FontWeight.Bold)
                Text(cache.lastError, style = MaterialTheme.typography.bodySmall)
                if (cache.lastSyncMillis > 0L) Text("Показывается последнее успешно загруженное расписание.", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onRetry) { Text("Повторить") }
        }
    }
}

@Composable
private fun LastSyncText(cache: CachedSchedule) {
    Text(
        "Последнее успешное обновление: ${if (cache.lastSyncMillis > 0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(cache.lastSyncMillis)) else "ещё не было"} · источников: ${cache.sourcesChecked}",
        style = MaterialTheme.typography.bodySmall,
        color = Color.Gray
    )
}

@Composable
private fun WeekCalendarScreen(
    vm: MainVm,
    profile: UserProfile,
    anchor: LocalDate,
    onAnchor: (LocalDate) -> Unit,
    onOpenDay: (LocalDate) -> Unit
) {
    val monday = anchor.minusDays((anchor.dayOfWeek.value - 1).toLong())
    val dates = (0..6).map { monday.plusDays(it.toLong()) }
    val weekNumber = academicWeek(monday, profile.semesterStart)
    val horizontal = rememberScrollState()
    val pxPerMinute = 0.72f
    val startMinute = 7 * 60
    val endMinute = 23 * 60
    val gridHeight = ((endMinute - startMinute) * pxPerMinute).dp

    Column(Modifier.fillMaxSize().padding(10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { onAnchor(anchor.minusWeeks(1)) }) { Icon(Icons.Default.ChevronLeft, "Предыдущая неделя") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Неделя ${monday.format(DateTimeFormatter.ofPattern("d MMM", Ru))} — ${monday.plusDays(6).format(DateTimeFormatter.ofPattern("d MMM", Ru))}", fontWeight = FontWeight.Bold)
                Text(if (weekNumber > 0) "Учебная №$weekNumber · ${if (weekNumber % 2 == 0L) "чётная" else "нечётная"}" else "Вне семестра", style = MaterialTheme.typography.labelSmall)
            }
            IconButton(onClick = { onAnchor(anchor.plusWeeks(1)) }) { Icon(Icons.Default.ChevronRight, "Следующая неделя") }
            TextButton(onClick = { onAnchor(LocalDate.now()) }) { Text("Сегодня") }
        }
        if (vm.cache.lastError.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            OfflineBanner(vm.cache, vm::sync)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().weight(1f).verticalScroll(rememberScrollState())) {
            Column(Modifier.width(48.dp)) {
                Spacer(Modifier.height(52.dp))
                Box(Modifier.height(gridHeight)) {
                    for (h in 7..23) {
                        Text(
                            String.format(Ru, "%02d:00", h),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Gray,
                            modifier = Modifier.offset(y = (((h * 60 - startMinute) * pxPerMinute).dp - 7.dp))
                        )
                    }
                }
            }
            Row(Modifier.horizontalScroll(horizontal)) {
                dates.forEach { date ->
                    val items = remember(vm.cache, vm.edits, date) { buildDayItems(vm.cache, vm.edits, date, profile.semesterStart, false) }
                    WeekDayColumn(date, items, gridHeight, pxPerMinute, startMinute, vm.colorSettings, onOpenDay)
                }
            }
        }
    }
}

@Composable
private fun WeekDayColumn(
    date: LocalDate,
    items: List<DayEventItem>,
    gridHeight: androidx.compose.ui.unit.Dp,
    pxPerMinute: Float,
    startMinute: Int,
    colors: EventColorSettings,
    onOpenDay: (LocalDate) -> Unit
) {
    Column(Modifier.width(142.dp).padding(horizontal = 2.dp)) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = if (date == LocalDate.now()) MgsuBlue else Color.White,
            modifier = Modifier.fillMaxWidth().height(50.dp).clickable { onOpenDay(date) }
        ) {
            Column(Modifier.padding(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.format(DateTimeFormatter.ofPattern("EEE", Ru)), color = if (date == LocalDate.now()) Color.White else Color.Gray)
                Text(date.dayOfMonth.toString(), fontWeight = FontWeight.Black, color = if (date == LocalDate.now()) Color.White else Color.Black)
            }
        }
        Spacer(Modifier.height(2.dp))
        Box(Modifier.fillMaxWidth().height(gridHeight).background(Color.White)) {
            for (h in 7..23) {
                HorizontalDivider(
                    modifier = Modifier.offset(y = (((h * 60 - startMinute) * pxPerMinute).dp)),
                    color = Color(0xFFE8ECF2)
                )
            }
            items.filter { it.event.startTime.isNotBlank() }.forEach { item ->
                val start = timeToMinutes(item.event.startTime) ?: return@forEach
                val end = timeToMinutes(item.event.endTime) ?: (start + 90)
                val top = ((start - startMinute).coerceAtLeast(0) * pxPerMinute).dp
                val height = ((end - start).coerceAtLeast(35) * pxPerMinute).dp
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = eventBackgroundColor(colors, item.event.type, item.customId != null),
                    modifier = Modifier
                        .padding(horizontal = 2.dp)
                        .fillMaxWidth()
                        .offset(y = top)
                        .height(height.coerceAtLeast(34.dp))
                        .clickable { onOpenDay(date) }
                ) {
                    Column(Modifier.padding(5.dp)) {
                        Text(item.event.startTime, fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1)
                        Text(if (item.customId != null) "Личное" else if (item.locallyModified) "Локально" else "МГСУ", fontSize = 8.sp, color = Color.Gray, maxLines = 1)
                        Text(item.event.title, fontWeight = FontWeight.SemiBold, fontSize = 10.sp, maxLines = 3)
                        if (item.event.room.isNotBlank()) Text(item.event.room, fontSize = 9.sp, maxLines = 1)
                    }
                }
            }
        }
    }
}

private fun calculateWindows(items: List<DayEventItem>): List<Pair<Int, Int>> {
    val timed = items.mapNotNull { item ->
        val start = timeToMinutes(item.event.startTime) ?: return@mapNotNull null
        val end = timeToMinutes(item.event.endTime) ?: return@mapNotNull null
        start to end
    }.sortedBy { it.first }
    return timed.zipWithNext().mapNotNull { (a, b) ->
        if (b.first - a.second >= 30) a.second to b.first else null
    }
}

private fun eventWord(n: Int): String = when {
    n % 10 == 1 && n % 100 != 11 -> "занятие"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "занятия"
    else -> "занятий"
}

private fun formatDuration(minutes: Int): String = when {
    minutes < 60 -> "$minutes мин"
    minutes % 60 == 0 -> "${minutes / 60} ч"
    else -> "${minutes / 60} ч ${minutes % 60} мин"
}

private fun buildDayItems(cache: CachedSchedule, edits: LocalEdits, date: LocalDate, semesterStart: String, showHidden: Boolean): List<DayEventItem> {
    val official = cache.events.filter { isOnDate(it, date, semesterStart) }.map { base ->
        val key = occurrenceKey(base.id, date)
        val hidden = base.id in edits.hiddenEventIds || key in edits.hiddenOccurrenceIds
        val shown = applyOverride(base, edits.eventOverrides[key])
        DayEventItem(
            key = key,
            event = shown,
            hidden = hidden,
            customNote = edits.notes[key].orEmpty(),
            locallyModified = key in edits.eventOverrides
        )
    }.filter { showHidden || !it.hidden }

    val custom = edits.customEvents.filter { it.date == date.toString() }.map { c ->
        val hidden = c.id in edits.deletedCustomEventIds
        DayEventItem(
            key = "custom:${c.id}", hidden = hidden, customId = c.id, customNote = c.note,
            event = ScheduleEvent(
                id = c.id, title = c.title, type = c.type, room = c.room, exactDate = c.date,
                startTime = c.startTime, endTime = c.endTime, sourceLabel = "Личное событие"
            )
        )
    }.filter { showHidden || !it.hidden }

    return (official + custom).sortedWith(compareBy<DayEventItem> { it.event.startTime.ifBlank { "99:99" } }.thenBy { it.event.title })
}

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


private const val TimelineStartHour = 7
private const val TimelineEndHour = 23

@Composable
private fun DayTimelineScreen(
    date: LocalDate,
    week: Long,
    items: List<DayEventItem>,
    colors: EventColorSettings,
    onBack: () -> Unit,
    onPreviousDay: () -> Unit,
    onNextDay: () -> Unit,
    onToday: () -> Unit,
    onAdd: () -> Unit,
    onEdit: (DayEventItem) -> Unit,
    onDelete: (DayEventItem) -> Unit,
    onMove: (DayEventItem, String, String) -> Unit,
    onTask: (DayEventItem) -> Unit,
    onCalendar: (DayEventItem) -> Unit,
    onCampus: (DayEventItem) -> Unit
) {
    val scroll = rememberScrollState()
    val timedItems = items.filter { timeToMinutes(it.event.startTime) != null }
    val untimedItems = items.filter { timeToMinutes(it.event.startTime) == null }

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Назад") } },
                title = {
                    Column {
                        Text(date.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Ru)).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold)
                        Text(
                            if (week > 0) "Неделя №$week · ${if (week % 2 == 0L) "чётная" else "нечётная"}" else "Личный календарь",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                actions = {
                    TextButton(onClick = onToday) { Text("Сегодня", color = Color.White) }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MgsuBlue,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onAdd, containerColor = MgsuBlue, contentColor = Color.White) {
                Icon(Icons.Default.Add, "Добавить событие")
            }
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().background(Color(0xFFF5F7FA)).verticalScroll(scroll)
        ) {
            Surface(color = Color.White, shadowElevation = 1.dp) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = onPreviousDay) { Icon(Icons.Default.ChevronLeft, "Предыдущий день") }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${items.size} ${calendarEventWord(items.size)}", fontWeight = FontWeight.SemiBold)
                        Text("Удерживайте карточку и тяните вверх или вниз", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    IconButton(onClick = onNextDay) { Icon(Icons.Default.ChevronRight, "Следующий день") }
                }
            }

            if (untimedItems.isNotEmpty()) {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Без времени", fontWeight = FontWeight.Bold, color = Color.DarkGray)
                    untimedItems.forEach { item ->
                        UntimedCalendarCard(
                            item = item,
                            colors = colors,
                            onEdit = { onEdit(item) },
                            onDelete = { onDelete(item) },
                            onTask = { onTask(item) },
                            onCalendar = { onCalendar(item) },
                            onCampus = { onCampus(item) }
                        )
                    }
                }
            }

            if (items.isEmpty()) {
                Surface(
                    modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    color = Color.White
                ) {
                    Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.EventAvailable, null, Modifier.size(42.dp), tint = MgsuBlue)
                        Spacer(Modifier.height(8.dp))
                        Text("На этот день пока ничего нет", fontWeight = FontWeight.SemiBold)
                        Text("Добавьте событие, заметку или напоминание.", color = Color.Gray, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Добавить") }
                    }
                }
            } else {
                TimelineGrid(
                    items = timedItems,
                    colors = colors,
                    onEdit = onEdit,
                    onDelete = onDelete,
                    onMove = onMove,
                    onTask = onTask,
                    onCalendar = onCalendar,
                    onCampus = onCampus
                )
            }
            Spacer(Modifier.height(96.dp))
        }
    }
}

@Composable
private fun TimelineGrid(
    items: List<DayEventItem>,
    colors: EventColorSettings,
    onEdit: (DayEventItem) -> Unit,
    onDelete: (DayEventItem) -> Unit,
    onMove: (DayEventItem, String, String) -> Unit,
    onTask: (DayEventItem) -> Unit,
    onCalendar: (DayEventItem) -> Unit,
    onCampus: (DayEventItem) -> Unit
) {
    val hourHeight = 72.dp
    val hourCount = TimelineEndHour - TimelineStartHour
    val totalHeight = (hourHeight.value * hourCount).dp

    Row(Modifier.fillMaxWidth().padding(top = 8.dp, end = 6.dp)) {
        Column(Modifier.width(58.dp).height(totalHeight)) {
            for (hour in TimelineStartHour until TimelineEndHour) {
                Box(Modifier.height(hourHeight).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Text(formatHour(hour), fontSize = 11.sp, color = Color.Gray, modifier = Modifier.padding(top = 2.dp))
                }
            }
        }
        Box(Modifier.weight(1f).height(totalHeight)) {
            Column(Modifier.fillMaxSize()) {
                repeat(hourCount) {
                    Box(Modifier.height(hourHeight).fillMaxWidth()) {
                        HorizontalDivider(color = Color(0xFFE1E5EA), thickness = 1.dp)
                    }
                }
            }
            items.forEach { item ->
                TimelineEventBlock(
                    item = item,
                    hourHeight = hourHeight,
                    colors = colors,
                    onEdit = { onEdit(item) },
                    onDelete = { onDelete(item) },
                    onMove = { start, end -> onMove(item, start, end) },
                    onTask = { onTask(item) },
                    onCalendar = { onCalendar(item) },
                    onCampus = { onCampus(item) }
                )
            }
        }
    }
}

@Composable
private fun TimelineEventBlock(
    item: DayEventItem,
    hourHeight: androidx.compose.ui.unit.Dp,
    colors: EventColorSettings,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMove: (String, String) -> Unit,
    onTask: () -> Unit,
    onCalendar: () -> Unit,
    onCampus: () -> Unit
) {
    val event = item.event
    val density = LocalDensity.current
    val originalStart = timeToMinutes(event.startTime) ?: return
    val parsedEnd = timeToMinutes(event.endTime) ?: (originalStart + 90)
    val windowMinutes = (TimelineEndHour - TimelineStartHour) * 60
    val duration = (parsedEnd - originalStart).coerceIn(15, windowMinutes)
    val minStart = TimelineStartHour * 60
    val maxStart = TimelineEndHour * 60 - duration
    val pxPerMinute = with(density) { hourHeight.toPx() } / 60f
    var dragY by remember(item.key, event.startTime, event.endTime) { mutableFloatStateOf(0f) }

    fun startForDrag(deltaPx: Float): Int {
        val steps = (deltaPx / pxPerMinute / 15f).roundToInt()
        return (originalStart + steps * 15).coerceIn(minStart, maxStart.coerceAtLeast(minStart))
    }

    val shownStart = startForDrag(dragY)
    val shownEnd = shownStart + duration
    val y = ((shownStart - minStart) / 60f * hourHeight.value).dp
    val naturalHeight = (duration / 60f * hourHeight.value).dp
    val blockHeight = if (naturalHeight < 52.dp) 52.dp else naturalHeight
    val color = eventBackgroundColor(colors, event.type, item.customId != null)

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = y)
            .padding(end = 6.dp, bottom = 3.dp)
            .height(blockHeight)
            .pointerInput(item.key, event.startTime, event.endTime) {
                detectDragGesturesAfterLongPress(
                    onDragEnd = {
                        val newStart = startForDrag(dragY)
                        if (newStart != originalStart) onMove(minutesToTime(newStart), minutesToTime(newStart + duration))
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragY += dragAmount.y
                    }
                )
            },
        shape = RoundedCornerShape(12.dp),
        color = color,
        shadowElevation = if (dragY == 0f) 1.dp else 6.dp
    ) {
        Row(Modifier.fillMaxSize().padding(start = 10.dp, top = 7.dp, bottom = 7.dp, end = 4.dp), verticalAlignment = Alignment.Top) {
            Box(Modifier.width(4.dp).fillMaxHeight().background(eventAccentColor(event.type, item.customId != null), RoundedCornerShape(4.dp)))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("${minutesToTime(shownStart)}–${minutesToTime(shownEnd)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MgsuBlue)
                Text(event.title, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2)
                val sourceMark = if (item.customId != null) "Личное" else if (item.locallyModified) "Локально изменено" else "МГСУ"
                val detail = listOf(sourceMark, event.type, event.room).filter { it.isNotBlank() }.joinToString(" · ")
                if (detail.isNotBlank()) Text(detail, fontSize = 11.sp, color = Color.DarkGray, maxLines = 1)
                if (item.customNote.isNotBlank()) Text(item.customNote, fontSize = 10.sp, color = Color.Gray, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.DragHandle, "Перетащить", tint = Color.Gray, modifier = Modifier.size(20.dp))
                Row {
                    if (event.room.isNotBlank()) IconButton(onClick = onCampus, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.LocationOn, "Корпус и маршрут", modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = onTask, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.TaskAlt, "Добавить задание", modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = onCalendar, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.EventAvailable, "В календарь", modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.Edit, "Изменить", modifier = Modifier.size(17.dp)) }
                    IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) { Icon(Icons.Default.DeleteOutline, "Удалить", modifier = Modifier.size(17.dp)) }
                }
            }
        }
    }
}

@Composable
private fun UntimedCalendarCard(
    item: DayEventItem,
    colors: EventColorSettings,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onTask: () -> Unit,
    onCalendar: () -> Unit,
    onCampus: () -> Unit
) {
    Surface(shape = RoundedCornerShape(14.dp), color = eventBackgroundColor(colors, item.event.type, item.customId != null), shadowElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.StickyNote2, null, tint = eventAccentColor(item.event.type, item.customId != null))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(item.event.title, fontWeight = FontWeight.Bold)
                val sourceMark = if (item.customId != null) "Личное" else if (item.locallyModified) "Локально изменено" else "МГСУ"
                val text = listOf(sourceMark, item.event.type, item.event.room, item.customNote).filter { it.isNotBlank() }.joinToString(" · ")
                if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.bodySmall, color = Color.DarkGray)
            }
            if (item.event.room.isNotBlank()) IconButton(onClick = onCampus) { Icon(Icons.Default.LocationOn, "Корпус и маршрут") }
            IconButton(onClick = onTask) { Icon(Icons.Default.TaskAlt, "Добавить задание") }
            IconButton(onClick = onCalendar) { Icon(Icons.Default.EventAvailable, "В календарь") }
            IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Изменить") }
            IconButton(onClick = onDelete) { Icon(Icons.Default.DeleteOutline, "Удалить") }
        }
    }
}

private fun timeToMinutes(value: String): Int? {
    val parts = value.trim().split(":")
    if (parts.size != 2) return null
    val h = parts[0].toIntOrNull() ?: return null
    val m = parts[1].toIntOrNull() ?: return null
    if (h !in 0..23 || m !in 0..59) return null
    return h * 60 + m
}

private fun minutesToTime(value: Int): String {
    val safe = value.coerceIn(0, 23 * 60 + 59)
    return String.format(Ru, "%02d:%02d", safe / 60, safe % 60)
}

private fun formatHour(hour: Int): String = String.format(Ru, "%02d:00", hour)

private fun calendarEventWord(count: Int): String = when {
    count % 10 == 1 && count % 100 != 11 -> "событие"
    count % 10 in 2..4 && count % 100 !in 12..14 -> "события"
    else -> "событий"
}

private fun calendarEventColor(type: String, custom: Boolean): Color {
    val t = type.uppercase(Ru)
    return when {
        custom || "ЛИЧ" in t || "ЗАМЕТ" in t -> Color(0xFFFFF3D9)
        "ЭКЗ" in t || "ЗАЧ" in t -> Color(0xFFFFE7E7)
        "ПРАК" in t || "СЕМИН" in t || "ЛАБ" in t -> Color(0xFFE8F5EC)
        else -> Color(0xFFE8F1FB)
    }
}

private fun calendarEventAccent(type: String, custom: Boolean): Color {
    val t = type.uppercase(Ru)
    return when {
        custom || "ЛИЧ" in t || "ЗАМЕТ" in t -> Color(0xFFE29A16)
        "ЭКЗ" in t || "ЗАЧ" in t -> Color(0xFFB3261E)
        "ПРАК" in t || "СЕМИН" in t || "ЛАБ" in t -> Color(0xFF2F7D4A)
        else -> MgsuBlue
    }
}

@Composable
private fun MonthCalendar(
    month: YearMonth,
    selected: LocalDate,
    allEvents: List<ScheduleEvent>,
    edits: LocalEdits,
    semesterStart: String,
    onDate: (LocalDate) -> Unit,
    prev: () -> Unit,
    next: () -> Unit
) {
    Surface(shape = RoundedCornerShape(20.dp), color = Color.White, shadowElevation = 1.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(prev) { Icon(Icons.Default.ChevronLeft, null) }
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Ru)).replaceFirstChar { it.uppercase() }, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                IconButton(next) { Icon(Icons.Default.ChevronRight, null) }
            }
            Row(Modifier.fillMaxWidth()) { listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach { Text(it, Modifier.weight(1f), textAlign = TextAlign.Center, color = Color.Gray, fontSize = 12.sp) } }
            val first = month.atDay(1)
            val shift = first.dayOfWeek.value - 1
            val cells = ((shift + month.lengthOfMonth() + 6) / 7) * 7
            for (row in 0 until cells / 7) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val dayNum = row * 7 + col - shift + 1
                        if (dayNum !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f)) else {
                            val d = month.atDay(dayNum)
                            val officialHas = allEvents.any { e ->
                                isOnDate(e, d, semesterStart) && e.id !in edits.hiddenEventIds && occurrenceKey(e.id, d) !in edits.hiddenOccurrenceIds
                            }
                            val customHas = edits.customEvents.any { it.date == d.toString() && it.id !in edits.deletedCustomEventIds }
                            val has = officialHas || customHas
                            val sel = d == selected
                            val today = d == LocalDate.now()
                            Box(
                                Modifier.weight(1f).aspectRatio(1f).padding(2.dp)
                                    .background(if (sel) MgsuBlue else if (today) Color(0xFFE6EEF7) else Color.Transparent, RoundedCornerShape(12.dp))
                                    .clickable { onDate(d) }, contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("$dayNum", color = if (sel) Color.White else Color.DarkGray, fontWeight = if (sel || today) FontWeight.Bold else FontWeight.Normal)
                                    if (has) Box(Modifier.size(4.dp).background(if (sel) Color.White else MgsuBlue, RoundedCornerShape(4.dp)))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(
    e: ScheduleEvent,
    initialNote: String,
    hidden: Boolean,
    official: Boolean,
    locallyModified: Boolean,
    colors: EventColorSettings,
    onNote: (String) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onRestore: () -> Unit,
    onResetOfficial: () -> Unit,
    onTask: () -> Unit,
    onCalendar: () -> Unit,
    onCampus: () -> Unit,
    onOpen: () -> Unit
) {
    var noteOpen by remember(e.id, initialNote) { mutableStateOf(initialNote.isNotBlank()) }
    var note by remember(e.id, initialNote) { mutableStateOf(initialNote) }
    val swipeState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> if (!hidden) noteOpen = true
                SwipeToDismissBoxValue.EndToStart -> if (!hidden) onDelete()
                SwipeToDismissBoxValue.Settled -> Unit
            }
            false
        }
    )
    SwipeToDismissBox(
        state = swipeState,
        modifier = Modifier.fillMaxWidth(),
        enableDismissFromStartToEnd = !hidden,
        enableDismissFromEndToStart = !hidden,
        backgroundContent = {
            val direction = swipeState.dismissDirection
            val bg = when (direction) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFFE8F1FB)
                SwipeToDismissBoxValue.EndToStart -> Color(0xFFFFE7E7)
                else -> Color.Transparent
            }
            Box(Modifier.fillMaxSize().background(bg, RoundedCornerShape(18.dp)).padding(horizontal = 18.dp)) {
                if (direction == SwipeToDismissBoxValue.StartToEnd) {
                    Row(Modifier.align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.EditNote, null, tint = MgsuBlue); Spacer(Modifier.width(6.dp)); Text("Заметка", fontWeight = FontWeight.Bold)
                    }
                } else if (direction == SwipeToDismissBoxValue.EndToStart) {
                    Row(Modifier.align(Alignment.CenterEnd), verticalAlignment = Alignment.CenterVertically) {
                        Text("Скрыть", fontWeight = FontWeight.Bold, color = Color(0xFFB3261E)); Spacer(Modifier.width(6.dp)); Icon(Icons.Default.DeleteOutline, null, tint = Color(0xFFB3261E))
                    }
                }
            }
        }
    ) {
        Surface(
            modifier = Modifier.pointerInput(e.id, hidden) {
                detectTapGestures(onLongPress = { if (!hidden) onEdit() })
            },
            shape = RoundedCornerShape(18.dp),
            color = if (hidden) Color(0xFFF1F1F1) else eventBackgroundColor(colors, e.type, e.sourceLabel == "Личное событие"),
            shadowElevation = 1.dp
        ) {
            Column(Modifier.padding(15.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    AssistChip(
                        onClick = {},
                        label = { Text(if (official) "Официальное МГСУ" else "Личное событие") },
                        leadingIcon = { Icon(if (official) Icons.Default.Verified else Icons.Default.Person, null, modifier = Modifier.size(16.dp)) }
                    )
                    if (official && locallyModified) {
                        AssistChip(
                            onClick = onResetOfficial,
                            label = { Text("Локально изменено") },
                            leadingIcon = { Icon(Icons.Default.EditNote, null, modifier = Modifier.size(16.dp)) }
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Surface(shape = RoundedCornerShape(12.dp), color = eventAccentColor(e.type, e.sourceLabel == "Личное событие")) {
                        Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(e.startTime.ifBlank { "—" }, color = Color.White, fontWeight = FontWeight.Bold)
                            if (e.endTime.isNotBlank()) Text(e.endTime, color = Color.White.copy(alpha = .86f), fontSize = 11.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(e.title, fontWeight = FontWeight.Bold)
                        Text(e.type, color = eventAccentColor(e.type, e.sourceLabel == "Личное событие"), style = MaterialTheme.typography.labelMedium)
                        if (e.room.isNotBlank()) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable(onClick = onCampus)) {
                                Icon(Icons.Default.LocationOn, null, modifier = Modifier.size(16.dp), tint = MgsuBlue)
                                Spacer(Modifier.width(3.dp)); Text("Аудитория: ${e.room}")
                            }
                        }
                        if (e.teacher.isNotBlank()) Text(e.teacher, color = Color.DarkGray)
                        if (e.group.isNotBlank()) Text(e.group, style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                    }
                    IconButton(if (hidden) onRestore else onDelete) { Icon(if (hidden) Icons.Default.RestoreFromTrash else Icons.Default.DeleteOutline, if (hidden) "Восстановить" else "Удалить") }
                }
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton({ noteOpen = !noteOpen }) { Icon(Icons.Default.EditNote, null); Spacer(Modifier.width(4.dp)); Text("Заметка") }
                    TextButton(onTask) { Icon(Icons.Default.TaskAlt, null); Spacer(Modifier.width(4.dp)); Text("Задание") }
                    TextButton(onCalendar) { Icon(Icons.Default.EventAvailable, null); Spacer(Modifier.width(4.dp)); Text("Календарь") }
                    if (e.room.isNotBlank()) TextButton(onCampus) { Icon(Icons.Default.Map, null); Spacer(Modifier.width(4.dp)); Text("Корпус") }
                    TextButton(onEdit) { Icon(Icons.Default.Edit, null); Spacer(Modifier.width(4.dp)); Text("Изменить") }
                    if (official && (locallyModified || hidden)) TextButton(onResetOfficial) { Icon(Icons.Default.RestartAlt, null); Spacer(Modifier.width(4.dp)); Text("Вернуть МГСУ") }
                    if (e.sourceUrl.isNotBlank()) TextButton(onOpen) { Icon(Icons.Default.OpenInNew, null); Spacer(Modifier.width(4.dp)); Text("Источник") }
                }
                AnimatedVisibility(noteOpen) {
                    OutlinedTextField(
                        note, { note = it; onNote(it) }, label = { Text("Моя заметка") },
                        modifier = Modifier.fillMaxWidth(), minLines = 2, enabled = !hidden
                    )
                }
                if (!hidden) Text("Свайп вправо — заметка · влево — скрыть · удерживание — редактировать", color = Color.Gray, style = MaterialTheme.typography.labelSmall)
                if (hidden) Text("Удалено только в вашем календаре — можно восстановить.", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun EventEditorDialog(
    title: String,
    date: LocalDate,
    initial: ScheduleEvent,
    initialNote: String,
    custom: Boolean,
    onDismiss: () -> Unit,
    onSave: (LocalDate, String, String, String, String, String, String, String, String) -> Unit
) {
    var dateText by remember { mutableStateOf(date.toString()) }
    var eventTitle by remember { mutableStateOf(initial.title) }
    var type by remember { mutableStateOf(initial.type.ifBlank { "Личное" }) }
    var start by remember { mutableStateOf(initial.startTime) }
    var end by remember { mutableStateOf(initial.endTime) }
    var room by remember { mutableStateOf(initial.room) }
    var teacher by remember { mutableStateOf(initial.teacher) }
    var group by remember { mutableStateOf(initial.group) }
    var note by remember { mutableStateOf(initialNote) }
    val parsedDate = runCatching { LocalDate.parse(dateText) }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (custom) OutlinedTextField(dateText, { dateText = it }, label = { Text("Дата, ГГГГ-ММ-ДД") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                OutlinedTextField(eventTitle, { eventTitle = it }, label = { Text("Название") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(type, { type = it }, label = { Text("Тип") }, placeholder = { Text("Лекция, экзамен, личное…") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(start, { start = it }, label = { Text("Начало") }, placeholder = { Text("08:30") }, modifier = Modifier.weight(1f), singleLine = true)
                    OutlinedTextField(end, { end = it }, label = { Text("Конец") }, placeholder = { Text("10:00") }, modifier = Modifier.weight(1f), singleLine = true)
                }
                if (custom) Text("Оставьте время пустым, чтобы сохранить запись как заметку без времени.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                OutlinedTextField(room, { room = it }, label = { Text("Место / аудитория") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                if (!custom) {
                    OutlinedTextField(teacher, { teacher = it }, label = { Text("Преподаватель") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    OutlinedTextField(group, { group = it }, label = { Text("Группа") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    Text("Изменения относятся только к этой дате и не меняют данные на сайте МГСУ.", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                OutlinedTextField(note, { note = it }, label = { Text("Заметка") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
        },
        confirmButton = {
            Button(
                onClick = { onSave(parsedDate ?: date, eventTitle.trim(), type.trim(), start.trim(), end.trim(), room.trim(), teacher.trim(), group.trim(), note.trim()) },
                enabled = eventTitle.isNotBlank() && (!custom || parsedDate != null)
            ) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onDismiss) { Text("Отмена") } }
    )
}

@Composable
private fun EmptyDay(syncing: Boolean, onAdd: () -> Unit) {
    Surface(shape = RoundedCornerShape(18.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.EventAvailable, null, Modifier.size(42.dp), tint = MgsuBlue); Spacer(Modifier.height(8.dp))
            Text(if (syncing) "Загружаю расписание…" else "На этот день занятий не найдено", fontWeight = FontWeight.SemiBold)
            Text("Можно добавить своё событие, напоминание или заметку.", color = Color.Gray, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp)); OutlinedButton(onClick = onAdd) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Добавить событие") }
        }
    }
}

private fun occurrenceKey(eventId: String, date: LocalDate): String = "$eventId@${date}"

private fun normalizeSearch(s: String): String = s.uppercase(Ru).replace('Ё', 'Е').replace(Regex("[^А-ЯA-Z0-9]+"), " ").trim()

private fun academicWeek(date: LocalDate, startIso: String): Long {
    val start = runCatching { LocalDate.parse(startIso) }.getOrElse { return -1 }
    return ChronoUnit.WEEKS.between(start, date) + 1
}

@Composable
private fun MgsuTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = MgsuBlue, secondary = Color(0xFFAC1422), background = Color(0xFFF5F7FA)), content = content)
}
