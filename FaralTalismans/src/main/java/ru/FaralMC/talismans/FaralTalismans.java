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
    private final VaultHook vault = new VaultHook();
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
        if (getCommand("talismans") != null) {
            getCommand("talismans").setExecutor(this);
            getCommand("talismans").setTabCompleter(this);
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
            boolean active = service.activeTalismans(player).contains(t);
            ItemStack item = service.createItem(t, 1);
            org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            lore.add(TalismanService.component(TextUtil.color("")));
            lore.add(TalismanService.component(TextUtil.color(active
                    ? getConfig().getString("menu.status-active", "&aАктивен")
                    : getConfig().getString("menu.status-inactive", "&7Не активен (нет в нужном слоте)"))));
            meta.lore(lore);
            item.setItemMeta(meta);
            inv.setItem(slot++, item);
        }
        player.openInventory(inv);
    }

    @EventHandler
    public void onMenuClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof MenuHolder)) {
            return;
        }
        event.setCancelled(true); // из меню предметы не забирают
        if (!getConfig().getBoolean("menu.click-to-buy", true)) {
            return;
        }
        if (!(event.getWhoClicked() instanceof Player)) {
            return;
        }
        Player player = (Player) event.getWhoClicked();
        String id = service.identify(event.getCurrentItem());
        Talisman t = id == null ? null : service.byId(id);
        if (t != null) {
            buy(player, t);
        }
    }

    /** Покупка талисмана за монеты Vault (на FunTime так покупают за ТК). */
    public void buy(Player player, Talisman t) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        if (t.price() <= 0) {
            player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.not-for-sale", "&7Этот талисман не продаётся."))));
            return;
        }
        if (!player.hasPermission("faraltalismans.buy")) {
            player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-permission", "&cНет прав."))));
            return;
        }
        if (!vault.available()) {
            player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-economy", "&cЭкономика недоступна (нужен Vault)."))));
            return;
        }
        double balance = vault.balance(player);
        if (balance < t.price()) {
            player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.not-enough-money", "&cНе хватает монет: нужно &f{price}&c, у вас &f{balance}")
                            .replace("{price}", service.formatPrice(t.price()))
                            .replace("{balance}", service.formatPrice(balance))
                            .replace("{talisman}", t.displayName()))));
            return;
        }
        if (!vault.withdraw(player, t.price())) {
            player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                    getConfig().getString("messages.no-economy", "&cЭкономика недоступна (нужен Vault)."))));
            return;
        }
        java.util.Map<Integer, ItemStack> rest = player.getInventory().addItem(service.createItem(t, 1));
        for (ItemStack drop : rest.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), drop);
        }
        player.sendMessage(TalismanService.component(prefix + TextUtil.color(
                getConfig().getString("messages.bought", "&aВы купили {talisman} &aза &f{price}")
                        .replace("{price}", service.formatPrice(t.price()))
                        .replace("{talisman}", t.displayName()))));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        service.clearAttributes(event.getPlayer());
        service.forget(event.getPlayer());
    }

    // ------------------------------------------------------------------ команды
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#C86BFFFaralMC &8» &f"));
        if (command.getName().equalsIgnoreCase("talismans")) {
            if (sender instanceof Player) {
                openMenu((Player) sender);
            } else {
                sender.sendMessage(prefix + "Команда только для игроков.");
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("faraltalismans.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            if (args.length < 3) {
                sender.sendMessage(prefix + "Использование: /talisman give <игрок> <id> [кол-во]");
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            Talisman talisman = service.byId(args[2]);
            if (target == null) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.player-not-found", "&cИгрок не найден.")));
                return true;
            }
            if (talisman == null) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.not-found", "&cТакого талисмана нет. Список: /talisman list")));
                return true;
            }
            int amount = 1;
            if (args.length >= 4) {
                try {
                    amount = Math.max(1, Integer.parseInt(args[3]));
                } catch (NumberFormatException ignored) {
                }
            }
            target.getInventory().addItem(service.createItem(talisman, amount));
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.given", "&aТалисман выдан игроку {player}")
                    .replace("{player}", target.getName())));
            target.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.received", "&aВы получили талисман!")));
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("buy")) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(prefix + "Команда только для игроков.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(prefix + "Использование: /talisman buy <id> (список: /talisman list)");
                return true;
            }
            Talisman toBuy = service.byId(args[1]);
            if (toBuy == null) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.not-found", "&cТакого талисмана нет. Список: /talisman list")));
                return true;
            }
            buy((Player) sender, toBuy);
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("list")) {
            sender.sendMessage(prefix + "Талисманы (" + service.all().size() + "):");
            for (Talisman t : service.all()) {
                String price = t.price() > 0 ? " &8| &e" + service.formatPrice(t.price()) : "";
                sender.sendMessage(TextUtil.color("&7- &f" + t.id() + " &8| ") + TalismanService.component(TextUtil.color(t.displayName()))
                        + TalismanService.component(TextUtil.color(price)));
            }
            return true;
        }
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("faraltalismans.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            reloadConfig();
            service.load();
            service.registerRecipes();
            startTask();
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.reloaded", "&aКонфиг перезагружен.")));
            return true;
        }
        if (sender instanceof Player) {
            openMenu((Player) sender);
        } else {
            sender.sendMessage(prefix + "Использование: /talisman <give|list|reload>");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (command.getName().equalsIgnoreCase("talismans")) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("give", "buy", "list", "reload");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("buy")) {
            List<String> ids = new ArrayList<>();
            service.all().forEach(t -> ids.add(t.id()));
            return ids;
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")) {
            List<String> ids = new ArrayList<>();
            service.all().forEach(t -> ids.add(t.id()));
            return ids;
        }
        return List.of();
    }
}
