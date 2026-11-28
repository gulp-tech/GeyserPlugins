package com.musd348.lmtops;

import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.black_ixx.playerpoints.models.SortedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.scheduler.BukkitTask;
import java.util.*;

/** Bukkit/Vault stay on main thread, distributed across ticks. PlayerPoints SQL is asynchronous. */
public final class TopManager {
    private final LMtops plugin;
    private final Map<String, List<TopEntry>> caches = new HashMap<>();
    private BukkitTask scanTask;
    private long generation;
    private boolean pointsRunning;
    public TopManager(LMtops plugin) { this.plugin = plugin; }

    public void stop() {
        ++generation;
        if (scanTask != null) scanTask.cancel();
        scanTask = null;
    }
    public void updateCache() {
        if (scanTask != null) return;
        final long cycle = ++generation;
        final int limit = Math.max(1, Math.min(10, plugin.getConfig().getInt("top-limit", 10)));
        final int perTick = Math.max(1, Math.min(200, plugin.getConfig().getInt("players-per-tick", 25)));
        Map<String, PriorityQueue<TopEntry>> queues = new HashMap<>();
        Comparator<TopEntry> ascending = Comparator.comparingDouble(TopEntry::value).thenComparing(TopEntry::name);
        for (String key : List.of("balance", "kills", "deaths", "blocks", "playtime")) queues.put(key, new PriorityQueue<>(ascending));
        Iterator<OfflinePlayer> players = Arrays.asList(Bukkit.getOfflinePlayers()).iterator();
        Iterator<String> data = new ArrayList<>(plugin.getDataManager().getConfig().getKeys(false)).iterator();
        scanTask = Bukkit.getScheduler().runTaskTimer(plugin, new Runnable() {
            boolean balanceError;
            void add(String type, TopEntry entry) {
                PriorityQueue<TopEntry> q = queues.get(type); q.add(entry);
                if (q.size() > limit) q.poll();
            }
            @Override public void run() {
                int remaining = perTick;
                while (remaining > 0 && players.hasNext()) {
                    OfflinePlayer p = players.next(); remaining--;
                    if (plugin.getEconomy() == null || p.getName() == null) continue;
                    try { add("balance", new TopEntry(p.getUniqueId(), p.getName(), plugin.getEconomy().getBalance(p))); }
                    catch (RuntimeException ex) { balanceError = true; }
                }
                while (remaining > 0 && !players.hasNext() && data.hasNext()) {
                    String key = data.next(); remaining--;
                    try {
                        UUID uuid = UUID.fromString(key);
                        OfflinePlayer p = Bukkit.getOfflinePlayer(uuid);
                        String name = p.getName() != null ? p.getName() : key;
                        for (String stat : List.of("kills", "deaths", "blocks", "playtime"))
                            add(stat, new TopEntry(uuid, name, plugin.getDataManager().getStat(uuid, stat)));
                    } catch (IllegalArgumentException ignored) { }
                }
                if (players.hasNext() || data.hasNext()) return;
                for (Map.Entry<String, PriorityQueue<TopEntry>> entry : queues.entrySet()) {
                    if (entry.getKey().equals("balance") && balanceError) continue;
                    List<TopEntry> result = new ArrayList<>(entry.getValue());
                    result.sort(ascending.reversed()); caches.put(entry.getKey(), List.copyOf(result));
                }
                if (balanceError) plugin.getLogger().warning("Vault не вернул некоторые балансы; прежний топ сохранён.");
                scanTask.cancel(); scanTask = null;
                if (plugin.getHologramManager() != null) plugin.getHologramManager().updateAllHolograms();
            }
        }, 1L, 1L);
        refreshPoints(cycle, limit);
    }
    private void refreshPoints(long cycle, int limit) {
        PlayerPointsAPI api = plugin.getPpAPI();
        if (api == null || pointsRunning) return;
        pointsRunning = true;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<TopEntry> result = null;
            String failure = null;
            try {
                result = readPoints(api, limit);
            } catch (RuntimeException ex) { failure = ex.getMessage(); }
            List<TopEntry> finalResult = result == null ? null : List.copyOf(result);
            String finalFailure = failure;
            if (!plugin.isEnabled()) return;
            Bukkit.getScheduler().runTask(plugin, () -> {
                pointsRunning = false;
                if (cycle != generation) return;
                if (finalResult == null) plugin.getLogger().warning("PlayerPoints недоступен; прежний топ сохранён: " + finalFailure);
                else {
                    caches.put("shards", finalResult);
                    if (scanTask == null && plugin.getHologramManager() != null) plugin.getHologramManager().updateAllHolograms();
                }
            });
        });
    }
    static List<TopEntry> readPoints(PlayerPointsAPI api, int limit) {
        List<TopEntry> result = new ArrayList<>();
        for (SortedPlayer p : api.getTopSortedPoints(limit)) {
            String name = p.getUsername();
            result.add(new TopEntry(p.getUniqueId(), name == null ? p.getUniqueId().toString() : name, p.getPoints()));
        }
        return result;
    }
    public List<TopEntry> getTop(String type) { return type == null ? List.of() : caches.getOrDefault(type.toLowerCase(Locale.ROOT), List.of()); }
    public List<TopEntry> getTopBalance() { return getTop("balance"); }
    public List<TopEntry> getTopKills() { return getTop("kills"); }
    public List<TopEntry> getTopDeaths() { return getTop("deaths"); }
    public List<TopEntry> getTopShards() { return getTop("shards"); }
    public List<TopEntry> getTopBlocks() { return getTop("blocks"); }
    public List<TopEntry> getTopPlaytime() { return getTop("playtime"); }
}
