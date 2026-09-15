# Build notes — beta 0.1.0

Эта ревизия исправляет CI-сборку GitHub Actions:

- APK (`assembleDebug`) собирается первым обязательным Gradle-этапом;
- сборка больше не зависит от доступности mgsu.ru/api-loft.mgsu.ru во время CI;
- ресурс логотипа уже находится внутри проекта;
- unit-тесты и Android Lint выполняются после APK как дополнительные beta-проверки и не блокируют выдачу APK;
- при ошибке `build.log` всё равно загружается в artifact `validation-logs-beta-0.1.0`;
- версия приложения остаётся `0.1.0-beta`.
