from pathlib import Path


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected exactly one match, got {count}")
    return text.replace(old, new, 1)


def replace_between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    a = text.find(start)
    if a < 0:
        raise RuntimeError(f"{label}: start marker not found")
    b = text.find(end, a)
    if b < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:a] + replacement + text[b:]


# Fix two small source details introduced before the one-shot refactor.
source_rules = Path("app/src/main/java/ru/mgsu/schedule/data/MgsuSourceRules.kt")
s = source_rules.read_text()
s = replace_once(
    s,
    'Regex("(?<!\\\\d)([1-6])\\\\s*[-–—]\\\\s*([1-6])\\\\s*(?:КУРС|К\\\\b|K\\\\b)")',
    'Regex("(?<!\\\\d)([1-6])\\\\s*(?:[-–—]|\\\\s)\\\\s*([1-6])\\\\s*(?:КУРС|К\\\\b|K\\\\b)")',
    "course range after URL marker normalization",
)
source_rules.write_text(s)

repo = Path("app/src/main/java/ru/mgsu/schedule/data/MgsuRepository.kt")
s = repo.read_text()
s = replace_once(
    s,
    """            var (_, sources) = indexedSources(force = false)\n            if (sources.isEmpty()) {\n                (_, sources) = indexedSources(force = true)\n            }\n""",
    """            var sources = indexedSources(force = false).second\n            if (sources.isEmpty()) {\n                sources = indexedSources(force = true).second\n            }\n""",
    "Kotlin source list assignment",
)
repo.write_text(s)

# Catalog schema version is intentionally bumped so bad full-university indexes from parser v5
# cannot be reused after upgrading.
models = Path("app/src/main/java/ru/mgsu/schedule/data/Models.kt")
s = models.read_text()
s = replace_once(s, "val schemaVersion: Int = 5,", "val schemaVersion: Int = 6,", "catalog schema")
models.write_text(s)

store = Path("app/src/main/java/ru/mgsu/schedule/data/AppStore.kt")
s = store.read_text()
s = replace_once(s, "const val CURRENT_PARSER_VERSION = 5", "const val CURRENT_PARSER_VERSION = 6", "parser version")
old = """        fun validProfile(profile: UserProfile): Boolean = when (profile.role) {\n            UserRole.STUDENT -> ScheduleParsingRules.isValidGroup(profile.group)\n            UserRole.TEACHER -> ScheduleParsingRules.isValidTeacher(profile.teacher)\n        }\n\n        fun normalizeProfile(profile: UserProfile): UserProfile = when (profile.role) {\n            UserRole.STUDENT -> profile.copy(group = ScheduleParsingRules.canonicalGroup(profile.group).orEmpty(), teacher = \"\")\n            UserRole.TEACHER -> profile.copy(teacher = ScheduleParsingRules.canonicalTeacher(profile.teacher).orEmpty(), group = \"\")\n        }\n\n        fun profileDisplayName(profile: UserProfile): String {\n            val normalized = normalizeProfile(profile)\n            return when (normalized.role) {\n                UserRole.STUDENT -> normalized.group.ifBlank { \"${normalized.institute}, ${normalized.course} курс\" }\n                UserRole.TEACHER -> normalized.teacher.ifBlank { \"Преподаватель\" }\n            }\n        }\n"""
new = """        fun validProfile(profile: UserProfile): Boolean =\n            profile.role == UserRole.STUDENT && ScheduleParsingRules.isValidGroup(profile.group)\n\n        fun normalizeProfile(profile: UserProfile): UserProfile = profile.copy(\n            role = UserRole.STUDENT,\n            group = ScheduleParsingRules.canonicalGroup(profile.group).orEmpty(),\n            teacher = \"\"\n        )\n\n        fun profileDisplayName(profile: UserProfile): String {\n            val normalized = normalizeProfile(profile)\n            return normalized.group.ifBlank { \"${normalized.institute}, ${normalized.course} курс\" }\n        }\n"""
s = replace_once(s, old, new, "student-only stored profile rules")
store.write_text(s)

