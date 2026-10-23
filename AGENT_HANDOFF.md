# AGENT HANDOFF — проект VoidPE (GeyserPlugins)

> Ты продолжаешь работу над Minecraft-сервером **VoidPE**. Прочитай этот файл целиком перед любыми действиями. Общайся с пользователем **по-русски**.

## 1. Что за проект

- Bedrock-only сервер **VoidPE** (раньше назывался FaralMC): **Purpur 26.2 (Java 25)** + **Geyser 2.12** + **Floodgate**. Заход с Java запрещён (кикает VoidTab), игроки — только Bedrock через Geyser.
- Репозиторий: https://github.com/gulp-tech/GeyserPlugins , рабочая ветка **`arena/874b2be9-geyserplugins`** (работать и пушить только в неё; PR из неё уже открыт — статус `pr_open`).
- Всё самописное: плагины Paper/Purpur (Maven, Java 25), расширения Geyser (Gradle), ресурспаки Bedrock.
- Релизы: тег `v*` → workflow `release.yml` публикует GitHub Release с **отдельными jar каждого модуля** (пользователь качает прямые ссылки на jar). Последний релиз: **v1.71.0** (15 ассетов). Следующий планируемый: **v1.72.0**.
- Локально в песочнице **нет Java/Maven/Gradle** — компиляция проверяется только GitHub Actions (`build.yml`). При этом `build.yml` в конце падает на upload-artifact из-за квоты артефактов — это норма: смотреть надо **результат шагов сборки модулей**, а не финальный статус рана. Целостность jar локально не проверить (`gh release download` в песочницу не работает).

## 2. ⚠️ Текущий блокер (важно!)

**GitHub Actions заблокирован биллингом аккаунта**: «The job was not started because recent account payments have failed or your spending limit needs to be increased». Джобы даже не стартуют. Пользователь должен починить оплату/лимит в GitHub → Settings → Billing & plans. Пока не починит — **ничего собрать и выпустить нельзя**, теги не ставить (релизный ран упадёт; перевыпуск тогда — удалить тег и запушить заново).

Как только пользователь скажет, что биллинг починен:
1. Дождаться/проверить CI-ран на **последний коммит ветки** (шаг VoidVersionsExtension = success). Ветка `arena/874b2be9-geyserplugins` — старая рабочая; актуальная сессия идёт в `arena/f38c83d1-geyserplugins` (она содержит всё из 874b2be9 + правки 1.4.1).
2. Поставить тег `v1.72.0`, дождаться release-рана, проверить `gh release view v1.72.0` — 15 ассетов, среди них **`VoidVersionsExtension-1.4.1.jar`**.
3. Дать пользователю прямую ссылку и инструкцию (см. раздел 6, задача №1).

## 3. Конвенции разработки

- Плагины: Maven, `src/main/java/ru/voidpe/<модуль>/`, ресурсы `plugin.yml` + `config.yml` с русскими комментариями. Версия — в `pom.xml` (`<version>`), в `plugin.yml` — `${project.version}`.
- Расширения Geyser: Gradle, версия и api — в `gradle.properties`, `extension.yml` собирается из шаблона. Компилируются против Geyser API, к ядру обращаются **только рефлексией** (ставится на любой актуальный Geyser).
- **Всё должно настраиваться в конфигах** (тексты, цвета, иконки, форматы). Цвета: `&a`, `&#RRGGBB`, в плагинах допустим `<gradient:...>`, а в файлах **DeluxeMenus — НЕЛЬЗЯ** (не поддерживает), только одноцветные `&#RRGGBB`.
- При правках плагина Faral* → переименовывать в Void* (уже сделаны VoidItems, VoidTalismans; остальные при их следующих правках). При смене jar старое имя удалить.
- Правки файлов — только точечные (`edit_file`), **по одной правке на файл за раз**, после — grep-проверка маркеров. Массовые правки YAML скриптами уже повреждали файлы; PyYAML не парсит flow-стиль в конфигах.
- Git: коммитить по одному логическому изменению, пушить в `arena/874b2be9-geyserplugins`. Если локальная история git «потерялась» (бывало из-за снапшотов воркспейса): `git fetch origin` → `git reset --hard origin/arena/874b2be9-geyserplugins` (коммиты на GitHub не теряются).
- Пользователю давать **только новые команды**, которые он ещё не применял. При обновлении плагинов напоминать: при смене jar старый удалить; пользователь при обновлениях удаляет старые `config.yml` в `plugins/<плагин>/` (поэтому важные данные нельзя хранить только в config.yml — см. VoidWardenCity points.yml).

## 4. Стек сервера и интеграции

LuckPerms (привилегии; **префикс LP — единый источник правды**: поменял в LP — поменялось в чате (ChatEx), скорборде и нике без перезахода), Vault (монеты), PlayerPoints (валюта «осколки»), Floodgate/Geyser (устройство игрока), WorldGuard (регионы), AuthMe (логин), DeluxeMenus (магазины/меню), Essentials.

Привилегии (группы LuckPerms, цены задавал пользователь, лестницы не выдумывать): Странник `strannik`, Герой `geroy`, Рыцарь `rycar`, Барон `baron`, Лорд `lord`, Князь `knyaz` (кит раз в 3 дня), Император `imperator` (кит раз в 7 дней). Выдача доната — только `lp user <ник> parent add <группа>` (НЕ `parent set`).

Оформление — в стиле скриншотов пользователя (NEVERTIME): тёмная тема, акцентные цвета. Заголовок скорборда — только текст названия сервера (картинку-полосу отклонил; логотип-картинка — только экран загрузки).

## 5. Все модули репозитория

### Плагины Paper/Purpur (папка = модуль, jar в `plugins/`)

