package ru.voidpe.pvp;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * VoidPvP — fast crits для Bedrock-сервера VoidPE.
 *
 * <p>Три вещи, каждая отключается в config.yml:</p>
 * <ul>
 *   <li><b>Нет задержки атаки</b> — атрибут ATTACK_SPEED поднимается, удар всегда
 *       наносит полный урон (режим 1.8).</li>
 *   <li><b>Криты без кд</b> — удар в прыжке/падении даёт критический урон, в том числе
 *       в спринте (в ваниле спринт крит отменяет). Криты не ограничены по времени.</li>
 *   <li><b>Нет неуязвимости между ударами</b> — после удара игрока по живому существу
 *       снимается 0.5-секундная неуязвимость, поэтому «тап-тап» проходит полностью:
 *       сколько раз попал, столько раз и урон.</li>
 * </ul>
 *
 * <p>Всё настройками в config.yml: множитель крита, режим «всегда крит»,
 * список миров-исключений, права, сообщения.</p>
 */
public final class VoidPvP extends JavaPlugin
        implements CommandExecutor, TabCompleter, Listener, PvPConfig {

    /** Значение ATTACK_SPEED по умолчанию у игрока — вернётся при выключении плагина. */
    private static final double DEFAULT_ATTACK_SPEED = 4.0;

    private volatile Settings settings;
    private Attribute attackSpeedAttribute;

    /** Исходные значения ATTACK_SPEED по игрокам (чтобы вернуть как было). */
    private final Map<UUID, Double> originalAttackSpeed = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        attackSpeedAttribute = resolveAttackSpeed();
        if (attackSpeedAttribute == null) {
            getLogger().warning("Атрибут ATTACK_SPEED не найден — задержку атаки убрать не получится "
                    + "(криты и снятие неуязвимости продолжат работать).");
        }
        settings = Settings.load(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new FastCritsListener(this), this);
        if (getCommand("voidpvp") != null) {
            getCommand("voidpvp").setExecutor(this);
            getCommand("voidpvp").setTabCompleter(this);
        }
        // Плагин мог быть включён на живом сервере (/reload) — применяем к тем, кто уже играет.
        for (Player player : Bukkit.getOnlinePlayers()) {
            applyTo(player);
        }
        getLogger().info("VoidPvP включён: " + settings.summary());
    }

    @Override
    public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            restoreAttackSpeed(player);
        }
        originalAttackSpeed.clear();
    }

    // ------------------------------------------------------------------
    // Атрибут ATTACK_SPEED
    // ------------------------------------------------------------------

    private static Attribute resolveAttackSpeed() {
        // В новых версиях имя «attack_speed», в старых — «generic.attack_speed».
        Attribute attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("attack_speed"));
        if (attribute == null) {
            attribute = Registry.ATTRIBUTE.get(NamespacedKey.minecraft("generic.attack_speed"));
        }
        return attribute;
    }

    /** Применяет настройки к игроку: убирает задержку атаки (если включено и мир разрешён). */
    public void applyTo(Player player) {
        Settings current = settings;
        if (current == null) {
            return;
        }
        if (!current.attackSpeedEnabled || current.isWorldDisabled(player.getWorld().getName())) {
            restoreAttackSpeed(player);
            return;
        }
        AttributeInstance instance = attackSpeedAttribute == null ? null : player.getAttribute(attackSpeedAttribute);
        if (instance == null) {
            return;
        }
        originalAttackSpeed.putIfAbsent(player.getUniqueId(), instance.getBaseValue());
        if (instance.getBaseValue() != current.attackSpeedValue) {
            instance.setBaseValue(current.attackSpeedValue);
        }
    }

    /** Возвращает игроку обычную задержку атаки. */
    public void restoreAttackSpeed(Player player) {
        AttributeInstance instance = attackSpeedAttribute == null ? null : player.getAttribute(attackSpeedAttribute);
        if (instance == null) {
            return;
        }
        double original = originalAttackSpeed.getOrDefault(player.getUniqueId(), DEFAULT_ATTACK_SPEED);
        if (instance.getBaseValue() != original) {
            instance.setBaseValue(original);
        }
    }

    /** Право на fast crits у игрока: пустое право в конфиге = разрешено всем. */
    @Override
    public boolean allowed(Player player) {
        Settings current = settings;
        if (current == null) {
            return false;
        }
        if (current.bypassOff != null && !current.bypassOff.isEmpty() && player.hasPermission(current.bypassOff)) {
            return false;
        }
        return current.require == null || current.require.isEmpty() || player.hasPermission(current.require);
    }

    @Override
    public Settings settings() {
        return settings;
    }

    // ------------------------------------------------------------------
    // События игрока
    // ------------------------------------------------------------------

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Через тик: при входе атрибуты ещё могут пересобираться ядром.
        Bukkit.getScheduler().runTask(this, () -> applyTo(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> applyTo(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> applyTo(player));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        originalAttackSpeed.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------------
    // Команда /voidpvp
    // ------------------------------------------------------------------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("voidpvp.use")) {
            sender.sendMessage(color(settings != null ? settings.msgNoPermission : "&cНедостаточно прав."));
            return true;
        }
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("voidpvp.admin")) {
                sender.sendMessage(color(settings != null ? settings.msgNoPermission : "&cНедостаточно прав."));
                return true;
            }
            reloadConfig();
            settings = Settings.load(this);
            for (Player player : Bukkit.getOnlinePlayers()) {
                applyTo(player);
            }
            sender.sendMessage(color(prefix() + settings.msgReloaded));
            getLogger().info("Конфиг перезагружен: " + settings.summary());
            return true;
        }
        // status
        Settings current = settings;
        if (current == null) {
            sender.sendMessage(color("&cКонфиг не загружен."));
            return true;
        }
        sender.sendMessage(color(prefix() + "&fVoidPvP &7v" + getDescription().getVersion()));
        sender.sendMessage(color("&7Система: " + (current.enabled ? "&aвключена" : "&cвыключена")
                + (current.disabledWorlds.isEmpty() ? "" : " &8| &7исключений миров: &e" + current.disabledWorlds.size())));
        sender.sendMessage(color("&7Криты: " + onOff(current.critsEnabled)
                + " &8| &7в воздухе: " + onOff(current.whileAirborne)
                + " &8| &7всегда: " + onOff(current.always)
                + " &8| &7×&e" + trim(current.multiplier)));
        sender.sendMessage(color("&7Задержка атаки убрана: " + onOff(current.attackSpeedEnabled && attackSpeedAttribute != null)
                + " &8| &7нет неуязвимости: " + onOff(current.noInvulnerability)));
        sender.sendMessage(color("&7Право: " + (current.require == null || current.require.isEmpty()
                ? "&aу всех" : "&e" + current.require)));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            String typed = args[0].toLowerCase(Locale.ROOT);
            List<String> out = new ArrayList<>();
            for (String option : List.of("status", "reload")) {
                if (option.startsWith(typed)) {
                    out.add(option);
                }
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------------
    // Мелочи
    // ------------------------------------------------------------------

    private String prefix() {
        Settings current = settings;
        return color(current != null ? current.msgPrefix : "");
    }

    private static String onOff(boolean value) {
        return value ? "&aда" : "&cнет";
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    /** Перевод &-кодов и &#RRGGBB в §-формат. */
    public static String color(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '&' && i + 7 < text.length() && text.charAt(i + 1) == '#') {
                String hex = text.substring(i + 2, i + 8);
                out.append('§').append('x');
                for (char digit : hex.toCharArray()) {
                    out.append('§').append(digit);
                }
                i += 7;
            } else if (ch == '&') {
                out.append('§');
            } else {
                out.append(ch);
            }
        }
        return out.toString();
    }

    // ------------------------------------------------------------------
    // Настройки
    // ------------------------------------------------------------------

    /** Снимок config.yml на момент загрузки. */
    public static final class Settings {
        final boolean enabled;
        final boolean critsEnabled;
        final boolean whileAirborne;
        final boolean always;
        final double multiplier;
        final boolean particles;
        final boolean sound;
        final java.util.Set<String> ignoredEntities;
        final boolean attackSpeedEnabled;
        final double attackSpeedValue;
        final boolean noInvulnerability;
        final java.util.Set<String> disabledWorlds;
        final String require;
        final String bypassOff;
        final String msgPrefix;
        final String msgReloaded;
        final String msgNoPermission;

        Settings(org.bukkit.configuration.file.FileConfiguration config) {
            boolean master = config.getBoolean("enabled", true);
            this.enabled = master;
            this.critsEnabled = master && config.getBoolean("crits.enabled", true);
            this.whileAirborne = config.getBoolean("crits.while-airborne", true);
            this.always = config.getBoolean("crits.always", false);
            this.multiplier = Math.max(1.0, config.getDouble("crits.multiplier", 1.5));
            this.particles = config.getBoolean("crits.particles", true);
            this.sound = config.getBoolean("crits.sound", true);
            java.util.Set<String> ignored = new java.util.LinkedHashSet<>();
            for (String name : config.getStringList("crits.ignored-entities")) {
                ignored.add(name.toUpperCase(Locale.ROOT));
            }
            this.ignoredEntities = java.util.Collections.unmodifiableSet(ignored);

            this.attackSpeedEnabled = master && config.getBoolean("attack-speed.enabled", true);
            this.attackSpeedValue = Math.max(0.1, config.getDouble("attack-speed.value", 1024.0));

            this.noInvulnerability = master && config.getBoolean("no-invulnerability.enabled", true);

            java.util.Set<String> worlds = new java.util.LinkedHashSet<>();
            for (String name : config.getStringList("worlds.disabled")) {
                worlds.add(name.toLowerCase(Locale.ROOT));
            }
            this.disabledWorlds = java.util.Collections.unmodifiableSet(worlds);

            this.require = config.getString("permissions.require", "");
            this.bypassOff = config.getString("permissions.bypass-off", "voidpvp.off");
            this.msgPrefix = config.getString("messages.prefix", "");
            this.msgReloaded = config.getString("messages.reloaded", "&aКонфиг перезагружен.");
            this.msgNoPermission = config.getString("messages.no-permission", "&cНедостаточно прав.");
        }

        static Settings load(VoidPvP plugin) {
            return new Settings(plugin.getConfig());
        }

        boolean isWorldDisabled(String world) {
            return !disabledWorlds.isEmpty() && disabledWorlds.contains(world.toLowerCase(Locale.ROOT));
        }

        /** Одной строкой для лога и /voidpvp. */
        String summary() {
            return "криты " + (critsEnabled ? "вкл" : "выкл")
                    + " (в воздухе " + (whileAirborne ? "да" : "нет")
                    + ", всегда " + (always ? "да" : "нет")
                    + ", ×" + multiplier + "), "
                    + "задержка атаки " + (attackSpeedEnabled ? "убрана" : "обычная")
                    + ", неуязвимость между ударами " + (noInvulnerability ? "снята" : "обычная");
        }
    }
}
