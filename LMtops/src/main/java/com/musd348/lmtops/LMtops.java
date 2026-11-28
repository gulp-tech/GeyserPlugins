package com.musd348.lmtops;

import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class LMtops extends JavaPlugin {

    private static LMtops instance;
    private Economy econ = null;
    private PlayerPointsAPI ppAPI = null;
    private DataManager dataManager;
    private TopManager topManager;
    private HologramManager hologramManager;
    private TopGUI topGUI;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        this.dataManager = new DataManager(this);
        this.topManager = new TopManager(this);
        this.hologramManager = new HologramManager(this);
        this.topGUI = new TopGUI(this);

        if (!setupEconomy()) {
            getLogger().warning("Vault не найден! Топ по балансу будет недоступен.");
        }

        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            this.ppAPI = PlayerPoints.getInstance().getAPI();
        }

        getCommand("top").setExecutor(new TopCommand(this));
        getCommand("topholo").setExecutor(new HologramCommand(this));

        getServer().getPluginManager().registerEvents(new PlayerListener(this), this);

        long updateInterval = getConfig().getLong("holograms.update-interval", 300) * 20L;

        Bukkit.getScheduler().runTaskLater(this, () -> hologramManager.updateAllHolograms(), 40L);

        Bukkit.getScheduler().runTaskTimer(this, () -> {
            topManager.updateCache();
            hologramManager.updateAllHolograms();
        }, updateInterval, updateInterval);

        getLogger().info("LMtops v2.0.0 успешно запущен (Bedrock Optimization active)!");
    }

    @Override
    public void onDisable() {
        if (dataManager != null) dataManager.saveAll();
        if (hologramManager != null) hologramManager.removeAllHolograms();
    }

    public void performReload() {
        reloadPlugin();
    }

    public void reloadPlugin() {
        reloadConfig();
        if (dataManager != null) dataManager.saveAll();
        if (hologramManager != null) {
            hologramManager.removeAllHolograms();
            hologramManager.updateAllHolograms();
        }
        if (topManager != null) topManager.updateCache();
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