| Модуль | Версия | Что делает |
|---|---|---|
| **VoidTab** | 1.16.0 | Скорборд, TAB, ник над головой. Bedrock-only (Java кик, обход — право `voidtab.bedrock-bypass`). Ник: [иконка устройства] [привилегия] название + Ник, снизу здоровье (20 ❤); устройство текстом в TAB. Иконки устройств — глифы из ресурспака (лист 256×256, ячейка 16×16, иконки маленькие гладкие скачанные; kbmouse — только мобильная ОС + клава/мышь, НЕ Windows-ПК). Команды: `/voidtab`. Подключает Vault, LuckPerms, PlayerPoints, Floodgate, Geyser. **1.16.0 — нормальное определение устройств:** модель (`DeviceModel`) теперь читается из клиентских данных сессии Geyser (у `FloodgatePlayer` метода `getDeviceModel()` НЕТ — прежний код был мёртвым), добавлен `UiProfile` (POCKET/CLASSIC) и кэш снимка на 15 с (рефлексия больше не дёргается на каждый рендер ника/скорборда), порядок разбора: модель → ОС → режим ввода → UI; консоли (Xbox на UWP, PS, Switch) распознаются по модели раньше «Windows»; новые категории `linux` и `dedicated` (без своих глифов, показываются текстом в TAB). Проверено тестом на 19 наборах устройства (реальный текст метода). |
| **VoidItems** | 1.8.0 | Кастомные предметы. `/items` — админ-меню выдачи. Предметы: трапка (незеритовый лом, ПКМ, мгновенно клетка по схеме из папки плагина, без файла — 3×3, пол 2 вглубь), лом спавнеров (одноразовый, **сохраняет моба**), пыль (снимает невидимость + белая обводка, БЕЗ голограммы и без счётчика задетых), дезориентация (все эффекты по 5 сек, без счётчика). Изумрудные предметы: крафт как алмазных из изумрудов → результат алмазный (броня Защита III + trim EMERALD/SENTRY, меч Острота III, инструменты Эффективность III), **лор полностью убран**, в донат-магазине их нет. |
| **VoidTalismans** | 1.7.5 | Талисманы + сферы. Талисманы: зачарованный тотем, простые названия, лор только `{stats}`, без курсива, работают **только в левой руке**, магазина нет (кроме донатного DeluxeMenus). `/talisman give`, `/sphere give` — раздельные команды; `/talisman` — только админ-меню; сообщений «одет/статус» быть не должно. Сферы (имена фикс.): «Сфера сумерек», «Сфера пламени», «Сфера бездны», «Сфера пустоты» (урон +4, броня +4, здоровье +4, скорость +5%; 3500 осколков или обмен на яйцо дракона; у пламени −3% скорости — одна сфера обязана иметь минус). Сферы **БЕЗ свечения**, только левая рука. Обмен атомарный: `/sphere exchange <игрок>` (парные команды меню дюпили — нельзя). Магазин: 1300/2600/5200/3500. |
| **VoidEnchants** | 1.2.1 | Кастомные зачарования: Автоплавка, Магнит, Бур. Серверная наковальня `/vench anvil` (нативная Bedrock-наковальня с кастомными чарами не чинится никак — только своё меню + форма расширения). Команды: `/voidenchants`. |
| **VoidCases** | 1.4.2 | Кейсы. Ключи **только виртуальные**. Анимация открытия — снаружи в мире (не в GUI): поставленный кейс не меняет блок, ПКМ открывает, голограмма прячется на время открытия, над крутящейся наградой — её название, урона открытия не наносят, один кейс одновременно открывает один игрок, в сообщениях не упоминать `/cases keys`. Титулы: `/titles` — меню (переключение между имеющимися и снятие). Титулы **БЕЗ скобок**, цвет не перетекает на ник (`&r` в конце; префиксы привилегий тоже с `&r`), видны в чате/скорборде/табе. Команды: `/cases`, `/titles`. В меню — **ТОЛЬКО ванильные стекло-панели** (кастомный филлер отклонён окончательно). |
| **VoidClans** | 1.2.0 | Кланы. `/clan` — меню, `/clan help`. |
| **VoidDrops** | 1.1.0 | Монеты (Vault) с мобов. Бусты по правам: `voiddrops.boost.baron` x1.2, `.lord` x1.5, `.knyaz` x2, `.imperator` x3. `/vdrops`. |
| **VoidAuthSpawn** | 1.2.0 | Точка, куда игрока ставит до/сразу после AuthMe-логина; возврат на прежнее место. `/authspawn set`. Права: `voidauthspawn.admin`, `.bypass`. Данные: `return-locations.yml`. Конфиги AuthMe — в `configs/AuthMe-*.yml`. |
| **VoidCombat** | 1.2.0 | Режим боя (PvP-тег 30 сек), кулдауны предметов, запрет фейерверков в бою, WorldGuard-безопасные зоны, **боссбар боя у обоих участников** (Adventure BossBar, `combat.display.bossbar*` в конфиге) — боссбар появился только в 1.2.0! Состояние: `plugins/VoidCombat/state.yml`. `/combat`, право `voidcombat.admin`. ⚠️ На сервере пользователя всё ещё стоит **1.1.0** (без боссбара). |
| **VoidAntiCheat** | **2.0.1 (собран локально, jar в корне репо)** | Античит ТОЛЬКО для Bedrock/Floodgate (19 проверок). **2.0 — полная переделка под «ноль ложных жалоб»:** улики в буферах (одно событие ≠ VL), компенсация лагов по джиттеру move-пакетов (пинг Geyser часто 0), предсказание вертикали по ванильной модели, rewind-reach (история хитбокса цели + допуск от пинга/скорости цели), широкие исключения окружения (лёд/слизень/мёд/сено/паутина/снег/лестницы/леса/транспорт/элитра/Levitation/Slow Falling/Riptide/поршни/взрывы/урон/опора-сущность), отдельные лимиты тач-ввода и лодок, `fast-attack-server` для совместимости с VoidPvP (сырой CPS там не чит). Слои: детект → коррекция (setback/cancel с устойчивостью) → наказание (по умолчанию **выключено**: `auto-punish: false`, `punishments.commands: []`). `/vac [info <ник>\|alerts\|reset <ник>\|setback <ник>\|reload]`, права `voidanticheat.admin/alerts/bypass(.проверка)`. Проверено: ядро 31/31, интеграционные сценарии 40/40 (25 честных профиля → 0 VL; 15 читов/коррекций). **2.0.1 — исправление падения при включении:** `org.bukkit.Registry` в Paper интерфейс, а в заглушке был классом → `IncompatibleClassChangeError` в `resolveRegistryEntries`; там же нашёлся `PotionEffect` (в Paper класс, в заглушке интерфейс). Теперь все jar’ы проходят `tools/paper_api_audit.py` (сверка с исходниками Paper), а `onEnable` ловит сбой реестров и продолжает работу. См. §6.2. |
| **VoidWardenCity** | 1.4.0 | Древний город Вардена. `/wcity` (или `/wcity tp`) — телепорт (кулдаун `teleport.cooldown-seconds`=300, цена `teleport.cost-points`=250 осколков; обход — `voidwardencity.bypass`). Админ: `/wcity setcenter`, `/wcity setspawn`, `/wcity chest`, `/wcity refill`, `/wcity reload`, `/wcity info`, `/wcity loot` (GUI своих предметов), `/wcity chances` (GUI шансов, кнопки −10/−5/−1/+1/+5/+10). **Центр и спавн хранятся в `plugins/VoidWardenCity/points.yml`** (источник правды; в config.yml только зеркало; есть миграция из старого config.yml и проверка записи перечитыванием). Лут сундуков: ванильная таблица 29 строк в `config.yml` (`loot:` chance/min/max; тримы ward 5%, silence 2%) + предметы из GUI (поверх, `custom-loot.chance-percent`=30); шансы из GUI пишутся в `loot-chances.yml`, предметы — в `loot-items.yml` (эти файлы НЕ в репозитории — генерируются на сервере). Сундук добирается до `rolls-min/max` = 5–10 наград. Интервал обновления лута по онлайну: 25+ игроков → 5 мин, 10+ → 10 мин, меньше → 20 мин. На сервере уже настроено: центр и спавн `world2 -648 -42 -544` (совпадают; если спавн нужен отдельно — `/wcity setspawn` в нужной точке). |

