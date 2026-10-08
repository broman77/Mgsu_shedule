
### Parser v8 — day-bounded lesson cells
- Lesson-cell geometry is now calculated independently inside each weekday instead of across the whole PDF page.
- Weekday labels are used directly to assign time rows when reliable, with pair-number cycles kept only as a fallback.
- Prevents an empty 19:40 row from swallowing the next day's first lesson and falsely showing classes through 21:00 across many groups.
- Parser cache version bumped to 8 so devices rebuild previously corrupted schedules.
## Parser v7 — IAG table row boundary fix

- Fixed false lessons leaking into empty rows when MGSU prints start/end time on separate lines.
- Time-row detection is now restricted to the real `Часы` column instead of overlapping the first group column.
- Bumped parser cache version so previously misparsed schedules are rebuilt.

# Changelog

## Student-only parser v6

- Removed teacher profile flow; only student groups can be created or restored.
- Replaced whole-university PDF indexing with page-first, institute/course-scoped discovery.
- Exact group-to-PDF mappings are refreshed from the official MGSU download page.
- Removed the 260-PDF fallback scan; sync retries only sources relevant to the selected group.
- Added regression tests for the current 2026/27 MGSU link and group-header naming.
- Bumped parser/catalog cache versions to 6.

## beta 0.1.0 — parser/bootstrap/UI repair (versionCode 13)

- исправлен поиск PDF на официальных поддоменах МГСУ, включая `www-20.mgsu.ru`;
- parser schema 5, catalog schema 5, автоматический сброс старых данных;
- rotation-aware PDF coordinates для landscape-расписаний;
- восстановление разрезанных заголовков групп и строк времени;
- строгая нормализация преподавателей, включая ФАМИЛИЮ В ВЕРХНЕМ РЕГИСТРЕ;
- первый запуск блокирует выбор профиля до построения официального каталога;
- исправлены настройки и нижняя навигация;
- видимое имя изменено на «Расписание МГСУ»;
- UI использует официальные сетевые логотип/фото МГСУ, workflow обновляет launcher logo из официального ресурса.
