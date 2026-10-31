# AGENT HANDOFF — проект VoidPE (GeyserPlugins)

> Ты продолжаешь работу над Minecraft-сервером **VoidPE**. Прочитай этот файл ЦЕЛИКОМ перед любыми действиями. С пользователем общаться **по-русски**. Работаешь в репозитории https://github.com/gulp-tech/GeyserPlugins, ветка **`arena/874b2be9-geyserplugins`** (коммитить и пушить только в неё; PR из ветки уже открыт, статус `pr_open`). Текущий HEAD: `17d5c22`.

## 1. Что за проект

- Bedrock-only сервер **VoidPE** (раньше FaralMC). Исторический стек: **Purpur 26.2 (Java 25)** + Geyser 2.12 + Floodgate, заход с Java запрещён.
- **НОВОЕ (указание пользователя):** сервер переезжает на кастомное ядро **Purpurbedrock 26.2** — чистый Bedrock-сервер с полной поддержкой Java-плагинов и сундучных Java-меню, клиенты Bedrock **1.21.0–26.52**. На нём НЕ используются плагины Geyser-Spigot/Floodgate и eCloud-расширения; данные игрока Bedrock ядро отдаёт через встроенные плейсхолдеры PlaceholderAPI `%purpurbedrock_*%` (см. раздел 8). Расширения Geyser из репозитория пока не удаляем — без прямого указания пользователя ничего не сносить.
- Всё самописное: плагины Paper/Purpur (Maven, Java 25), расширения Geyser (Gradle), ресурспаки Bedrock, конфиги в `configs/`.
- Релизы: тег `v*` → `release.yml` публикует GitHub Release с **отдельными jar каждого модуля**; пользователь качает прямые ссылки. Последний релиз: **v1.71.0** (15 ассетов). Следующий запланирован **v1.72.0** (16 ассетов: + VoidVersionsExtension 1.4.0, VoidRtpZone 1.0.0, VoidTab 1.16.0, VoidCombat 1.3.0).

## 2. ⚠️ ГЛАВНЫЙ БЛОКЕР: GitHub Actions не работает (биллинг)

- Все джобы падают на старте: «The job was not started because recent account payments have failed or your spending limit needs to be increased». Проверено многократно, включая последний коммит `17d5c22` (ран `40201964974`). Пока пользователь не починит оплату/лимит в **GitHub → Settings → Billing & plans**, собрать или выпустить НЕЛЬЗЯ НИЧЕГО. Теги не ставить (релизный ран упадёт; перевыпуск потом — удалить тег и запушить заново).
- Как только пользователь напишет, что биллинг починен: (1) найти CI-ран на последний коммит ветки и проверить, что шаги **VoidVersionsExtension** и **VoidRtpZone** = success (финальный статус рана может быть failure из-за квоты upload-artifact — смотреть именно шаги); (2) поставить тег `v1.72.0`; (3) проверить `gh release view v1.72.0` — 16 ассетов, есть `VoidVersionsExtension-1.4.0.jar` и `VoidRtpZone-1.0.0.jar`; (4) дать пользователю прямые ссылки.
- Пользователю уже обещана ссылка вида `https://github.com/gulp-tech/GeyserPlugins/releases/download/v1.72.0/VoidRtpZone-1.0.0.jar` — она оживёт после релиза.

## 3. Среда песочницы (важно, не тратить время зря)

- Локально **нет Java/Maven/Gradle**, и поставить их нельзя: сеть разрешена только к `github.com`, `codeload.github.com`, `api.github.com`, `registry.npmjs.org`, `pypi.org`, `files.pythonhosted.org`. Ассеты GitHub-релизов через прокси НЕ качаются («Not Found»/SSL error), Maven Central и repo.papermc.io недоступны.
- Через PyPI ставится колесо `jdk4py` (Temurin JRE 25), но это урезанный рантайм **без компилятора** (нет `javac`, нет модуля `jdk.compiler`). Локальная сборка jar невозможна в принципе; байткод вручную не собирать и «честные» jar без CI не отдавать. Вся проверка компиляции — только GitHub Actions.
- `gh release download` в песочницу тоже не работает — целостность jar локально не проверить.
- Снапшот воркспейса периодически **теряет локальную историю git** (было 6+ раз): локальный HEAD внезапно оказывается на `cb97e99 Initial commit`, пуш отклоняется. Лечение: `git fetch origin` → `git reset --hard origin/arena/874b2be9-geyserplugins` (на GitHub коммиты целы; перед reset сохрани новые файлы в /tmp).

