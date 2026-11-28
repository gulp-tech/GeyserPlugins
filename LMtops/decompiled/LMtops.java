/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  net.milkbowl.vault.economy.Economy
 *  org.black_ixx.playerpoints.PlayerPoints
 *  org.black_ixx.playerpoints.PlayerPointsAPI
 *  org.bukkit.Bukkit
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.event.Listener
 *  org.bukkit.plugin.Plugin
 *  org.bukkit.plugin.RegisteredServiceProvider
 *  org.bukkit.plugin.java.JavaPlugin
 */
package com.musd348.lmtops;

import com.musd348.lmtops.DataManager;
import com.musd348.lmtops.HologramCommand;
import com.musd348.lmtops.HologramManager;
import com.musd348.lmtops.PlayerListener;
import com.musd348.lmtops.TopCommand;
import com.musd348.lmtops.TopGUI;
import com.musd348.lmtops.TopManager;
import net.milkbowl.vault.economy.Economy;
import org.black_ixx.playerpoints.PlayerPoints;
import org.black_ixx.playerpoints.PlayerPointsAPI;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandExecutor;
import org.bukkit.event.Listener;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public class LMtops
extends JavaPlugin {
    private static LMtops instance;
    private Economy econ = null;
    private PlayerPointsAPI ppAPI = null;
    private DataManager dataManager;
    private TopManager topManager;
    private HologramManager hologramManager;
    private TopGUI topGUI;

    public void onEnable() {
        instance = this;
        this.saveDefaultConfig();
        this.dataManager = new DataManager(this);
        this.topManager = new TopManager(this);
        this.hologramManager = new HologramManager(this);
        this.topGUI = new TopGUI(this);
        if (!this.setupEconomy()) {
            this.getLogger().warning("Vault \u043d\u0435 \u043d\u0430\u0439\u0434\u0435\u043d! \u0422\u043e\u043f \u043f\u043e \u0431\u0430\u043b\u0430\u043d\u0441\u0443 \u0431\u0443\u0434\u0435\u0442 \u043d\u0435\u0434\u043e\u0441\u0442\u0443\u043f\u0435\u043d.");
        }
        if (Bukkit.getPluginManager().isPluginEnabled("PlayerPoints")) {
            this.ppAPI = PlayerPoints.getInstance().getAPI();
        }
        this.getCommand("top").setExecutor((CommandExecutor)new TopCommand(this));
        this.getCommand("topholo").setExecutor((CommandExecutor)new HologramCommand(this));
        this.getServer().getPluginManager().registerEvents((Listener)new PlayerListener(this), (Plugin)this);
        long updateInterval = this.getConfig().getLong("holograms.update-interval", 300L) * 20L;
        Bukkit.getScheduler().runTaskLater((Plugin)this, () -> this.hologramManager.updateAllHolograms(), 40L);
        Bukkit.getScheduler().runTaskTimer((Plugin)this, () -> {
            this.topManager.updateCache();
            this.hologramManager.updateAllHolograms();
        }, updateInterval, updateInterval);
        this.getLogger().info("LMtops v2.0.0 \u0443\u0441\u043f\u0435\u0448\u043d\u043e \u0437\u0430\u043f\u0443\u0449\u0435\u043d (Bedrock Optimization active)!");
    }

    public void onDisable() {
        if (this.dataManager != null) {
            this.dataManager.saveAll();
        }
        if (this.hologramManager != null) {
            this.hologramManager.removeAllHolograms();
        }
    }

    public void performReload() {
        this.reloadPlugin();
    }

    public void reloadPlugin() {
        this.reloadConfig();
        if (this.dataManager != null) {
            this.dataManager.saveAll();
        }
        if (this.hologramManager != null) {
            this.hologramManager.removeAllHolograms();
            this.hologramManager.updateAllHolograms();
        }
        if (this.topManager != null) {
            this.topManager.updateCache();
        }
    }

    private boolean setupEconomy() {
        if (this.getServer().getPluginManager().getPlugin("Vault") == null) {
            return false;
        }
        RegisteredServiceProvider rsp = this.getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            return false;
        }
        this.econ = (Economy)rsp.getProvider();
        return this.econ != null;
    }

    public static LMtops getInstance() {
        return instance;
    }

    public Economy getEconomy() {
        return this.econ;
    }

    public PlayerPointsAPI getPpAPI() {
        return this.ppAPI;
    }

    public DataManager getDataManager() {
        return this.dataManager;
    }

    public TopManager getTopManager() {
        return this.topManager;
    }

    public HologramManager getHologramManager() {
        return this.hologramManager;
    }

    public TopGUI getTopGUI() {
        return this.topGUI;
    }
}

