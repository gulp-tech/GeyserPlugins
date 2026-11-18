package ru.voidpe.music;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * VoidMusic — музыка для игроков в мире спавна.
 *
 * Пока игрок находится в настроенном мире (по умолчанию world), ему играет
 * трек из ресурс-пака VoidMusicPack.mcpack. После прослушивания — пауза
 * pause-seconds (5 минут), потом трек начинается заново. При выходе из мира
 * звук глушится.
 *
 * Команды:
 *   /vmusic start [игрок] — начать музыку (voidmusic.admin)
 *   /vmusic stop [игрок]  — прекратить для себя (все); для другого — админ
 *   /vmusic status        — состояние (voidmusic.admin)
 *   /vmusic reload        — перезагрузка конфига (voidmusic.admin)
 */
public final class VoidMusic extends JavaPlugin implements Listener, CommandExecutor {

    private String worldName;
    private String sound;
    private float volume;
    private float pitch;
    private int trackSeconds;
    private int pauseSeconds;

    private final Map<UUID, BukkitTask> loops = new HashMap<>();
    private final Set<UUID> muted = new HashSet<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        getCommand("vmusic").setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isInWorld(player)) {
                startFor(player);
            }
        }
        getLogger().info("VoidMusic включён: мир=" + worldName
                + ", звук=" + sound
                + ", цикл=" + trackSeconds + "с трек + " + pauseSeconds + "с пауза");
    }

    @Override
    public void onDisable() {
        for (Map.Entry<UUID, BukkitTask> entry : loops.entrySet()) {
            entry.getValue().cancel();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null) {
                player.stopSound(sound);
            }
        }
        loops.clear();
    }

    private void reloadSettings() {
        worldName = getConfig().getString("world", "world");
        sound = getConfig().getString("sound", "voidpe:spawn_music");
        volume = (float) getConfig().getDouble("volume", 1.0D);
        pitch = (float) getConfig().getDouble("pitch", 1.0D);
        trackSeconds = Math.max(5, getConfig().getInt("track-seconds", 172));
        pauseSeconds = Math.max(0, getConfig().getInt("pause-seconds", 300));
    }

    private boolean isInWorld(Player player) {
        World world = player.getWorld();
        return world != null && worldName.equalsIgnoreCase(world.getName());
    }

    private void playFor(Player player) {
        Location location = player.getLocation();
        player.playSound(location, sound, volume, pitch);
    }

    /** Запускает (или перезапускает) цикл музыки для игрока. */
    private void startFor(Player player) {
        stopFor(player, false);
        playFor(player);
        long period = (trackSeconds + pauseSeconds) * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline()) {
                stopFor(player, false);
                return;
            }
            playFor(player);
        }, period, period);
        loops.put(player.getUniqueId(), task);
    }

    /** Останавливает цикл и глушит звук у игрока. */
    private void stopFor(Player player, boolean stopSound) {
        BukkitTask task = loops.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        if (stopSound) {
            player.stopSound(sound);
        }
    }

    // ---------- события ----------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isInWorld(player)) {
            return;
        }
        // лёгкая задержка: Bedrock-клиент успевает загрузить мир и паки
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && isInWorld(player) && !muted.contains(player.getUniqueId())) {
                startFor(player);
            }
        }, 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isInWorld(player)) {
            if (!muted.contains(player.getUniqueId())) {
                startFor(player);
            }
        } else {
            stopFor(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFor(event.getPlayer(), false);
        muted.remove(event.getPlayer().getUniqueId());
    }

    // ---------- команды ----------

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase();
        switch (sub) {
            case "start":
                return handleStart(sender, args);
            case "stop":
                return handleStop(sender, args);
            case "reload":
                return handleReload(sender);
            case "status":
                return handleStatus(sender, args);
            default:
                sender.sendMessage("§e/vmusic start [игрок] §7— начать музыку (админы)");
                sender.sendMessage("§e/vmusic stop [игрок] §7— прекратить (для себя — все игроки)");
                sender.sendMessage("§e/vmusic status §7— состояние (админы)");
                sender.sendMessage("§e/vmusic reload §7— перезагрузка конфига (админы)");
                return true;
        }
    }

    private boolean handleStart(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidmusic.admin")) {
            sender.sendMessage("§cНет прав (нужно voidmusic.admin).");
            return true;
        }
        Player target = resolvePlayer(sender, args, 1);
        if (target == null) {
            return true;
        }
        muted.remove(target.getUniqueId());
        startFor(target);
        sender.sendMessage("§aМузыка запущена" + (target.equals(sender) ? "." : " для " + target.getName() + "."));
        return true;
    }

    private boolean handleStop(CommandSender sender, String[] args) {
        Player target;
        if (args.length >= 2) {
            if (!sender.hasPermission("voidmusic.admin")) {
                sender.sendMessage("§cПрекратить музыку для другого игрока может только админ (voidmusic.admin).");
                return true;
            }
            target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage("§cИгрок не найден.");
                return true;
            }
        } else {
            if (!(sender instanceof Player)) {
                sender.sendMessage("§cКонсоль: /vmusic stop <игрок>");
                return true;
            }
            if (!sender.hasPermission("voidmusic.stop")) {
                sender.sendMessage("§cНет прав (нужно voidmusic.stop).");
                return true;
            }
            target = (Player) sender;
        }
        muted.add(target.getUniqueId());
        stopFor(target, true);
        sender.sendMessage("§eМузыка выключена" + (target.equals(sender) ? "." : " для " + target.getName() + ".")
                + " §7Вернётся при перезаходе (или /vmusic start).");
        return true;
    }

    private boolean handleReload(CommandSender sender) {
        if (!sender.hasPermission("voidmusic.admin")) {
            sender.sendMessage("§cНет прав (нужно voidmusic.admin).");
            return true;
        }
        reloadSettings();
        for (UUID uuid : new HashSet<>(loops.keySet())) {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null && !muted.contains(uuid)) {
                startFor(player);
            }
        }
        sender.sendMessage("§aVoidMusic перезагружен: мир=" + worldName
                + ", цикл=" + trackSeconds + "с трек + " + pauseSeconds + "с пауза.");
        return true;
    }

    private boolean handleStatus(CommandSender sender, String[] args) {
        if (!sender.hasPermission("voidmusic.admin")) {
            sender.sendMessage("§cНет прав (нужно voidmusic.admin).");
            return true;
        }
        Player target = args.length >= 2
                ? Bukkit.getPlayerExact(args[1])
                : (sender instanceof Player ? (Player) sender : null);
        sender.sendMessage("§eVoidMusic: мир=" + worldName + ", звук=" + sound
                + ", цикл=" + trackSeconds + "с + " + pauseSeconds + "с");
        if (target != null) {
            String state = loops.containsKey(target.getUniqueId())
                    ? "§aиграет"
                    : (muted.contains(target.getUniqueId()) ? "§cвыключена игроком" : "§7не запущена");
            sender.sendMessage("§e" + target.getName() + ": " + state
                    + (isInWorld(target) ? " (в мире спавна)" : " (в другом мире)"));
        } else if (args.length >= 2) {
            sender.sendMessage("§cИгрок не найден.");
        }
        return true;
    }

    private Player resolvePlayer(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage("§cИгрок не найден.");
            }
            return target;
        }
        if (sender instanceof Player) {
            return (Player) sender;
        }
        sender.sendMessage("§cКонсоль: укажите игрока.");
        return null;
    }
}
