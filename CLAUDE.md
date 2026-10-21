# gigaide-rust

Плагин поддержки Rust для GIGA IDE (IntelliJ Platform 261+). Сосед плагинов `../idea-golang-support` и `../idea-dotnet-support`:
оттуда берутся решения и код (DAP-клиент, run configurations, виджеты), но без их PSI/семантики.

## Архитектура
- **Вся работа с языком — rust-analyzer** через платформенный LSP-клиент (`com.intellij.platform.lsp`). Своего парсера нет и не будет.
- **Лёгкий лексер** (`lang/RustLexer.kt`) — только ради токенов: Ctrl + мышь, подсветка до старта сервера, скобки, отступы,
  комментирование. Файл = плоский список токенов (`RustParserDefinition`).
- Пакеты:
  - `io.github.rustsupport` (основной jar, без LSP-классов): `lang/`, `toolchain/`, `settings/`, `monitor/`.
  - `io.github.rustsupport.lsp` — content module (`src/main/resources/io.github.rustsupport.lsp.xml`). Всё, что трогает
    `com.intellij.platform.lsp`, живёт только здесь; основной код его не импортирует (обратная связь — через message bus / EP).
- rust-analyzer стартует при открытии проекта с Rust (`RustAnalyzerStartupActivity`), свой виджет в статус-баре с CPU/RAM
  (`RustAnalyzerStatus.kt`), навигация Ctrl+клик — `RustAnalyzerGotoDeclarationHandler`.
- Кастомные запросы к rust-analyzer: `RustAnalyzer.client(project)` + `client.sendRequestSync(...)` (см. `RustAnalyzerNavigation.kt`).

## Сборка
- `.\build.ps1` (тесты + ZIP) или напрямую: `$env:JAVA_HOME='C:\Program Files\GIGA IDE\GIGA IDE 2026.1\jbr'; .\gradlew.bat test --offline -q`.
- Всегда `--offline` (прокси машины). `runIde` — с `--no-configuration-cache`.
- Платформа = установленная GIGA IDE (`localIdePath` в `gradle.properties`).
- **Не писать `gradle.properties` через PowerShell `Set-Content`/`Out-File`** — добавляет BOM, Gradle перестаёт видеть первое свойство.
- Kotlin API/language 2.3 (stdlib платформы), JVM 21.

## Правила
- Тесты обязательны для чистой логики (лексер, отступы, разбор вывода cargo, тексты). JUnit 4, `src/test/kotlin`.
- Живой запуск (`runIde`) и управление десктопом делает только оркестратор; агенты в worktree — только `test`/`buildPlugin`.
- Тестовый проект для живых проверок: `C:\Users\dvislobokov\github\komodo`.
- После живой проверки в `idea.log` песочницы не должно быть `Plugin to blame: Rust` и SEVERE от плагина.
- Все платформенные вызовы из фона — в read action; запросы к серверу — с таймаутом, ошибки сервера не должны ломать IDE.
- Комментарии и KDoc — на английском, в стиле существующего кода (объясняют «почему», с пометкой «seen live» для проверенного вживую).
- На Windows у rustc должен быть MSVC (Build Tools с «Desktop development with C++» и Windows SDK); иначе он берёт `link.exe` из Git.
- **Сеть — только по согласию пользователя.** Плагин сам ничего не скачивает: недостающее (CodeLLDB и т. п.) только *предлагается*
  кнопкой в уведомлении/настройках, скачивание — по клику, через платформенный `HttpRequests` (прокси IDE) в отменяемой фоновой
  задаче, распаковка в процессе. Никаких внешних `curl`/`wget`/`Invoke-WebRequest`/`bitsadmin`. Запуск тулчейна пользователя
  (`rustup component add` по кнопке, cargo, rust-analyzer) — можно.
