package ru.mgsu.schedule.data

import java.security.MessageDigest

object ChangeDetector {
    fun detect(old: List<ScheduleEvent>, fresh: List<ScheduleEvent>, now: Long = System.currentTimeMillis()): List<ScheduleChange> {
        if (old.isEmpty()) return emptyList()

        val oldByKey = old.groupBy(::identityKey)
        val newByKey = fresh.groupBy(::identityKey)
        val changes = mutableListOf<ScheduleChange>()

        for (key in (oldByKey.keys + newByKey.keys).toSet()) {
            val before = oldByKey[key].orEmpty().firstOrNull()
            val after = newByKey[key].orEmpty().firstOrNull()
            when {
                before == null && after != null -> changes += change(now, "ADDED", after.title, "Добавлено занятие", "", describe(after))
                before != null && after == null -> changes += change(now, "REMOVED", before.title, "Занятие удалено из расписания", describe(before), "")
                before != null && after != null -> {
                    val diffs = mutableListOf<String>()
                    if (before.room != after.room) diffs += "аудитория ${before.room.ifBlank { "—" }} → ${after.room.ifBlank { "—" }}"
                    if (before.startTime != after.startTime || before.endTime != after.endTime) {
                        diffs += "время ${before.startTime.ifBlank { "—" }}–${before.endTime.ifBlank { "—" }} → ${after.startTime.ifBlank { "—" }}–${after.endTime.ifBlank { "—" }}"
                    }
                    if (before.teacher != after.teacher) diffs += "преподаватель ${before.teacher.ifBlank { "—" }} → ${after.teacher.ifBlank { "—" }}"
                    if (before.title != after.title) diffs += "название изменено"
                    if (before.type != after.type) diffs += "тип ${before.type} → ${after.type}"
                    if (diffs.isNotEmpty()) changes += change(now, "CHANGED", after.title, diffs.joinToString("; "), describe(before), describe(after))
                }
            }
        }
        return changes.take(60)
    }

    private fun identityKey(e: ScheduleEvent): String = listOf(
        e.group.trim().uppercase(),
        e.weekday?.toString().orEmpty(),
        e.exactDate.orEmpty(),
        e.title.trim().uppercase(),
        e.weekNumbers.joinToString(","),
        e.weekParity
    ).joinToString("|")

    private fun describe(e: ScheduleEvent): String = listOf(
        e.exactDate ?: e.weekday?.let { "день $it" }.orEmpty(),
        listOf(e.startTime, e.endTime).filter { it.isNotBlank() }.joinToString("–"),
        e.room,
        e.teacher
    ).filter { it.isNotBlank() }.joinToString(" · ")

    private fun change(now: Long, kind: String, title: String, details: String, oldValue: String, newValue: String): ScheduleChange {
        val raw = "$now|$kind|$title|$details|$oldValue|$newValue"
        return ScheduleChange(sha1(raw), now, kind, title, details, oldValue, newValue)
    }

    private fun sha1(value: String): String = MessageDigest.getInstance("SHA-1")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