## 4. Конвенции разработки

- Плагины: Maven, `src/main/java/ru/voidpe/<модуль>/`, ресурсы `plugin.yml` + `config.yml` с русскими комментариями; версия в `pom.xml`, в `plugin.yml` — `${project.version}`. Расширения Geyser: Gradle, версия/api в `gradle.properties`; к ядру Geyser — **только рефлексией** (компилируются против API, ставятся на любой актуальный Geyser).
- **Всё настраивается в конфигах** (тексты, цвета, иконки, форматы). Цвета: `&a`, `&#RRGGBB`, в плагинах допустим `<gradient:...>`; в **DeluxeMenus градиенты НЕЛЬЗЯ** — только одноцветные `&#RRGGBB`.
- При правках плагин Faral* переименовывать в Void* (VoidItems, VoidTalismans уже сделаны). Критичные данные НЕ хранить только в config.yml: пользователь при обновлениях удаляет старые `config.yml` (образец решения — `points.yml` у VoidWardenCity, `zones.yml` у VoidRtpZone).
- Правки файлов — только точечные, **по одной правке на файл**, после — grep-проверка. Массовые правки YAML скриптами уже повреждали файлы; PyYAML не парсит flow-стиль конфигов.
- Пользователю давать **только новые команды**, которые он ещё не применял. Выдача привилегий — только `lp user <ник> parent add <ранг>` (НЕ parent set).
- Консоль у пользователя — **Pterodactyl** (панель хостинга): команды вводятся **по одной, без слэша `/`**, Enter после каждой. Многострочная вставка склеивается в одну строку и ломает LuckPerms (был инцидент: весь блок ушёл одной командой, LP воспринял хвост как контексты и упал на сохранении группы). Команды для копирования давать файлом по raw-ссылке GitHub (из чата `&` превращается в `&amp;`, а `essentials.fly` — в ссылку).

## 5. Плагины (папка = модуль; jar ставится в `plugins/`)

