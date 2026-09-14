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
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.mgsu.schedule.data.*
import ru.mgsu.schedule.worker.SyncWorker
import ru.mgsu.schedule.worker.TomorrowWorker
import ru.mgsu.schedule.worker.isOnDate
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.concurrent.TimeUnit

private val MgsuBlue = Color(0xFF0F3C73)
private val Ru = Locale("ru", "RU")

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MgsuTheme { MgsuApp() } }
    }
}

class MainVm(private val context: android.app.Application) : AndroidViewModel(context) {
    private val store = AppStore(context)
    var profile by mutableStateOf<UserProfile?>(null); private set
    var cache by mutableStateOf(store.readCache()); private set
    var edits by mutableStateOf(LocalEdits()); private set
    var syncing by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            profile = store.profileFlow.first()
            edits = store.editsFlow.first()
            loaded = true
        }
    }

    fun saveProfile(p: UserProfile) = viewModelScope.launch {
        store.saveProfile(p); profile = p; scheduleWorkers(); sync()
    }

    fun resetProfile() = viewModelScope.launch { store.clearProfile(); profile = null }

    fun sync() = viewModelScope.launch {
        val p = profile ?: return@launch
        syncing = true
        cache = MgsuRepository(context).sync(p)
        syncing = false
    }

    fun note(id: String, note: String) = viewModelScope.launch {
        edits = edits.copy(notes = edits.notes.toMutableMap().apply { if (note.isBlank()) remove(id) else put(id, note) })
        store.saveEdits(edits)
    }

    fun hide(id: String) = viewModelScope.launch {
        edits = edits.copy(hiddenEventIds = edits.hiddenEventIds + id); store.saveEdits(edits)
    }

    fun restoreAll() = viewModelScope.launch {
        edits = edits.copy(hiddenEventIds = emptySet()); store.saveEdits(edits)
    }

    private fun scheduleWorkers() {
        val wm = WorkManager.getInstance(context)
        wm.enqueueUniquePeriodicWork("mgsu_sync", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build())
        wm.enqueueUniquePeriodicWork("mgsu_tomorrow", ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<TomorrowWorker>(12, TimeUnit.HOURS).build())
    }
}

@Composable
fun MgsuApp(vm: MainVm = viewModel()) {
    if (!vm.loaded) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MgsuBlue) }; return }
    val p = vm.profile
    if (p == null) ProfileSetup(onSave = vm::saveProfile)
    else ScheduleHome(vm, p)
}

