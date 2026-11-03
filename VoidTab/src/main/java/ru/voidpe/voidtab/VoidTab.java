package ru.voidpe.voidtab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * VoidTab — компактный скорборд (таб) для Geyser/Floodgate сервера VoidPE.
 * Purpur 26.2 / Java 25.
 */
public final class VoidTab extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Hooks hooks;
    private SidebarManager sidebar;
    private NametagManager nametag;
    private BukkitTask task;
    private BukkitTask appearanceTask;
    private long lastLpRefreshMs;
    private int refreshCursor;
    // Игроки, которых надо обновить после появления (вход/телепорт). Работа НЕ
    // делается в тик спауна: в этот момент клиент и так грузит скин и модель
    // игрока, и лишние пакеты сверху вешают слабые телефоны («игра застывает,
    // когда игрок появляется перед тобой»). Очередь дренируется по одному
    // игроку за тик — появление остаётся «голым» спауном ваниллы.
    private final java.util.ArrayDeque<java.util.UUID> appearanceQueue = new java.util.ArrayDeque<>();
    private boolean warnedNoFloodgate;

    @Override
    public void onEnable() {
        migrateFromFaralTab();
        saveDefaultConfig();
        migrateConfig();
        hooks = new Hooks(getLogger());
        hooks.setMetaCacheSeconds(getConfig().getLong("luckperms.cache-seconds", 5L));
        sidebar = new SidebarManager(this);
        nametag = new NametagManager(this);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("voidtab") != null) {
            getCommand("voidtab").setExecutor(this);
            getCommand("voidtab").setTabCompleter(this);
        }

        startTask();
        startAppearanceTask();
        // игроки, уже онлайн при /reload
        hooks.refreshLuckPermsMeta();
        Bukkit.getOnlinePlayers().forEach(p -> {
            sidebar.update(p, true);
            nametag.apply(p, true);
        });
        getLogger().info("VoidTab включён (Bedrock-only). Floodgate: " + (hooks.hasFloodgate() ? "найден, Java-игроки будут кикаться" : "НЕ найден — bedrock-only не сможет отличать Java, поставьте Floodgate!"));
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        if (appearanceTask != null) {
            appearanceTask.cancel();
        }
    }

    /** Поставить игрока в очередь обновления после появления (без дублей). */
    private void queueAppearance(Player player) {
        if (player != null && !appearanceQueue.contains(player.getUniqueId())) {
            appearanceQueue.add(player.getUniqueId());
        }
    }

    /**
     * Дренаж очереди появления: не больше performance.appearance-per-tick
     * игроков за тик. Ник/скорборд появляются на долю секунды позже спауна,
     * зато сам момент появления игрока не нагружает ни сервер, ни клиентов.
     */
    private void startAppearanceTask() {
        if (appearanceTask != null) {
            appearanceTask.cancel();
        }
        appearanceTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            int perTick = Math.max(1, getConfig().getInt("performance.appearance-per-tick", 1));
            for (int i = 0; i < perTick; i++) {
                java.util.UUID uuid = appearanceQueue.poll();
                if (uuid == null) {
                    return;
                }
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    sidebar.update(p, false);
                    nametag.apply(p, false);
                }
            }
        }, 1L, 1L);
    }

    /** Переезд с FaralTab: подхватить конфиг и hidden.yml из plugins/FaralTab. */
    private void migrateFromFaralTab() {
        try {
            java.io.File pluginsDir = getDataFolder().getParentFile();
            if (pluginsDir == null) {
                return;
            }
            java.io.File oldDir = new java.io.File(pluginsDir, "FaralTab");
            if (!oldDir.isDirectory() || oldDir.equals(getDataFolder())) {
                return;
            }
            getDataFolder().mkdirs();
            for (String name : new String[]{"config.yml", "hidden.yml"}) {
                java.io.File from = new java.io.File(oldDir, name);
                java.io.File to = new java.io.File(getDataFolder(), name);
                if (from.isFile() && !to.exists()) {
                    java.nio.file.Files.copy(from.toPath(), to.toPath());
                    getLogger().info("Перенёс " + name + " из старой папки FaralTab.");
                }
            }
        } catch (Throwable t) {
            getLogger().warning("Не удалось перенести данные FaralTab: " + t.getMessage());
        }
    }

    /** Старые конфиги: значок устройства в ТАБЕ больше не нужен — он только в нике над головой. */
    private void migrateConfig() {
        java.util.List<String> lines = getConfig().getStringList("scoreboard.lines");
        boolean changed = false;
        java.util.List<String> cleaned = new java.util.ArrayList<>();
        java.util.Map<String, String> oldDefaults = java.util.Map.of(
                "&fМонеты: &#FFD93D{coins} {icon:spark}", "&fМонеты: &#FFD93D{coins} {icon:coin}",
                "&fВ игре: &#FFD93D{playtime_hours}ч {icon:coin}", "&fВ игре: &#FFD93D{playtime_hours}ч {icon:clock}");
        for (String line : lines) {
            String c = oldDefaults.getOrDefault(line, line)
                    .replace("{device_icon} ", "").replace("{device_icon}", "");
            if (!c.equals(line)) {
                changed = true;
            }
            cleaned.add(c);
        }
        if (changed) {
            getConfig().set("scoreboard.lines", cleaned);
            saveConfig();
            getLogger().info("Миграция: {device_icon} убран из строк скорборда (значок остался только в нике).");
        }
        // Заголовок скорборда: простой &b&lVoidPE (градиент жирность в Bedrock не даёт).
        if (!getConfig().getBoolean("scoreboard.title-simple-migrated", false)) {
            String title = getConfig().getString("scoreboard.title", "");
            if (title != null && (title.contains("FaralMC") || title.contains("VoidPE"))) {
                getConfig().set("scoreboard.title", "&b&lVoidPE");
                getLogger().info("Миграция: заголовок скорборда — &b&lVoidPE (синий, жирный).");
            }
            getConfig().set("scoreboard.title-simple-migrated", true);
            getConfig().set("scoreboard.title-bold-migrated", true);
            saveConfig();
        }
        // 1.16.6: тяжёлые настройки производительности применяем и на старом конфиге
        // (пользователь обновляет плагин, не удаляя config.yml, — иначе фиксы не доедут).
        if (!getConfig().getBoolean("performance-migrated-1-16-6", false)) {
            if (getConfig().get("nametag.use-teams") == null) {
                getConfig().set("nametag.use-teams", false);
            }
            if (!getConfig().isSet("luckperms.cache-seconds")) {
                getConfig().set("luckperms.cache-seconds", 5);
            }
            if (!getConfig().isSet("performance.ping-cache-seconds")) {
                getConfig().set("performance.ping-cache-seconds", 2);
            }
            if (getConfig().getLong("luckperms.force-name-resend-interval-seconds", 60L) == 60L) {
                getConfig().set("luckperms.force-name-resend-interval-seconds", 0);
            }
            getConfig().set("performance-migrated-1-16-6", true);
            saveConfig();
            getLogger().info("Миграция 1.16.6: команды неймтегов выключены (use-teams: false), принудительная пересылка имён выключена — меньше пакетов и фризов.");
        }

        // 1.16.7: в 1.16.6 команды неймтегов были выключены — из-за этого пропало
        // оформление над головой. Возвращаем их всем, кто успел обновиться.
        if (!getConfig().getBoolean("nametag-teams-restored-1-16-7", false)) {
            if (!getConfig().getBoolean("nametag.use-teams", true)) {
                getConfig().set("nametag.use-teams", true);
                getLogger().info("Миграция 1.16.7: команды неймтегов снова включены (оформление над головой вернулось).");
            }
            getConfig().set("nametag-teams-restored-1-16-7", true);
            saveConfig();
        }

        // 1.16.8: РЕСУРСПАК УБРАН (он вызывал лаги). Чистим всё, что рисовало глифы:
        // иконки-коды, {icon:...} в строках и сами символы приватной области.
        if (!getConfig().getBoolean("resourcepack-removed-1-16-8", false)) {
            org.bukkit.configuration.ConfigurationSection icons = getConfig().getConfigurationSection("icons");
            java.util.List<String> glyphKeys = new java.util.ArrayList<>();
            if (icons != null) {
                for (String key : icons.getKeys(false)) {
                    String value = icons.getString(key, "");
                    if (value != null && GLYPH_CODE.matcher(value.trim()).matches()) {
                        glyphKeys.add(key);
                    }
                }
            }
            for (String key : glyphKeys) {
                getConfig().set("icons." + key, null);
            }
            java.util.List<String> fresh = new java.util.ArrayList<>();
            for (String line : getConfig().getStringList("scoreboard.lines")) {
                String cleanedLine = stripGlyphs(line)
                        .replace("{group_icon} ", "")
                        .replace(" {group_icon}", "")
                        .replace("{group_icon}", "")
                        .replaceAll("\\s*\\{icon:[^}]+\\}", "")
                        .replaceAll("\\s{2,}$", "");
                fresh.add(cleanedLine);
            }
            getConfig().set("scoreboard.lines", fresh);
            String title = stripGlyphs(getConfig().getString("scoreboard.title", ""));
            if (title.isBlank()) {
                title = "<gradient:#55C8FF:#1B6FFF>VoidPE</gradient>";
            }
            getConfig().set("scoreboard.title", title);
            getConfig().set("scoreboard.title-logo.enabled", false);
            getConfig().set("scoreboard.title-logo.start-code", null);
            getConfig().set("scoreboard.title-logo.glyphs", null);
            getConfig().set("resourcepack-removed-1-16-8", true);
            saveConfig();
            getLogger().info("Миграция 1.16.8: ресурспак убран — иконки и глифы удалены из конфига"
                    + (glyphKeys.isEmpty() ? "" : " (ключей icons: " + glyphKeys.size() + ")") + ".");
        }

        // Заголовок скорборда: пиксельная полоса -> обычный читаемый текст VoidPE (один раз).
        if (!getConfig().getBoolean("scoreboard.title-text-migrated", false)) {
            getConfig().set("scoreboard.title-logo.enabled", false);
            getConfig().set("scoreboard.title-text-migrated", true);
            saveConfig();
            getLogger().info("Миграция: заголовок скорборда — текстовый VoidPE (полоса-картинка выключена).");
        }
    }

    /** Код глифа вида E101 (из убранного ресурспака). */
    private static final java.util.regex.Pattern GLYPH_CODE =
            java.util.regex.Pattern.compile("^[Ee][0-9A-Fa-f]{3}$");

    /** Убрать символы приватной области Unicode (глифы убранного ресурспака). */
    private static String stripGlyphs(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            int cp = text.codePointAt(i);
            if (cp >= 0xE000 && cp <= 0xF8FF) {
                i += Character.charCount(cp) - 1;
                continue;
            }
            sb.appendCodePoint(cp);
        }
        return sb.toString();
    }

    public Hooks hooks() {
        return hooks;
    }

    public SidebarManager sidebar() {
        return sidebar;
    }

    public NametagManager nametag() {
        return nametag;
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        // Цикл НЕ проходит всех игроков в одном тике (это давало микро-фриз каждые
        // 2 секунды, особенно заметный при телепортах): задача крутится каждые
        // 2 тика и обрабатывает небольшую пачку игроков по кругу. Полный обход
        // всех занимает примерно update-interval-ticks, как раньше.
        task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            // Префикс LuckPerms — единый источник, но кэш LP обновляем не каждый
            // цикл, а раз в luckperms.refresh-cache-seconds (по умолчанию 10 сек):
            // полный обход LuckPerms по всем игрокам — дорогая рефлексия.
            // Смена префикса при этом применится максимум через 10 секунд,
            // а сообщение в чат обновляет кэш мгновенно (onChatRefreshLuckPerms).
            if (getConfig().getBoolean("luckperms.refresh-cache", true)) {
                long nowMs = System.currentTimeMillis();
                long periodMs = Math.max(1L, getConfig().getLong("luckperms.refresh-cache-seconds", 10L)) * 1000L;
                if (nowMs - lastLpRefreshMs >= periodMs) {
                    lastLpRefreshMs = nowMs;
                    hooks.refreshLuckPermsMeta();
                }
            }
            java.util.List<Player> players = new java.util.ArrayList<>(Bukkit.getOnlinePlayers());
            if (players.isEmpty()) {
                return;
            }
            long interval = Math.max(10L, getConfig().getLong("update-interval-ticks", 40L));
            int configured = getConfig().getInt("performance.batch-size", 0);
            // Авто-пачка: так, чтобы полный круг занял ~interval тиков.
            int batch = configured > 0 ? configured
                    : Math.max(1, (int) Math.ceil(players.size() * 2.0 / interval));
            int count = Math.min(batch, players.size());
            boolean forceNames = getConfig().getBoolean("luckperms.force-name-resend", true);
            for (int i = 0; i < count; i++) {
                Player p = players.get((refreshCursor + i) % players.size());
                sidebar.update(p, false);
                nametag.apply(p, forceNames);
            }
            refreshCursor = (refreshCursor + count) % players.size();
        }, 2L, 2L);
    }

    // ------------------------------------------------------------- события
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isJavaPlayer(player)) {
            getLogger().info("Bedrock-only: кикнут Java-игрок " + player.getName() + " (" + player.getUniqueId() + ")");
            player.kick(LegacyComponentSerializer.legacySection().deserialize(
                    TextUtil.color(getConfig().getString("bedrock-only.kick-message",
                            "&cVoidPE — сервер только для Bedrock!"))));
            return;
        }
        long delay = Math.max(0L, getConfig().getLong("join-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            hooks.refreshLuckPermsMeta(player.getUniqueId());
            // НЕ обновляем ник/скорборд прямо сейчас: игрок только что заспавнился
            // у остальных, их клиенты грузят его скин. Ставим в очередь появления —
            // обработается по одному за тик чуть позже (см. startAppearanceTask).
            queueAppearance(player);
            showJoinTitle(player);
            // в лог сервера: чем клиент представился и какой значок получил (диагностика устройств)
            getLogger().info("Устройство " + player.getName() + ": OS=" + hooks.deviceOs(player.getUniqueId())
                    + " (Floodgate=" + hooks.floodgateDeviceOs(player.getUniqueId())
                    + ", Geyser=" + hooks.geyserDeviceOs(player.getUniqueId()) + ")"
                    + ", ввод=" + hooks.inputMode(player.getUniqueId())
                    + ", модель=" + hooks.deviceModel(player.getUniqueId())
                    + " -> значок " + sidebar.deviceIconName(player));
        }, delay);
    }

    /** Большой синий VoidPE при входе (как логотип на экране загрузки у референса). */
    private void showJoinTitle(Player player) {
        if (!getConfig().getBoolean("join-title.enabled", true)) {
            return;
        }
        Component title = component(sidebar.render(player,
                getConfig().getString("join-title.title", "<gradient:#55C8FF:#1B6FFF>VoidPE</gradient>")));
        Component subtitle = component(sidebar.render(player,
                getConfig().getString("join-title.subtitle", "&fДобро пожаловать!")));
        long fadeIn = getConfig().getLong("join-title.fade-in-ticks", 10L);
        long stay = getConfig().getLong("join-title.stay-ticks", 70L);
        long fadeOut = getConfig().getLong("join-title.fade-out-ticks", 20L);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacySectionText == null ? "" : legacySectionText);
    }

    /**
     * Сервер только для Bedrock. Java-игрок = зашёл напрямую на Purpur, мимо Geyser/Floodgate.
     * Если Floodgate на сервере не стоит, отличить Java от Bedrock нельзя — никого не кикаем.
     */
    private boolean isJavaPlayer(Player player) {
        if (!getConfig().getBoolean("bedrock-only.enabled", true)) {
            return false;
        }
        if (player.hasPermission("voidtab.bedrock-bypass")) {
            return false;
        }
        if (hooks.hasPurpurBedrock()) {
            return false; // ядро Purpurbedrock: все подключения — Bedrock по построению
        }
        if (!hooks.hasFloodgate()) {
            if (!warnedNoFloodgate) {
                warnedNoFloodgate = true;
                getLogger().warning("bedrock-only включён, но Floodgate не найден: Java-игроки НЕ кикаются. Поставьте Floodgate на этот сервер.");
            }
            return false;
        }
        return !hooks.isBedrock(player.getUniqueId());
    }

    /**
     * Перед следующим сообщением в чат обновляем кэш LuckPerms: ChatEx сам
     * читает префикс из LuckPerms на каждое сообщение, а эта инвалидация не
     * даёт ему и скорборду залипнуть на старом значении.
     */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChatRefreshLuckPerms(AsyncPlayerChatEvent event) {
        hooks.refreshLuckPermsMeta(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        appearanceQueue.remove(event.getPlayer().getUniqueId());
        sidebar.remove(event.getPlayer().getUniqueId());
        hooks.forget(event.getPlayer().getUniqueId());
        nametag.remove(event.getPlayer());
    }

    /**
     * Телепорт: точечный рефреш ТОЛЬКО телепортирующегося, но НЕ в тик появления
     * и не в следующий: клиент в этот момент впервые рисует игрока (скин, модель,
     * ник) и лишние пакеты сверху дают фриз у видящих его игроков. Ждём
     * performance.appearance-delay-ticks и ставим в общую очередь появления
     * (по одному игроку за тик) — телепорт остаётся чистым ванильным спауном.
     */
    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        boolean worldChange = event.getTo() != null && event.getFrom().getWorld() != null
                && event.getTo().getWorld() != null
                && !event.getFrom().getWorld().equals(event.getTo().getWorld());
        long delay = Math.max(0L, getConfig().getLong("performance.appearance-delay-ticks", 5L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (!player.isOnline()) {
                return;
            }
            queueAppearance(player);
            // Смена мира: команды неймтегов создаются только для игроков ТОГО ЖЕ мира
            // (в чужих мирах ник не виден — не тратим пакеты). Поэтому после перехода
            // надо один раз пересобрать ники всем, кто теперь в этом мире: иначе у
            // них не будет оформления над головой. Идёт через ту же очередь
            // появления (по одному игроку за тик), а не залпом в одном тике.
            if (worldChange && player.getWorld() != null) {
                for (Player other : player.getWorld().getPlayers()) {
                    if (!other.equals(player)) {
                        queueAppearance(other);
                    }
                }
            }
        }, delay);
    }

    // ------------------------------------------------------------- команды
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#FFB340VoidPE &8» &f"));
        if (args.length >= 1 && args[0].equalsIgnoreCase("debug")) {
            if (!sender.hasPermission("voidtab.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            Player target = args.length >= 2 ? Bukkit.getPlayerExact(args[1])
                    : (sender instanceof Player ? (Player) sender : null);
            if (target == null) {
                sender.sendMessage(prefix + "Использование: /voidtab debug [игрок]");
                return true;
            }
            java.util.UUID tu = target.getUniqueId();
            hooks.refreshLuckPermsMeta(tu);
            sender.sendMessage(prefix + "VoidTab v" + getDescription().getVersion()
                    + " | Floodgate: " + (hooks.hasFloodgate() ? "&aда" : "&cНЕТ"));
            sender.sendMessage(prefix + "Игрок &f" + target.getName() + "&7: Floodgate OS=&b" + hooks.floodgateDeviceOs(tu)
                    + " &7| Geyser OS=&b" + hooks.geyserDeviceOs(tu)
                    + " &7| Ввод Floodgate=&b" + hooks.inputMode(tu)
                    + " &7| Ввод Geyser=&b" + hooks.geyserInputMode(tu)
                    + " &7| Модель=&b" + hooks.deviceModel(tu));
            sender.sendMessage(prefix + "Итог: &b" + hooks.deviceOs(tu) + " &7-> значок &b" + sidebar.deviceIconName(target)
                    + " &7(" + sidebar.deviceName(target) + ") | Префикс ника: '" + nametag.appliedPrefix(tu) + "'");
            if (hooks.hasPurpurBedrock()) {
                sender.sendMessage(prefix + "Purpurbedrock: device=&b" + hooks.bedrockDevice(target)
                        + " &7| id=&b" + hooks.bedrockDeviceId(target)
                        + " &7| os=&b" + hooks.bedrockOs(target)
                        + " &7| ввод=&b" + hooks.bedrockInput(target)
                        + " &7| КМ-телефон=&b" + hooks.bedrockQuery(target, "is_phone_kbm")
                        + " &7| модель=&b" + hooks.bedrockModel(target)
                        + " &7| версия=&b" + hooks.bedrockVersion(target)
                        + " &7| пинг=&b" + hooks.bedrockPing(target));
            }
            String[] dbgLp = hooks.hasLuckPerms() ? hooks.luckPermsGroup(tu) : null;
            String dbgRaw = sidebar.groupRaw(target);
            sender.sendMessage(prefix + "Группа: LuckPerms=&b" + (dbgLp == null ? "null (не подключён/игрок не загружен)" : dbgLp[0])
                    + " &7| groupRaw=&b" + dbgRaw
                    + " &7| icons." + dbgRaw + "=&b" + getConfig().getString("icons." + dbgRaw, "<нет в конфиге>")
                    + " &7| rank-icon=&b" + getConfig().getBoolean("nametag.rank-icon", true));
            sender.sendMessage(prefix + "LuckPerms сейчас: префикс=&b'" + hooks.luckPermsPrefix(tu)
                    + "&7' | титул/суффикс=&b'" + hooks.luckPermsSuffix(tu) + "&7'");
            sender.sendMessage(prefix + "Все онлайн:");
            for (Player online : Bukkit.getOnlinePlayers()) {
                sender.sendMessage(prefix + "&7- &f" + online.getName() + " &7-> &b" + sidebar.deviceIconName(online)
                        + " &7(" + hooks.deviceOs(online.getUniqueId()) + "/" + hooks.inputMode(online.getUniqueId()) + ")");
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("refresh")) {
            Player target = null;
            if (args.length >= 2) {
                if (!sender.hasPermission("voidtab.admin")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.player-not-found", "&cИгрок не найден.")));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
            }
            hooks.refreshLuckPermsMeta();
            if (target != null) {
                sidebar.update(target, true);
                nametag.apply(target, true);
            } else {
                Bukkit.getOnlinePlayers().forEach(online -> {
                    sidebar.update(online, true);
                    nametag.apply(online, true);
                });
            }
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.refreshed", "&aПрефикс, скорборд и ник обновлены.")));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("perf")) {
            if (!sender.hasPermission("voidtab.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            int online = Bukkit.getOnlinePlayers().size();
            long t0 = System.nanoTime();
            for (Player p : Bukkit.getOnlinePlayers()) {
                sidebar.update(p, false);
            }
            long tSidebar = (System.nanoTime() - t0) / 1_000_000L;
            t0 = System.nanoTime();
            for (Player p : Bukkit.getOnlinePlayers()) {
                nametag.apply(p, false);
            }
            long tNametag = (System.nanoTime() - t0) / 1_000_000L;
            // Худший случай: «появился игрок» — всем зрителям создаются команды.
            t0 = System.nanoTime();
            for (Player p : Bukkit.getOnlinePlayers()) {
                nametag.apply(p, true);
            }
            long tBurst = (System.nanoTime() - t0) / 1_000_000L;
            int teams = 0;
            int boards = 1;
            for (Player p : Bukkit.getOnlinePlayers()) {
                boards++;
                teams += p.getScoreboard().getTeams().size();
            }
            boolean teamsOn = getConfig().getBoolean("nametag.use-teams", true);
            sender.sendMessage(prefix + "Игроков: &b" + online + "&7, бордов: &b" + boards + "&7, команд: &b" + teams);
            sender.sendMessage(prefix + "Скорборд: &b" + tSidebar + " мс &7| ник: &b" + tNametag
                    + " мс &7| появление (все сразу): &b" + tBurst + " мс");
            sender.sendMessage(prefix + "Режим ника: " + (teamsOn ? "&aкоманды вкл" : "&cкоманды выкл")
                    + " &7| команд всего: &b" + teams + " &7(на борд: &b" + (online == 0 ? 0 : teams / boards) + "&7)");
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voidtab.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            reloadConfig();
            hooks.setMetaCacheSeconds(getConfig().getLong("luckperms.cache-seconds", 5L));
            hooks.refreshLuckPermsMeta();
            sidebar.invalidate();
            startTask();
            Bukkit.getOnlinePlayers().forEach(online -> {
                sidebar.update(online, true);
                nametag.apply(online, true);
            });
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.reloaded", "&aКонфиг перезагружен.")));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("voidtab.admin")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.player-not-found", "&cИгрок не найден.")));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
                if (!sender.hasPermission("voidtab.toggle")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
            } else {
                sender.sendMessage(prefix + "Использование: /voidtab <reload|toggle [игрок]>");
                return true;
            }
            boolean visible;
            if (args[0].equalsIgnoreCase("on")) {
                sidebar.show(target);
                visible = true;
            } else if (args[0].equalsIgnoreCase("off")) {
                sidebar.hide(target);
                visible = false;
            } else {
                visible = sidebar.toggle(target);
            }
            String key = visible ? "messages.shown" : "messages.hidden";
            String def = visible ? "&aСкорборд включён." : "&eСкорборд скрыт.";
            target.sendMessage(prefix + TextUtil.color(getConfig().getString(key, def)));
            return true;
        }
        sender.sendMessage(prefix + "Использование: /voidtab <reload|refresh [игрок]|debug [игрок]|perf|toggle [игрок]|on|off>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> subs = new ArrayList<>();
            for (String sub : List.of("reload", "refresh", "debug", "perf", "toggle", "on", "off")) {
                if (sub.startsWith(args[0].toLowerCase(java.util.Locale.ROOT))) {
                    subs.add(sub);
                }
            }
            return subs;
        }
        if (args.length == 2 && List.of("refresh", "debug", "perf", "toggle", "on", "off").contains(args[0].toLowerCase(java.util.Locale.ROOT))) {
            List<String> names = new ArrayList<>();
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.getName().toLowerCase(java.util.Locale.ROOT).startsWith(args[1].toLowerCase(java.util.Locale.ROOT))) {
                    names.add(online.getName());
                }
            }
            return names;
        }
        return List.of();
    }
}