### Расширения Geyser (Gradle; установка ТОЛЬКО в `plugins/Geyser-Spigot/extensions/`, НЕ в `plugins/`!)

| Модуль | Версия | Что делает |
|---|---|---|
| **VoidAnvilExtension** | 1.0.0 | Bedrock-форма наковальни (Cumulus): команда `/voidanvil anvil` → Bedrock-игроку форма, внутри вызывает `/vench anvil`. Образец по Cumulus 1.1.2: кнопки без callback + `validResultHandler`/`clickedButtonId()` (`.button(String, lambda)` против 1.1.2 НЕ компилируется). |
| **VoidPvP** | **1.0.2** | Fast crits для PvP: поднимает базовый `ATTACK_SPEED` (1024) — нет задержки атаки, удар всегда полный; крит в прыжке/падении, **включая спринт** (в ваниле спринт крит отменяет) — урон ×1.5 + частицы/звук с сервера; после удара через тик сбрасывается `noDamageTicks`, поэтому «тап-тап» проходит полностью (ванильные 0.5 с неуязвимости не мешают). Ванильный крит (падение без спринта) не удваивается. Стрелы, sweep, огонь не трогает. Права: `voidpvp.use` (команда), `voidpvp.admin` (reload), `voidpvp.off` (отключить игроку); `permissions.require` — выдавать только по праву. Миры-исключения — `worlds.disabled`. Команда `/voidpvp [status|reload]`. Конфиг-эталон: `configs/VoidPvP-config.yml`. **Совместимость с VoidAntiCheat 2.0**: конфликт снят в самом античите — при включённом VoidPvP он определяет режим без кд атаки (`fastAttackServer()`) и не считает сырой CPS автокликером, проверяя только ровность интервалов. Собран под Purpur API 26.2 (api-version 26.2, Java 25), добавлен в `build.yml` и `release.yml` (16-й ассет). **jar лежит в корне репо (`VoidPvP-1.0.2.jar`, 14.6 КБ; версии 1.0.0 и 1.0.1 НЕ ставить, см. §6.3)**: собран локально ECJ против заглушек Bukkit с точными дескрипторами (проверено парсером constant pool — все ссылки на `org/bukkit/*` совпадают с реальным API), байткод 65. На живом сервере ещё НЕ запускался. После починки CI jar из репозитория удалить и брать из релиза. |
| **VoidQuiet** | 1.0.0 | Мини-плагин «тишина в чате»: глушит сообщения входа (`PlayerJoinEvent.setJoinMessage(null)`), выхода (`PlayerQuitEvent.setQuitMessage(null)`) и смертей/убийств (`PlayerDeathEvent.setDeathMessage(null)`) на приоритете HIGHEST — то есть перебивает Essentials независимо от его конфига. Дополнительно выключает ванильный геймрул `showDeathMessages` во всех мирах (при старте, при `WorldLoadEvent` и после reload), потому что часть строк о смерти шлёт само ядро. Вместо скрытия можно задать своё сообщение (`join.message` / `quit.message` / `death.message`, пусто = скрыть); плейсхолдеры `{player}` (display-name со значками), `{username}` (чистый ник), `{online}`, `{killer}`, `{weapon}`, `{world}`. Исключения по мирам — `worlds.disabled`. Команда `/voidquiet [status\|reload]` (алиас `/vquiet`). Проверено тестом: 13 проверок (скрытие, свои сообщения, выключенный плагин, мир-исключение, отдельные флаги, формат, hex-цвета). Собран локально ECJ против заглушек с точными дескрипторами; вписан в `build.yml` и `release.yml`. **jar в корне репо: `VoidQuiet-1.0.0.jar`** (10.8 КБ). |
| **VoidVersionsExtension** | **1.5.0 (собран локально, лежит в корне репо)** | Расширяет вход Bedrock с нативных 26.30–26.52 до **1.20–26.52**. На `GeyserPreInitializeEvent`: рефлексией сканирует кодеки cloudburst (`org.cloudburstmc.protocol.bedrock.codec.v<протокол>.Bedrock_v<протокол>`, диапазон из конфига, отсечка `min-version`), санитизирует и дописывает в списки `GameProtocol` (SUPPORTED_BEDROCK_CODECS/PROTOCOLS/VERSIONS). **1.4.2 ПРОВЕРЕНА НА ЖИВОМ КЛИЕНТЕ (23.10.2026, Bedrock 1.21.130 / протокол 898): вход, логин, мир, скорборд, чат, голограммы и предметы в хотбаре — работают.** Оставался один дефект: геометрия мира рисовалась «пластинами» — клиенту 898 отдавалась палитра блоков 26.30 (runtime id не совпадали; предметы были корректны, т.к. маппинг идёт через java-состояния). **В 1.5.0 сделан даунгрейд палитры**: в jar лежит `palette/block_palette.1_21_110.nbt` из Geyser `4d1c5f3e` (MIT; в том Geyser эта палитра обслуживала ровно 844/859/860/898), на PostInitialize `BlockRegistries.BLOCKS` для 844–898 регистрируется копия `BlockMappings`, где `javaToBedrockBlocks`/`javaToVanillaBedrockBlocks`/одиночные блоки/`stateDefinitionMap`/`itemFrames`/`structureBlockStates`/кастомные блоки/`jigsawStates`/`remappedVanillaIds` пересчитаны на runtime id 1.21.110. Копия создаётся через `sun.misc.Unsafe.allocateInstance`, final-поля пишутся `objectFieldOffset`+`putObject`; исходный `BlockMappings(898)` не мутируется. Ключ соответствия — `name|sorted states` (числа канонизируются), поиск: точное совпадение → по имени → воздух. Покрытие (проверено на данных): 15845/16913 = 93.7 % блоков 26.30 совпадают с 1.21.110 точно, 1068 новых (циннабар, сера и их плиты/стены) в 1.21.130 отсутствуют → станут воздухом. `bedrockRuntimeMap` не пересобирается (он индексируется по 26.30 id и в игровом пути Geyser не используется — только `getBedrockBlockDefinition(int)`, который никто не зовёт; пересобрать нельзя, т.к. конструктор блока требует `NbtMap`, а палитра разобрана в обычные Map). Конфиг: `legacy-palette=true` (выключить пересчёт), `legacy-palette-only=true` (регистрировать только протоколы, покрытые палитрой; в логе `/geyser version` — счётчик `skippedNoPalette`). NBT читается своим ридером (`LegacyBlockPalette`: gzip + big-endian, теги 1–12), чтобы не зависеть от cloudburst. **В 1.4.2 добавлены заглушки пакетов**: Geyser 2.12 шлёт при входе `VoxelShapesPacket` (есть только с протокола 924) — для старых версий кодек получает заглушку (свободный номер < 0x7FF + пустой сериализатор), иначе `NullPointerException` в `BedrockPacketCodec.getPacketId` → «An internal error occurred!». **В 1.4.1 санитизация своя** (`Sanitizer`): берутся только версионно-независимые `ILLEGAL_SERIALIZER`/`IGNORED_SERIALIZER` из `CodecProcessor` и применяются поштучно (нет такого пакета в версии — правило молча пропускается), `PartyChangedPacket` глушится с протокола 944. Штатный `processCodec` для добавляемых версий больше НЕ вызывается: он подменяет сериализаторы номерами версий Geyser (v291/v712/v748/v975) — для чужой версии протокола это чужой формат (битый инвентарь/экипировка), а на протоколах 589/594 ещё и падал на `RefreshEntitlementsPacket`. Протоколы новее `DEFAULT_BEDROCK_PROTOCOL` не регистрируются (у core нет под них ни переводчиков, ни палитр), в логе есть счётчик пропущенных. Через Unsafe переписывает `VersionCommand.SUPPORTED_BEDROCK_RANGE`, чтобы `geyser version` показывал наши версии (формат — `version-command-format=full|range`). **Патч реестров**: на PostInitialize/PostReload в `BlockRegistries.BLOCKS`, `Registries.ITEMS`, `Registries.TAGS` для старых протоколов регистрируются маппинги младшей родной версии (иначе вход умирал на LoginPacket: «No appropriate value for version: 898»). Конфиг: `plugins/Geyser-Spigot/extensions/voidversions/config.properties` (enabled, min-version, scan-from/to-protocol, disabled-protocols, verbose, **sanitize**, **version-command-format**); недостающие ключи дописываются в уже существующий файл. Известный предел: старые клиенты зайдут, но получат палитру блоков/предметов 26.30 — блоки и предметы новее их версии могут отображаться не тем, чем являются (для реальной поддержки 1.20 нужен отдельный этап — палитры из старых Geyser 2.x и по-версионные ITEMS/TAGS). API расширения 2.11 на Geyser 2.12 — совместим (проверено по исходнику ApiVersion). |