| Модуль | Версия | Суть |
|---|---|---|
| **VoidTab** | 1.16.0 | Скорборд, TAB, ник над головой ([иконка устройства][привилегия] название Ник, снизу здоровье 20 ❤; устройство текстом в TAB). Bedrock-only: Java-игроков кикает (обход — `voidtab.bedrock-bypass`). Префикс LuckPerms — единый источник (чат/скорборд/ник). Иконки устройств — глифы ресурспака (лист 256×256, ячейка 16×16; kbmouse — только мобильная ОС + клава/мышь, НЕ Windows). `/voidtab`. Устройство/пинг в КОДЕ: бридж на ядро Purpurbedrock (`BedrockPlaceholderHook.resolveOnline`, рефлексия; `device_id`→иконки 1-в-1, `{ping}` = RakNet RTT), Floodgate/Geyser — fallback. |
| **VoidItems** | 1.8.0 | Кастомные предметы, `/items` — админ-меню. Трапка (незеритовый лом, ПКМ, клетка по схеме из папки плагина, без файла — 3×3, пол 2 вглубь); лом спавнеров (одноразовый, сохраняет моба); пыль (снимает невидимость + белая обводка, БЕЗ голограммы и счётчика задетых); дезориентация (все эффекты по 5 сек, без счётчика). Изумрудные: крафт алмазных из изумрудов → Защита III + trim EMERALD/SENTRY, меч Острота III, инструменты Эффективность III, лор УБРАН, в донат-магазине их нет. |
| **VoidTalismans** | 1.7.5 | Талисманы (зачарованный тотем, простые названия, лор только `{stats}`, без курсива, только левая рука, магазина нет кроме донатного) + сферы: «Сфера сумерек/пламени/бездны/пустоты» (урон +4, броня +4, здоровье +4, скорость +5%; у пламени −3% скорости; 3500 осколков или обмен на яйцо дракона; БЕЗ свечения, только левая рука). Команды раздельные: `/talisman give`, `/sphere give`; атомарный обмен `/sphere exchange <игрок>`; `/talisman` — только админ-меню, без сообщений о статусах. |
| **VoidEnchants** | 1.2.4 | Чары: Автоплавка, Магнит, Бур I/II. Нативная Bedrock-наковальня НЕ чинится — серверное меню `/vench anvil` + форма расширения. Админ: `/vench give <ник> miner1\|miner2\|miner3` (готовые кирки шахтёра), `/vench give <ник> book_autosmelt\|book_magnet\|book_drill`, `/vench enchant autosmelt\|magnet\|drill [ур.]` (предмет в руке; drill до 2, остальные 1). Право `voidenchants.admin`. **1.2.3:** контейнеры не перехватываются, монеты VoidDrops магнит не подбирает. **1.2.4:** износ надетой брони — `armor.durability-multiplier: 0.4` (в 2.5 раза медленнее; `1.0` = ваниль, `0.0` = броня не изнашивается). |
| **VoidCases** | 1.4.2 | Кейсы: ключи только виртуальные; анимация открытия снаружи в мире (кейс-блок не меняется, ПКМ открывает, голограмма прячется, над наградой её название, урона нет, один открывающий на кейс, в сообщениях НЕ упоминать `/cases keys`). Титулы `/titles`: БЕЗ скобок, `&r` в конце, видны в чате/скорборде/табе. Стёкла в меню — только ванильные. |
| **VoidClans** | 1.2.0 | Кланы: `/clan`, `/clan help`. |
| **VoidDrops** | 1.1.0 | Монеты (Vault) с мобов; бусты правами: `voiddrops.boost.baron` ×1.2, `.lord` ×1.5, `.knyaz` ×2, `.imperator` ×3 (плагин берёт наибольший). `/vdrops`. |
| **VoidAuthSpawn** | 1.2.0 | Точка до/после AuthMe-логина + возврат на прежнее место. `/authspawn set`; `return-locations.yml`. Конфиги AuthMe — `configs/AuthMe-*.yml`. |
| **VoidCombat** | 1.2.0 | Режим боя 30 сек, кулдауны, запрет фейерверков в бою, WorldGuard-зоны, **боссбар боя** (только с 1.2.0). `/combat`; состояние `state.yml`. ⚠️ На сервере пользователя стоит 1.1.0 — напомнить заменить jar (v1.66.0). |
| **VoidAntiCheat** | 1.0.0 | Античит Floodgate-only (Floodgate — рефлексией), 19 проверок. `/vac`. Пользователь: «не работает он» — плагина не было в `plugins/`; ждём установки и строк из latest.log. |
| **VoidWardenCity** | 1.4.0 | Древний город: `/wcity` телепорт (кулдаун 300 с, цена 250 осколков, обход `voidwardencity.bypass`); админ: `setcenter/setspawn/chest/refill/reload/info`, GUI: `/wcity loot`, `/wcity chances` (кнопки ±1/±5/±10). Центр/спавн — в **points.yml** (источник правды, config.yml — зеркало). Лут: ванильная таблица 29 строк в config.yml + GUI-предметы (`custom-loot.chance-percent` 30), шансы — `loot-chances.yml`, предметы — `loot-items.yml`; сундук добирается до 5–10 наград; обновление по онлайну 25+/10+/<10 → 5/10/20 мин. На сервере настроено: центр=спавн `world2 -648 -42 -544`, GUI-лута 54 предмета. |
| **VoidRtpZone** | **1.0.0 (НОВЫЙ, jar не выпущен)** | Мини-плагин: игрок входит в зону → ему выполняется `/rtp`. `/rtpzone wand` (огненный стержень, метка PDC) — ЛКМ точка 1, ПКМ точка 2; `/rtpzone save <имя>`, `remove`, `list`, `reload`. Зоны — `zones.yml`; команды триггера, кулдаун 10 с, пропуск CREATIVE/SPECTATOR — в config.yml. Оптимизация: без таймеров, PlayerMoveEvent фильтруется сменой блока, зоны по мирам, срабатывание на переходе снаружи→внутрь. Права: `voidrtpzone.admin`, `voidrtpzone.bypass`. Добавлен в build.yml/release.yml (коммит `6a2ed82`). |

