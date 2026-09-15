package ru.mgsu.schedule.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

private val Context.dataStore by preferencesDataStore("mgsu_schedule_prefs")

class AppStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profileKey = stringPreferencesKey("profile")
    private val profilesKey = stringPreferencesKey("profiles_v2")
    private val editsKey = stringPreferencesKey("edits")
    private val favoritesKey = stringPreferencesKey("favorites")
    private val tasksKey = stringPreferencesKey("study_tasks")
    private val colorsKey = stringPreferencesKey("event_colors_v1")
    private val notificationSettingsKey = stringPreferencesKey("lesson_notification_settings_v1")
    private val recentSelectionsKey = stringPreferencesKey("recent_selections_v1")
    private val cacheFile = File(context.filesDir, "schedule_cache.json")
    private val catalogFile = File(context.filesDir, "suggestion_catalog.json")
    private val changesFile = File(context.filesDir, "schedule_changes.json")
    private val diagnosticsFile = File(context.filesDir, "sync_diagnostics.json")

    val profileCollectionFlow: Flow<ProfileCollection> = context.dataStore.data.map(::decodeProfileCollection)

    val profileFlow: Flow<UserProfile?> = context.dataStore.data.map { prefs ->
        val state = decodeProfileCollection(prefs)
        state.profiles.firstOrNull { it.id == state.currentId }?.profile
            ?: state.profiles.firstOrNull()?.profile
    }

    val editsFlow: Flow<LocalEdits> = context.dataStore.data.map { prefs ->
        prefs[editsKey]?.let { runCatching { json.decodeFromString<LocalEdits>(it) }.getOrNull() } ?: LocalEdits()
    }

    val favoritesFlow: Flow<FavoritesState> = context.dataStore.data.map { prefs ->
        prefs[favoritesKey]?.let { runCatching { json.decodeFromString<FavoritesState>(it) }.getOrNull() } ?: FavoritesState()
    }

    val tasksFlow: Flow<List<StudyTask>> = context.dataStore.data.map { prefs ->
        prefs[tasksKey]?.let { runCatching { json.decodeFromString<List<StudyTask>>(it) }.getOrNull() } ?: emptyList()
    }

    val eventColorSettingsFlow: Flow<EventColorSettings> = context.dataStore.data.map { prefs ->
        prefs[colorsKey]?.let { runCatching { json.decodeFromString<EventColorSettings>(it) }.getOrNull() } ?: EventColorSettings()
    }

    val lessonNotificationSettingsFlow: Flow<LessonNotificationSettings> = context.dataStore.data.map { prefs ->
        prefs[notificationSettingsKey]?.let { runCatching { json.decodeFromString<LessonNotificationSettings>(it) }.getOrNull() } ?: LessonNotificationSettings()
    }

    val recentSelectionsFlow: Flow<RecentSelections> = context.dataStore.data.map { prefs ->
        prefs[recentSelectionsKey]?.let { runCatching { json.decodeFromString<RecentSelections>(it) }.getOrNull() } ?: RecentSelections()
    }

    suspend fun createProfile(profile: UserProfile, name: String = profileDisplayName(profile)): ProfileCollection {
        var result = ProfileCollection()
        context.dataStore.edit { prefs ->
            val state = decodeProfileCollection(prefs)
            val entry = SavedProfile(
                id = UUID.randomUUID().toString(),
                name = name.ifBlank { profileDisplayName(profile) },
                profile = profile,
                createdAtMillis = System.currentTimeMillis()
            )
            result = state.copy(currentId = entry.id, profiles = state.profiles + entry)
            prefs[profilesKey] = json.encodeToString(result)
            prefs[profileKey] = json.encodeToString(profile)
        }
        return result
    }

    suspend fun saveProfile(profile: UserProfile): ProfileCollection {
        var result = ProfileCollection()
        context.dataStore.edit { prefs ->
            val state = decodeProfileCollection(prefs)
            val current = state.profiles.firstOrNull { it.id == state.currentId }
            result = if (current == null) {
                val entry = SavedProfile(UUID.randomUUID().toString(), profileDisplayName(profile), profile, System.currentTimeMillis())
                state.copy(currentId = entry.id, profiles = state.profiles + entry)
            } else {
                state.copy(profiles = state.profiles.map { if (it.id == current.id) it.copy(name = profileDisplayName(profile), profile = profile) else it })
            }
            prefs[profilesKey] = json.encodeToString(result)
            prefs[profileKey] = json.encodeToString(profile)
        }
        return result
    }

    suspend fun switchProfile(id: String): ProfileCollection {
        var result = ProfileCollection()
        context.dataStore.edit { prefs ->
            val state = decodeProfileCollection(prefs)
            val target = state.profiles.firstOrNull { it.id == id }
            result = if (target == null) state else state.copy(currentId = id)
            prefs[profilesKey] = json.encodeToString(result)
            target?.let { prefs[profileKey] = json.encodeToString(it.profile) }
        }
        return result
    }

    suspend fun deleteProfile(id: String): ProfileCollection {
        var result = ProfileCollection()
        context.dataStore.edit { prefs ->
            val state = decodeProfileCollection(prefs)
            val remaining = state.profiles.filterNot { it.id == id }
            val nextId = if (state.currentId == id) remaining.firstOrNull()?.id.orEmpty() else state.currentId
            result = ProfileCollection(nextId, remaining)
            prefs[profilesKey] = json.encodeToString(result)
            val next = remaining.firstOrNull { it.id == nextId }
            if (next != null) prefs[profileKey] = json.encodeToString(next.profile) else prefs.remove(profileKey)
        }
        return result
    }

    suspend fun clearProfile() {
        context.dataStore.edit { it.remove(profileKey); it[profilesKey] = json.encodeToString(ProfileCollection()) }
    }

    suspend fun saveEdits(edits: LocalEdits) {
        context.dataStore.edit { it[editsKey] = json.encodeToString(edits) }
    }

    suspend fun saveFavorites(favorites: FavoritesState) {
        context.dataStore.edit { it[favoritesKey] = json.encodeToString(favorites) }
    }

    suspend fun saveTasks(tasks: List<StudyTask>) {
        context.dataStore.edit { it[tasksKey] = json.encodeToString(tasks) }
    }

    suspend fun saveEventColorSettings(settings: EventColorSettings) {
        context.dataStore.edit { it[colorsKey] = json.encodeToString(settings) }
    }

    suspend fun saveLessonNotificationSettings(settings: LessonNotificationSettings) {
        context.dataStore.edit { it[notificationSettingsKey] = json.encodeToString(settings) }
    }

    suspend fun saveRecentSelections(value: RecentSelections) {
        context.dataStore.edit { it[recentSelectionsKey] = json.encodeToString(value) }
    }

    fun readCache(): CachedSchedule = runCatching {
        if (!cacheFile.exists()) CachedSchedule()
        else json.decodeFromString<CachedSchedule>(cacheFile.readText())
    }.getOrDefault(CachedSchedule())

    fun clearCache() {
        if (cacheFile.exists()) cacheFile.delete()
    }

    fun writeCache(cache: CachedSchedule) {
        val tmp = File(context.filesDir, "schedule_cache.tmp")
        tmp.writeText(json.encodeToString(cache))
        if (cacheFile.exists()) cacheFile.delete()
        if (!tmp.renameTo(cacheFile)) {
            cacheFile.writeText(json.encodeToString(cache))
            tmp.delete()
        }
    }

    fun readChanges(): List<ScheduleChange> = runCatching {
        if (!changesFile.exists()) emptyList()
        else json.decodeFromString<List<ScheduleChange>>(changesFile.readText())
    }.getOrDefault(emptyList())

    fun appendChanges(changes: List<ScheduleChange>) {
        if (changes.isEmpty()) return
        val merged = (changes + readChanges()).distinctBy { it.id }.sortedByDescending { it.timestampMillis }.take(500)
        val tmp = File(context.filesDir, "schedule_changes.tmp")
        tmp.writeText(json.encodeToString(merged))
        if (changesFile.exists()) changesFile.delete()
        if (!tmp.renameTo(changesFile)) {
            changesFile.writeText(json.encodeToString(merged))
            tmp.delete()
        }
    }

    fun clearChanges() {
        if (changesFile.exists()) changesFile.delete()
    }


    fun readDiagnostics(): SyncDiagnostics = runCatching {
        if (!diagnosticsFile.exists()) SyncDiagnostics()
        else json.decodeFromString<SyncDiagnostics>(diagnosticsFile.readText())
    }.getOrDefault(SyncDiagnostics())

    fun writeDiagnostics(value: SyncDiagnostics) {
        val tmp = File(context.filesDir, "sync_diagnostics.tmp")
        tmp.writeText(json.encodeToString(value))
        if (diagnosticsFile.exists()) diagnosticsFile.delete()
        if (!tmp.renameTo(diagnosticsFile)) {
            diagnosticsFile.writeText(json.encodeToString(value))
            tmp.delete()
        }
    }

    fun encodeBackup(value: AppBackup): String = json.encodeToString(value)

    fun decodeBackup(raw: String): AppBackup {
        val value = json.decodeFromString<AppBackup>(raw)
        require(value.schemaVersion in 1..1) { "Неподдерживаемая версия резервной копии: ${value.schemaVersion}" }
        return value
    }

    suspend fun applyBackup(value: AppBackup) {
        context.dataStore.edit { prefs ->
            prefs[profilesKey] = json.encodeToString(value.profiles)
            val current = value.profiles.profiles.firstOrNull { it.id == value.profiles.currentId }
                ?: value.profiles.profiles.firstOrNull()
            if (current != null) prefs[profileKey] = json.encodeToString(current.profile) else prefs.remove(profileKey)
            prefs[editsKey] = json.encodeToString(value.edits)
            prefs[favoritesKey] = json.encodeToString(value.favorites)
            prefs[tasksKey] = json.encodeToString(value.tasks)
            prefs[colorsKey] = json.encodeToString(value.colors)
            prefs[notificationSettingsKey] = json.encodeToString(value.notifications)
            prefs[recentSelectionsKey] = json.encodeToString(value.recentSelections)
        }
        writeCache(value.cache)
        writeChanges(value.changes)
    }

    fun writeChanges(changes: List<ScheduleChange>) {
        if (changes.isEmpty()) {
            clearChanges()
            return
        }
        val tmp = File(context.filesDir, "schedule_changes.tmp")
        tmp.writeText(json.encodeToString(changes.take(500)))
        if (changesFile.exists()) changesFile.delete()
        if (!tmp.renameTo(changesFile)) {
            changesFile.writeText(json.encodeToString(changes.take(500)))
            tmp.delete()
        }
    }

    fun readCatalog(): SuggestionCatalog = runCatching {
        if (!catalogFile.exists()) SuggestionCatalog()
        else json.decodeFromString<SuggestionCatalog>(catalogFile.readText())
    }.getOrDefault(SuggestionCatalog())

    fun writeCatalog(catalog: SuggestionCatalog) {
        val tmp = File(context.filesDir, "suggestion_catalog.tmp")
        tmp.writeText(json.encodeToString(catalog))
        if (catalogFile.exists()) catalogFile.delete()
        if (!tmp.renameTo(catalogFile)) {
            catalogFile.writeText(json.encodeToString(catalog))
            tmp.delete()
        }
    }

    private fun decodeProfileCollection(prefs: Preferences): ProfileCollection {
        val stored = prefs[profilesKey]?.let { runCatching { json.decodeFromString<ProfileCollection>(it) }.getOrNull() }
        if (stored != null && stored.profiles.isNotEmpty()) {
            val current = stored.currentId.takeIf { id -> stored.profiles.any { it.id == id } } ?: stored.profiles.first().id
            return stored.copy(currentId = current)
        }
        val legacy = prefs[profileKey]?.let { runCatching { json.decodeFromString<UserProfile>(it) }.getOrNull() }
        return if (legacy != null) {
            val entry = SavedProfile("legacy-profile", profileDisplayName(legacy), legacy, 0L)
            ProfileCollection(entry.id, listOf(entry))
        } else ProfileCollection()
    }

    companion object {
        fun profileDisplayName(profile: UserProfile): String = when (profile.role) {
            UserRole.STUDENT -> profile.group.ifBlank { "${profile.institute}, ${profile.course} курс" }
            UserRole.TEACHER -> profile.teacher.ifBlank { "Преподаватель" }
        }
    }
}