main = Path("app/src/main/java/ru/mgsu/schedule/MainActivity.kt")
s = main.read_text()

s = replace_once(
    s,
    """            favorites = loadedFavorites.copy(\n                teachers = loadedFavorites.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).toSet(),\n                groups = loadedFavorites.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).toSet()\n            )\n""",
    """            favorites = loadedFavorites.copy(\n                teachers = emptySet(),\n                groups = loadedFavorites.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).toSet()\n            )\n""",
    "clear legacy teacher favorites",
)
s = replace_once(
    s,
    """            recentSelections = loadedRecent.copy(\n                teachers = loadedRecent.teachers.filter(ScheduleParsingRules::isValidTeacher).mapNotNull(ScheduleParsingRules::canonicalTeacher).distinct().take(8),\n                groups = loadedRecent.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().take(10)\n            )\n""",
    """            recentSelections = loadedRecent.copy(\n                teachers = emptyList(),\n                groups = loadedRecent.groups.filter(ScheduleParsingRules::isValidGroup).mapNotNull(ScheduleParsingRules::canonicalGroup).distinct().take(10)\n            )\n""",
    "clear legacy teacher recents",
)
s = replace_once(
    s,
    """            } else {\n                bootstrapReady = true\n                ensureCatalog()\n            }\n""",
    """            } else {\n                bootstrapReady = true\n                profile?.let { ensureStudentGroups(it.institute, it.course) }\n            }\n""",
    "existing profile catalog load",
)

s = replace_between(
    s,
    "    fun saveProfile(p: UserProfile) = viewModelScope.launch {",
    "\n    fun startAddProfile() {",
    """    fun saveProfile(p: UserProfile) = viewModelScope.launch {\n        if (p.role != UserRole.STUDENT || !ScheduleParsingRules.isValidGroup(p.group)) return@launch\n        val normalized = p.copy(\n            role = UserRole.STUDENT,\n            group = ScheduleParsingRules.canonicalGroup(p.group).orEmpty(),\n            teacher = \"\"\n        )\n        profiles = store.createProfile(normalized)\n        profile = normalized\n        store.clearCache()\n        cache = CachedSchedule(parserVersion = AppStore.CURRENT_PARSER_VERSION)\n        scheduleWorkers()\n        sync()\n    }\n""",
    "save student profile",
)

s = replace_between(
    s,
    "    fun prepareFirstLaunchCatalog() {",
    "\n    fun teacherSuggestions(query: String): List<String> {",
    """    fun prepareFirstLaunchCatalog() {\n        if (catalogJob?.isActive == true) return\n        catalogJob = viewModelScope.launch {\n            bootstrapReady = false\n            bootstrapError = \"\"\n            catalogError = \"\"\n            catalogLoading = true\n            bootstrapMessage = \"Проверяем официальную страницу расписания НИУ МГСУ…\"\n            try {\n                catalog = catalogRepository.refresh(force = true)\n                if (catalog.sourceLabels.isEmpty()) {\n                    bootstrapError = \"На странице МГСУ не найдено файлов расписания. Проверьте интернет и повторите загрузку.\"\n                } else {\n                    bootstrapReady = true\n                }\n            } catch (t: Throwable) {\n                bootstrapError = t.message ?: \"Не удалось загрузить расписание НИУ МГСУ\"\n            } finally {\n                catalogLoading = false\n            }\n        }\n    }\n\n    fun ensureStudentGroups(institute: String, course: Int, force: Boolean = false) {\n        catalogJob?.cancel()\n        catalogJob = viewModelScope.launch {\n            catalogLoading = true\n            catalogError = \"\"\n            try {\n                catalog = catalogRepository.refreshGroups(institute, course, force)\n                val count = catalog.groups.count { group ->\n                    ScheduleParsingRules.groupInstitute(group).equals(institute, ignoreCase = true) &&\n                        ScheduleParsingRules.groupCourse(group) == course\n                }\n                if (count == 0) catalogError = \"Для выбранного института и курса группы не найдены\"\n            } catch (t: Throwable) {\n                catalogError = t.message ?: \"Не удалось получить группы с сайта МГСУ\"\n            } finally {\n                catalogLoading = false\n            }\n        }\n    }\n""",
    "first launch and student group loading",
)

