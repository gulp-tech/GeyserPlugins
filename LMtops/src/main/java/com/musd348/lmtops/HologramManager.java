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
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.persistence.PersistentDataType;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

public class HologramManager implements Listener {

    private final LMtops plugin;
    private Map<World, Map<String, List<TextDisplay>>> indexed;
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

        indexed = new HashMap<>();
        try {
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
        } finally { indexed = null; }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!plugin.getConfig().getBoolean("holograms.enabled", true)) return;
        ConfigurationSection all = plugin.getConfig().getConfigurationSection("holograms");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection section = all.getConfigurationSection(id);
            if (section == null || !section.getBoolean("enabled", true)
                || !event.getWorld().getName().equals(section.getString("world", "world"))) continue;
            int x = (int) Math.floor(section.getDouble("x")) >> 4;
            int z = (int) Math.floor(section.getDouble("z")) >> 4;
            if (event.getChunk().getX() != x || event.getChunk().getZ() != z) continue;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!event.getWorld().isChunkLoaded(x, z)) return;
                ConfigurationSection current = plugin.getConfig().getConfigurationSection("holograms." + id);
                if (current != null && current.getBoolean("enabled", true))
                    spawnOrUpdateHologram(id, new Location(event.getWorld(), current.getDouble("x"), current.getDouble("y"), current.getDouble("z")), current);
            });
        }
    }

    private Map<String, List<TextDisplay>> indexWorld(World world) {
        Map<String, List<TextDisplay>> result = new HashMap<>();
        for (TextDisplay display : world.getEntitiesByClass(TextDisplay.class)) {
            for (String tag : display.getScoreboardTags()) if (tag.startsWith(TAG_PREFIX))
                result.computeIfAbsent(tag.substring(TAG_PREFIX.length()), ignored -> new ArrayList<>()).add(display);
        }
        return result;
    }

    public void spawnOrUpdateHologram(String id, Location location, ConfigurationSection holoSec) {
        if (location == null || location.getWorld() == null) return;

        World world = location.getWorld();

        // Do not synchronously load chunks for a hologram; ChunkLoadEvent restores it.
        int cx = location.getBlockX() >> 4, cz = location.getBlockZ() >> 4;
        if (!world.isChunkLoaded(cx, cz)) return;
        Map<String, List<TextDisplay>> worldIndex = indexed == null ? indexWorld(world) : indexed.computeIfAbsent(world, this::indexWorld);
        List<TextDisplay> existingDisplays = worldIndex.getOrDefault(id, List.of());

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

        float targetRange = bedrockOpt ? viewRange : 1.0f;
        if (Float.compare(textDisplay.getViewRange(), targetRange) != 0) textDisplay.setViewRange(targetRange);
        if (textDisplay.isShadowed() != !disableShadow) textDisplay.setShadowed(!disableShadow);
        if (textDisplay.getLineWidth() != maxLineWidth) textDisplay.setLineWidth(maxLineWidth);

        if (transparentBg) {
            Color bg = Color.fromARGB(0, 0, 0, 0);
            if (!bg.equals(textDisplay.getBackgroundColor())) textDisplay.setBackgroundColor(bg);
        } else {
            int alpha = config.getInt("holograms.background-alpha", 0);
            Color bg = Color.fromARGB(Math.max(0, Math.min(255, alpha)), 0, 0, 0);
            if (!bg.equals(textDisplay.getBackgroundColor())) textDisplay.setBackgroundColor(bg);
        }

        String billboardStr = holoSec != null ? holoSec.getString("billboard") : null;
        if (billboardStr == null) billboardStr = config.getString("holograms.default-billboard", "FIXED");

        try {
            Display.Billboard target = Display.Billboard.valueOf(billboardStr.toUpperCase(Locale.ROOT));
            if (textDisplay.getBillboard() != target) textDisplay.setBillboard(target);
        } catch (IllegalArgumentException e) {
            if (textDisplay.getBillboard() != Display.Billboard.FIXED) textDisplay.setBillboard(Display.Billboard.FIXED);
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