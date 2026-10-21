package ru.voidpe.drops;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ThreadLocalRandom;

/**
 * VoidDrops — монеты (Vault) выпадают с мобов и игроков зачарованным
 * золотым слитком. Подбор слитка начисляет монеты; сумма — в actionbar.
 * С игрока при смерти падает процент баланса (по умолчанию 10%,
 * если баланс меньше минимума — ничего не падает и не списывается).
 */
public final class VoidDrops extends JavaPlugin implements Listener, CommandExecutor {

    private VaultHook vault;
    private NamespacedKey coinKey;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        vault = new VaultHook(getLogger());
        coinKey = new NamespacedKey(this, "coin_amount");
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("vdrops") != null) {
            getCommand("vdrops").setExecutor(this);
        }
        getLogger().info("VoidDrops включён");
    }

    private Component comp(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(TextUtil.color(legacy))
                .decoration(TextDecoration.ITALIC, false);
    }

    private String fmt(double amount) {
        if (amount == Math.floor(amount)) {
            return String.format("%,d", (long) amount).replace(',', ' ');
        }
        return String.format("%,.2f", amount).replace(',', ' ');
    }

    // ------------------------------------------------------------------ дроп

    private void spawnCoin(Location loc, long amount) {
        if (amount <= 0 || loc == null || loc.getWorld() == null) {
            return;
        }
        ItemStack stack = new ItemStack(Material.GOLD_INGOT);
        ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            String name = getConfig().getString("item.name", "&#FFD93DМонеты: &f{amount}");
            meta.displayName(comp(name.replace("{amount}", fmt(amount))));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            meta.getPersistentDataContainer().set(coinKey, PersistentDataType.LONG, amount);
            stack.setItemMeta(meta);
        }
        Item dropped = loc.getWorld().dropItemNaturally(loc, stack);
        dropped.setPickupDelay(10);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobDeath(EntityDeathEvent event) {
        if (event.getEntity() instanceof Player) {
            return;
        }
        if (!getConfig().getBoolean("mobs.enabled", true)) {
            return;
        }
        if (getConfig().getBoolean("mobs.only-player-kill", true) && event.getEntity().getKiller() == null) {
            return;
        }
        double chance = getConfig().getDouble("mobs.chance", 0.35);
        if (ThreadLocalRandom.current().nextDouble() > chance) {
            return;
        }
        long min = Math.max(1, getConfig().getLong("mobs.min", 1));
        long max = Math.max(min, getConfig().getLong("mobs.max", 5));
        long amount = min == max ? min : ThreadLocalRandom.current().nextLong(min, max + 1);
        spawnCoin(event.getEntity().getLocation(), amount);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (!getConfig().getBoolean("players.enabled", true)) {
            return;
        }
        Player dead = event.getEntity();
        if (vault == null || !vault.available()) {
            return;
        }
        double balance = vault.balance(dead);
        double minBalance = getConfig().getDouble("players.min-balance", 100);
        if (balance < minBalance) {
            return; // меньше минимума — ничего не падает и не списывается
        }
        double percent = getConfig().getDouble("players.percent", 10);
        long amount = (long) Math.floor(balance * percent / 100.0);
        if (amount <= 0) {
            return;
        }
        if (!vault.withdraw(dead, amount)) {
            return;
        }
        spawnCoin(dead.getLocation(), amount);
    }

    // ------------------------------------------------------------------ подбор

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        ItemStack stack = event.getItem().getItemStack();
        if (stack.getItemMeta() == null) {
            return;
        }
        Long amount = stack.getItemMeta().getPersistentDataContainer()
                .get(coinKey, PersistentDataType.LONG);
        if (amount == null) {
            return;
        }
        event.setCancelled(true);
        Player player = (Player) event.getEntity();
        long total = amount * stack.getAmount();
        event.getItem().remove();
        if (vault != null && vault.available()) {
            vault.deposit(player, total);
        }
        String text = getConfig().getString("messages.picked", "&#FFD93D+{amount} монет");
        player.sendActionBar(comp(text.replace("{amount}", fmt(total))));
    }

    // ------------------------------------------------------------------ команда

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("voiddrops.admin")) {
                sender.sendMessage(comp("&cТолько для админов."));
                return true;
            }
            reloadConfig();
            vault = new VaultHook(getLogger());
            sender.sendMessage(comp("&#7CFC00VoidDrops перезагружен."));
            return true;
        }
        sender.sendMessage(comp("&7VoidDrops: &f/vdrops reload &7— перезагрузка (админ)"));
        return true;
    }
}
