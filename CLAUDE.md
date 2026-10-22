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
- Расширения протокола rust-analyzer (`rust-analyzer/expandMacro`, `experimental/*`): интерфейс `RustAnalyzerServer` с `@JsonRequest`
  (подставлен платформе через `lsp4jServerClass` дескриптора), ответы — сырой JSON, разбор в `RustAnalyzerProtocol`; отправка —
  `RustAnalyzerRequests.send` (`RustAnalyzerExtensions.kt`). Лог сервера — tool window `rust-analyzer` (`RustAnalyzerLog.kt`).

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

## Отладчик (`io.github.rustsupport.debugger`)
- Свой DAP-клиент на XDebugger API (как в go/dotnet-плагинах; `intellij.platform.dap` в GIGA нет), адаптер — **CodeLLDB** или **lldb-dap** LLVM (`RustDebugAdapters`: Auto / CodeLLDB / lldb-dap в настройках Debugger).
  Сессия — только через `XDebuggerManager.newSessionBuilder(...)`, раннер всегда отдаёт `RunContentDescriptor` (иначе GIGA не строит UI).
- Запуск: `RustDebugRunner` для `RustDebuggableConfiguration` → `RustDebugProcess`: `cargo <build> --message-format=json` (вывод в консоль
  сессии), exe из `compiler-artifact` (`CargoBuild`), потом `codelldb --connect <port>` и `initialize`/`launch` (`CodeLldbLaunch`).
- Транспорт CodeLLDB — TCP (адаптер подключается к порту IDE), **не stdio**: при `terminal: console` программа наследует stdout/stderr адаптера
  (проверено зондом) и по stdio ломала бы framing. stdout/stderr адаптера = вывод программы, stdin адаптера = её ввод.
- `sourceLanguages: ["rust"]` — это `--settings` адаптера (там же `scriptConfig.lang.rust.sysroot`, иначе rust.py ищет rustc в своём PATH);
  `evaluate` — с контекстом `watch`/`hover`, не `repl` (в CodeLLDB это команды LLDB).
- CodeLLDB: путь из настроек → расширение VS Code / Insiders / Cursor / VSCodium (новейшее) → скачанный в `<system>/rust-support/codelldb/<ver>`.
  **Сам ничего не скачивает**: только по кнопке пользователя, с подтверждением (версия, URL, размер), через `HttpRequests`, распаковка `java.util.zip`.
  Без сети — «Install from VSIX...» (`CodeLldbInstaller.installFromVsix`).
- lldb-dap: путь из настроек → PATH → Xcode / CommandLineTools / Homebrew llvm / `/usr/lib/llvm-*` / `C:\Program Files\LLVM`. Транспорт — stdio
  (вывод программы приходит событиями `output`, ввода у программы нет); форматтеры Rust — `initCommands` как у `rust-lldb`
  (`lldb_lookup.py` + `lldb_commands`); паника — `setFunctionBreakpoints` на `rust_panic`; LLDB-команды в `repl` — с префиксом `` ` ``.
- **Пути ко всем бинарям настраиваются в IDE** (cargo, rustc, rustfmt, cargo-clippy, rustup, rust-analyzer, codelldb, lldb-dap): у
  пользователей бывают свои сборки инструментов (например, из их VS Code-плагина) вне PATH и `~/.cargo/bin`. Новый внешний бинарь —
  сразу с полем пути в настройках. `RustToolchain.environment` передаёт заданные пути дальше (`RUSTC`, `RUSTFMT`, их папки первыми в PATH).
- **Прокси и зеркала реестра.** Реестр крейтов может быть за системным/корпоративным прокси или заменён зеркалом
  (`~/.cargo/config.toml`: `[http] proxy`, `[source.crates-io] replace-with`, `[registries]`; env `HTTPS_PROXY`/`CARGO_HTTP_PROXY`).
  Данные о крейтах (версии, features, описание) — через сам `cargo` (`cargo info`, `cargo metadata`, `cargo add`): он применяет
  свою конфигурацию прокси и зеркал. Сначала локальный кэш реестра (`$CARGO_HOME/registry/index`, `registry/cache`), сеть — только
  по действию пользователя. Если всё же нужен прямой HTTP — только `HttpRequests` (прокси IDE, в т.ч. системный/PAC), никогда
  захардкоженный `crates.io` в обход настроенного зеркала.
