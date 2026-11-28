package com.musd348.lmtops;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PlayerListener implements Listener {
    private final LMtops plugin;
    private final Map<UUID, Long> joinTimes = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new HashMap<>();

    public PlayerListener(LMtops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        joinTimes.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (joinTimes.containsKey(uuid)) {
            long sessionSeconds = (System.currentTimeMillis() - joinTimes.get(uuid)) / 1000;
            plugin.getDataManager().addStat(uuid, "playtime", sessionSeconds);
            joinTimes.remove(uuid);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        plugin.getDataManager().addStat(event.getPlayer().getUniqueId(), "blocks", 1);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        plugin.getDataManager().addStat(victim.getUniqueId(), "deaths", 1);

        Player killer = victim.getKiller();
        if (killer != null && !killer.equals(victim)) {
            long cooldown = plugin.getConfig().getLong("kill-farm-interval-seconds", 60) * 1000L;
            long now = System.currentTimeMillis();

            Map<UUID, Long> targetMap = killCooldowns.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
            long lastKill = targetMap.getOrDefault(victim.getUniqueId(), 0L);

            if (now - lastKill >= cooldown) {
                plugin.getDataManager().addStat(killer.getUniqueId(), "kills", 1);
                targetMap.put(victim.getUniqueId(), now);
            }
        }
    }
}