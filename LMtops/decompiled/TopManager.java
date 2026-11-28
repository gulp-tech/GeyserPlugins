/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.OfflinePlayer
 */
package com.musd348.lmtops;

import com.musd348.lmtops.LMtops;
import com.musd348.lmtops.TopEntry;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

public class TopManager {
    private final LMtops plugin;
    private final Map<String, List<TopEntry>> caches = new HashMap<String, List<TopEntry>>();

    public TopManager(LMtops plugin) {
        this.plugin = plugin;
        this.updateCache();
    }

    public void updateCache() {
        this.caches.put("balance", this.fetchBalanceTop());
        this.caches.put("kills", this.fetchDataTop("kills"));
        this.caches.put("deaths", this.fetchDataTop("deaths"));
        this.caches.put("blocks", this.fetchDataTop("blocks"));
        this.caches.put("playtime", this.fetchDataTop("playtime"));
        this.caches.put("shards", this.fetchShardsTop());
    }

    public List<TopEntry> getTop(String type) {
        return this.caches.getOrDefault(type.toLowerCase(), new ArrayList());
    }

    public List<TopEntry> getTopBalance() {
        return this.getTop("balance");
    }

    public List<TopEntry> getTopKills() {
        return this.getTop("kills");
    }

    public List<TopEntry> getTopDeaths() {
        return this.getTop("deaths");
    }

    public List<TopEntry> getTopShards() {
        return this.getTop("shards");
    }

    public List<TopEntry> getTopBlocks() {
        return this.getTop("blocks");
    }

    public List<TopEntry> getTopPlaytime() {
        return this.getTop("playtime");
    }

    private List<TopEntry> fetchBalanceTop() {
        if (this.plugin.getEconomy() == null) {
            return new ArrayList<TopEntry>();
        }
        return Arrays.stream(Bukkit.getOfflinePlayers()).filter(p -> p.getName() != null).map(p -> new TopEntry(p.getUniqueId(), p.getName(), this.plugin.getEconomy().getBalance(p))).sorted((a, b) -> Double.compare(b.value(), a.value())).limit(10L).collect(Collectors.toList());
    }

    private List<TopEntry> fetchDataTop(String stat) {
        ArrayList<TopEntry> list = new ArrayList<TopEntry>();
        for (String key : this.plugin.getDataManager().getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                OfflinePlayer op = Bukkit.getOfflinePlayer((UUID)uuid);
                String name = op.getName() != null ? op.getName() : key;
                double val = this.plugin.getDataManager().getStat(uuid, stat);
                list.add(new TopEntry(uuid, name, val));
            }
            catch (Exception exception) {}
        }
        return list.stream().sorted((a, b) -> Double.compare(b.value(), a.value())).limit(10L).collect(Collectors.toList());
    }

    private List<TopEntry> fetchShardsTop() {
        return new ArrayList<TopEntry>();
    }
}