@Composable
private fun ProfileSetup(onSave: (UserProfile) -> Unit) {
    var role by remember { mutableStateOf<UserRole?>(null) }
    var institute by remember { mutableStateOf("ИАГ") }
    var course by remember { mutableIntStateOf(1) }
    var form by remember { mutableStateOf("Очная") }
    var group by remember { mutableStateOf("") }
    var teacher by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("2026-08-31") }
    val institutes = listOf("ИАГ", "ИПГС", "ИГЭС", "ИИЭСМ", "ИЦТМС", "ИЭУКСН", "ИИС ОИАЭ", "ИДО")

    Column(Modifier.fillMaxSize().background(Color(0xFFF5F7FA)).padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Spacer(Modifier.height(20.dp))
        Surface(shape = RoundedCornerShape(24.dp), color = MgsuBlue, modifier = Modifier.fillMaxWidth()) {
            Box(Modifier.fillMaxWidth().height(220.dp)) {
                Image(
                    painter = painterResource(R.drawable.official_campus),
                    contentDescription = "Кампус НИУ МГСУ",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(Modifier.fillMaxSize().background(Color(0x880F3C73)))
                Column(
                    Modifier.fillMaxSize().padding(20.dp),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Image(
                        painter = painterResource(R.drawable.official_mgsu_logo),
                        contentDescription = "Логотип НИУ МГСУ",
                        modifier = Modifier.height(86.dp).widthIn(max = 150.dp),
                        contentScale = ContentScale.Fit
                    )
                    Column {
                        Text("МГСУ Расписание", color = Color.White, fontWeight = FontWeight.Black, fontSize = 28.sp)
                        Text("Занятия, экзамены и заметки в одном календаре", color = Color.White.copy(alpha=.92f), modifier = Modifier.padding(top=5.dp))
                    }
                }
            }
        }
        Text("Кто вы?", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            RoleCard("Студент", Icons.Default.School, role == UserRole.STUDENT) { role = UserRole.STUDENT }
            RoleCard("Преподаватель", Icons.Default.Person, role == UserRole.TEACHER) { role = UserRole.TEACHER }
        }
        AnimatedVisibility(role != null) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (role == UserRole.STUDENT) {
                    DropField("Институт", institute, institutes) { institute = it }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        DropField("Курс", "$course", (1..6).map { "$it" }, Modifier.weight(1f)) { course = it.toInt() }
                        DropField("Форма", form, listOf("Очная", "Очно-заочная", "Заочная"), Modifier.weight(2f)) { form = it }
                    }
                    OutlinedTextField(group, { group = it }, label={ Text("Группа — как в расписании") }, placeholder={ Text("Например: ИИЭСМ 2к 30") }, modifier=Modifier.fillMaxWidth(), singleLine=true)
                } else if (role == UserRole.TEACHER) {
                    OutlinedTextField(teacher, { teacher = it }, label={ Text("Фамилия и инициалы") }, placeholder={ Text("Например: Орлов В.А.") }, modifier=Modifier.fillMaxWidth(), singleLine=true)
                }
                OutlinedTextField(start, { start = it }, label={ Text("Понедельник 1-й учебной недели") }, supportingText={ Text("Нужно для чётных/нечётных и отдельных номеров недель") }, modifier=Modifier.fillMaxWidth(), singleLine=true)
                Button(onClick={ onSave(UserProfile(role!!, institute, course, form, group.trim(), teacher.trim(), start, true)) },
                    enabled = (role == UserRole.STUDENT && group.isNotBlank()) || (role == UserRole.TEACHER && teacher.length >= 3),
                    modifier=Modifier.fillMaxWidth().height(52.dp), colors=ButtonDefaults.buttonColors(containerColor=MgsuBlue)) {
                    Text("Открыть календарь")
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Text("Неофициальное приложение. Расписание, фото и символика — из официальных ресурсов НИУ МГСУ (mgsu.ru).", style=MaterialTheme.typography.bodySmall, color=Color.Gray)
    }
}

@Composable
private fun RowScope.RoleCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick:()->Unit) {
    Surface(modifier=Modifier.weight(1f).clickable(onClick=onClick), shape=RoundedCornerShape(18.dp), color=if(selected) MgsuBlue else Color.White, tonalElevation=2.dp) {
        Column(Modifier.padding(16.dp), horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(icon, null, tint=if(selected) Color.White else MgsuBlue)
            Text(title, color=if(selected) Color.White else Color.DarkGray, fontWeight=FontWeight.SemiBold, textAlign=TextAlign.Center)
        }
    }
}

@Composable
private fun DropField(label: String, value: String, options: List<String>, modifier: Modifier = Modifier, onValue:(String)->Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedTextField(value, {}, readOnly=true, label={ Text(label) }, trailingIcon={ Icon(Icons.Default.ArrowDropDown,null,Modifier.clickable{expanded=true}) }, modifier=Modifier.fillMaxWidth().clickable{expanded=true})
        DropdownMenu(expanded, {expanded=false}) { options.forEach { DropdownMenuItem({Text(it)}, {onValue(it);expanded=false}) } }
    }
}

