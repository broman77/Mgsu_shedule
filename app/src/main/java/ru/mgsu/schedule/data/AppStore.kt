package ru.mgsu.schedule.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

private val Context.dataStore by preferencesDataStore("mgsu_schedule_prefs")

class AppStore(private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val profileKey = stringPreferencesKey("profile")
    private val editsKey = stringPreferencesKey("edits")
    private val cacheFile = File(context.filesDir, "schedule_cache.json")

    val profileFlow: Flow<UserProfile?> = context.dataStore.data.map { prefs ->
        prefs[profileKey]?.let { runCatching { json.decodeFromString<UserProfile>(it) }.getOrNull() }
    }

    val editsFlow: Flow<LocalEdits> = context.dataStore.data.map { prefs ->
        prefs[editsKey]?.let { runCatching { json.decodeFromString<LocalEdits>(it) }.getOrNull() } ?: LocalEdits()
    }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { it[profileKey] = json.encodeToString(profile) }
    }

    suspend fun clearProfile() {
        context.dataStore.edit { it.remove(profileKey) }
    }

    suspend fun saveEdits(edits: LocalEdits) {
        context.dataStore.edit { it[editsKey] = json.encodeToString(edits) }
    }

    fun readCache(): CachedSchedule = runCatching {
        if (!cacheFile.exists()) CachedSchedule()
        else json.decodeFromString<CachedSchedule>(cacheFile.readText())
    }.getOrDefault(CachedSchedule())

    fun writeCache(cache: CachedSchedule) {
        val tmp = File(context.filesDir, "schedule_cache.tmp")
        tmp.writeText(json.encodeToString(cache))
        if (cacheFile.exists()) cacheFile.delete()
        tmp.renameTo(cacheFile)
    }
}