s = replace_once(
    s,
    "if (q.isBlank()) return (validRecent + validFavorites.sorted()).distinct().take(10)",
    "if (q.isBlank()) return (validRecent + validFavorites.sorted() + base).distinct().take(20)",
    "blank group suggestions",
)

s = replace_once(
    s,
    """                \"На первом запуске приложение загружает и обрабатывает официальные файлы занятий и экзаменов. Это может занять несколько минут.\",\n""",
    """                \"Приложение получает список файлов с официальной страницы МГСУ. PDF нужного курса загрузится после выбора института и курса.\",\n""",
    "first launch explanation",
)

profile_setup = r'''@Composable
private fun ProfileSetup(vm: MainVm, onSave: (UserProfile) -> Unit) {
    var institute by remember { mutableStateOf("ИАГ") }
    var course by remember { mutableIntStateOf(1) }
    var form by remember { mutableStateOf("Очная") }
    var group by remember { mutableStateOf("") }
    var inputError by remember { mutableStateOf("") }
    var start by remember { mutableStateOf("2026-08-31") }
    val institutes = listOf("ИАГ", "ИПГС", "ИГЭС", "ИИЭСМ", "ИЦТМС", "ИЭУКСН", "ИИС ОИАЭ", "ИДО")

    LaunchedEffect(institute, course) {
        group = ""
        vm.ensureStudentGroups(institute, course)
    }

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
                        Text("Студенческое расписание, задания и личный календарь", color = Color.White.copy(alpha = .94f), modifier = Modifier.padding(top = 5.dp))
                        Text("Данные расписания: официальный сайт НИУ МГСУ", color = Color.White.copy(alpha = .78f), fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
                    }
                }
            }
        }
        if (vm.profiles.profiles.isNotEmpty()) {
            Text("Сохранённые группы", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                vm.profiles.profiles.forEach { saved ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = if (saved.id == vm.profiles.currentId) Color(0xFFE9F1FA) else Color.White,
                        modifier = Modifier.fillMaxWidth().clickable { vm.switchProfile(saved.id) }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.School, null, tint = MgsuBlue)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(saved.name, fontWeight = FontWeight.Bold)
                                Text("${saved.profile.institute} · ${saved.profile.studyForm}", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                            }
                            Icon(Icons.Default.ChevronRight, null)
                        }
                    }
                }
            }
            HorizontalDivider()
            Text("Добавить ещё одну группу", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        } else {
            Text("Выберите свою учебную группу", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }

        DropField("Институт", institute, institutes) { institute = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DropField("Курс", "$course", (1..6).map { "$it" }, Modifier.weight(1f)) { course = it.toInt() }
            DropField("Форма", form, listOf("Очная", "Очно-заочная", "Заочная"), Modifier.weight(2f)) { form = it }
        }
        AutoCompleteField(
            label = "Группа",
            value = group,
            placeholder = "Например: ИПГС 1-1",
            suggestions = vm.groupSuggestions(group, institute, course),
            loading = vm.catalogLoading,
            favorites = vm.favorites.groups,
            onToggleFavorite = vm::toggleFavoriteGroup,
            onValueChange = { group = it; inputError = "" },
            onSelected = { group = it; inputError = ""; vm.recordGroupSelection(it) }
        )
        if (vm.catalogError.isNotBlank()) {
            Text(vm.catalogError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            AssistChip(
                onClick = { vm.ensureStudentGroups(institute, course, true) },
                label = { Text("Повторить загрузку групп") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) }
            )
        }
        OutlinedTextField(
            start, { start = it }, label = { Text("Понедельник 1-й учебной недели") },
            supportingText = { Text("Нужен для чётных/нечётных недель и номеров учебных недель") },
            modifier = Modifier.fillMaxWidth(), singleLine = true
        )
        val canonicalGroup = group.takeIf(ScheduleParsingRules::isValidGroup)?.let(ScheduleParsingRules::canonicalGroup)
        if (group.isNotBlank() && canonicalGroup == null) {
            Text("Выберите точную группу из списка, например ИПГС 1-1.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        if (inputError.isNotBlank()) {
            Text(inputError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Button(
            onClick = {
                val g = canonicalGroup
                if (g == null) {
                    inputError = "Сначала выберите точную группу из списка МГСУ."
                    return@Button
                }
                group = g
                vm.recordGroupSelection(g)
                onSave(UserProfile(UserRole.STUDENT, institute, course, form, g, "", start, true))
            },
            enabled = canonicalGroup != null && !vm.catalogLoading,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MgsuBlue)
        ) { Text("Открыть расписание") }

        Spacer(Modifier.height(8.dp))
        Text("Неофициальное студенческое приложение. Расписание берётся из публичных файлов НИУ МГСУ (mgsu.ru).", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
    }
}

'''
s = replace_between(
    s,
    "@Composable\nprivate fun ProfileSetup(vm: MainVm, onSave: (UserProfile) -> Unit) {",
    "@Composable\nprivate fun AutoCompleteField(",
    profile_setup,
    "student-only profile setup",
)

