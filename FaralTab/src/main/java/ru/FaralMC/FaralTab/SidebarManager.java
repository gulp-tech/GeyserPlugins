package ru.FaralMC.FaralTab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.io.File;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Компактный Bedrock-скорборд (сервер FaralMC — только Bedrock: Geyser + Floodgate).
 * Иконки — всегда глифы из FaralResourcePack, никаких Java-заглушек.
 * Строки скорборда — это префиксы команд (entries невидимы), поэтому:
 *  - нет мерцания при обновлении (меняем только изменившиеся строки);
 *  - иконка из ресурспака занимает ровно 1 символ и не раздувает таб.
 */
public final class SidebarManager {

    private static final String OBJECTIVE = "faraltab";
    private static final int MAX_LINES = 15;
    private static final char[] ENTRY_CODES = "0123456789abcde".toCharArray();

    private final FaralTab plugin;
    private final Map<UUID, List<String>> cache = new HashMap<>();
    private final Set<UUID> hidden = new HashSet<>();
    private File hiddenFile;

    public SidebarManager(FaralTab plugin) {
        this.plugin = plugin;
        loadHidden();
    }

    // ------------------------------------------------------------ жизненный цикл
    public void show(Player player) {
        hidden.remove(player.getUniqueId());
        saveHidden();
        update(player, true);
    }

    public void hide(Player player) {
        hidden.add(player.getUniqueId());
        saveHidden();
        cache.remove(player.getUniqueId());
        player.setScoreboard(Bukkit.getScoreboardManager().getNewScoreboard());
    }

    public boolean toggle(Player player) {
        if (hidden.contains(player.getUniqueId())) {
            show(player);
            return true;
        }
        hide(player);
        return false;
    }

    public boolean isHidden(UUID uuid) {
        return hidden.contains(uuid);
    }

    public void remove(UUID uuid) {
        cache.remove(uuid);
    }

