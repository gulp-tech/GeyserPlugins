package com.musd348.lmtops;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class PlayerListener implements Listener {
    private final LMtops plugin;
    private final Map<UUID, Long> joinTimes = new HashMap<>();
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new HashMap<>();
    public PlayerListener(LMtops plugin) {
        this.plugin = plugin;
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) joinTimes.put(player.getUniqueId(), now);
    }
    @EventHandler public void onJoin(PlayerJoinEvent event) { joinTimes.put(event.getPlayer().getUniqueId(), System.currentTimeMillis()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { recordTime(event.getPlayer().getUniqueId(), System.currentTimeMillis(), false); }
    public void flushOnlineTime() {
        long now = System.currentTimeMillis();
        for (UUID uuid : java.util.List.copyOf(joinTimes.keySet())) recordTime(uuid, now, true);
        long cooldown = Math.max(0, plugin.getConfig().getLong("kill-farm-interval-seconds", 60)) * 1000L;
        killCooldowns.values().forEach(m -> m.values().removeIf(t -> now - t >= cooldown));
        killCooldowns.values().removeIf(Map::isEmpty);
    }
    private void recordTime(UUID uuid, long now, boolean keep) {
        Long start = joinTimes.get(uuid);
        if (start == null) return;
        long seconds = Math.max(0, (now - start) / 1000);
        if (seconds > 0) plugin.getDataManager().addStat(uuid, "playtime", seconds);
        if (keep) joinTimes.put(uuid, start + seconds * 1000); else joinTimes.remove(uuid);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) { plugin.getDataManager().addStat(event.getPlayer().getUniqueId(), "blocks", 1); }
    @EventHandler public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        plugin.getDataManager().addStat(victim.getUniqueId(), "deaths", 1);
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim)) return;
        long cooldown = Math.max(0, plugin.getConfig().getLong("kill-farm-interval-seconds", 60)) * 1000L;
        long now = System.currentTimeMillis();
        Map<UUID, Long> targets = killCooldowns.computeIfAbsent(killer.getUniqueId(), k -> new HashMap<>());
        if (now - targets.getOrDefault(victim.getUniqueId(), 0L) >= cooldown) {
            plugin.getDataManager().addStat(killer.getUniqueId(), "kills", 1);
            targets.put(victim.getUniqueId(), now);
        }
    }
}
