package com.musd348.lmtops;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

/** Main-thread-only data; malformed existing data must never be overwritten. */
public final class DataManager {
    private final LMtops plugin;
    private final File file;
    private final YamlConfiguration config = new YamlConfiguration();
    private boolean dirty;

    public DataManager(LMtops plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "playerdata.yml");
        if (file.exists()) {
            try { config.load(file); }
            catch (IOException | InvalidConfigurationException ex) {
                throw new IllegalStateException("Не удалось прочитать playerdata.yml; файл не будет перезаписан", ex);
            }
        }
    }

    public void saveAll() {
        if (!dirty) return;
        Path temp = null;
        try {
            Path path = file.toPath();
            Files.createDirectories(path.getParent());
            temp = Files.createTempFile(path.getParent(), "playerdata-", ".tmp");
            Files.writeString(temp, config.saveToString(), StandardCharsets.UTF_8);
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ex) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            dirty = false;
        } catch (IOException ex) {
            plugin.getLogger().severe("Не удалось сохранить playerdata.yml: " + ex.getMessage());
        } finally {
            if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
        }
    }
    public FileConfiguration getConfig() { return config; }
    public long getStat(UUID uuid, String stat) { return config.getLong(uuid + "." + stat, 0); }
    public void addStat(UUID uuid, String stat, long amount) {
        if (amount == 0) return;
        config.set(uuid + "." + stat, getStat(uuid, stat) + amount);
        dirty = true;
    }
}
