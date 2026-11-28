package ru.voidpe.pnx.rtpzone;

import org.powernukkitx.Player;
import org.powernukkitx.block.Block;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.block.BlockBreakEvent;
import org.powernukkitx.event.entity.EntityLevelChangeEvent;
import org.powernukkitx.event.player.PlayerInteractEvent;
import org.powernukkitx.event.player.PlayerMoveEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.event.player.PlayerTeleportEvent;
import org.powernukkitx.item.Item;
import org.powernukkitx.level.Location;
import org.powernukkitx.plugin.PluginBase;
import java.io.IOException;
import java.util.*;

public final class VoidRtpZone extends PluginBase implements Listener {
    private static final String WAND_TAG = "voidpe_rtpzone_wand";
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private final Map<UUID, Point[]> selections = new HashMap<>();
    private final EntryTracker tracker = new EntryTracker();
    private ZoneStore store;
    private record Point(String world, int dimension, int x, int y, int z) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        store = new ZoneStore(getDataFolder().toPath().resolve("zones.yml"));
        try { zones.putAll(store.load()); }
        catch (IOException e) { getLogger().error("Зоны не загружены, плагин выключен: " + e.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        var pm = getServer().getPluginManager();
        pm.registerEvent(PlayerInteractEvent.class, this, EventPriority.LOW, (l,e) -> ((VoidRtpZone) l).interact((PlayerInteractEvent)e), this, true);
        pm.registerEvent(BlockBreakEvent.class, this, EventPriority.LOW, (l,e) -> ((VoidRtpZone) l).breakBlock((BlockBreakEvent)e), this, true);
        pm.registerEvent(PlayerMoveEvent.class, this, EventPriority.MONITOR, (l,e) -> ((VoidRtpZone) l).move((PlayerMoveEvent)e), this, true);
        pm.registerEvent(PlayerQuitEvent.class, this, EventPriority.NORMAL, (l,e) -> ((VoidRtpZone) l).quit((PlayerQuitEvent)e), this);
        pm.registerEvent(PlayerTeleportEvent.class, this, EventPriority.MONITOR, (l,e) -> ((VoidRtpZone) l).teleport((PlayerTeleportEvent)e), this, true);
        pm.registerEvent(EntityLevelChangeEvent.class, this, EventPriority.MONITOR, (l,e) -> { if (((EntityLevelChangeEvent)e).getEntity() instanceof Player p) ((VoidRtpZone) l).tracker.leave(p.getUniqueId()); }, this, true);
        getLogger().info("VoidRtpZone включён, зон: " + zones.size());
    }
    private Item wand() {
        String material = getConfig().getString("wand.material", "BLAZE_ROD").toLowerCase(Locale.ROOT);
        Item item = Item.get(material);
        if (item.isNull()) item = Item.get("minecraft:blaze_rod");
        item.setCustomName(color(getConfig().getString("wand.name", "Выделитель зоны RTP")));
        item.setLore(getConfig().getStringList("wand.lore").stream().map(VoidRtpZone::color).toArray(String[]::new));
        item.setNbt(item.getOrCreateNbt().putByte(WAND_TAG, 1));
        return item;
    }
    private boolean isWand(Item item) { return item != null && item.hasNbt() && item.getNbt().getByte(WAND_TAG) == 1; }
    private void interact(PlayerInteractEvent event) {
        if (!isWand(event.getItem())) return;
        var action = event.getAction();
        if (action != PlayerInteractEvent.Action.LEFT_CLICK_BLOCK && action != PlayerInteractEvent.Action.RIGHT_CLICK_BLOCK) return;
        Block block = event.getBlock(); if (block == null || block.getLevel() == null) return;
        event.setCancelled();
        Player p = event.getPlayer();
        if (!p.hasPermission("voidrtpzone.admin")) { message(p, "admin-only"); return; }
        int index = action == PlayerInteractEvent.Action.LEFT_CLICK_BLOCK ? 0 : 1;
        Point point = new Point(block.getLevel().getFolderName(), block.getLevel().getDimension(), block.getFloorX(), block.getFloorY(), block.getFloorZ());
        selections.computeIfAbsent(p.getUniqueId(), x -> new Point[2])[index] = point;
        message(p, index == 0 ? "pos1" : "pos2", "x", point.x, "y", point.y, "z", point.z);
    }
    private void breakBlock(BlockBreakEvent event) { if (isWand(event.getItem())) event.setCancelled(); }
    private boolean skip(Player p) {
        for (String name : getConfig().getStringList("trigger.skip-gamemodes")) {
            if ((name.equalsIgnoreCase("SURVIVAL") && p.isSurvival()) || (name.equalsIgnoreCase("CREATIVE") && p.isCreative()) ||
                (name.equalsIgnoreCase("ADVENTURE") && p.isAdventure()) || (name.equalsIgnoreCase("SPECTATOR") && p.isSpectator())) return true;
        }
        return false;
    }
    private void move(PlayerMoveEvent event) {
        Player p = event.getPlayer(); UUID id = p.getUniqueId();
        Location from = event.getFrom(), to = event.getTo();
        if (from == null || to == null || to.getLevel() == null || !getConfig().getBoolean("enabled", true)) { tracker.leave(id); return; }
        if (from.getLevel() == to.getLevel() && from.getFloorX() == to.getFloorX() && from.getFloorY() == to.getFloorY() && from.getFloorZ() == to.getFloorZ()) return;
        boolean within = inZone(to);
        long seconds = Math.max(0, getConfig().getLong("trigger.cooldown-seconds", 10));
        long cooldown = seconds > Long.MAX_VALUE / 1000 ? Long.MAX_VALUE : seconds * 1000;
        if (!tracker.update(id, within, p.hasPermission("voidrtpzone.bypass") || skip(p), System.currentTimeMillis(), cooldown)) return;
        boolean console = getConfig().getBoolean("trigger.as-console", false);
        for (String raw : getConfig().getStringList("trigger.commands")) {
            String cmd = raw.replace("{player}", p.getName()).trim();
            if (!cmd.isEmpty()) getServer().executeCommand(console ? getServer().getConsoleSender() : p, cmd);
        }
    }
    private boolean inZone(Location location) {
        if (location == null || location.getLevel() == null) return false;
        String world = location.getLevel().getFolderName();
        return zones.values().stream().anyMatch(z -> z.world().equals(world) && z.dimension() == location.getLevel().getDimension() && z.contains(location.getFloorX(),location.getFloorY(),location.getFloorZ()));
    }
    // PNX teleports are not PlayerMoveEvents. A teleport out must reset the entry
    // state even if the player never walks at the destination before returning.
    private void teleport(PlayerTeleportEvent event) {
        if (!inZone(event.getTo())) tracker.leave(event.getPlayer().getUniqueId());
    }
    private void quit(PlayerQuitEvent e) { UUID id = e.getPlayer().getUniqueId(); selections.remove(id); tracker.forget(id); }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("voidrtpzone.admin")) { message(sender, "admin-only"); return true; }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "wand" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cТолько для игроков."); return true; }
                Item item = wand();
                if (!p.getInventory().canAddItem(item) || p.getInventory().addItem(item).length != 0) { p.sendMessage("§cНет места в инвентаре."); return true; }
                message(p, "wand-given");
            }
            case "save" -> {
                if (!(sender instanceof Player p)) { sender.sendMessage("§cТолько для игроков."); return true; }
                if (args.length != 2) { help(sender); return true; }
                Point[] points = selections.get(p.getUniqueId());
                if (points == null || points[0] == null || points[1] == null || !points[0].world.equals(points[1].world) || points[0].dimension != points[1].dimension) { message(p,"no-selection"); return true; }
                try {
                    Point a = points[0], b = points[1];
                    Zone z = Zone.between(args[1].toLowerCase(Locale.ROOT), a.world, a.dimension, a.x,a.y,a.z,b.x,b.y,b.z);
                    Map<String,Zone> next = new LinkedHashMap<>(zones); next.put(z.name(),z); persist(next);
                    message(sender,"saved","name",z.name());
                } catch (IllegalArgumentException e) { sender.sendMessage("§cИмя зоны: латинские буквы, цифры, _ и - (до 64 символов)."); }
                catch (IOException e) { error(sender,e); }
            }
            case "remove", "delete" -> {
                if (args.length != 2) { help(sender); return true; }
                String name = args[1].toLowerCase(Locale.ROOT);
                if (!zones.containsKey(name)) { message(sender,"no-such-zone","name",name); return true; }
                Map<String,Zone> next = new LinkedHashMap<>(zones); next.remove(name);
                try { persist(next); message(sender,"removed","name",name); } catch (IOException e) { error(sender,e); }
            }
            case "list" -> {
                if (zones.isEmpty()) message(sender,"list-empty");
                else for (Zone z : zones.values()) message(sender,"list-line","name",z.name(),"world",z.world(),"size",z.volume());
            }
            case "reload" -> {
                try {
                    Map<String,Zone> loaded = store.load(); reloadConfig(); zones.clear(); zones.putAll(loaded); tracker.clearInside();
                    message(sender,"reloaded","count",zones.size());
                } catch (IOException e) { error(sender,e); }
            }
            default -> help(sender);
        }
        return true;
    }
    private void persist(Map<String,Zone> next) throws IOException { store.save(next); zones.clear(); zones.putAll(next); tracker.clearInside(); }
    private void error(CommandSender sender, Exception e) { getLogger().error("Ошибка зон: " + e.getMessage()); sender.sendMessage("§cНе удалось загрузить или сохранить зоны: " + e.getMessage()); }
    private void help(CommandSender sender) { for (String line : getConfig().getStringList("messages.help")) sender.sendMessage(color(line)); }
    private void message(CommandSender sender, String key, Object... replacements) {
        String text = getConfig().getString("messages." + key, key);
        for (int i=0; i+1<replacements.length; i+=2) text = text.replace("{"+replacements[i]+"}", String.valueOf(replacements[i+1]));
        sender.sendMessage(color(getConfig().getString("messages.prefix", "") + text));
    }
    private static String color(String value) {
        // Bedrock has no Java-style §x RGB sequence. Preserve readable text and legacy colours.
        return value.replaceAll("&#[0-9a-fA-F]{6}", "§d").replace('&','§');
    }
}
