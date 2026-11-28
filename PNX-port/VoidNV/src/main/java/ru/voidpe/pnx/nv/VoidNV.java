package ru.voidpe.pnx.nv;

import org.powernukkitx.Player;
import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.entity.effect.Effect;
import org.powernukkitx.entity.effect.EffectType;
import org.powernukkitx.event.EventPriority;
import org.powernukkitx.event.Listener;
import org.powernukkitx.event.player.PlayerDeathEvent;
import org.powernukkitx.event.player.PlayerQuitEvent;
import org.powernukkitx.plugin.PluginBase;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PNX version of VoidNV. No Bukkit/Geyser dependency. */
public final class VoidNV extends PluginBase implements Listener {
    private final Set<UUID> active = new HashSet<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // PNX's annotation-based event compiler reflectively opens java.lang.ClassLoader
        // and can fail without JVM --add-opens. Register the same events directly.
        getServer().getPluginManager().registerEvent(PlayerDeathEvent.class, this,
                EventPriority.MONITOR, (listener, event) -> ((VoidNV) listener).onDeath((PlayerDeathEvent) event), this, true);
        getServer().getPluginManager().registerEvent(PlayerQuitEvent.class, this,
                EventPriority.NORMAL, (listener, event) -> ((VoidNV) listener).onQuit((PlayerQuitEvent) event), this);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("voidnv")) {
            if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("voidnv.admin")) {
                    sender.sendMessage(Helpers.color("&cНет прав."));
                } else {
                    reloadConfig();
                    sender.sendMessage(Helpers.color("&aVoidNV перезагружен."));
                }
            } else sender.sendMessage(Helpers.color("&7Использование: &f/voidnv reload"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only", "&cКоманда только для игроков."));
            return true;
        }
        if (!player.hasPermission("voidnv.use")) {
            player.sendMessage(message("no-permission", "&cНет доступа к команде /nv."));
            return true;
        }
        UUID uuid = player.getUniqueId();
        if (active.contains(uuid) && player.hasEffect(EffectType.NIGHT_VISION)) {
            player.sendMessage(message("already", "&eНочное зрение уже включено."));
            return true;
        }
        long now = System.currentTimeMillis();
        long remaining = Helpers.remainingSeconds(now, cooldowns.getOrDefault(uuid, 0L));
        if (remaining > 0) {
            player.sendMessage(message("cooldown", "&cПодожди ещё &f{seconds} сек.").replace("{seconds}", Long.toString(remaining)));
            return true;
        }
        Effect requested = Effect.get(EffectType.NIGHT_VISION).setInfinite().setAmplifier(0).setVisible(false).setAmbient(false);
        player.addEffect(requested);
        if (player.getEffect(EffectType.NIGHT_VISION) != requested) {
            player.sendMessage(message("failed", "&cНе удалось включить ночное зрение."));
            return true;
        }
        active.add(uuid);
        cooldowns.put(uuid, Helpers.deadline(now, Math.max(0L, getConfig().getLong("cooldown-seconds", 300L))));
        player.sendMessage(message("enabled", "&aНочное зрение включено."));
        return true;
    }

    public void onDeath(PlayerDeathEvent event) {
        active.remove(event.getEntity().getUniqueId());
    }

    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        // Cooldown remains until it expires, even if the player reconnects.
    }

    private String message(String key, String fallback) {
        return Helpers.color(getConfig().getSection("messages").getString(key, fallback));
    }
}
