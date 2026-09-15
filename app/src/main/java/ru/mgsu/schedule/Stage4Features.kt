package ru.mgsu.schedule

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.mgsu.schedule.data.SyncDiagnostics
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Stage4Blue = Color(0xFF0F3C73)
private val Stage4Ru = Locale("ru", "RU")

@Composable
fun Stage4SettingsBlocks(
    diagnostics: SyncDiagnostics,
    syncing: Boolean,
    backupStatus: String,
    onExportBackup: (Uri) -> Unit,
    onImportBackup: (Uri) -> Unit,
    onSyncNow: () -> Unit,
    onShowTodayNotification: () -> Unit
) {
    var backupOpen by remember { mutableStateOf(false) }
    var diagnosticsOpen by remember { mutableStateOf(false) }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) onExportBackup(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onImportBackup(uri)
    }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { backupOpen = !backupOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.Backup, null, tint = Stage4Blue)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Резервная копия", fontWeight = FontWeight.Bold)
                    Text("Профили, заметки, задания и настройки", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                }
                Icon(if (backupOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (backupOpen) {
                Spacer(Modifier.height(10.dp))
                Text(
                    "Экспорт сохраняет пользовательские данные и последний офлайн-кэш в один JSON-файл. Его можно импортировать на другом телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.DarkGray
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { exportLauncher.launch("MGSU-Schedule-backup-${LocalDate.now()}.json") },
                        colors = ButtonDefaults.buttonColors(containerColor = Stage4Blue),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.UploadFile, null); Spacer(Modifier.width(5.dp)); Text("Экспорт")
                    }
                    OutlinedButton(
                        onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Download, null); Spacer(Modifier.width(5.dp)); Text("Импорт")
                    }
                }
                if (backupStatus.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    AssistChip(
                        onClick = {},
                        label = { Text(backupStatus) },
                        leadingIcon = { Icon(if (backupStatus.startsWith("Ошибка")) Icons.Default.ErrorOutline else Icons.Default.CheckCircle, null) }
                    )
                }
            }
        }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = Color.White) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth().clickable { diagnosticsOpen = !diagnosticsOpen },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Default.MonitorHeart, null, tint = Stage4Blue)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text("Диагностика синхронизации", fontWeight = FontWeight.Bold)
                    Text(syncStateLabel(diagnostics), style = MaterialTheme.typography.bodySmall, color = if (diagnostics.lastError.isBlank()) Color(0xFF2F7D4A) else Color(0xFFB3261E))
                }
                Icon(if (diagnosticsOpen) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
            }
            if (diagnosticsOpen) {
                Spacer(Modifier.height(10.dp))
                DiagnosticRow("Последняя попытка", formatMillis(diagnostics.lastAttemptMillis))
                DiagnosticRow("Последний успех", formatMillis(diagnostics.lastSuccessMillis))
                DiagnosticRow("Сервер", diagnostics.activeHost.ifBlank { "—" })
                DiagnosticRow("PDF найдено", diagnostics.sourcesDiscovered.toString())
                DiagnosticRow("Подходит профилю", diagnostics.sourcesMatched.toString())
                DiagnosticRow("PDF прочитано", diagnostics.sourcesChecked.toString())
                DiagnosticRow("Занятий распознано", diagnostics.parsedEvents.toString())
                DiagnosticRow("Ошибок PDF", diagnostics.failedPdfs.toString())
                if (diagnostics.usedOfflineCache) {
                    AssistChip(onClick = {}, label = { Text("Показывается сохранённое расписание") }, leadingIcon = { Icon(Icons.Default.CloudOff, null) })
                }
                if (diagnostics.lastError.isNotBlank()) {
                    Text(diagnostics.lastError, color = Color(0xFFB3261E), style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                }
                Button(
                    onClick = onSyncNow,
                    enabled = !syncing,
                    colors = ButtonDefaults.buttonColors(containerColor = Stage4Blue),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (syncing) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.White)
                    else Icon(Icons.Default.Sync, null)
                    Spacer(Modifier.width(6.dp)); Text(if (syncing) "Проверяю…" else "Проверить сейчас")
                }
            }
        }
    }

    Surface(shape = RoundedCornerShape(16.dp), color = Color(0xFFE9F1FA)) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.LockClock, null, tint = Stage4Blue)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("Сводка «Сегодня» на экране блокировки", fontWeight = FontWeight.Bold)
                Text("Показывает число пар, первую и последнюю пару и план дня. Отображение на lock screen зависит от системных настроек Android.", style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = onShowTodayNotification) { Text("Показать") }
        }
    }
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.width(12.dp))
        Text(value, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodySmall)
    }
}

private fun syncStateLabel(value: SyncDiagnostics): String = when {
    value.lastAttemptMillis == 0L -> "Проверка ещё не выполнялась"
    value.lastError.isBlank() && value.lastSuccessMillis > 0L -> "Синхронизация работает"
    value.usedOfflineCache -> "Сайт недоступен — работает офлайн-кэш"
    else -> "Есть ошибка синхронизации"
}

private fun formatMillis(value: Long): String {
    if (value <= 0L) return "—"
    return runCatching {
        DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm", Stage4Ru)
            .format(Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()))
    }.getOrDefault("—")
}