### Ресурспаки

| Файл | Версия | Что внутри |
|---|---|---|
| **VoidResourcePack.mcpack** (исходники: папка `VoidResourcePack/`) | 17.0 (header [1,17,0]) | Главный пак: шрифт с глифами иконок устройств (лист 256×256, ячейка 16×16), иконки рангов и прочее. Сборка: `tools/build_mcpack.py`, иконки: `tools/make_icons.py`. Превью: папка `preview/`. |
| **VoidSpheres.mcpack** | 3.2.0 | Текстуры 4 сфер: ЕДИНЫЙ стиль — один шаблон с золотой окантовкой, меняется только цвет свечения (перекраска текстур пользователя; свои нарисованные пользователь отклонял). Маппинг сфер Geyser: `configs/Geyser-spheres-mapping.json` — ОДИН файл, дубли (`spheres.json` рядом) недопустимы. |

### Папка `configs/` (готовые конфиги для сервера, не плагины)

DeluxeMenus: `DeluxeMenus-donate.yml` (донат-магазин: разделы — кастомные предметы, зелья, кирки, талисманы, сферы; иконка раздела сфер — сфера с настоящей текстурой; выдачи только `parent add`), `DeluxeMenus-shop*.yml`, `DeluxeMenus-titles.yml`, `DeluxeMenus-menu.yml`; AuthMe: `AuthMe-config.yml`, `AuthMe-commands.yml`, `AuthMe-messages_ru.yml`; плюс BetterRTP, Essentials, LiteBans, LiteKits, PlayerPoints, SkinsRestorer, PluginHidePro, Geyser (`geyser-config.yml`, `Geyser-custom-skulls.yml`), `VoidTab-config.yml` (эталонный конфиг), `messages_ru.properties`. Файлы раскладываются вручную по папкам соответствующих плагинов.