s = replace_once(
    s,
    "Text(if (profile.role == UserRole.STUDENT) profile.group else profile.teacher, fontWeight = FontWeight.Bold, maxLines = 1)",
    "Text(profile.group, fontWeight = FontWeight.Bold, maxLines = 1)",
    "student title",
)
main.write_text(s)

readme = Path("README.md")
readme.write_text("""# Расписание МГСУ — beta 0.1.0\n\nНеофициальное Android-приложение **для студентов НИУ МГСУ**. Расписание загружается только из публичных официальных страниц и PDF-файлов МГСУ.\n\n## Что изменено в student-only parser v6\n\n- режим преподавателя убран из создания и хранения профилей; приложение работает только с учебными группами студентов;\n- первый запуск больше не скачивает и не индексирует сотни PDF всего университета;\n- сначала приложение читает актуальную страницу файлов расписания МГСУ, а после выбора института и курса загружает только подходящие PDF;\n- выбранная группа связывается с конкретным официальным PDF, после чего синхронизация разбирает только этот небольшой набор источников;\n- если МГСУ переименовал файл в новом семестре, приложение один раз обновляет страницу и перестраивает индекс выбранной группы;\n- старый кэш parser/catalog v5 автоматически сбрасывается (parser 6 / catalog schema 6);\n- при ошибке сети сохраняется последнее успешно полученное расписание;\n- координатный PDF-парсер по-прежнему выбирает только точную колонку выбранной группы и официальные интервалы пар.\n\n## Версия\n\n- `versionName = 0.1.0-beta`\n- `versionCode = 13`\n- `minSdk = 26`\n- `targetSdk = 35`\n- `compileSdk = 35`\n\n## Сборка\n\nGitHub Actions workflow `Build Android APK` собирает debug APK. Готовый APK публикуется как artifact `MGSU-Schedule-beta-0.1.0-debug`.\n\n## Источники данных\n\nОсновной источник: публичная страница НИУ МГСУ «Файлы расписания для скачивания» и размещённые на официальных доменах МГСУ PDF. Приложение не является официальным приложением университета.\n""")

changelog = Path("CHANGELOG.md")
old = changelog.read_text()
entry = """# Changelog\n\n## Student-only parser v6\n\n- Removed teacher profile flow; only student groups can be created or restored.\n- Replaced whole-university PDF indexing with page-first, institute/course-scoped discovery.\n- Exact group-to-PDF mappings are refreshed from the official MGSU download page.\n- Removed the 260-PDF fallback scan; sync retries only sources relevant to the selected group.\n- Added regression tests for the current 2026/27 MGSU link and group-header naming.\n- Bumped parser/catalog cache versions to 6.\n\n"""
if old.startswith("# Changelog\n"):
    old = old[len("# Changelog\n"):].lstrip("\n")
changelog.write_text(entry + old)

print("Student-only refactor applied")