@Composable
private fun ScheduleHome(vm: MainVm, profile: UserProfile) {
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var month by remember { mutableStateOf(YearMonth.from(selected)) }
    var showHidden by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (vm.cache.lastSyncMillis == 0L) vm.sync()
    }
    val week = academicWeek(selected, profile.semesterStart)
    val events = vm.cache.events.filter { isOnDate(it, selected, profile.semesterStart) }.filter { showHidden || it.id !in vm.edits.hiddenEventIds }

    Scaffold(topBar={
        TopAppBar(title={ Column { Text(if(profile.role==UserRole.STUDENT) profile.group else profile.teacher, fontWeight=FontWeight.Bold); Text("${profile.institute.takeIf{profile.role==UserRole.STUDENT}.orEmpty()} · ${if(week>0) "${week}-я неделя" else "вне семестра"}", style=MaterialTheme.typography.labelSmall) } },
            actions={ IconButton({vm.sync()}) { if(vm.syncing) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth=2.dp) else Icon(Icons.Default.Refresh,"Обновить") }; IconButton({vm.resetProfile()}){Icon(Icons.Default.Settings,"Профиль")} }, colors=TopAppBarDefaults.topAppBarColors(containerColor=MgsuBlue, titleContentColor=Color.White, actionIconContentColor=Color.White))
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize().background(Color(0xFFF5F7FA)), contentPadding=PaddingValues(12.dp), verticalArrangement=Arrangement.spacedBy(10.dp)) {
            item { MonthCalendar(month, selected, vm.cache.events, profile.semesterStart, { selected=it; month=YearMonth.from(it) }, { month=month.minusMonths(1) }, { month=month.plusMonths(1) }) }
            item {
                Surface(shape=RoundedCornerShape(16.dp), color=if(week%2==0L) Color(0xFFE9F1FA) else Color(0xFFF2ECF8)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Default.CalendarMonth,null,tint=MgsuBlue); Spacer(Modifier.width(10.dp))
                        Column { Text(selected.format(DateTimeFormatter.ofPattern("EEEE, d MMMM", Ru)).replaceFirstChar{it.uppercase()}, fontWeight=FontWeight.Bold); Text(if(week>0) "Учебная неделя №$week · ${if(week%2==0L) "чётная" else "нечётная"}" else "Дата вне рассчитанного учебного периода") }
                    }
                }
            }
            if (vm.cache.lastError.isNotBlank()) item { AssistChip(onClick={vm.sync()}, label={Text("Ошибка обновления: ${vm.cache.lastError}. Нажмите, чтобы повторить")}, leadingIcon={Icon(Icons.Default.Warning,null)}) }
            if (events.isEmpty()) item { EmptyDay(vm.syncing) }
            items(events, key={it.id}) { e -> EventCard(e, vm.edits.notes[e.id].orEmpty(), e.id in vm.edits.hiddenEventIds, onNote={vm.note(e.id,it)}, onHide={vm.hide(e.id)}, onOpen={ if(e.sourceUrl.isNotBlank()) ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(e.sourceUrl))) }) }
            if (vm.edits.hiddenEventIds.isNotEmpty()) item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                    TextButton({showHidden=!showHidden}) { Icon(if(showHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,null); Spacer(Modifier.width(6.dp)); Text(if(showHidden) "Скрыть удалённые" else "Показать удалённые (${vm.edits.hiddenEventIds.size})") }
                    TextButton({vm.restoreAll()}) { Text("Восстановить все") }
                }
            }
            item {
                Text("Обновлено: ${if(vm.cache.lastSyncMillis>0) java.text.DateFormat.getDateTimeInstance().format(java.util.Date(vm.cache.lastSyncMillis)) else "ещё не загружено"} · источников: ${vm.cache.sourcesChecked}", style=MaterialTheme.typography.bodySmall, color=Color.Gray)
            }
        }
    }
}

