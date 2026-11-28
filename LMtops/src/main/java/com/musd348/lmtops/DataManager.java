package com.musd348.lmtops;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

public class DataManager {
    private final LMtops plugin;
    private final File file;
    private FileConfiguration config;

    public DataManager(LMtops plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        if (!file.exists()) {
            plugin.saveResource("playerdata.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration(file);
    }

    public void saveAll() {
        try {
            config.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public FileConfiguration getConfig() { return config; }

    public long getStat(UUID uuid, String stat) {
        return config.getLong(uuid.toString() + "." + stat, 0);
    }

    public void addStat(UUID uuid, String stat, long amount) {
        long current = getStat(uuid, stat);
        config.set(uuid.toString() + "." + stat, current + amount);
    }
}