## 6. Очередь задач (по приоритету)

1. **Сразу после починки биллинга GitHub Actions пользователем:** проверить CI-ран на последний коммит ветки (шаг VoidVersionsExtension — success), удалить из репозитория `VoidVersionsExtension-1.5.0.jar` (он лежит там временно, т.к. релизные ассеты из песочницы не загружаются), поставить тег **v1.72.0**, проверить релиз: 15 ассетов, есть `VoidVersionsExtension-1.5.0.jar`. Пользователь заменяет jar в `plugins/Geyser-Spigot/extensions/` (старый удалить), **полный рестарт** (не reload). Подтверждение в логе: строки вида «реестр BlockRegistries.BLOCKS: добавлено старых протоколов: N» (также ITEMS и TAGS). Затем проверка заходом реальным клиентом **Bedrock 1.21.130** (протокол 898). Если упадёт дальше — пользователь пришлёт стектрейс, чинить итеративно (это ожидаемо: слои несовместимости могут всплывать по одному).
2. **VoidPvP 1.0.2**: удалить старый jar, положить `VoidPvP-1.0.2.jar`. 1.0.0 падала при включении (`IncompatibleClassChangeError: World`), 1.0.1 не переприменяла атрибут — §6.3.
3. **VoidCombat на сервере стоит 1.1.0** — пользователь должен заменить jar на 1.2.0 (релиз v1.66.0), чтобы появился боссбар боя. Старый jar удалить, в папке должен быть только один.
3. **VoidAntiCheat 2.0.1**: удалить старый jar античита из `plugins/`, положить `VoidAntiCheat-2.0.1.jar` (версия 2.0.0 падала при включении — не ставить), **полный рестарт** (не reload). Наказания выключены по умолчанию — сначала неделя алертов. При ложняке: `/vac info <ник>` + строка алерта (`проверка VL=… — деталь`), затем `settings.enforcement: false`. Ранее «не работает он» объяснялось тем, что плагина не было в `plugins/`. Подробности — §6.2.
4. По желанию пользователя: `/wcity setspawn` в точке, отличной от центра (сейчас спавн = центр города).

## 7. Грабли — не повторять

- **Jar расширения Geyser в `plugins/` вместо `plugins/Geyser-Spigot/extensions/`** — Paper отклонит его («does not contain a paper-plugin.yml or plugin.yml»), расширение не загрузится. Уже наступали.
- Пользователь при обновлениях **удаляет старые config.yml** — критичные данные держать вне config.yml (образец: points.yml у VoidWardenCity).
- Массовые правки YAML heredoc/скриптами повреждали файлы; PyYAML не парсит flow-стиль (`- {material: ...}`). Только точечные правки, по одной на файл, потом grep-проверка.
- Нативная Bedrock-наковальня с кастомными чарами **не чинится** ни PrepareAnvilEvent, ни расширением — только серверное меню VoidEnchants + Cumulus-форма VoidAnvilExtension. В Cumulus 1.1.2 `.button(String, lambda)` не компилируется: кнопки без callback + `validResultHandler`/`clickedButtonId()`.
- Дубли файлов маппинга сфер недопустимы — один `configs/Geyser-spheres-mapping.json`.
- Красный `build.yml` ≠ упала компиляция: смотреть шаги модулей (квота артефактов). Перевыпуск упавшего релиза: удалить тег и запушить заново (`gh run rerun`/`workflow run` недоступны).
- Локальной Java/Maven/Gradle в песочнице нет — **но расширения Geyser можно собрать локально** (проверено 23.10.2026 на 1.4.1):
  JRE берётся из PyPI (`python3 -m venv jv && jv/bin/pip install jdk4py`, JDK 25, но **без `javac`** — jdk.compiler вырезан), компилятор — Eclipse ECJ из npm-пакета `exoproc-jjit` (`package/deps/ecj-3.46.100.jar`, запускается на этом JRE: `java -jar ecj.jar -source 21 -target 21 ...`). Geyser API в песочнице нет, поэтому пишутся **заглушки** (`Extension` с default `dataFolder()`/`logger()`, `ExtensionLogger`, три lifecycle-события, `@Subscribe`) строго по исходникам Geyser `d14f204`. После компиляции проверить: в jar только `ru/voidpe/**` + `extension.yml` (никаких `org/geysermc`), байткод major 65 (Java 21), в пуле констант только реальные дескрипторы API. **Плагины Paper так собрать нельзя** — их зависимости (Purpur/Paper API) в песочницу не скачать.
- **Ассеты релиза из песочницы загрузить нельзя**: `uploads.github.com` заблокирован (SSL EOF), `gh release create <tag> <file>` падает на аплоаде, `api.github.com/.../assets` даёт 404. Обход, пока CI мёртв: коммитить готовый jar в корень репозитория (прецедент — `VoidResourcePack.mcpack`, он там специально) и давать ссылку `https://github.com/gulp-tech/GeyserPlugins/raw/<ветка>/<файл>.jar`. После первого CI-релиза такие jar из репозитория удалить.
- `github.com` (HTML/raw) из песочницы отдаёт 404 на любой путь, `raw.githubusercontent.com` вообще недоступен — проверять ссылки для пользователя через `gh api repos/.../contents/<path>` (там есть `download_url` и размер), а не curl'ом страницы.
- Снапшот воркспейса периодически теряет локальную историю git: восстановление — `git fetch origin` + `git reset --hard origin/<ветка>` (на GitHub коммиты целы). **Важно:** при этом `.git` может откатиться до «Initial commit», а файлы в рабочей папке остаются новыми — `reset --hard` их ПЕРЕЗАПИШЕТ. Поэтому сначала скопировать изменённые файлы в `/tmp`, потом сбрасывать, потом вернуть. Проверка целостности: `git status --short` должен показывать только реально изменённые тома. Бывает и так, что текущая ветка в сессии (`arena/f38c83d1-geyserplugins`) новее «рабочей» `arena/874b2be9-geyserplugins` — сверять `git log` обеих.

