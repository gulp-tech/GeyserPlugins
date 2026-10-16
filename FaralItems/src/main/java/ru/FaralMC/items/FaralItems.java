package ru.FaralMC.items;

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
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * FaralItems — кастомные предметы как на FunTime (Bedrock-only сервер FaralMC):
 * трапка, явная пыль, дезориентация, лом спавнеров, особые зелья.
 */
public final class FaralItems extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private ItemsService service;

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
        service = new ItemsService(this);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(service, this);
        if (getCommand("items") != null) {
            getCommand("items").setExecutor(this);
            getCommand("items").setTabCompleter(this);
        }
        getLogger().info("FaralItems включён, предметов: " + service.all().size());
    }

    @Override
    public void onDisable() {
        if (service != null) {
            service.shutdown(); // вернуть блоки всех активных трапок
        }
    }

    public ItemsService service() {
        return service;
    }

    // ------------------------------------------------------------------ меню
    public void openMenu(Player player) {
        List<CustomItem> all = new ArrayList<>(service.all());
        int rows = Math.max(1, Math.min(6, getConfig().getInt("menu.rows", 2)));
        Inventory inv = Bukkit.createInventory(new MenuHolder(), rows * 9,
                ItemsService.component(TextUtil.color(getConfig().getString("menu.title", "&#C86BFFКастомные предметы"))));
        int slot = 0;
        for (CustomItem item : all) {
            if (slot >= rows * 9) {
                break;
            }
            inv.setItem(slot++, service.createItem(item, 1));
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true); // настоящий предмет выдаём сами
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        if (!player.hasPermission("faralitems.admin")) {
            return;
        }
        String id = service.identify(event.getCurrentItem());
        CustomItem item = id == null ? null : service.byId(id);
        if (item == null) {
            return;
        }
        Map<Integer, ItemStack> rest = player.getInventory().addItem(service.createItem(item, 1));
        for (ItemStack drop : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        String text = getConfig().getString("messages.received", "&aВыдан предмет: {item}")
                .replace("{item}", item.displayName());
        player.sendMessage(ItemsService.component(prefix + TextUtil.color(text)));
    }

    // ------------------------------------------------------------------ команда
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        if (!(sender instanceof Player)) {
            sender.sendMessage(prefix + "Команда только для игроков.");
            return true;
        }
        Player player = (Player) sender;
        if (!player.hasPermission("faralitems.admin")) {
            player.sendMessage(ItemsService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-permission", "&cТолько для админов."))));
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