## 6. Расширения Geyser (Gradle; установка ТОЛЬКО в `plugins/Geyser-Spigot/extensions/`, НЕ в `plugins/`)

- **VoidAnvilExtension 1.0.0** — Bedrock-форма наковальни (Cumulus): `/voidanvil anvil` → форма → `/vench anvil`. Cumulus 1.1.2: `.button(String, lambda)` НЕ компилируется — кнопки без callback + `validResultHandler`/`clickedButtonId()`.
- **VoidVersionsExtension 1.4.0 (jar не выпущен)** — вход Bedrock 1.20–26.52 вместо нативных 26.30–26.52. На PreInitialize сканирует кодеки cloudburst (отсечка `min-version`), прогоняет через `CodecProcessor.processCodec`, дописывает в списки GameProtocol; через Unsafe переписывает кэш `VersionCommand.SUPPORTED_BEDROCK_RANGE` (иначе `geyser version` врёт). **1.4.0: патч реестров** — на PostInitialize/PostReload в `BlockRegistries.BLOCKS`, `Registries.ITEMS`, `Registries.TAGS` старым протоколам подставляются маппинги младшей родной версии, иначе вход умирает на LoginPacket «No appropriate value for version: 898» (898 = Bedrock 1.21.130). Конфиг: `extensions/voidversions/config.properties` (enabled, min-version, scan-from/to-protocol, disabled-protocols, verbose). Runtime-проверка 1.4.0 ещё НЕ проводилась — после релиза нужен заход клиентом 1.21.130; возможны следующие слои несовместимости, чинить по стектрейсам. API 2.11 на Geyser 2.12 совместим (проверено по исходникам Geyser ref d14f204).

## 7. Ресурспаки

- **VoidResourcePack.mcpack 17.0** (исходники `VoidResourcePack/`, сборка `tools/build_mcpack.py`, иконки `tools/make_icons.py`, превью `preview/`): шрифт-глифы иконок устройств и рангов, страница E1 (НЕ E0 — её занимает GeyserJavaGlowing). Иконки маленькие гладкие скачанные; пользователь просил их НЕ менять.
- **VoidSpheres.mcpack 3.2.0**: текстуры 4 сфер, единый стиль (шаблон + золотая окантовка, меняется цвет свечения; перекраска текстур пользователя). Маппинг Geyser — `configs/Geyser-spheres-mapping.json`, ОДИН файл (дубли недопустимы).

## 8. Привилегии, приваты, бусты (сделано, коммиты fa38797 + 24a1ee5)

