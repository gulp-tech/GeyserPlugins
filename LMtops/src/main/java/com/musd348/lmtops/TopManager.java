package com.musd348.lmtops;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.util.*;
import java.util.stream.Collectors;

public class TopManager {
    private final LMtops plugin;
    private final Map<String, List<TopEntry>> caches = new HashMap<>();

    public TopManager(LMtops plugin) {
        this.plugin = plugin;
        updateCache();
    }

    public void updateCache() {
        caches.put("balance", fetchBalanceTop());
        caches.put("kills", fetchDataTop("kills"));
        caches.put("deaths", fetchDataTop("deaths"));
        caches.put("blocks", fetchDataTop("blocks"));
        caches.put("playtime", fetchDataTop("playtime"));
        caches.put("shards", fetchShardsTop());
    }

    public List<TopEntry> getTop(String type) {
        return caches.getOrDefault(type.toLowerCase(), new ArrayList<>());
    }

    public List<TopEntry> getTopBalance() { return getTop("balance"); }
    public List<TopEntry> getTopKills() { return getTop("kills"); }
    public List<TopEntry> getTopDeaths() { return getTop("deaths"); }
    public List<TopEntry> getTopShards() { return getTop("shards"); }
    public List<TopEntry> getTopBlocks() { return getTop("blocks"); }
    public List<TopEntry> getTopPlaytime() { return getTop("playtime"); }

    private List<TopEntry> fetchBalanceTop() {
        if (plugin.getEconomy() == null) return new ArrayList<>();
        return Arrays.stream(Bukkit.getOfflinePlayers())
                .filter(p -> p.getName() != null)
                .map(p -> new TopEntry(p.getUniqueId(), p.getName(), plugin.getEconomy().getBalance(p)))
                .sorted((a, b) -> Double.compare(b.value(), a.value()))
                .limit(10).collect(Collectors.toList());
    }

    private List<TopEntry> fetchDataTop(String stat) {
        List<TopEntry> list = new ArrayList<>();
        for (String key : plugin.getDataManager().getConfig().getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                OfflinePlayer op = Bukkit.getOfflinePlayer(uuid);
                String name = op.getName() != null ? op.getName() : key;
                double val = plugin.getDataManager().getStat(uuid, stat);
                list.add(new TopEntry(uuid, name, val));
            } catch (Exception ignored) {}
        }
        return list.stream().sorted((a, b) -> Double.compare(b.value(), a.value()))
                .limit(10).collect(Collectors.toList());
    }

    private List<TopEntry> fetchShardsTop() {
        return new ArrayList<>();
    }
}