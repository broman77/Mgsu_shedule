# Build fix v2

Исправления:
- добавлен обязательный opt-in для `ExperimentalMaterial3Api`, используемого `TopAppBar`;
- workflow обновлён до `actions/checkout@v6` и `gradle/actions/setup-gradle@v6`;
- перед полной сборкой отдельно запускается `compileDebugKotlin`;
- лог компилятора сохраняется как artifact `compiler-log`, поэтому следующая ошибка будет видна целиком;
- версия приложения: 1.0.2 (versionCode 3).
