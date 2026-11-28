package com.musd348.lmtops;

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

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class HologramManager {

    private final LMtops plugin;
    private static final String HOLO_TAG = "lmtops_hologram";
    private static final String TAG_PREFIX = "lmtops_id_";

    public HologramManager(LMtops plugin) {
        this.plugin = plugin;
    }

    public void loadHolograms() {
        updateAllHolograms();
    }

    public void updateAllHolograms() {
        FileConfiguration config = plugin.getConfig();
        if (!config.getBoolean("holograms.enabled", true)) return;

        ConfigurationSection section = config.getConfigurationSection("holograms");
        if (section == null) return;

        for (String key : section.getKeys(false)) {
            if (key.equals("enabled") || key.equals("update-interval") ||
                    key.equals("background-alpha") || key.equals("default-billboard")) continue;

            ConfigurationSection holoSec = section.getConfigurationSection(key);
            if (holoSec == null || !holoSec.getBoolean("enabled", true)) {
                removeHologramEntities(key);
                continue;
            }

            String worldName = holoSec.getString("world", "world");
            World world = Bukkit.getWorld(worldName);
            if (world == null) continue;

            double x = holoSec.getDouble("x");
            double y = holoSec.getDouble("y");
            double z = holoSec.getDouble("z");
            Location loc = new Location(world, x, y, z);

            spawnOrUpdateHologram(key, loc, holoSec);
        }
    }

    public void spawnOrUpdateHologram(String id, Location location, ConfigurationSection holoSec) {
        if (location == null || location.getWorld() == null) return;

        World world = location.getWorld();

        // 1. Принудительно загружаем чанк, чтобы Bukkit видел сущности
        if (!location.getChunk().isLoaded()) {
            location.getChunk().load();
        }

        // 2. Ищем ВСЕ существующие сущности с ID этой голограммы во всем мире
        List<TextDisplay> existingDisplays = new ArrayList<>();
        for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
            if (entity.getScoreboardTags().contains(TAG_PREFIX + id)) {
                existingDisplays.add((TextDisplay) entity);
            }
        }

        TextDisplay textDisplay;

        // 3. ЖЕСТКАЯ ЗАЩИТА ОТ ДУБЛИКАТОВ:
        if (existingDisplays.isEmpty()) {
            // Если голограммы нет — спавним одну новую
            textDisplay = world.spawn(location, TextDisplay.class, display -> {
                display.addScoreboardTag(HOLO_TAG);
                display.addScoreboardTag(TAG_PREFIX + id);
                display.setPersistent(true);
            });
        } else {
            // Если нашли одну или несколько — берем первую
            textDisplay = existingDisplays.get(0);

            // Телепортируем на актуальные координаты из конфига
            if (!textDisplay.getLocation().equals(location)) {
                textDisplay.teleport(location);
            }

            // МГНОВЕННО УДАЛЯЕМ ВСЕ ЛИШНИЕ ДУБЛИКАТЫ (копии #2, #3, #4 и т.д.)
            for (int i = 1; i < existingDisplays.size(); i++) {
                existingDisplays.get(i).remove();
            }
        }

        FileConfiguration config = plugin.getConfig();

        // 4. Оптимизация для Bedrock (GeyserMC)
        boolean bedrockOpt = config.getBoolean("bedrock-optimization.enabled", true);
        float viewRange = (float) config.getDouble("bedrock-optimization.view-range", 0.25);
        boolean disableShadow = config.getBoolean("bedrock-optimization.disable-text-shadow", true);
        boolean transparentBg = config.getBoolean("bedrock-optimization.force-transparent-background", true);
        int maxLineWidth = config.getInt("bedrock-optimization.max-line-width", 300);

        textDisplay.setViewRange(bedrockOpt ? viewRange : 1.0f);
        textDisplay.setShadowed(!disableShadow);
        textDisplay.setLineWidth(maxLineWidth);

        if (transparentBg) {
            textDisplay.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
        } else {
            int alpha = config.getInt("holograms.background-alpha", 0);
            textDisplay.setBackgroundColor(Color.fromARGB(alpha, 0, 0, 0));
        }

        String billboardStr = holoSec != null ? holoSec.getString("billboard") : null;
        if (billboardStr == null) billboardStr = config.getString("holograms.default-billboard", "FIXED");

        try {
            textDisplay.setBillboard(Display.Billboard.valueOf(billboardStr.toUpperCase()));
        } catch (IllegalArgumentException e) {
            textDisplay.setBillboard(Display.Billboard.FIXED);
        }

        // 5. Кэширование текста (не отправляет пакеты рендера, если текст не менялся)
        String rawText = generateHologramContent(id, holoSec);
        NamespacedKey textKey = new NamespacedKey(plugin, "last_rendered_text");
        String currentText = textDisplay.getPersistentDataContainer().get(textKey, PersistentDataType.STRING);

        if (currentText == null || !currentText.equals(rawText)) {
            textDisplay.text(ColorUtil.toComponent(rawText));
            textDisplay.getPersistentDataContainer().set(textKey, PersistentDataType.STRING, rawText);
        }
    }

    private String generateHologramContent(String id, ConfigurationSection holoSec) {
        if (holoSec == null) return "";

        String type = holoSec.getString("type", id);
        List<String> rawLines = holoSec.getStringList("lines");
        if (rawLines.isEmpty()) return "";

        List<TopEntry> entries = plugin.getTopManager().getTop(type);
        String emptyName = plugin.getConfig().getString("messages.empty-placeholder", "Пусто");
        String emptyVal = plugin.getConfig().getString("messages.empty-value", "0");
        String currSym = plugin.getConfig().getString("currency.balance-symbol", "$");
        String shardSym = plugin.getConfig().getString("currency.shards-name", "оск.");

        Map<String, String> replacements = new HashMap<>();
        for (int i = 1; i <= 10; i++) {
            if (i <= entries.size()) {
                TopEntry entry = entries.get(i - 1);
                String name = entry.name();
                String valStr = formatValue(type, entry.value(), currSym, shardSym);
                replacements.put("{top_name_" + i + "}", name);
                replacements.put("{top_value_" + i + "}", valStr);
            } else {
                replacements.put("{top_name_" + i + "}", emptyName);
                replacements.put("{top_value_" + i + "}", emptyVal);
            }
        }

        String timeNow = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"));
        replacements.put("{update_time}", timeNow);

        List<String> formattedLines = new ArrayList<>();
        for (String line : rawLines) {
            String processed = line;
            for (Map.Entry<String, String> r : replacements.entrySet()) {
                processed = processed.replace(r.getKey(), r.getValue());
            }
            formattedLines.add(processed);
        }

        return ColorUtil.color(String.join("\n", formattedLines));
    }

    private String formatValue(String type, double value, String currSym, String shardSym) {
        return switch (type.toLowerCase()) {
            case "balance" -> String.format("%.2f", value);
            case "kills", "deaths", "blocks" -> String.format("%,d", (long) value);
            case "shards" -> String.format("%,d", (long) value);
            case "playtime" -> {
                long totalSeconds = (long) value;
                long hours = totalSeconds / 3600;
                long minutes = (totalSeconds % 3600) / 60;
                yield String.format("%dч %dмин", hours, minutes);
            }
            default -> String.valueOf(value);
        };
    }

    public boolean createHologram(String id, Location loc, String type) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("holograms");
        if (section == null) section = plugin.getConfig().createSection("holograms");
        if (section.contains(id)) return false;

        ConfigurationSection holo = section.createSection(id);
        holo.set("enabled", true);
        holo.set("world", loc.getWorld().getName());
        holo.set("x", loc.getX());
        holo.set("y", loc.getY());
        holo.set("z", loc.getZ());
        holo.set("type", type);
        holo.set("billboard", "FIXED");

        List<String> defaultLines = List.of(
                "&#FFD700&l✦ ТОП " + id.toUpperCase() + " ✦",
                "&#AAAAAA▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                "&#FFD700❶ &#FFFFFF{top_name_1} &8- &#FFD700{top_value_1}",
                "&#C0C0C0❷ &#FFFFFF{top_name_2} &8- &#C0C0C0{top_value_2}",
                "&#CD7F32❸ &#FFFFFF{top_name_3} &8- &#CD7F32{top_value_3}",
                "&#AAAAAA▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬",
                "&#777777Обновлено: {update_time}"
        );
        holo.set("lines", defaultLines);
        plugin.saveConfig();

        spawnOrUpdateHologram(id, loc, holo);
        return true;
    }

    public boolean createHologramAtLocation(String id, Location loc, String type) {
        return createHologram(id, loc, type);
    }

    public boolean deleteHologram(String id) {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("holograms");
        if (section == null || !section.contains(id)) return false;

        removeHologramEntities(id);
        section.set(id, null);
        plugin.saveConfig();
        return true;
    }

    public boolean removeHologram(String id) {
        return deleteHologram(id);
    }

    public void removeHologramEntities(String id) {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getScoreboardTags().contains(HOLO_TAG) && entity.getScoreboardTags().contains(TAG_PREFIX + id)) {
                    entity.remove();
                }
            }
        }
    }

    public int purgeAllHologramsInRadius(Location loc, double radius) {
        int removed = 0;
        if (loc == null || loc.getWorld() == null) return 0;

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, radius, radius, radius)) {
            if (entity instanceof TextDisplay textDisplay && textDisplay.getScoreboardTags().contains(HOLO_TAG)) {
                textDisplay.remove();
                removed++;
            }
        }
        return removed;
    }

    public int purgeAllHologramsInWorlds() {
        int removed = 0;
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntitiesByClass(TextDisplay.class)) {
                if (entity.getScoreboardTags().contains(HOLO_TAG)) {
                    entity.remove();
                    removed++;
                }
            }
        }
        return removed;
    }

    public void removeAllHolograms() {
        purgeAllHologramsInWorlds();
    }

    public Set<String> getHologramIds() {
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("holograms");
        if (section == null) return Collections.emptySet();

        Set<String> ids = new HashSet<>();
        for (String key : section.getKeys(false)) {
            if (!key.equals("enabled") && !key.equals("update-interval") &&
                    !key.equals("background-alpha") && !key.equals("default-billboard")) {
                ids.add(key);
            }
        }
        return ids;
    }
}