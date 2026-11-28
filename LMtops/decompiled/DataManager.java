/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.configuration.file.FileConfiguration
 *  org.bukkit.configuration.file.YamlConfiguration
 */
package com.musd348.lmtops;

import com.musd348.lmtops.LMtops;
import java.io.File;
import java.io.IOException;
import java.util.UUID;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;

public class DataManager {
    private final LMtops plugin;
    private final File file;
    private FileConfiguration config;

    public DataManager(LMtops plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        if (!this.file.exists()) {
            plugin.saveResource("playerdata.yml", false);
        }
        this.config = YamlConfiguration.loadConfiguration((File)this.file);
    }

    public void saveAll() {
        try {
            this.config.save(this.file);
        }
        catch (IOException e) {
            e.printStackTrace();
        }
    }

    public FileConfiguration getConfig() {
        return this.config;
    }

    public long getStat(UUID uuid, String stat) {
        return this.config.getLong(uuid.toString() + "." + stat, 0L);
    }

    public void addStat(UUID uuid, String stat, long amount) {
        long current = this.getStat(uuid, stat);
        this.config.set(uuid.toString() + "." + stat, (Object)(current + amount));
    }
}

