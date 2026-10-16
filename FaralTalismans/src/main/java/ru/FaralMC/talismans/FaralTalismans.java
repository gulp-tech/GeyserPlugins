package ru.FaralMC.talismans;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.List;

/**
 * FaralTalismans — талисманы как на FunTime: носишь в инвентаре — получаешь эффекты.
 * Bedrock-only сервер FaralMC (Geyser/Floodgate), Purpur 26.2.
 */
public final class FaralTalismans extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private TalismanService service;
    private BukkitTask task;

    /** Маркер нашего меню, чтобы отличать его от обычных инвентарей. */
    static final class MenuHolder implements InventoryHolder {
        @Override
        public Inventory getInventory() {
            return null;
        }
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        service = new TalismanService(this);
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("talisman") != null) {
            getCommand("talisman").setExecutor(this);
            getCommand("talisman").setTabCompleter(this);
        }
        startTask();
        getLogger().info("FaralTalismans включён, талисманов: " + service.all().size());
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
        Bukkit.getOnlinePlayers().forEach(p -> service.clearAttributes(p));
    }

    public TalismanService service() {
        return service;
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        long interval = Math.max(10L, getConfig().getLong("settings.refresh-interval-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimer(this,
                () -> Bukkit.getOnlinePlayers().forEach(service::refresh), interval, interval);
    }

    // ------------------------------------------------------------------ меню
    public void openMenu(Player player) {
        int rows = Math.max(1, Math.min(6, getConfig().getInt("menu.rows", 3)));
        Inventory inv = Bukkit.createInventory(new MenuHolder(), rows * 9,
                TalismanService.component(TextUtil.color(getConfig().getString("menu.title", "&#C86BFFТалисманы"))));
        int slot = 0;
        for (Talisman t : service.all()) {
            if (slot >= rows * 9) {
                break;
            }
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true); // предмет из меню не забираем — выдаём настоящий
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("faraltalismans.admin")) {
            return;
        }
        String id = service.identify(event.getCurrentItem());
        Talisman t = id == null ? null : service.byId(id);
        if (t == null) {
            return;
        }
        java.util.Map<Integer, ItemStack> rest = player.getInventory().addItem(service.createItem(t, 1));
        for (ItemStack drop : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                getConfig().getString("messages.received", "&aВы получили талисман!")
                        .replace("{talisman}", t.displayName()))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clearAttributes(event.getPlayer());
    }

    // ------------------------------------------------------------------ команды
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        if (!(sender instanceof Player)) {
            sender.sendMessage(prefix + "Команда только для игроков.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("faraltalismans.admin")) {
            player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-permission", "&cНет прав."))));
            return true;
        }
        openMenu(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return List.of();
    }
}
