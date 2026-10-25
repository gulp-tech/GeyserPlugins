# Промпт: создание плагина VoidRtpZone

Скопируй блок ниже целиком и отдай агенту/разработчику, у которого есть среда сборки (JDK 25 + Maven).

---

## ПРОМПТ

Создай плагин **VoidRtpZone 1.0.0** для Minecraft-сервера: Purpur 26.2, Java 25, Maven. Сервер Bedrock-only (игроки с телефонов/консолей), плагин обычный Paper/Purpur (Bukkit API), один jar на выходе.

### Что делает плагин

Админ выделяет предмету две точки кубоида и сохраняет зону. Когда обычный игрок **заходит в зону**, плагин автоматически выполняет за него команду `/rtp` (случайный телепорт). Нужно, чтобы игроки, попавшие в определённое место (например, на спавн-локацию), сразу разлетались по миру.

### Структура проекта

- Maven-проект, артефакт `VoidRtpZone`, версия `1.0.0`, `finalName: VoidRtpZone-1.0.0`.
- Пакет `ru.voidpe.rtpzone`, главный класс `VoidRtpZone` (один Java-файл — плагин мини).
- Зависимость: Purpur API `26.2.build.2632-stable` (scope provided), Java 25. Ресурсы с filtering=true (в plugin.yml версия `${project.version}`).
- Файлы: `pom.xml`, `src/main/java/ru/voidpe/rtpzone/VoidRtpZone.java`, `src/main/resources/plugin.yml`, `src/main/resources/config.yml`.

### plugin.yml

- name: VoidRtpZone, main: ru.voidpe.rtpzone.VoidRtpZone, api-version: '26.2', author: VoidPE.
- Команда `rtpzone` (алиас `rtpz`), permission `voidrtpzone.admin`.
- Права: `voidrtpzone.admin` (default op) — команды; `voidrtpzone.bypass` (default false) — игрока зоны не телепортируют.

### Команды (только для админа)

- `/rtpzone wand` — выдать предмет-выделитель: BLAZE_ROD с именем и лором из конфига и **невидимой меткой в PersistentDataContainer** (NamespacedKey плагина). Проверка предмета — только по PDC, не по имени.
- Выделение: ЛКМ по блоку — точка 1, ПКМ по блоку — точка 2 (только основная рука, клик отменяется, чтобы не ломать блоки). В чат — подтверждение с координатами.
- `/rtpzone save <имя>` — сохранить зону: нормализовать min/max по каждой оси, мир берётся из точек (обе точки обязаны быть в одном мире). Имя — в нижний регистр; повторный save с тем же именем перезаписывает зону.
- `/rtpzone remove <имя>`, `/rtpzone list` (имя, мир, объём), `/rtpzone reload` (конфиг + зоны), без аргументов — справка.
- TabComplete: подкоманды; для remove — имена зон.

### Хранение зон

- Отдельный файл **`plugins/VoidRtpZone/zones.yml`** (НЕ config.yml — конфиг на сервере при обновлениях удаляют, зоны не должны слетать). Формат: `zones.<имя>.{world,min-x,min-y,min-z,max-x,max-y,max-z}`.
- Сохранять сразу при save/remove.

### Срабатывание зоны (ядро плагина — должно быть ОПТИМИЗИРОВАНО)

- **Ни одного таймера/шедулера.** Только `PlayerMoveEvent`.
- Первый и главный фильтр: если игрок не сменил блок (from и to — одинаковые blockX/blockY/blockZ и мир), событие игнорируется сразу.
- Зоны в памяти сгруппированы по имени мира (`Map<String, List<Zone>>`): в мире без зон — мгновенный выход. Проверка попадания — 6 сравнений int по предрассчитанным min/max.
- Срабатывание только на переходе «снаружи → внутрь»: хранить `Set<UUID>` игроков, находящихся внутри; при выходе — удалять. Плюс кулдаун на игрока (по умолчанию 10 секунд, `Map<UUID, Long>`).
- Не срабатывать: у игроков с `voidrtpzone.bypass` и в режимах из конфига `trigger.skip-gamemodes` (по умолчанию CREATIVE, SPECTATOR — чтобы админ спокойно выделял зону).
- При входе выполнить команды из конфига `trigger.commands` (по умолчанию `["rtp"]`): от игрока (`performCommand`) или от консоли (`as-console: true`, подставляется `{player}`).
- Очистка состояния игрока при выходе с сервера и смене мира.

### config.yml (все тексты настраиваются, комментарии на русском)

- `enabled: true`
- `wand.material` (BLAZE_ROD), `wand.name`, `wand.lore` — цвета в формате `&a` и `&#RRGGBB`.
- `trigger.commands`, `trigger.as-console: false`, `trigger.cooldown-seconds: 10`, `trigger.skip-gamemodes: [CREATIVE, SPECTATOR]`.
- `messages`: prefix и все сообщения (wand-given, pos1, pos2, saved, removed, no-selection, no-such-zone, list-empty, list-line, reloaded, help) с плейсхолдерами `{x} {y} {z} {name} {world} {size} {count}`.
- Цвета выводить через Adventure: перевести `&#RRGGBB` в legacy-шестнадцатеричный формат и `&` в `§`, собирать через `LegacyComponentSerializer.legacySection()`.

### Требования к коду

- События: interact с `ignoreCancelled = true` и проверкой `EquipmentSlot.HAND`; move — priority MONITOR, ignoreCancelled.
- Никаких обращений к диску в обработчике движения; zones.yml пишется только в командах.
- Аккуратные логи при старте: сколько зон загружено.

### Результат и проверка

Отдай исходники и собранный `VoidRtpZone-1.0.0.jar` (собирается `mvn package`). Проверка на сервере: поставил jar в `plugins/` → `/rtpzone wand` → выделил две точки → `/rtpzone save test` → в `zones.yml` появилась зона → зашёл в зону обычным игроком в выживании — выполнился `/rtp`, повторно внутри не срабатывает; в креативе не срабатывает; `/rtpzone reload` работает.