@Composable
private fun MonthCalendar(month: YearMonth, selected: LocalDate, allEvents: List<ScheduleEvent>, semesterStart: String, onDate:(LocalDate)->Unit, prev:()->Unit, next:()->Unit) {
    Surface(shape=RoundedCornerShape(20.dp), color=Color.White, shadowElevation=1.dp) {
        Column(Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween, verticalAlignment=Alignment.CenterVertically) {
                IconButton(prev){Icon(Icons.Default.ChevronLeft,null)}
                Text(month.format(DateTimeFormatter.ofPattern("LLLL yyyy", Ru)).replaceFirstChar{it.uppercase()}, fontWeight=FontWeight.Bold, fontSize=18.sp)
                IconButton(next){Icon(Icons.Default.ChevronRight,null)}
            }
            Row(Modifier.fillMaxWidth()) { listOf("Пн","Вт","Ср","Чт","Пт","Сб","Вс").forEach { Text(it, Modifier.weight(1f), textAlign=TextAlign.Center, color=Color.Gray, fontSize=12.sp) } }
            val first = month.atDay(1); val shift = first.dayOfWeek.value-1; val cells = ((shift+month.lengthOfMonth()+6)/7)*7
            for (row in 0 until cells/7) {
                Row(Modifier.fillMaxWidth()) {
                    for (col in 0..6) {
                        val dayNum = row*7+col-shift+1
                        if(dayNum !in 1..month.lengthOfMonth()) Spacer(Modifier.weight(1f).aspectRatio(1f)) else {
                            val d=month.atDay(dayNum); val has=allEvents.any{isOnDate(it,d,semesterStart)}; val sel=d==selected; val today=d==LocalDate.now()
                            Box(Modifier.weight(1f).aspectRatio(1f).padding(2.dp).background(if(sel) MgsuBlue else if(today) Color(0xFFE6EEF7) else Color.Transparent, RoundedCornerShape(12.dp)).clickable{onDate(d)}, contentAlignment=Alignment.Center) {
                                Column(horizontalAlignment=Alignment.CenterHorizontally) { Text("$dayNum", color=if(sel) Color.White else Color.DarkGray, fontWeight=if(sel||today) FontWeight.Bold else FontWeight.Normal); if(has) Box(Modifier.size(4.dp).background(if(sel) Color.White else MgsuBlue, RoundedCornerShape(4.dp))) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventCard(e: ScheduleEvent, initialNote: String, hidden: Boolean, onNote:(String)->Unit, onHide:()->Unit, onOpen:()->Unit) {
    var noteOpen by remember(e.id) { mutableStateOf(initialNote.isNotBlank()) }
    var note by remember(e.id, initialNote) { mutableStateOf(initialNote) }
    Surface(shape=RoundedCornerShape(18.dp), color=if(hidden) Color(0xFFF1F1F1) else Color.White, shadowElevation=1.dp) {
        Column(Modifier.padding(15.dp)) {
            Row(verticalAlignment=Alignment.Top) {
                Surface(shape=RoundedCornerShape(12.dp), color=MgsuBlue) { Text(e.startTime.ifBlank{"—"}, Modifier.padding(horizontal=10.dp,vertical=7.dp), color=Color.White, fontWeight=FontWeight.Bold) }
                Spacer(Modifier.width(12.dp)); Column(Modifier.weight(1f)) { Text(e.title, fontWeight=FontWeight.Bold); Text(e.type, color=MgsuBlue, style=MaterialTheme.typography.labelMedium); if(e.room.isNotBlank()) Text("Аудитория: ${e.room}"); if(e.teacher.isNotBlank()) Text(e.teacher, color=Color.DarkGray); if(e.group.isNotBlank()) Text(e.group, style=MaterialTheme.typography.bodySmall, color=Color.Gray) }
                IconButton(onHide, enabled=!hidden){Icon(Icons.Default.DeleteOutline,"Удалить")}
            }
            Row { TextButton({noteOpen=!noteOpen}){Icon(Icons.Default.EditNote,null);Spacer(Modifier.width(4.dp));Text(if(noteOpen)"Скрыть заметку" else "Заметка")}; TextButton(onOpen){Icon(Icons.Default.OpenInNew,null);Spacer(Modifier.width(4.dp));Text("Источник")}}
            AnimatedVisibility(noteOpen) { OutlinedTextField(note, {note=it;onNote(it)}, label={Text("Моя заметка")}, modifier=Modifier.fillMaxWidth(), minLines=2) }
            if(hidden) Text("Удалено локально — можно восстановить ниже календаря", color=Color.Gray, style=MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable private fun EmptyDay(syncing:Boolean) { Surface(shape=RoundedCornerShape(18.dp), color=Color.White) { Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment=Alignment.CenterHorizontally) { Icon(Icons.Default.EventAvailable,null,Modifier.size(42.dp),tint=MgsuBlue); Spacer(Modifier.height(8.dp)); Text(if(syncing)"Загружаю расписание…" else "На этот день занятий не найдено", fontWeight=FontWeight.SemiBold); Text("Если расписание только что изменили на сайте, нажмите ↻ сверху.", color=Color.Gray, textAlign=TextAlign.Center) } } }

private fun academicWeek(date: LocalDate, startIso: String): Long {
    val start=runCatching{LocalDate.parse(startIso)}.getOrElse{return -1}; return ChronoUnit.WEEKS.between(start,date)+1
}

@Composable
private fun MgsuTheme(content:@Composable()->Unit) {
    MaterialTheme(colorScheme=lightColorScheme(primary=MgsuBlue, secondary=Color(0xFFAC1422), background=Color(0xFFF5F7FA)), content=content)
}
