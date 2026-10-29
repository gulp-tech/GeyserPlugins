package ru.voidpe.nv;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * VoidNV — команда /nv: ночное зрение навсегда (до смерти).
 * Смерть снимает эффект (это делает сама игра), плагин его НЕ восстанавливает.
 * Кулдаун команды — cooldown-seconds (по умолчанию 300 = 5 минут).
 */
public final class VoidNV extends JavaPlugin implements Listener, CommandExecutor {

    private static final Pattern HEX = Pattern.compile("&#([0-9A-Fa-f]{6})");

    /** Кому мы выдали ночное зрение (именно плагинное, а не от зелья). */
    private final Set<UUID> active = new HashSet<>();
    /** Конец кулдауна /nv по игрокам (millis). */
    private final Map<UUID, Long> cooldowns = new HashMap<>();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("nv") != null) {
            getCommand("nv").setExecutor(this);
        }
        if (getCommand("voidnv") != null) {
            getCommand("voidnv").setExecutor(this);
        }
        getLogger().info("VoidNV включён: /nv — ночное зрение до смерти, кулдаун "
                + getConfig().getLong("cooldown-seconds", 300L) + " сек.");
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (command.getName().equalsIgnoreCase("voidnv")) {
            if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("voidnv.admin")) {
                    sender.sendMessage(component("&cНет прав."));
                    return true;
                }
                reloadConfig();
                sender.sendMessage(component("&aVoidNV перезагружен."));
                return true;
            }
            sender.sendMessage(component("&7Использование: &f/voidnv reload"));
            return true;
        }

        // ---- /nv ----
        if (!(sender instanceof Player player)) {
            sender.sendMessage(component(getConfig().getString("messages.players-only", "&cКоманда только для игроков.")));
            return true;
        }
        if (!player.hasPermission("voidnv.use")) {
            send(player, "messages.no-permission", "&cНет доступа.");
            return true;
        }
        // Уже включено нами и эффект реально висит — не жжём кулдаун зря.
        if (active.contains(player.getUniqueId()) && player.hasPotionEffect(PotionEffectType.NIGHT_VISION)) {
            send(player, "messages.already", "&eНочное зрение уже включено.");
            return true;
        }
        long now = System.currentTimeMillis();
        long until = cooldowns.getOrDefault(player.getUniqueId(), 0L);
        if (until > now) {
            long seconds = (until - now) / 1000L + 1L;
            player.sendMessage(component(color(getConfig().getString("messages.cooldown",
                    "&cПодожди ещё &f{seconds} сек.")).replace("{seconds}", String.valueOf(seconds))));
            return true;
        }
        // Навсегда: огромный срок. Смерть снимет эффект сама, мы его не вернём.
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.NIGHT_VISION, Integer.MAX_VALUE, 0, false, false, false));
        active.add(player.getUniqueId());
        cooldowns.put(player.getUniqueId(),
                now + Math.max(0L, getConfig().getLong("cooldown-seconds", 300L)) * 1000L);
        send(player, "messages.enabled", "&aНочное зрение включено.");
        return true;
    }

    /** Смерть: эффект сняла игра; просто забываем игрока. НЕ восстанавливаем. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        active.remove(event.getEntity().getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        active.remove(event.getPlayer().getUniqueId());
        // Кулдаун при выходе не сбрасываем: он по UUID и переживёт перезаход
        // до перезапуска сервера (специально не чистим cooldowns).
    }

    // ------------------------------------------------------------- тексты

    private void send(Player player, String key, String def) {
        player.sendMessage(component(getConfig().getString(key, def)));
    }

    private Component component(String legacy) {
        return LegacyComponentSerializer.legacySection().deserialize(color(legacy == null ? "" : legacy));
    }

    private String color(String text) {
        Matcher m = HEX.matcher(text == null ? "" : text);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            StringBuilder rep = new StringBuilder("§x");
            for (char c : m.group(1).toCharArray()) {
                rep.append('§').append(c);
            }
            m.appendReplacement(out, Matcher.quoteReplacement(rep.toString()));
        }
        m.appendTail(out);
        return out.toString().replace('&', '§');
    }
}