## 6.1. Как убрать сообщения входа/выхода и смертей (23.10.2026)

Ни один наш плагин их не шлёт (проверено: VoidTab/VoidCombat/VoidClans/VoidAuthSpawn только слушают события и ничего не бродкастят). Источники — EssentialsX и ванильный геймрул:

- **Вход/выход** — `plugins/Essentials/config.yml`: `hide-join-quit-messages-above: 0`. В исходнике `EssentialsPlayerListener` эта проверка идёт ПЕРВОЙ (`hideJoinQuitMessages() || ...`), поэтому скрывает сообщение даже если задан `custom-*-message`. Значение -1 возвращает сообщения.
- **Смерти** — там же `death-messages: false` (свои сообщения Essentials) + ванильный геймрул `showDeathMessages=false` в каждом мире (`/execute in minecraft:overworld run gamerule showDeathMessages false` и т.д.). Одно без другого неполно: геймрул глушит ванильные строки, настройка Essentials — его собственные.
- Приветствие первого входа шлёт **AuthMe** (`configs/AuthMe-commands.yml`, строки 33 и 43, `broadcast ... впервые зашёл`) — если нужно убрать и его, правится там.
- Формат: в `custom-*-message` `{PLAYER}` подставляет display-name (у нас в нём значки устройства и привилегии из VoidTab), `{USERNAME}` — чистый ник. Если сообщения нужны, но без значков — использовать `{USERNAME}`.

В `configs/Essentials-config.yml` уже выставлены `death-messages: false` и `hide-join-quit-messages-above: 0`.

## 7.1. GeyserReversion — проверено 23.10.2026, с Geyser 2.12 НЕ совместим