    public void updateAll() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            update(player, false);
        }
    }

    // ------------------------------------------------------------ обновление
    public void update(Player player, boolean force) {
        if (!plugin.getConfig().getBoolean("scoreboard.enabled", true)) {
            return;
        }
        if (hidden.contains(player.getUniqueId())) {
            return;
        }

        List<String> lines = new ArrayList<>();
        for (String raw : plugin.getConfig().getStringList("scoreboard.lines")) {
            lines.add(render(player, raw));
            if (lines.size() >= MAX_LINES) {
                break;
            }
        }

        UUID uuid = player.getUniqueId();
        if (!force && lines.equals(cache.get(uuid))) {
            return; // ничего не изменилось — не трогаем скорборд (нет мерцания)
        }
        cache.put(uuid, lines);

        Scoreboard board = player.getScoreboard();
        if (board.getObjective(OBJECTIVE) == null) {
            board = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(board);
        }
        Objective objective = board.getObjective(OBJECTIVE);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE, Criteria.DUMMY, component(renderTitle(player)));
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        } else {
            objective.displayName(component(renderTitle(player)));
        }

        int total = lines.size();
        for (int i = 0; i < MAX_LINES; i++) {
            String entry = "§" + ENTRY_CODES[i] + "§r";
            Team team = board.getTeam("ft" + i);
            if (team == null) {
                team = board.registerNewTeam("ft" + i);
                team.addEntry(entry);
            }
            if (i < total) {
                team.prefix(component(lines.get(i)));
                objective.getScore(entry).setScore(total - i);
            } else {
                team.prefix(Component.empty());
                board.resetScores(entry);
            }
        }
    }

    private String renderTitle(Player player) {
        // Заголовок — маленькая картинка FaralMC из ресурспака: полоса глифов \uE010..\uE017,
        // высота ровно одна строка, как обычный текст, только текстурой.
        if (plugin.getConfig().getBoolean("scoreboard.title-logo.enabled", true)) {
            try {
                int start = Integer.parseInt(
                        plugin.getConfig().getString("scoreboard.title-logo.start-code", "E010").trim(), 16);
                int count = Math.max(1, Math.min(12,
                        plugin.getConfig().getInt("scoreboard.title-logo.glyphs", 8)));
                StringBuilder logo = new StringBuilder();
                for (int i = 0; i < count; i++) {
                    logo.append(new String(Character.toChars(start + i)));
                }
                return logo.toString();
            } catch (IllegalArgumentException ignored) {
                // битый конфиг — упадём на текстовый заголовок ниже
            }
        }
        return render(player, plugin.getConfig().getString("scoreboard.title", "FaralMC"));
    }

    // ------------------------------------------------------------ рендер строки
    String render(Player player, String raw) {
        Hooks hooks = plugin.hooks();
        String text = raw;

        // иконки: {icon:coin} и т.д.
        for (String name : plugin.getConfig().getConfigurationSection("icons") == null
                ? Set.<String>of() : plugin.getConfig().getConfigurationSection("icons").getKeys(false)) {
            String token = "{icon:" + name + "}";
            if (text.contains(token)) {
                text = text.replace(token, iconChar(name));
            }
        }

        String groupRaw = "default";
        String groupDisplay = null;
        String[] lp = hooks.hasLuckPerms() ? hooks.luckPermsGroup(player.getUniqueId()) : null;
        if (lp != null) {
            groupRaw = lp[0];
            groupDisplay = lp[1];
        } else {
            String vg = hooks.vaultGroup(player);
            if (vg != null && !vg.isBlank()) {
                groupRaw = vg;
            }
        }
        String group = plugin.getConfig().getString("groups." + groupRaw.toLowerCase(Locale.ROOT) + ".display");
        if (group == null && plugin.getConfig().getBoolean("groups.use-luckperms-display-name", false)) {
            group = groupDisplay;
        }
        if (group == null) {
            group = groupRaw.isEmpty() ? "Игрок"
                    : Character.toUpperCase(groupRaw.charAt(0)) + groupRaw.substring(1);
        }

        double balance = hooks.hasEconomy() ? hooks.balance(player) : -1;
        String coins;
        if (balance < 0) {
            coins = plugin.getConfig().getString("defaults.coins", "0");
        } else if (plugin.getConfig().getBoolean("coins.use-vault-format", false) && hooks.economyFormat(balance) != null) {
            coins = hooks.economyFormat(balance);
        } else {
            coins = formatNumber(balance,
                    plugin.getConfig().getInt("coins.decimals", 0),
                    plugin.getConfig().getBoolean("coins.grouping", true));
        }

        int points = hooks.hasPlayerPoints() ? hooks.points(player.getUniqueId()) : -1;
        String shards = points < 0
                ? plugin.getConfig().getString("defaults.shards", "0")
                : formatNumber(points, 0, plugin.getConfig().getBoolean("shards.grouping", true));

        int kills = stat(player, Statistic.PLAYER_KILLS);
        int deaths = stat(player, Statistic.DEATHS);
        String kd = deaths == 0
                ? formatNumber(kills, 2, false)
                : formatNumber((double) kills / deaths, 2, false);

        long playTicks = stat(player, Statistic.PLAY_ONE_MINUTE);
        long totalSeconds = playTicks / 20L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        String playtime = plugin.getConfig().getString("playtime.format", "{h}ч {m}м")
                .replace("{h}", String.valueOf(hours))
                .replace("{m}", String.valueOf(minutes));

        text = text.replace("{device_icon}", deviceIconChar(player));
        text = text.replace("{device}", deviceName(player));

        Map<String, String> values = new HashMap<>();
        values.put("player", player.getName());
        values.put("group", group);
        values.put("group_raw", groupRaw);
        values.put("coins", coins);
        values.put("coins_raw", balance < 0 ? "0" : String.valueOf((long) balance));
        values.put("shards", shards);
        values.put("kills", String.valueOf(kills));
        values.put("deaths", String.valueOf(deaths));
        values.put("kd", kd);
        values.put("online", String.valueOf(Bukkit.getOnlinePlayers().size()));
        values.put("max", String.valueOf(Bukkit.getMaxPlayers()));
        values.put("ping", String.valueOf(player.getPing()));
        values.put("playtime_hours", String.valueOf(hours));
        values.put("playtime_minutes", String.valueOf(totalSeconds / 60L));
        values.put("playtime", playtime);
        values.put("world", player.getWorld().getName());

        for (Map.Entry<String, String> e : values.entrySet()) {
            text = text.replace("{" + e.getKey() + "}", e.getValue());
        }

        if (plugin.getConfig().getBoolean("hooks.placeholderapi", true)) {
            text = hooks.applyPlaceholders(player, text);
        }
        return TextUtil.color(text);
    }

    /**
     * Имя иконки устройства: Floodgate DeviceOs (Enum.name) + InputMode.
     * Телефон/планшет с подключённой клавиатурой и мышью — отдельное устройство kbmouse.
     */
    public String deviceIconName(Player player) {
        String os = plugin.hooks().deviceOs(player.getUniqueId());
        String input = plugin.hooks().inputMode(player.getUniqueId());
        boolean mobileOs = os.equals("GOOGLE") || os.equals("IOS") || os.equals("AMAZON");
        if (input.equals("KEYBOARD_MOUSE") && (mobileOs || os.equals("UNKNOWN"))) {
            return os.equals("UNKNOWN") ? "windows" : "kbmouse";
        }
        if (os.equals("UWP") || os.equals("WIN32") || os.contains("WINDOWS")) {
            return "windows";
        }
        if (os.equals("GOOGLE") || os.equals("AMAZON")) {
            return "android";
        }
        if (os.equals("IOS") || os.equals("OSX") || os.equals("TVOS")) {
            return "apple";
        }
        if (input.equals("KEYBOARD_MOUSE")) {
            return "windows";
        }
        return "device";
    }

    /** Человекочитаемое устройство: Windows / Android / iOS / Устройство. */
    public String deviceName(Player player) {
        String icon = deviceIconName(player);
        if (icon.equals("windows")) {
            return plugin.getConfig().getString("nametag.device-names.windows", "Windows");
        }
        if (icon.equals("android")) {
            return plugin.getConfig().getString("nametag.device-names.android", "Android");
        }
        if (icon.equals("apple")) {
            return plugin.getConfig().getString("nametag.device-names.apple", "iOS");
        }
        if (icon.equals("kbmouse")) {
            return plugin.getConfig().getString("nametag.device-names.kbmouse", "Клавиатура+мышь");
        }
        return plugin.getConfig().getString("nametag.device-names.device", "Устройство");
    }

    /** Глиф устройства из ресурспака для ника/скорборда. */
    public String deviceIconChar(Player player) {
        return iconChar(deviceIconName(player));
    }

    /** Иконка — всегда глиф из Bedrock-ресурспака: сервер только для Bedrock. */
    private String iconChar(String name) {
        String code = plugin.getConfig().getString("icons." + name, "");
        if (code == null || code.isBlank()) {
            return "";
        }
        try {
            int cp = Integer.parseInt(code.trim(), 16);
            return new String(Character.toChars(cp));
        } catch (IllegalArgumentException e) {
            return "";
        }
    }

    // ------------------------------------------------------------ утилиты
    private int stat(Player player, Statistic statistic) {
        try {
            return player.getStatistic(statistic);
        } catch (Throwable t) {
            return 0;
        }
    }

    private String formatNumber(double value, int decimals, boolean grouping) {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.ROOT);
        if (grouping) {
            symbols.setGroupingSeparator(
                    plugin.getConfig().getString("numbers.grouping-separator", " ").charAt(0));
        }
        StringBuilder pattern = new StringBuilder(grouping ? "#,##0" : "0");
        if (decimals > 0) {
            pattern.append('.');
            pattern.append("0".repeat(decimals));
        }
        return new DecimalFormat(pattern.toString(), symbols).format(value);
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(
                legacySectionText == null ? "" : legacySectionText);
    }

    // ------------------------------------------------------------ скрытые игроки
    private void loadHidden() {
        try {
            hiddenFile = new File(plugin.getDataFolder(), "hidden.yml");
            if (hiddenFile.exists()) {
                YamlConfiguration cfg = YamlConfiguration.loadConfiguration(hiddenFile);
                for (String s : cfg.getStringList("hidden")) {
                    try {
                        hidden.add(UUID.fromString(s));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            plugin.getLogger().warning("Не удалось загрузить hidden.yml: " + t.getMessage());
        }
    }

    private void saveHidden() {
        if (!plugin.getConfig().getBoolean("scoreboard.remember-hidden", true)) {
            return;
        }
        try {
            if (hiddenFile == null) {
                hiddenFile = new File(plugin.getDataFolder(), "hidden.yml");
            }
            YamlConfiguration cfg = new YamlConfiguration();
            List<String> list = new ArrayList<>();
            for (UUID u : hidden) {
                list.add(u.toString());
            }
            cfg.set("hidden", list);
            cfg.save(hiddenFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось сохранить hidden.yml: " + e.getMessage());
        }
    }
}