- Ранги: Странник `strannik` / Герой `geroy` / Рыцарь `rycar` / Барон `baron` / Лорд `lord` / Князь `knyaz` / Император `imperator` (+ стафф `helper`, `moderator`, `sozdatel`; медиа `tiktoker`, `youtuber`). Наследование LP: каждый старший включает младших; веса 10–70; префиксы цветные без скобок с `&r`. Цены в меню доната (осколки): 12/20/95/222/350/540/950; выдача — `points take` + `parent add` (уже в меню).
- Возможности по рангам: дома (`essentials.sethome.multiple.<ранг>`, сеты в Essentials: 2/2/3/4/5/7/10), workbench (Герой), enderchest+keepxp (Рыцарь), feed/heal + буст ×1.2 (Барон), fly + ×1.5 (Лорд), hat/nick + ×2 (Князь), ×3 + приваты (Император). Киты и кулдауны (Князь 3 дня, Император 7 дней) — в LiteKits, НЕ в LP.
- **Все консольные команды** — файл `configs/LuckPerms-ranks-commands.txt` (пользователю давать raw-ссылку: `https://raw.githubusercontent.com/gulp-tech/GeyserPlugins/arena/874b2be9-geyserplugins/configs/LuckPerms-ranks-commands.txt`). Пользователь ввёл их по одной; цепочка наследования и geroy→strannik подтверждены логом. Остаток (права/веса/префиксы) мог быть введён частично — при вопросах проверять `lp group imperator permission info` и `lp group imperator info` (Weight: 70).
- **Приваты (лимит регионов) по группам:** strannik 4, geroy 5, rycar 6, baron 5, lord 6, knyaz 7, imperator 8; helper 4, moderator 4, sozdatel 12; tiktoker 6, youtuber 6. В меню доната строки «Приватов (регионов): N» добавлены только продаваемым рангам. ⚠️ **Плагин приватов у пользователя НЕ WorldGuard** (название он не сказал) — ноды `worldguard.region.max-region-count.*` в файле команд могут не работать; спросить название плагина и дать верные права. ⚠️ Аномалия: rycar 6 > baron 5 при старшем Бароне — похоже на опечатку пользователя, ждём подтверждения (обещал спросить; если подтвердит — поменять в меню и командах).
- `tiktoker`/`youtuber`: в VoidTab заданы показ (`&#55E6FFТиктокер`, `&#FF5555Ютубер`) и иконки E11F/E11E; реальных прав, кроме лимита приватов, не назначалось; групп в LP может ещё не быть (`lp creategroup tiktoker`).
- Меню: `configs/DeluxeMenus-donate.yml` (ранги+магазины), `DeluxeMenus-*.yml` магазинов, `DeluxeMenus-menu.yml` (главное `/menu`), `DeluxeMenus-titles.yml`. В Pl-Hide-Pro (`configs/PluginHidePro-config.yml`, группы default/helper/moderator/staff через `plhide.group.*`) добавлены команды: clan, cases, titles, voidtab, wcity, donate, privshop, podryvnik, vench anvil, combat — в оба списка (commands + tabcomplete). В живой конфиг на сервере пользователь добавлял строки вручную; могла потеряться пара `vench anvil`/`combat` — при жалобах проверять.

## 9. Ядро Purpurbedrock и плейсхолдеры (указание пользователя, коммит 17d5c22)

Ядро само регистрирует в PlaceholderAPI (persist=true, `/papi reload` не сбрасывает):
`%purpurbedrock_device%` (📱 Телефон / 🖥 ПК / 🎮 Геймпад / PlayStation / Xbox / Switch), `%purpurbedrock_input%` (Сенсор / Клавиатура+Мышь / Контроллер / VR, обновляется в реальном времени), `%purpurbedrock_version%` (напр. 1.21.130), `%purpurbedrock_ping%` (число, мс; также проброшен в `Player#getPing()`), `%purpurbedrock_ping_colored%` (§a ≤60, §e ≤130, §c >130), `%purpurbedrock_os%` (Android/iOS/Windows/macOS/Linux/консоли без эмодзи), `%purpurbedrock_protocol%` (685–2193), `%purpurbedrock_ui%` (Pocket/Classic), `%purpurbedrock_xuid%`, `%purpurbedrock_is_bedrock%` (true/false для view/click_requirement).

Уже сделано: в `configs/VoidTab-config.yml` скорборд получил строки «Пинг: %purpurbedrock_ping% мс» и «Версия: %purpurbedrock_version%» (PAPI в строках обрабатывается, `hooks.placeholderapi: true`); в `configs/DeluxeMenus-menu.yml` профиль игрока получил блок «Подключение» (device/input/version/ping) и `view_requirement` `is_bedrock = true`. **Иконки глифов не трогать.** Определение устройства/пинга в КОДЕ VoidTab переписано на API ядра (VoidTab 1.16.0: рефлексия на `BedrockPlaceholderHook.resolveOnline(Player, String)`, ключи device/device_id/os/input/device_model/version/ping/is_bedrock; Floodgate/Geyser остались fallback; проверка — `/voidtab debug` и лог входа). header/footer TAB в VoidTab не существует.

