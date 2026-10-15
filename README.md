# GeyserPlugins — плагины FaralMC под Geyser/Floodgate

Плагины пишутся **исключительно для Bedrock-игроков**: связка Purpur 26.2 (ядро на Java 25) +
Geyser + Floodgate. **С Java заходить нельзя** — FaralTab кикает любого игрока без Floodgate
(`bedrock-only` в конфиге), поэтому никаких Java-заглушек вместо иконок в проекте нет:
у всех игроков Bedrock, у всех стоит ресурспак.

## FaralTab — таб / скорборд с кастомными иконками

При входе — большой синий заголовок **FaralMC** на весь экран (`join-title:` в конфиге), а в игре оформление как на референсе (сервер **FaralMC**): компактная тёмная панель,
градиентный заголовок, монеты, осколки, привилегия, У/С и время в игре.
Иконка занимает ровно **один символ строки** — скорборд не становится большим.

![Иконки](preview/icons-preview.png)

![Мокап скорборда](preview/scoreboard-preview.png)

Что показывает (всё настраивается в `FaralTab/src/main/resources/config.yml`):

| Строка | Источник |
|---|---|
| Имя | ник игрока |
| Донат (привилегия) | LuckPerms (или Vault Permission), названия групп — в `groups:` конфига |
| Монеты | Vault Economy (EssentialsX и т.п.) |
| Осколки | **PlayerPoints** |
| У/С | статистика: убийства / смерти (+ `{kd}`) |
| В игре | статистика времени, часы (`{playtime_hours}`, `{playtime}`) |

### Установка

1. **Плагин:** скачайте `FaralTab-1.2.0.jar` из [артефактов GitHub Actions](../../actions)
   (вкладка Actions → последний запуск → `FaralMC-release`) → в `plugins/` Purpur 26.2.
   Для всех функций поставьте рядом: **Vault** + экономика, **PlayerPoints**, **LuckPerms**
   (всё это softdepend — без них плагин стартует, строки покажут `defaults:` из конфига).
2. **Ресурспак:** `FaralResourcePack.mcpack` → в `plugins/Geyser-Spigot/packs/`, рестарт.
   В конфиге Geyser поставьте **`force-resource-packs: true`** — без пака игрок не зайдёт,
   а иконки не превратятся в пустоту. Подробнее: [FaralResourcePack/README.md](FaralResourcePack/README.md).
3. Перезагрузка конфига на лету: `/faraltab reload`. Скрыть/показать таб: `/faraltab toggle`.

### Команды и права

| Команда | Право | Что делает |
|---|---|---|
| `/faraltab reload` | `faraltab.admin` (op) | перезагрузить config.yml |
| `/faraltab toggle [игрок]` | `faraltab.toggle` (всем) / `faraltab.admin` для чужих | вкл/выкл скорборд |
| `/faraltab on / off` | то же | явно включить/выключить |

Обход bedrock-only для тестов админа — право `faraltab.bedrock-bypass`
(по умолчанию нет ни у кого, даже у опов; выдаётся вручную в LuckPerms).

### Сборка вручную

```bash
cd FaralTab && mvn package        # нужен JDK 25, jar в target/
python3 tools/build_mcpack.py     # FaralResourcePack.mcpack в корне
python3 tools/make_icons.py       # перегенерация иконок и превью
```

> Если в Maven-репозитории Purpur ещё нет `purpur-api 26.2-R0.1-SNAPSHOT`,
> в `FaralTab/pom.xml` замените зависимость на
> `io.papermc.paper:paper-api:26.2-R0.1-SNAPSHOT (у Purpur схема версий своя: 26.2.build.<номер>-stable, CI подбирает номер сам)` — плагин использует только
> стандартный Bukkit/Paper API и на Purpur работает так же.

## Структура репозитория

```
FaralTab/               исходники плагина (Maven, Java 25, api-version 26.2)
FaralResourcePack/      исходники Bedrock-пака (manifest.json, font/glyph_E0.png)
FaralResourcePack.mcpack  готовый пак для Geyser (папка packs/)
preview/                превью иконок и мокап скорборда
tools/                  генераторы иконок и сборщик .mcpack
.github/workflows/      CI: собирает jar + mcpack в артефакты при каждом пуше
```
