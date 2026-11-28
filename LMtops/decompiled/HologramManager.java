/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.Bukkit
 *  org.bukkit.Color
 *  org.bukkit.Location
 *  org.bukkit.NamespacedKey
 *  org.bukkit.World
 *  org.bukkit.configuration.ConfigurationSection
 *  org.bukkit.configuration.file.FileConfiguration
 *  org.bukkit.entity.Display$Billboard
 *  org.bukkit.entity.Entity
 *  org.bukkit.entity.TextDisplay
 *  org.bukkit.persistence.PersistentDataType
 *  org.bukkit.plugin.Plugin
 */
package com.musd348.lmtops;

import com.musd348.lmtops.ColorUtil;
import com.musd348.lmtops.LMtops;
import com.musd348.lmtops.TopEntry;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.TextDisplay;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

public class HologramManager {
    private final LMtops plugin;
    private static final String HOLO_TAG = "lmtops_hologram";
    private static final String TAG_PREFIX = "lmtops_id_";

    public HologramManager(LMtops plugin) {
        this.plugin = plugin;
    }

    public void loadHolograms() {
        this.updateAllHolograms();
    }

    public void updateAllHolograms() {
        FileConfiguration config = this.plugin.getConfig();
        if (!config.getBoolean("holograms.enabled", true)) {
            return;
        }
        ConfigurationSection section = config.getConfigurationSection("holograms");
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            if (key.equals("enabled") || key.equals("update-interval") || key.equals("background-alpha") || key.equals("default-billboard")) continue;
            ConfigurationSection holoSec = section.getConfigurationSection(key);
            if (holoSec == null || !holoSec.getBoolean("enabled", true)) {
                this.removeHologramEntities(key);
                continue;
            }
            String worldName = holoSec.getString("world", "world");
            World world = Bukkit.getWorld((String)worldName);
            if (world == null) continue;
            double x = holoSec.getDouble("x");
            double y = holoSec.getDouble("y");
            double z = holoSec.getDouble("z");
            Location loc = new Location(world, x, y, z);
            this.spawnOrUpdateHologram(key, loc, holoSec);
        }
    }

    public void spawnOrUpdateHologram(String id, Location location, ConfigurationSection holoSec) {
        String billboardStr;
        TextDisplay textDisplay;
        if (location == null || location.getWorld() == null) {
            return;
        }
        World world = location.getWorld();
        if (!location.getChunk().isLoaded()) {
            location.getChunk().load();
        }
        ArrayList<TextDisplay> existingDisplays = new ArrayList<TextDisplay>();
        for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
            if (!entity.getScoreboardTags().contains(TAG_PREFIX + id)) continue;
            existingDisplays.add((TextDisplay)entity);
        }
        if (existingDisplays.isEmpty()) {
            textDisplay = (TextDisplay)world.spawn(location, TextDisplay.class, display -> {
                display.addScoreboardTag(HOLO_TAG);
                display.addScoreboardTag(TAG_PREFIX + id);
                display.setPersistent(true);
            });
        } else {
            textDisplay = (TextDisplay)existingDisplays.get(0);
            if (!textDisplay.getLocation().equals((Object)location)) {
                textDisplay.teleport(location);
            }
            for (int i = 1; i < existingDisplays.size(); ++i) {
                ((TextDisplay)existingDisplays.get(i)).remove();
            }
        }
        FileConfiguration config = this.plugin.getConfig();
        boolean bedrockOpt = config.getBoolean("bedrock-optimization.enabled", true);
        float viewRange = (float)config.getDouble("bedrock-optimization.view-range", 0.25);
        boolean disableShadow = config.getBoolean("bedrock-optimization.disable-text-shadow", true);
        boolean transparentBg = config.getBoolean("bedrock-optimization.force-transparent-background", true);
        int maxLineWidth = config.getInt("bedrock-optimization.max-line-width", 300);
        textDisplay.setViewRange(bedrockOpt ? viewRange : 1.0f);
        textDisplay.setShadowed(!disableShadow);
        textDisplay.setLineWidth(maxLineWidth);
        if (transparentBg) {
            textDisplay.setBackgroundColor(Color.fromARGB((int)0, (int)0, (int)0, (int)0));
        } else {
            int alpha = config.getInt("holograms.background-alpha", 0);
            textDisplay.setBackgroundColor(Color.fromARGB((int)alpha, (int)0, (int)0, (int)0));
        }
        String string = billboardStr = holoSec != null ? holoSec.getString("billboard") : null;
        if (billboardStr == null) {
            billboardStr = config.getString("holograms.default-billboard", "FIXED");
        }
        try {
            textDisplay.setBillboard(Display.Billboard.valueOf((String)billboardStr.toUpperCase()));
        }
        catch (IllegalArgumentException e) {
            textDisplay.setBillboard(Display.Billboard.FIXED);
        }
        String rawText = this.generateHologramContent(id, holoSec);
        NamespacedKey textKey = new NamespacedKey((Plugin)this.plugin, "last_rendered_text");
        String currentText = (String)textDisplay.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);
        if (currentText == null || !currentText.equals(rawText)) {
            textDisplay.text(ColorUtil.toComponent(rawText));
            textDisplay.getPersistentDataContainer().set(textKey, PersistentDataType.STRING, (Object)rawText);
        }
    }

    private String generateHologramContent(String id, ConfigurationSection holoSec) {
        if (holoSec == null) {
            return "";
        }
        String type = holoSec.getString("type", id);
        List rawLines = holoSec.getStringList("lines");
        if (rawLines.isEmpty()) {
            return "";
        }
        List<TopEntry> entries = this.plugin.getTopManager().getTop(type);
        String emptyName = this.plugin.getConfig().getString("messages.empty-placeholder", "\u041f\u0443\u0441\u0442\u043e");
        String emptyVal = this.plugin.getConfig().getString("messages.empty-value", "0");
        String currSym = this.plugin.getConfig().getString("currency.balance-symbol", "$");
        String shardSym = this.plugin.getConfig().getString("currency.shards-name", "\u043e\u0441\u043a.");
        HashMap<Object, String> replacements = new HashMap<Object, String>();
        for (int i = 1; i <= 10; ++i) {
            if (i <= entries.size()) {
                TopEntry entry = entries.get(i - 1);
                String name = entry.name();
                String valStr = this.formatValue(type, entry.value(), currSym, shardSym);
                replacements.put("{top_name_" + i + "}", name);
                replacements.put("{top_value_" + i + "}", valStr);
                continue;
            }
            replacements.put("{top_name_" + i + "}", emptyName);
            replacements.put("{top_value_" + i + "}", emptyVal);
        }
        String timeNow = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        replacements.put("{update_time}", timeNow);
        ArrayList<String> formattedLines = new ArrayList<String>();
        Iterator iterator = rawLines.iterator();
        while (iterator.hasNext()) {
            String line;
            String processed = line = (String)iterator.next();
            for (Map.Entry r : replacements.entrySet()) {
                processed = processed.replace((CharSequence)r.getKey(), (CharSequence)r.getValue());
            }
            formattedLines.add(processed);
        }
        return ColorUtil.color(String.join((CharSequence)"\n", formattedLines));
    }

    private String formatValue(String type, double value, String currSym, String shardSym) {
        return switch (type.toLowerCase()) {
            case "balance" -> String.format("%.2f", value);
            case "kills", "deaths", "blocks" -> String.format("%,d", (long)value);
            case "shards" -> String.format("%,d", (long)value);
            case "playtime" -> {
                long totalSeconds = (long)value;
                long hours = totalSeconds / 3600L;
                long minutes = totalSeconds % 3600L / 60L;
                yield String.format("%d\u0447 %d\u043c\u0438\u043d", hours, minutes);
            }
            default -> String.valueOf(value);
        };
    }

    public boolean createHologram(String id, Location loc, String type) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("holograms");
        if (section == null) {
            section = this.plugin.getConfig().createSection("holograms");
        }
        if (section.contains(id)) {
            return false;
        }
        ConfigurationSection holo = section.createSection(id);
        holo.set("enabled", (Object)true);
        holo.set("world", (Object)loc.getWorld().getName());
        holo.set("x", (Object)loc.getX());
        holo.set("y", (Object)loc.getY());
        holo.set("z", (Object)loc.getZ());
        holo.set("type", (Object)type);
        holo.set("billboard", (Object)"FIXED");
        List<String> defaultLines = List.of("&#FFD700&l\u2726 \u0422\u041e\u041f " + id.toUpperCase() + " \u2726", "&#AAAAAA\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac", "&#FFD700\u2776 &#FFFFFF{top_name_1} &8- &#FFD700{top_value_1}", "&#C0C0C0\u2777 &#FFFFFF{top_name_2} &8- &#C0C0C0{top_value_2}", "&#CD7F32\u2778 &#FFFFFF{top_name_3} &8- &#CD7F32{top_value_3}", "&#AAAAAA\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac\u25ac", "&#777777\u041e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u043e: {update_time}");
        holo.set("lines", defaultLines);
        this.plugin.saveConfig();
        this.spawnOrUpdateHologram(id, loc, holo);
        return true;
    }

    public boolean createHologramAtLocation(String id, Location loc, String type) {
        return this.createHologram(id, loc, type);
    }

    public boolean deleteHologram(String id) {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("holograms");
        if (section == null || !section.contains(id)) {
            return false;
        }
        this.removeHologramEntities(id);
        section.set(id, null);
        this.plugin.saveConfig();
        return true;
    }

    public boolean removeHologram(String id) {
        return this.deleteHologram(id);
    }

    public void removeHologramEntities(String id) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (!entity.getScoreboardTags().contains(HOLO_TAG) || !entity.getScoreboardTags().contains(TAG_PREFIX + id)) continue;
                entity.remove();
            }
        }
    }

    public int purgeAllHologramsInRadius(Location loc, double radius) {
        int removed = 0;
        if (loc == null || loc.getWorld() == null) {
            return 0;
        }
        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            TextDisplay textDisplay;
            if (!(entity instanceof TextDisplay) || !(textDisplay = (TextDisplay)entity).getScoreboardTags().contains(HOLO_TAG)) continue;
            textDisplay.remove();
            ++removed;
        }
        return removed;
    }

    public int purgeAllHologramsInWorlds() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (!entity.getScoreboardTags().contains(HOLO_TAG)) continue;
                entity.remove();
                ++removed;
            }
        }
        return removed;
    }

    public void removeAllHolograms() {
        this.purgeAllHologramsInWorlds();
    }

    public Set<String> getHologramIds() {
        ConfigurationSection section = this.plugin.getConfig().getConfigurationSection("holograms");
        if (section == null) {
            return Collections.emptySet();
        }
        HashSet<String> ids = new HashSet<String>();
        for (String key : section.getKeys(false)) {
            if (key.equals("enabled") || key.equals("update-interval") || key.equals("background-alpha") || key.equals("default-billboard")) continue;
            ids.add(key);
        }
        return ids;
    }
}