## 10. Очередь задач (по приоритету)

1. **Биллинг → CI → релиз v1.72.0** (см. раздел 2). После релиза пользователь: VoidRtpZone-1.0.0.jar в `plugins/`; VoidVersionsExtension-1.4.0.jar заменить в `plugins/Geyser-Spigot/extensions/` (старый удалить), полный рестарт; в логе — строки «реестр ... добавлено старых протоколов: N».
2. **Проверка захода клиентом Bedrock 1.21.130** после VoidVersions 1.4.0 (патч реестров в рантайме не проверен). Если упадёт — стектрейс сюда, чинить итеративно.
3. Пользователю заменить **VoidCombat 1.1.0 → 1.2.0** (боссбар) — действие на его стороне.
4. **VoidAntiCheat**: пользователь ставит jar (v1.65.0) в `plugins/`, рестарт, фидбэк/лог.
5. Спросить/уточнить: **название плагина приватов** (не WorldGuard) → дать верные права на лимиты приватов всем группам; подтвердить аномалию **rycar 6 / baron 5**.
6. ✅ Сделано (VoidTab 1.16.0): определение устройства/пинга в коде через ядро Purpurbedrock; кик Java при наличии бриджа отключён (ядру он не нужен). После релиза проверить `/voidtab debug` на 2–3 разных устройствах.
7. По желанию пользователя: `/wcity setspawn` отдельно от центра (сейчас спавн = центр).

## 11. Грабли — не повторять

- Jar расширения Geyser в `plugins/` вместо `plugins/Geyser-Spigot/extensions/` — не загрузится (Paper: «does not contain a paper-plugin.yml or plugin.yml»). Уже наступали.
- Многострочная вставка в Pterodactyl-консоль = одна склеенная команда (инцидент с LuckPerms-контекстами на 895 символов и падением сохранения в H2). Только по одной команде; при «мусорных» контекстах лечение — рестарт сервера (в базу не записалось) + ввод по одной.
- Копирование команд из чата портит текст (`&` → `&amp;`, `essentials.fly` → ссылка). Давать raw-ссылку на файл.
- Красный `build.yml` ≠ упала компиляция: смотреть шаги модулей (квота артефактов на upload). Перевыпуск релиза: удалить тег, запушить заново.
- Дубли маппингов сфер недопустимы; стёкла в меню — только ванильные панели; титулы без скобок с `&r`; сферы без свечения и только левая рука; голограмма пыли запрещена.
- Сторонние плагины на сервере (Lmauction/LMstash/LMbuyer/LMRoom, ZNPCsPlus, LiteKits, GeyserEvent, Pl-Hide-Pro, HeadDatabase, CoreProtect, BetterRTP, ViaVersion, AuthMe, Essentials, DeluxeMenus, DecentHolograms и т.д.) — НЕ наши, без просьбы пользователя не трогать; их WARN в логе — не наши задачи.

## 12. Состояние сервера пользователя (по последним логам)

- Панель Pterodactyl (my.byteland.ru), сервер GULP-MAIN, адрес 194.87.209.146:20242. Админ/игрок — MUSD38 (привилегия «Создатель»).
- Последний полный лог был ещё на старом стеке: Purpur 26.2 build 121, Geyser 2.12.0-b1268 (d14f204), UDP 19139; миры world, world2, nether, end. Переезд на Purpurbedrock объявлен пользователем позже — фактическое состояние ядра на сервере не подтверждено логами.
- LuckPerms: хранилище H2; группы рангов существуют, цепочка наследования собрана (подтверждены geroy→strannik, rycar→geroy, imperator→knyaz), старые префиксы вида `[Герой] ` перекрываются нашими setprefix с приоритетами 10–70 по мере ввода.
- VoidWardenCity: точки из points.yml (центр=спавн `world2 -648 -42 -544`), GUI-лута 54 предмета.

*Файл обновлён после коммита `17d5c22`. Если правишь этот файл — пиши частями (одна большая запись уже обрезалась и теряла хвост, коммит c283283).*



