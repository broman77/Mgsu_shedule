# Build fix 1.0.1

Исправлена ошибка компиляции Kotlin в `MainActivity.kt`: тип параметра Compose-функции был записан как `content:@Composable()->Unit`, что давало `type expected`.

Исправлено на:

```kotlin
private fun MgsuTheme(content: @Composable () -> Unit)
```

Также `actions/setup-java` обновлён до v5.
