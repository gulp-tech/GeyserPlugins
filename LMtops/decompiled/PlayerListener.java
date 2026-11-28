/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.entity.Player
 *  org.bukkit.event.EventHandler
 *  org.bukkit.event.Listener
 *  org.bukkit.event.block.BlockBreakEvent
 *  org.bukkit.event.entity.PlayerDeathEvent
 *  org.bukkit.event.player.PlayerJoinEvent
 *  org.bukkit.event.player.PlayerQuitEvent
 */
package com.musd348.lmtops;

import com.musd348.lmtops.LMtops;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class PlayerListener
implements Listener {
    private final LMtops plugin;
    private final Map<UUID, Long> joinTimes = new HashMap<UUID, Long>();
    private final Map<UUID, Map<UUID, Long>> killCooldowns = new HashMap<UUID, Map<UUID, Long>>();

    public PlayerListener(LMtops plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.joinTimes.put(event.getPlayer().getUniqueId(), System.currentTimeMillis());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        if (this.joinTimes.containsKey(uuid)) {
            long sessionSeconds = (System.currentTimeMillis() - this.joinTimes.get(uuid)) / 1000L;
            this.plugin.getDataManager().addStat(uuid, "playtime", sessionSeconds);
            this.joinTimes.remove(uuid);
        }
    }

    @EventHandler
    public void onBreak(BlockBreakEvent event) {
        this.plugin.getDataManager().addStat(event.getPlayer().getUniqueId(), "blocks", 1L);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        this.plugin.getDataManager().addStat(victim.getUniqueId(), "deaths", 1L);
        Player killer = victim.getKiller();
        if (killer != null && !killer.equals((Object)victim)) {
            Map targetMap;
            long lastKill;
            long cooldown = this.plugin.getConfig().getLong("kill-farm-interval-seconds", 60L) * 1000L;
            long now = System.currentTimeMillis();
            if (now - (lastKill = (targetMap = this.killCooldowns.computeIfAbsent(killer.getUniqueId(), k -> new HashMap())).getOrDefault(victim.getUniqueId(), 0L).longValue()) >= cooldown) {
                this.plugin.getDataManager().addStat(killer.getUniqueId(), "kills", 1L);
                targetMap.put(victim.getUniqueId(), now);
            }
        }
    }
}

