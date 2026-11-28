package ru.voidpe.pnx.music;

import org.powernukkitx.Player;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.entity.EntityLevelChangeEvent;
import org.powernukkitx.event.player.PlayerJoinEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.level.Level;
import org.powernukkitx.plugin.PluginBase;
import org.powernukkitx.scheduler.TaskHandler;

import java.util.ArrayList;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Personal spawn music for the PowerNukkitX Bedrock server. */
public final class VoidMusic extends PluginBase implements Listener {
    private String worldName;
    private String sound;
    private float volume;
    private float pitch;
    private int trackSeconds;
    private int pauseSeconds;
    private int periodTicks;

    private final Map<UUID, TaskHandler> loops = new ConcurrentHashMap<>();
    private final Map<UUID, TaskHandler> delayed = new ConcurrentHashMap<>();
    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        readSettings();
        var manager = getServer().getPluginManager();
        // Direct event executors avoid PNX's reflective generated executor.
        manager.registerEvent(PlayerJoinEvent.class, this, EventPriority.NORMAL,
                (listener, event) -> ((VoidMusic) listener).onJoin((PlayerJoinEvent) event), this);
        manager.registerEvent(PlayerQuitEvent.class, this, EventPriority.NORMAL,
                (listener, event) -> ((VoidMusic) listener).onQuit((PlayerQuitEvent) event), this);
        manager.registerEvent(EntityLevelChangeEvent.class, this, EventPriority.MONITOR,
                (listener, event) -> ((VoidMusic) listener).onLevelChange((EntityLevelChangeEvent) event), this, true);
        for (Player player : getServer().getOnlinePlayers().values()) {
            if (isInWorld(player)) queueReconcile(player, 20);
        }
    }

    @Override
    public void onDisable() {
        for (TaskHandler task : delayed.values()) task.cancel();
        delayed.clear();
        for (UUID uuid : new ArrayList<>(loops.keySet())) {
            Player player = getServer().getPlayer(uuid).orElse(null);
            if (player != null) stopFor(player, true);
            else { TaskHandler task = loops.remove(uuid); if (task != null) task.cancel(); }
        }
        muted.clear();
    }

    private void readSettings() {
        worldName = getConfig().getString("world", "world");
        sound = getConfig().getString("sound", "voidpe:spawn_music").trim();
        if (sound.isEmpty()) sound = "voidpe:spawn_music"; // Never send a stop-all-sounds packet.
        volume = MusicPolicy.boundedVolume(getConfig().getDouble("volume", 1.0));
        pitch = MusicPolicy.boundedPitch(getConfig().getDouble("pitch", 1.0));
        trackSeconds = Math.max(5, getConfig().getInt("track-seconds", 172));
        pauseSeconds = Math.max(0, getConfig().getInt("pause-seconds", 300));
        periodTicks = MusicPolicy.periodTicks(trackSeconds, pauseSeconds);
    }

    private boolean isInWorld(Player player) {
        Level level = player.getLevel();
        return level != null && (MusicPolicy.isWorld(worldName, level.getFolderName()) || MusicPolicy.isWorld(worldName, level.getName()));
    }

    private void playFor(Player player) {
        Level level = player.getLevel();
        if (level != null && player.isOnline()) level.addSound(player.getPosition(), sound, volume, pitch, player);
    }

    private void stopSound(Player player, String soundName) {
        Level level = player.getLevel();
        if (level != null && player.isOnline() && soundName != null && !soundName.isEmpty())
            level.stopSound(player.getPosition(), soundName, player);
    }

    private void startFor(Player player) {
        cancelDelayed(player.getUniqueId());
        stopFor(player, true);
        playFor(player);
        UUID id = player.getUniqueId();
        TaskHandler task = getServer().getScheduler().scheduleDelayedRepeatingTask(this, () -> {
            if (!player.isOnline()) { stopFor(player, false); return; }
            playFor(player);
        }, periodTicks, periodTicks);
        TaskHandler previous = loops.put(id, task);
        if (previous != null) previous.cancel();
    }

    private void stopFor(Player player, boolean stopSound) {
        TaskHandler task = loops.remove(player.getUniqueId());
        if (task != null) task.cancel();
        if (stopSound) stopSound(player, sound);
    }

    private void cancelDelayed(UUID id) {
        TaskHandler task = delayed.remove(id);
        if (task != null) task.cancel();
    }

    private void queueReconcile(Player player, int delay) {
        UUID id = player.getUniqueId();
        cancelDelayed(id);
        TaskHandler task = getServer().getScheduler().scheduleDelayedTask(this, () -> {
            delayed.remove(id);
            if (!player.isOnline()) return;
            if (isInWorld(player) && !muted.contains(id)) startFor(player);
            else stopFor(player, true);
        }, delay);
        TaskHandler previous = delayed.put(id, task);
        if (previous != null) previous.cancel();
    }

    private void onJoin(PlayerJoinEvent event) {
        queueReconcile(event.getPlayer(), 20);
    }

    private void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        cancelDelayed(player.getUniqueId());
        stopFor(player, false);
        muted.remove(player.getUniqueId());
    }

    private void onLevelChange(EntityLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) queueReconcile(player, 1);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "status" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "start": return startCommand(sender, args);
            case "stop": return stopCommand(sender, args);
            case "status": return statusCommand(sender, args);
            case "reload": return reloadCommand(sender);
            default:
                sender.sendMessage("§e/vmusic start [игрок] §7— начать музыку (админы)");
                sender.sendMessage("§e/vmusic stop [игрок] §7— прекратить музыку");
                sender.sendMessage("§e/vmusic status §7— состояние (админы)");
                sender.sendMessage("§e/vmusic reload §7— перечитать настройки (админы)");
                return true;
        }
    }

    private boolean checkAdmin(CommandSender sender) {
        if (sender.hasPermission("voidmusic.admin")) return true;
        sender.sendMessage("§cНет прав (нужно voidmusic.admin).");
        return false;
    }

    // Join remaining arguments so Bedrock names containing spaces can be used.
    private Player resolvePlayer(CommandSender sender, String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) return player;
            sender.sendMessage("§cКонсоль: укажите игрока.");
            return null;
        }
        String name = String.join(" ", java.util.Arrays.copyOfRange(args, 1, args.length));
        Player result = getServer().getPlayerExact(name);
        if (result == null || !result.isOnline()) { sender.sendMessage("§cИгрок не найден."); return null; }
        return result;
    }

    private boolean startCommand(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) return true;
        Player player = resolvePlayer(sender, args);
        if (player == null) return true;
        muted.remove(player.getUniqueId());
        startFor(player);
        sender.sendMessage("§aМузыка запущена для " + player.getName() + ".");
        return true;
    }

    private boolean stopCommand(CommandSender sender, String[] args) {
        if (args.length > 1 && !checkAdmin(sender)) return true;
        if (args.length < 2 && sender instanceof Player && !sender.hasPermission("voidmusic.stop")) {
            sender.sendMessage("§cНет прав (нужно voidmusic.stop)."); return true;
        }
        Player player = resolvePlayer(sender, args);
        if (player == null) return true;
        muted.add(player.getUniqueId());
        cancelDelayed(player.getUniqueId());
        stopFor(player, true);
        sender.sendMessage("§eМузыка выключена для " + player.getName() + ". §7Вернётся при перезаходе.");
        return true;
    }

    private boolean statusCommand(CommandSender sender, String[] args) {
        if (!checkAdmin(sender)) return true;
        sender.sendMessage("§eVoidMusic: мир=" + worldName + ", звук=" + sound + ", цикл=" + trackSeconds + "с + " + pauseSeconds + "с");
        Player player = args.length > 1 ? resolvePlayer(sender, args) : sender instanceof Player p ? p : null;
        if (player != null) {
            UUID id = player.getUniqueId();
            String state = loops.containsKey(id) ? "§aиграет" : muted.contains(id) ? "§cвыключена игроком" : "§7не запущена";
            sender.sendMessage("§e" + player.getName() + ": " + state + (isInWorld(player) ? " (в мире спавна)" : " (в другом мире)"));
        }
        return true;
    }

    private boolean reloadCommand(CommandSender sender) {
        if (!checkAdmin(sender)) return true;
        // Stop the old sound before reading a potentially different sound name.
        for (UUID id : new ArrayList<>(loops.keySet())) {
            Player player = getServer().getPlayer(id).orElse(null);
            if (player != null) stopFor(player, true);
            else { TaskHandler task = loops.remove(id); if (task != null) task.cancel(); }
        }
        reloadConfig();
        readSettings();
        for (Player player : getServer().getOnlinePlayers().values()) {
            if (!muted.contains(player.getUniqueId()) && isInWorld(player)) queueReconcile(player, 1);
        }
        sender.sendMessage("§aVoidMusic перезагружен: мир=" + worldName + ", цикл=" + trackSeconds + "с + " + pauseSeconds + "с.");
        return true;
    }
}
