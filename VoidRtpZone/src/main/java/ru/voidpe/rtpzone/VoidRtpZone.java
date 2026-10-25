package ru.voidpe.rtpzone;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VoidRtpZone — зоны с автозапуском /rtp при входе игрока.
 *
 * <p>Админ: {@code /rtpzone wand} → ЛКМ/ПКМ по блокам выделяют кубоид →
 * {@code /rtpzone save <имя>}. Зоны лежат в zones.yml (не в config.yml —
 * конфиг при обновлениях удаляют, зоны не должны слетать).</p>
 *
 * <p>Оптимизация: ни одного таймера. PlayerMoveEvent отбрасывается на
 * первом же сравнении, если игрок не сменил блок (поворот головы и т.п.);
 * зоны сгруппированы по мирам — мир без зон выходит сразу; проверка зоны —
 * шесть сравнений int. Срабатывание только на переходе «снаружи → внутрь».</p>
 */
public final class VoidRtpZone extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private record Zone(String name, String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(int x, int y, int z) {
            return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }

        long volume() {
            return (long) (maxX - minX + 1) * (maxY - minY + 1) * (maxZ - minZ + 1);
        }
    }

    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");

    private NamespacedKey wandKey;
    /** Зоны по мирам: мир без зон не проверяется вообще. */
    private final Map<String, List<Zone>> zonesByWorld = new HashMap<>();
    /** Игроки, которые прямо сейчас внутри какой-нибудь зоны. */
    private final Set<UUID> inside = new HashSet<>();
    /** Игрок → пара выделенных точек (клоны, мир из Location). */
    private final Map<UUID, Location[]> selections = new HashMap<>();
    /** Игрок → время последнего срабатывания (кулдаун). */
    private final Map<UUID, Long> lastTrigger = new HashMap<>();
    private final Set<GameMode> skipModes = EnumSet.noneOf(GameMode.class);

    @Override
    public void onEnable() {
        saveDefaultConfig();
        wandKey = new NamespacedKey(this, "zone_wand");
        loadZones();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("rtpzone") != null) {
            getCommand("rtpzone").setExecutor(this);
            getCommand("rtpzone").setTabCompleter(this);
        }
        getLogger().info("VoidRtpZone включён, зон загружено: " + zoneCount());
    }

    // ------------------------------------------------------------ зоны: файл

    private File zonesFile() {
        return new File(getDataFolder(), "zones.yml");
    }

    private void loadZones() {
        zonesByWorld.clear();
        inside.clear();
        File file = zonesFile();
        if (!file.exists()) {
            return;
        }
        YamlConfiguration data = YamlConfiguration.loadConfiguration(file);
        if (data.isConfigurationSection("zones")) {
            for (String name : data.getConfigurationSection("zones").getKeys(false)) {
                String p = "zones." + name + ".";
                Zone zone = new Zone(name,
                        data.getString(p + "world", "world"),
                        data.getInt(p + "min-x"), data.getInt(p + "min-y"), data.getInt(p + "min-z"),
                        data.getInt(p + "max-x"), data.getInt(p + "max-y"), data.getInt(p + "max-z"));
                zonesByWorld.computeIfAbsent(zone.world(), w -> new ArrayList<>()).add(zone);
            }
        }
        skipModes.clear();
        for (String raw : getConfig().getStringList("trigger.skip-gamemodes")) {
            try {
                skipModes.add(GameMode.valueOf(raw.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                getLogger().warning("Неизвестный режим игры в skip-gamemodes: " + raw);
            }
        }
    }

    private void saveZones() {
        YamlConfiguration data = new YamlConfiguration();
        for (List<Zone> zones : zonesByWorld.values()) {
            for (Zone z : zones) {
                String p = "zones." + z.name() + ".";
                data.set(p + "world", z.world());
                data.set(p + "min-x", z.minX());
                data.set(p + "min-y", z.minY());
                data.set(p + "min-z", z.minZ());
                data.set(p + "max-x", z.maxX());
                data.set(p + "max-y", z.maxY());
                data.set(p + "max-z", z.maxZ());
            }
        }
        try {
            data.save(zonesFile());
        } catch (IOException e) {
            getLogger().warning("Не удалось сохранить zones.yml: " + e.getMessage());
        }
    }

    private int zoneCount() {
        int n = 0;
        for (List<Zone> zones : zonesByWorld.values()) {
            n += zones.size();
        }
        return n;
    }

    private Zone findZone(String name) {
        for (List<Zone> zones : zonesByWorld.values()) {
            for (Zone z : zones) {
                if (z.name().equalsIgnoreCase(name)) {
                    return z;
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ wand

    private ItemStack buildWand() {
        Material material = Material.matchMaterial(getConfig().getString("wand.material", "BLAZE_ROD"));
        ItemStack item = new ItemStack(material != null ? material : Material.BLAZE_ROD);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(comp(getConfig().getString("wand.name", "Выделитель зоны RTP")));
            List<Component> lore = new ArrayList<>();
            for (String line : getConfig().getStringList("wand.lore")) {
                lore.add(comp(line));
            }
            meta.lore(lore);
            meta.getPersistentDataContainer().set(wandKey, PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isWand(ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return false;
        }
        return item.getItemMeta().getPersistentDataContainer().has(wandKey, PersistentDataType.BYTE);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getClickedBlock() == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!isWand(player.getInventory().getItemInMainHand())) {
            return;
        }
        Action action = event.getAction();
        if (action != Action.LEFT_CLICK_BLOCK && action != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        event.setCancelled(true); // не ломаем/не тыкаем блок выделителем
        Location block = event.getClickedBlock().getLocation();
        Location[] sel = selections.computeIfAbsent(player.getUniqueId(), u -> new Location[2]);
        if (action == Action.LEFT_CLICK_BLOCK) {
            sel[0] = block;
            msg(player, "pos1", "x", block.getBlockX(), "y", block.getBlockY(), "z", block.getBlockZ());
        } else {
            sel[1] = block;
            msg(player, "pos2", "x", block.getBlockX(), "y", block.getBlockY(), "z", block.getBlockZ());
        }
    }

    // ------------------------------------------------------------ триггер зоны

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to == null || !getConfig().getBoolean("enabled", true)) {
            return;
        }
        Location from = event.getFrom();
        // Главный фильтр: игрок не сменил блок (поворот камеры, микродвижения) — выходим.
        if (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ() && from.getWorld() == to.getWorld()) {
            return;
        }
        List<Zone> zones = zonesByWorld.get(to.getWorld().getName());
        if (zones == null || zones.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        boolean inZone = false;
        for (Zone zone : zones) {
            if (zone.contains(to.getBlockX(), to.getBlockY(), to.getBlockZ())) {
                inZone = true;
                break;
            }
        }
        if (!inZone) {
            inside.remove(uuid);
            return;
        }
        // Срабатываем только на переходе снаружи → внутрь.
        if (!inside.add(uuid)) {
            return;
        }
        if (player.hasPermission("voidrtpzone.bypass") || skipModes.contains(player.getGameMode())) {
            return;
        }
        long cooldownMs = Math.max(0, getConfig().getLong("trigger.cooldown-seconds", 10)) * 1000L;
        long now = System.currentTimeMillis();
        Long last = lastTrigger.get(uuid);
        if (last != null && now - last < cooldownMs) {
            return;
        }
        lastTrigger.put(uuid, now);
        boolean asConsole = getConfig().getBoolean("trigger.as-console", false);
        for (String raw : getConfig().getStringList("trigger.commands")) {
            String cmd = raw.replace("{player}", player.getName());
            if (asConsole) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            } else {
                player.performCommand(cmd);
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        inside.remove(uuid);
        selections.remove(uuid);
        lastTrigger.remove(uuid);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        inside.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------ команды

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "wand" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Только для игроков.");
                    return true;
                }
                player.getInventory().addItem(buildWand());
                msg(player, "wand-given");
            }
            case "save" -> {
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("Только для игроков.");
                    return true;
                }
                if (args.length < 2) {
                    sendHelp(sender);
                    return true;
                }
                Location[] sel = selections.get(player.getUniqueId());
                if (sel == null || sel[0] == null || sel[1] == null
                        || sel[0].getWorld() != sel[1].getWorld()) {
                    msg(player, "no-selection");
                    return true;
                }
                String name = args[1].toLowerCase(Locale.ROOT);
                Zone old = findZone(name);
                if (old != null) {
                    zonesByWorld.get(old.world()).remove(old);
                }
                Zone zone = new Zone(name, sel[0].getWorld().getName(),
                        Math.min(sel[0].getBlockX(), sel[1].getBlockX()),
                        Math.min(sel[0].getBlockY(), sel[1].getBlockY()),
                        Math.min(sel[0].getBlockZ(), sel[1].getBlockZ()),
                        Math.max(sel[0].getBlockX(), sel[1].getBlockX()),
                        Math.max(sel[0].getBlockY(), sel[1].getBlockY()),
                        Math.max(sel[0].getBlockZ(), sel[1].getBlockZ()));
                zonesByWorld.computeIfAbsent(zone.world(), w -> new ArrayList<>()).add(zone);
                saveZones();
                msg(player, "saved", "name", name);
            }
            case "remove", "delete" -> {
                if (args.length < 2) {
                    sendHelp(sender);
                    return true;
                }
                Zone zone = findZone(args[1]);
                if (zone == null) {
                    msg(sender, "no-such-zone", "name", args[1]);
                    return true;
                }
                zonesByWorld.get(zone.world()).remove(zone);
                saveZones();
                msg(sender, "removed", "name", zone.name());
            }
            case "list" -> {
                if (zoneCount() == 0) {
                    msg(sender, "list-empty");
                    return true;
                }
                for (List<Zone> zones : zonesByWorld.values()) {
                    for (Zone z : zones) {
                        msg(sender, "list-line", "name", z.name(), "world", z.world(), "size", z.volume());
                    }
                }
            }
            case "reload" -> {
                reloadConfig();
                loadZones();
                msg(sender, "reloaded", "count", zoneCount());
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        for (String line : getConfig().getStringList("messages.help")) {
            sender.sendMessage(comp(line));
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            for (String sub : new String[]{"wand", "save", "remove", "list", "reload"}) {
                if (sub.startsWith(args[0].toLowerCase(Locale.ROOT))) {
                    out.add(sub);
                }
            }
        } else if (args.length == 2 && args[0].equalsIgnoreCase("remove")) {
            for (List<Zone> zones : zonesByWorld.values()) {
                for (Zone z : zones) {
                    if (z.name().startsWith(args[1].toLowerCase(Locale.ROOT))) {
                        out.add(z.name());
                    }
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------ текст

    private void msg(CommandSender sender, String key, Object... replace) {
        String text = getConfig().getString("messages." + key, key);
        for (int i = 0; i + 1 < replace.length; i += 2) {
            text = text.replace("{" + replace[i] + "}", String.valueOf(replace[i + 1]));
        }
        String prefix = getConfig().getString("messages.prefix", "");
        sender.sendMessage(comp(prefix + text));
    }

    private static Component comp(String raw) {
        return LegacyComponentSerializer.legacySection().deserialize(color(raw));
    }

    /** &#RRGGBB → §x§R§R§G§G§B§B, затем обычные &коды → §. */
    private static String color(String raw) {
        if (raw == null) {
            return "";
        }
        Matcher matcher = HEX.matcher(raw);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            String hex = matcher.group(1);
            StringBuilder code = new StringBuilder("§x");
            for (char c : hex.toCharArray()) {
                code.append('§').append(c);
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(code.toString()));
        }
        matcher.appendTail(out);
        return out.toString().replace('&', '§');
    }
}
