package com.musd348.lmtops;

import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class LMtops extends JavaPlugin {
    private static LMtops instance;
    private Economy econ;
    private PlayerPointsAPI ppAPI;
    private DataManager dataManager;
    private TopManager topManager;
    private HologramManager hologramManager;
    private TopGUI topGUI;
    private PlayerListener playerListener;
    private BukkitTask refreshTask, holoTask, saveTask;

    @Override public void onEnable() {
        instance = this;
        saveDefaultConfig();
        try { dataManager = new DataManager(this); }
        catch (IllegalStateException ex) {
            getLogger().severe(ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        if (!setupEconomy()) getLogger().warning("Экономика Vault недоступна; топ баланса будет пуст.");
        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            try { ppAPI = PlayerPoints.getInstance().getAPI(); }
            catch (RuntimeException | LinkageError ex) { getLogger().warning("Не удалось подключить PlayerPoints: " + ex.getMessage()); }
        }
        topManager = new TopManager(this);
        hologramManager = new HologramManager(this);
        topGUI = new TopGUI(this);
        playerListener = new PlayerListener(this);
        getCommand("top").setExecutor(new TopCommand(this));
        getCommand("topholo").setExecutor(new HologramCommand(this));
        getServer().getPluginManager().registerEvents(playerListener, this);
        getServer().getPluginManager().registerEvents(topGUI, this);
        getServer().getPluginManager().registerEvents(hologramManager, this);
        scheduleTasks();
        topManager.updateCache();
        Bukkit.getScheduler().runTaskLater(this, () -> hologramManager.updateAllHolograms(), 40L);
        getLogger().info("LMtops 2.0.1 запущен; обновление топов распределяется по тикам.");
    }
    private static long interval(long value, long minimum, long multiplier) {
        return Math.max(minimum, Math.min(value, 604800L)) * multiplier;
    }
    private void scheduleTasks() {
        if (refreshTask != null) refreshTask.cancel();
        if (holoTask != null) holoTask.cancel();
        if (saveTask != null) saveTask.cancel();
        long refresh = interval(getConfig().getLong("cache-update-interval", 300), 5, 20);
        long holo = interval(getConfig().getLong("holograms.update-interval", 300), 5, 20);
        long save = interval(getConfig().getLong("auto-save-interval", 5), 1, 1200);
        refreshTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            playerListener.flushOnlineTime(); topManager.updateCache();
        }, refresh, refresh);
        holoTask = Bukkit.getScheduler().runTaskTimer(this, () -> hologramManager.updateAllHolograms(), holo, holo);
        saveTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            playerListener.flushOnlineTime(); dataManager.saveAll();
        }, save, save);
    }
    @Override public void onDisable() {
        if (topManager != null) topManager.stop();
        if (playerListener != null) playerListener.flushOnlineTime();
        if (dataManager != null) dataManager.saveAll();
        if (hologramManager != null) hologramManager.removeAllHolograms();
        instance = null;
    }
    public void performReload() { reloadPlugin(); }
    public void reloadPlugin() {
        reloadConfig();
        if (playerListener != null) playerListener.flushOnlineTime();
        if (dataManager != null) dataManager.saveAll();
        if (topManager != null) { topManager.stop(); topManager.updateCache(); }
        if (hologramManager != null) hologramManager.updateAllHolograms();
        scheduleTasks();
    }
    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("Vault") == null) return false;
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) return false;
        econ = rsp.getProvider();
        return econ != null;
    }
    public static LMtops getInstance() { return instance; }
    public Economy getEconomy() { return econ; }
    public PlayerPointsAPI getPpAPI() { return ppAPI; }
    public DataManager getDataManager() { return dataManager; }
    public TopManager getTopManager() { return topManager; }
    public HologramManager getHologramManager() { return hologramManager; }
    public TopGUI getTopGUI() { return topGUI; }
}
