package ru.voidpe.bedrockpvp;

import com.destroystokyo.paper.event.entity.EntityKnockbackByEntityEvent;
import io.papermc.paper.event.entity.EntityKnockbackEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Paper/Purpur Bedrock-style combat; no packet interception or PvP plugin changes. */
public final class BedrockPvP extends JavaPlugin implements Listener {
    private final Map<UUID, Double> oldSpeed = new HashMap<>();
    private double speed;
    private double upwardCap;
    private double wearFactor;

    @Override public void onEnable() {
        saveDefaultConfig();
        speed = CombatMath.safeClamp(getConfig().getDouble("attack-speed.base-value", 1024), 1024, 1, 1024);
        upwardCap = CombatMath.safeClamp(getConfig().getDouble("knockback.max-upward-velocity", .36), .36, 0, 4);
        wearFactor = CombatMath.safeClamp(getConfig().getDouble("armor.durability-factor", 2.0 / 3), 2.0 / 3, 0, 1);
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player player : Bukkit.getOnlinePlayers()) applySpeed(player);
        long ticks = Math.max(20, getConfig().getLong("attack-speed.refresh-ticks", 40));
        Bukkit.getScheduler().runTaskTimer(this, () -> Bukkit.getOnlinePlayers().forEach(this::applySpeed), ticks, ticks);
        if (Bukkit.getPluginManager().isPluginEnabled("VoidPvP"))
            getLogger().warning("VoidPvP also enabled: remove it and restart to avoid conflicting combat handlers!");
        getLogger().info("BedrockPvP enabled. This is an approximation, not a full Bedrock combat engine.");
    }

    private void applySpeed(Player player) {
        AttributeInstance attr = player.getAttribute(Attribute.ATTACK_SPEED);
        if (attr == null) return;
        oldSpeed.putIfAbsent(player.getUniqueId(), attr.getBaseValue());
        if (Double.compare(attr.getBaseValue(), speed) != 0) attr.setBaseValue(speed);
    }

    private void restoreSpeed(Player player) {
        Double before = oldSpeed.remove(player.getUniqueId());
        AttributeInstance attr = player.getAttribute(Attribute.ATTACK_SPEED);
        if (before != null && attr != null && Double.compare(attr.getBaseValue(), speed) == 0) attr.setBaseValue(before);
    }

    @Override public void onDisable() {
        for (Player player : Bukkit.getOnlinePlayers()) restoreSpeed(player);
        oldSpeed.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) { applySpeed(event.getPlayer()); }
    @EventHandler public void onQuit(PlayerQuitEvent event) { restoreSpeed(event.getPlayer()); }
    @EventHandler public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(this, () -> { if (player.isOnline()) applySpeed(player); });
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSweep(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player && event.getCause() == EntityDamageEvent.DamageCause.ENTITY_SWEEP_ATTACK) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getCause() != EntityKnockbackEvent.Cause.ENTITY_ATTACK || !(event.getEntity() instanceof Player victim)) return;
        if (!(event instanceof EntityKnockbackByEntityEvent hit) || !(hit.getHitBy() instanceof Player)) return;
        Vector impulse = event.getKnockback();
        double y = CombatMath.limitedUpwardImpulse(victim.getVelocity().getY(), impulse.getY(), upwardCap);
        if (Double.compare(y, impulse.getY()) != 0) event.setKnockback(impulse.setY(y));
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onArmorWear(PlayerItemDamageEvent event) {
        ItemStack item = event.getItem();
        if (!armorType(item.getType()) || !isEquipped(event.getPlayer(), item)) return;
        int result = CombatMath.reducedWear(event.getDamage(), wearFactor, ThreadLocalRandom.current()::nextDouble);
        if (result == 0) event.setCancelled(true);
        else if (result != event.getDamage()) event.setDamage(result);
    }

    private static boolean isEquipped(Player player, ItemStack item) {
        for (ItemStack worn : player.getInventory().getArmorContents()) {
            if (worn == item || (worn != null && worn.equals(item))) return true;
        }
        return false;
    }

    static boolean armorType(Material material) {
        String name = material.name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE") || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS");
    }
}