Пользователь предложил попробовать [GeyserReversion](https://github.com/oryxel1/GeyserReversion) вместо самодельного даунгрейда палитры. Разбор исходников (ветка `ouranos`, релиз Build 5 от 30.05.2026):

- Расширение собрано против `org.geysermc.geyser:core:2.10.0-SNAPSHOT`, `api: 2.9.10`, репозиторий **архивирован** (21.09.2026), README прямо пишет «This will flag anticheats».
- `TARGET_CODEC = Bedrock_v944.CODEC` (26.10) — захардкожен: любой клиент «представляется» Geyser как протокол **944**.
- На `GeyserPostInitializeEvent` **пересоздаёт RakNet-сервер** Geyser: рефлексия по приватным полям `org.geysermc.geyser.network.netty.GeyserServer` (`bootstrapFutures`, `group`, `childGroup`, `playerGroup`), плюс instrumentation-агент и class transformers (`BaseBedrockCodecHelperTransformer`).
- В `libs/ouranos-1.0-all.jar` есть канонические палитры блоков **v361…v944** (48 версий, включая v898) — перевод блоков настоящий, не косметика.

**Почему не взлетит на нашем Geyser 2.12 (d14f204):**
1. В 2.12 пакет `org.geysermc.geyser.network.netty.*` переименован в `network.bedrock.raknet.*`, класса `GeyserServer` больше нет — его заменил `RaknetServer` (`GeyserImpl.geyserServer`). Импорты в `ServerUtil` → `NoClassDefFoundError` на PostInitialize.
2. В 2.12 зарегистрированы только `v1001` (26.30+), `v2168` (26.40+), `v2169`, `v2193` (26.50+). Кодека **v944 нет** → даже с пропатченными классами Geyser отверг бы протокол 944.

**Главная находка (важнее самого Reversion):** Geyser **2.10** регистрирует `Bedrock_v898` для версий «1.21.130»–«1.21.132» и везёт `block_palette.1_21_130.nbt` именно для v898. То есть клиент пользователя (1.21.130 / 898) поддерживается **нативно**, без палитровых хаков — достаточно откатить Geyser на 2.10 (вместе с Floodgate того же билда), удалить VoidVersionsExtension и, если понадобится, понизить `api:` у VoidAnvilExtension с 2.11.0 на 2.10.0 (использует только Cumulus/команды/lifecycle — проверено по импортам).

GeyserReversion имеет смысл только поверх Geyser **2.10** и только если нужны клиенты **старше** 1.21.130 (до 1.12): он переводит 26.10 → 898 и ниже. С VoidVersionsExtension несовместим по определению (оба перехватывают протоколы/реестры) — ставить вместо него, не вместе.

**Скачать из песочницы нельзя:** `release-assets.githubusercontent.com` (ассеты релизов), `repo.opencollab.dev`, `repo1.maven.org`, `api.modrinth.com` заблокированы — jar и сборку проверить нечем, только чтение исходников через `gh api`/codeload.

## 6.2. VoidAntiCheat 2.0 — что сделано и как проверено (23.10.2026)

Файлы: `VoidAntiCheat/src/main/java/ru/voidpe/anticheat/` — `VoidAntiCheat.java` (ядро),
`ViolationState`, `LagCompensation`, `PredictionMeter`, `TargetHistory`, `EnvironmentSnapshot`,
`SpeedBudget`, `PlayerData`, `CheckSettings`; конфиг `src/main/resources/config.yml`,
эталон для сервера — `configs/VoidAntiCheat-config.yml`; jar — `VoidAntiCheat-2.0.0.jar` в корне.

Главные решения (обоснование — в шапке конфига и `VoidAntiCheat/README.md`):

- **Одно событие ≠ VL.** Буфер на проверку; повторы чаще `sample-gap-*-ms` весят 0.25.
  BadPackets (`buffer: 0`) корректируется сразу — там сама улика и есть доказательство.
- **Setback/cancel — только при устойчивости** (`setback-sustain-ms: 700`, `cancel-sustain-ms: 250`)
  и только на стабильном канале (`LagCompensation.unstable()` блокирует коррекции).
- **Наказание — только при** VL выше порога ≥20 с, ≥2 активных проверках, пинге ≤400 и TPS ≥17.
  По умолчанию выключено полностью.
- **Исправленные баги прошлой итерации:** `EnvironmentSnapshot.stepExempt()` содержал
  `jumpBoost >= 0` (всегда true) — проверка STEP была мёртвой, теперь `> 0`; слизень/мёд/недавний
  урон добавлены в исключения Speed и вертикали; `getFallDistance()` — **float** (не double);
  `Player#leaveVehicle()` — **boolean** (было `void` → был бы NoSuchMethodError при setback из лодки);
  `PlayerArmSwingEvent#getHand()` вместо несуществующего `isOffHand()`;
  `flag()` ограничивает VL значением `max-vl`; авто-кликер больше не глушится кулдауном
  (`markAction` убран) — иначе буфер не набирался за разумное время.
- **Серверный импульс** (`velocity.getY() > movement.server-launch-velocity-y`) отключает
  вертикальные проверки: винд-чарж, riptide, поршни, прыжковые платформы и любые плагины
  через `setVelocity` больше не дают ложный Fly. Клиент это значение не контролирует.

Тестовые стенды (вне репозитория, `/tmp`; при потере пересоздавать по рецепту §7):

- `CoreTest` — 31/31: чистые классы (LagCompensation, PredictionMeter, SpeedBudget,
  ViolationState, TargetHistory, EnvironmentSnapshot), 11 честных профилей, 5 читов,
  лаг-профили, rewind, стресс 2000 профилей.
- `PluginIT` — **40/40** (25 честных → 0 VL, 15 читов/коррекций): настоящие обработчики плагина на фейковом Bukkit
  (`FakeWorld/FakePlayer/FakeBoat`, `MapConfig`, `TestPlugin extends VoidAntiCheat`,
  `peek(Player)`, конфиг генерируется из YAML скриптом в `ConfigData.java`).
  Честные (0 VL): ходьба, спринт, 60 спринт-прыжков, вода, слизень, лёд 9 бл/с, лестница,
  пузырьковый столб, паутина, порошковый снег, леса, Jump Boost II, Slow Falling, лодка на
  синем льду 20 бл/с, стояние на сущности, 2 пакета на тик, серверный импульс velocity Y=0.9,
  тач-reach 3.65, 12 CPS, 3000 тиков случайного профиля, лаг-спайк 1.3 с, телепорт 700 бл.,
  нокбэк о землю, одиночный рывок 0.9 бл.
  Читы: Fly, Hover, Speed ×2.8, Reach 4.4/6.5, Step, NoFall, NoClip, Spider, AutoClicker 60 CPS,
  BadPackets, BlockReach, off-hand.
- Компиляция и прогон: ECJ 3.46 на JRE из `jdk4py` (`-source 21 -target 21`, заглушки Bukkit),
  jar — python `zipfile` + `MANIFEST.MF`; в jar только `ru/voidpe/**`, `plugin.yml`, `config.yml`,
  байткод major 65. Дескрипторы API сверены с исходниками Paper
  (`getFallDistance()F`, `leaveVehicle()Z`, `getHand()`, `getNearbyEntities(BoundingBox)`,
  `Registry.ATTRIBUTE/EFFECT/ENCHANTMENT`, `getTPS()[D`, `getCurrentTick()I`) — важно, потому что
  заглушки легко «придумывают» несуществующую сигнатуру.

Порядок установки: удалить старый `VoidAntiCheat-*.jar` из `plugins/`, положить новый, полный
рестарт. Первые дни — смотреть алерты; при ложняке сначала `settings.enforcement: false`,
затем сообщить проверку/детали из алерта (`<проверка> VL=… — деталь`).

## 6.3. Почему локально собранные jar падали: класс vs интерфейс (23.10.2026)

VoidAntiCheat 2.0.0 на живом сервере упал при включении:

```
IncompatibleClassChangeError: Found interface org.bukkit.Registry, but class was expected
    at ru.voidpe.anticheat.VoidAntiCheat.resolveRegistryEntries(VoidAntiCheat.java:195)
```

Причина не в коде, а в **заглушке**: в Paper `org.bukkit.Registry` — интерфейс, а в заглушке был
`final class`. ECJ поэтому скомпилировал `invokevirtual` вместо `invokeinterface`, и JVM отказалась
связывать вызов. Там же нашлось второе такое же расхождение: `org.bukkit.potion.PotionEffect` в Paper —
класс, а в заглушке интерфейс (`getAmplifier()` компилировался как `invokeinterface`).

Что сделано, чтобы это не повторилось:

1. `tools/paper_api_audit.py` — читает пул констант jar и сверяет **каждую** ссылку на
   `org.bukkit` / `io.papermc` / `com.destroystokyo` с исходниками PaperMC/Paper: вид типа
   (class/interface/enum → ловит IncompatibleClassChangeError), сигнатуру члена с учётом
   наследования (ловит NoSuchMethodError) и статичность полей. Запускать после **каждой**
   локальной сборки, код возврата 1 = jar ставить нельзя.
2. `tools/localbuild/stubs.py` — генератор заглушек (виды типов правильные, зафиксированы в одном
   месте), `tools/localbuild/gen_config_data.py` — снимок config.yml для тестов,
   `tools/localbuild/smoke/SmokeIT.java` — стенд: 8 честных профилей → 0 VL, 8 читов ловятся (18/18).
   Подробности и команды — `tools/README.md`.
3. `onEnable` теперь ловит `Throwable` вокруг чтения реестров: если API снова изменится, античит
   продолжит работать на консервативных дефолтах, а в лог уйдёт одна строка WARN, вместо
   «Everything is broken» и выключения плагина.

Тем же аудитом проверены остальные jar в корне: **VoidPvP-1.0.0 был сломан так же**
(`World#getName`, `World#spawnParticle`, `Registry#get` компилировались как вызовы класса) —
пересобран как **VoidPvP-1.0.1.jar**. VoidQuiet 1.0.0 и VoidVersionsExtension 1.5.1 — чисто.

### 6.3.1. VoidPvP 1.0.2: почему «не убирает задержку атаки»

Лог с сервера (`Task #178 for VoidPvP v1.0.0 generated an exception … IncompatibleClassChangeError:
Found interface org.bukkit.World, but class was expected at VoidPvP.applyTo(VoidPvP.java:112)`) показывает,
что в 1.0.0 задача падала на первой же строке `applyTo` (`player.getWorld().getName()`) — до
`setBaseValue` дело не доходило ни разу. Поэтому атрибут ATTACK_SPEED оставался ванильным (4.0), и
задержка атаки была как в 1.9+, хотя плагин «включён».

В 1.0.2 дополнительно к исправленной сборке:

- `Attribute.ATTACK_SPEED` берётся константой API (реестр — только как резерв): не зависит от
  переименований `attack_speed` / `generic.attack_speed`;
- задача `refreshAll()` раз в `attack-speed.refresh-ticks` (40 тиков) переприменяет значение —
  если другой плагин или ядро пересоберут атрибуты, задержка вернётся не более чем на 2 секунды;
- самопроверка: если `getValue()` после установки меньше половины запрошенного, в лог уходит ОДНА
  строка WARN «Сервер не применил ATTACK_SPEED…» (раньше молчало);
- `/voidpvp status` игроку показывает фактический `ATTACK_SPEED` и задержку в тиках
  (при 1024 это 0.02 тика — то есть задержки нет).

### 6.3.2. Город Вардена: защита блоков (VoidWardenCity 1.4.1)

Защита в плагине была, но по умолчанию выключена (`protection.prevent-break: false`), поэтому
город разбирали. В 1.4.1:

- `protection.enabled` — мастер-выключатель, по умолчанию **true** (старые ключи
  `prevent-break` / `prevent-place` больше не читаются);
- обход — право из `protection.bypass-permission` (по умолчанию `voidwardencity.admin`);
- закрыты все способы испортить город: ломка, установка, взрывы (крипер/TNT/кровать/витер) на
  **любых** блоках (раньше — только сундуки), поршни, огонь (поджог и распространение),
  `EntityChangeBlockEvent` (эндермены и прочие), поток воды/лавы (`BlockFromToEvent`);
- антиспам сообщения «здесь нельзя строить» — не чаще раза в `protection.message-cooldown-seconds`.

**До сборки нового jar** защиту можно включить на текущей версии: в
`plugins/VoidWardenCity/config.yml` поставить `prevent-break: true` и `prevent-place: true`, затем
`/wcity reload`. Это закрывает ломку, установку и защиту сундуков от взрывов; поршни/огонь/эндермены
закрывает только 1.4.1.

Проверка перед установкой (быстро, без сервера):

```bash
python3 tools/paperapi_audit.py VoidAntiCheat-2.0.1.jar VoidPvP-1.0.2.jar VoidQuiet-1.0.0.jar VoidNick-1.0.0.jar
```

`tools/paper_api_audit.py` оставлен обёрткой над `paperapi_audit.py` (старое имя из документации
продолжает работать). Аудитор учитывает наследование интерфейсов (`Audience.sendMessage`),
вложенные типы (`EntityDamageEvent$DamageCause`) и enum-константы, поэтому не шумит ложными
срабатываниями. Код возврата: 0 — чисто, 1 — есть расхождения, 2 — часть типов не проверена
(adventure-классы вне paper-api).

### 6.3.3. VoidNick 1.0.0 — смена ника «с нуля» (только консоль)

Задача: игрок хочет начать заново под другим ником. Прокси (Velocity/Bungee) на сервере нет,
поэтому **настоящее имя аккаунта и UUID не меняются** — плагин подменяет ник на стороне сервера
(чат, таб-лист, скорборд, вход/выход) и стирает всё, что делало игрока «старым». В F3 и в логах
ядра остаётся настоящее имя.

Команды (из консоли; в игре прав нет намеренно):

```
/voidnick set <старый> <новый>   переименовать и сбросить
/voidnick revert <ник>           вернуть ник, права и данные целиком
/voidnick info <ник>             что записано по игроку
/voidnick list                   все переименованные
/voidnick history [ник]          журнал операций
/voidnick reload                 перечитать конфиг
```

Порядок `set`: снимок (группы LuckPerms + баланс) → архив файлов
`playerdata/`, `stats/`, `advancements/` в `plugins/VoidNick/archives/<дата>/` → сброс: OP,
`lp user <ник> clear` + `lp user <ник> parent add <default-group>`, обнуление баланса,
команды из `reset.commands` → запись в `data.yml` и `journal.yml` → сообщение в консоль.
`revert` возвращает OP (флаг `was-op`), группы/primary-группу, баланс (`deposit`), файлы из
архива и выполняет `reset.restore-commands`.

Ограничения, о которых надо помнить:

- ник в LuckPerms/экономике ищется по **настоящему** имени — так и задумано, команды идут по нему;
- жёстко прописанные имена в чужих YAML (регионы, дома Essentials и т.п.) автоматом не правятся —
  для этого есть `reset.commands` / `reset.restore-commands`;
- файлы `playerdata` нельзя переносить у играющего игрока → `rename.require-offline: true`;
- `world:` в `config.yml` задаёт мир, из которого берутся `playerdata/stats/advancements`
  (пусто — первый загруженный мир).

Проверено: ECJ-компиляция против заглушек (`tools/localbuild/bukkit_stubs.py`) + аудит
`tools/paperapi_audit.py` — расхождений с настоящим Paper API нет; jar `VoidNick-1.0.0.jar`
собран (7 классов).

## 8. Состояние сервера пользователя (по последнему логу)

Purpur 26.2 build 121, Geyser 2.12.0-b1268 (git-master-d14f204), UDP-порт 19139, миры: world, world2, nether, end. Игрок/админ — MUSD38 (привилегия «Создатель»). VoidWardenCity: точки загружены из points.yml — центр и спавн `world2 -648 -42 -544`, GUI-лута загружено 54 предмета. На сервере также стоят **чужие плагины (не наши, не трогать)**: Lmauction/LMstash/LMbuyer/LMRoom, ZNPCsPlus, LiteKits, GeyserEvent, Pl-Hide-Pro, HeadDatabase, CoreProtect, BetterRTP, ViaVersion, AuthMe, Essentials, DeluxeMenus, DecentHolograms и т.д. Их WARN в логе (duplicate keys у Lmauction, дубль UUID пака GeyserEvent, пустой префикс Floodgate) — не наши задачи, если пользователь не попросит.