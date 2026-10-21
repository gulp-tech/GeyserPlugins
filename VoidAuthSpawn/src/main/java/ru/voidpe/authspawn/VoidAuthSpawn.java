package ru.voidpe.authspawn;

import fr.xephi.authme.api.v3.AuthMeApi;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * VoidAuthSpawn — дополнение к AuthMe для Bedrock-сервера VoidPE.
 *
 * <p>Когда зашедшему игроку ещё нужно регистрироваться в AuthMe
 * (аккаунта в базе AuthMe нет), плагин телепортирует его в точку из
 * config.yml (секция {@code spawn:}). По желанию там же можно включить
 * телепорт уже зарегистрированных, но ещё не вошедших игроков
 * ({@code teleport.not-logged-in}) и удержание у точки до регистрации
 * ({@code hold.enabled}).</p>
 *
 * <p>Проверка идёт через официальный AuthMe API
 * ({@code fr.xephi.authme.api.v3.AuthMeApi#isRegistered}), с задержкой
 * после входа и несколькими повторными проверками: так телепорт нашего
 * плагина срабатывает после того, как AuthMe и Floodgate закончат
 * свою обработку входа.</p>
 */
public final class VoidAuthSpawn extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    /** Игроки, которых мы уже отправили к точке и ждём регистрации/входа. */
    private final Set<UUID> awaitingAuth = new HashSet<>();
    /** Кому уже показали сообщение о телепорте (чтобы не спамить при повторных проверках). */
    private final Set<UUID> messaged = new HashSet<>();
    private boolean warnedNoAuthMe;
    private boolean warnedNoWorld;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("authspawn") != null) {
            getCommand("authspawn").setExecutor(this);
            getCommand("authspawn").setTabCompleter(this);
        }
        if (!authMePresent()) {
            getLogger().warning("AuthMe не найден! VoidAuthSpawn включён, но телепортировать "
                    + "незарегистрированных сможет только когда AuthMe будет установлен и включён.");
        } else {
            getLogger().info("AuthMe найден: незарегистрированные игроки будут телепортироваться в точку из config.yml.");
        }
    }

    // ------------------------------------------------------------- события

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("enabled", true)) {
            return;
        }
        Player player = event.getPlayer();
        long delay = Math.max(1L, getConfig().getLong("teleport.check-delay-ticks", 20L));
        int times = Math.max(1, getConfig().getInt("teleport.check-times", 3));
        long interval = Math.max(1L, getConfig().getLong("teleport.check-interval-ticks", 40L));
        for (int i = 0; i < times; i++) {
            final int attempt = i;
            Bukkit.getScheduler().runTaskLater(this, () -> checkPlayer(player, attempt), delay + interval * i);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("hold.enabled", false)) {
            return;
        }
        if (event.getTo() == null || !awaitingAuth.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        if (event.getFrom().getBlockX() == event.getTo().getBlockX()
                && event.getFrom().getBlockY() == event.getTo().getBlockY()
                && event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return; // повернулся/подпрыгнул на месте — не трогаем
        }
        Location point = spawnLocation();
        if (point == null || point.getWorld() == null || event.getTo().getWorld() == null
                || !point.getWorld().equals(event.getTo().getWorld())) {
            return;
        }
        double radius = Math.max(0.5, getConfig().getDouble("hold.radius", 3.0));
        if (event.getTo().distanceSquared(point) > radius * radius) {
            event.getPlayer().teleport(point);
            send(event.getPlayer(), msg("hold-back"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingAuth.remove(event.getPlayer().getUniqueId());
        messaged.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- логика

    private void checkPlayer(Player player, int attempt) {
        if (!player.isOnline() || !getConfig().getBoolean("enabled", true)) {
            return;
        }
        if (player.hasPermission("voidauthspawn.bypass")) {
            awaitingAuth.remove(player.getUniqueId());
            return;
        }
        Boolean needs = needsTeleport(player);
        if (needs == null) {
            return; // AuthMe ещё не готов — подождём следующую проверку
        }
        if (!needs) {
            awaitingAuth.remove(player.getUniqueId());
            return;
        }
        Location point = spawnLocation();
        if (point == null) {
            return;
        }
        // Не дёргаем игрока, если он уже стоит у точки.
        if (point.getWorld() != null && point.getWorld().equals(player.getWorld())
                && player.getLocation().distanceSquared(point) < 1.0) {
            awaitingAuth.add(player.getUniqueId());
            return;
        }
        player.teleport(point);
        awaitingAuth.add(player.getUniqueId());
        if (getConfig().getBoolean("teleport.play-sound", true)) {
            String sound = getConfig().getString("teleport.sound", "minecraft:entity.enderman.teleport");
            if (sound != null && !sound.isBlank()) {
                player.playSound(point, sound, 0.7f, 1.0f);
            }
        }
        if (messaged.add(player.getUniqueId())) {
            send(player, msg("teleported"));
        }
    }

    /**
     * @return true — игрока надо телепортировать; false — не надо;
     *         null — AuthMe пока не готов, решение принять нельзя.
     */
    private Boolean needsTeleport(Player player) {
        if (!authMePresent()) {
            if (!warnedNoAuthMe) {
                warnedNoAuthMe = true;
                getLogger().warning("AuthMe не включён — проверка регистрации пропущена.");
            }
            return false;
        }
        AuthMeApi api;
        try {
            api = AuthMeApi.getInstance();
        } catch (NoClassDefFoundError | ExceptionInInitializerError e) {
            return false;
        }
        if (api == null) {
            return null;
        }
        boolean registered;
        try {
            registered = api.isRegistered(player.getName());
        } catch (RuntimeException e) {
            return null;
        }
        if (!registered) {
            return getConfig().getBoolean("teleport.unregistered", true);
        }
        if (getConfig().getBoolean("teleport.not-logged-in", false)) {
            try {
                return !api.isAuthenticated(player);
            } catch (RuntimeException e) {
                return null;
            }
        }
        return false;
    }

    private boolean authMePresent() {
        return getServer().getPluginManager().isPluginEnabled("AuthMe");
    }

    /** Точка из config.yml; null — если мир не найден и запасной вариант выключен. */
    private Location spawnLocation() {
        String worldName = getConfig().getString("spawn.world", "world");
        World world = worldName == null ? null : Bukkit.getWorld(worldName);
        if (world == null) {
            if (!warnedNoWorld) {
                warnedNoWorld = true;
                getLogger().warning("Мир '" + worldName + "' из config.yml не найден!");
            }
            if (getConfig().getBoolean("spawn.fallback-to-world-spawn", true) && !Bukkit.getWorlds().isEmpty()) {
                return Bukkit.getWorlds().get(0).getSpawnLocation();
            }
            return null;
        }
        return new Location(world,
                getConfig().getDouble("spawn.x", 0.5),
                getConfig().getDouble("spawn.y", 64.0),
                getConfig().getDouble("spawn.z", 0.5),
                (float) getConfig().getDouble("spawn.yaw", 0.0),
                (float) getConfig().getDouble("spawn.pitch", 0.0));
    }

    // ------------------------------------------------------------- команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "set" -> {
                if (!sender.hasPermission("voidauthspawn.admin")) {
                    send(sender, msg("no-permission"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    send(sender, msg("players-only"));
                    return true;
                }
                Location loc = player.getLocation();
                getConfig().set("spawn.world", loc.getWorld() == null ? "world" : loc.getWorld().getName());
                getConfig().set("spawn.x", round(loc.getX()));
                getConfig().set("spawn.y", round(loc.getY()));
                getConfig().set("spawn.z", round(loc.getZ()));
                getConfig().set("spawn.yaw", round(loc.getYaw()));
                getConfig().set("spawn.pitch", round(loc.getPitch()));
                saveConfig();
                send(sender, msg("point-set")
                        .replace("{world}", loc.getWorld() == null ? "?" : loc.getWorld().getName())
                        .replace("{x}", fmt(loc.getX()))
                        .replace("{y}", fmt(loc.getY()))
                        .replace("{z}", fmt(loc.getZ())));
                return true;
            }
            case "tp" -> {
                if (!sender.hasPermission("voidauthspawn.admin")) {
                    send(sender, msg("no-permission"));
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    send(sender, msg("players-only"));
                    return true;
                }
                Location point = spawnLocation();
                if (point == null) {
                    send(sender, msg("point-missing"));
                    return true;
                }
                player.teleport(point);
                send(sender, msg("teleported-self"));
                return true;
            }
            case "reload" -> {
                if (!sender.hasPermission("voidauthspawn.admin")) {
                    send(sender, msg("no-permission"));
                    return true;
                }
                reloadConfig();
                warnedNoWorld = false;
                send(sender, msg("reloaded"));
                return true;
            }
            default -> {
                for (String line : getConfig().getStringList("messages.help")) {
                    send(sender, line);
                }
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("voidauthspawn.admin")) {
            List<String> out = new ArrayList<>();
            for (String s : List.of("set", "tp", "reload")) {
                if (s.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(s);
                }
            }
            return out;
        }
        return List.of();
    }

    // ------------------------------------------------------------- тексты

    private String msg(String key) {
        return getConfig().getString("messages." + key, "");
    }

    private void send(CommandSender sender, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(comp((prefix == null ? "" : prefix) + text));
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private String fmt(double v) {
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
