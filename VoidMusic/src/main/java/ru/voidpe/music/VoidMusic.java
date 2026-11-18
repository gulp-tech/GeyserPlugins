package ru.voidpe.music;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * VoidMusic — музыка для игроков в мире спавна.
 *
 * Пока игрок находится в настроенном мире (по умолчанию world), ему играет
 * трек из ресурс-пака VoidMusicPack.mcpack. Трек перезапускается каждые
 * loop-seconds (длительность трека). При выходе из мира звук глушится.
 */
public final class VoidMusic extends JavaPlugin implements Listener {

    private String worldName;
    private String sound;
    private float volume;
    private float pitch;
    private int loopSeconds;

    private final Map<UUID, BukkitTask> loops = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadSettings();
        getServer().getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) {
            startFor(player);
        }
        getLogger().info("VoidMusic включён: мир=" + worldName
                + ", звук=" + sound
                + ", петля=" + loopSeconds + "с");
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
        loopSeconds = getConfig().getInt("loop-seconds", 220);
        if (loopSeconds < 5) {
            loopSeconds = 5;
        }
    }

    private boolean isInWorld(Player player) {
        World world = player.getWorld();
        return world != null && worldName.equalsIgnoreCase(world.getName());
    }

    private void playFor(Player player) {
        Location location = player.getLocation();
        player.playSound(location, sound, volume, pitch);
    }

    /** Запускает (или перезапускает) петлю музыки для игрока. */
    private void startFor(Player player) {
        stopFor(player, false);
        playFor(player);
        long period = loopSeconds * 20L;
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (!player.isOnline() || !isInWorld(player)) {
                stopFor(player, true);
                return;
            }
            playFor(player);
        }, period, period);
        loops.put(player.getUniqueId(), task);
    }

    /** Останавливает петлю и глушит звук у игрока. */
    private void stopFor(Player player, boolean stopSound) {
        BukkitTask task = loops.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        if (stopSound) {
            player.stopSound(sound);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!isInWorld(player)) {
            return;
        }
        // лёгкая задержка: Bedrock-клиент успевает загрузить мир и паки
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (player.isOnline() && isInWorld(player)) {
                startFor(player);
            }
        }, 20L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        if (isInWorld(player)) {
            startFor(player);
        } else {
            stopFor(player, true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        stopFor(event.getPlayer(), false);
    }
}
