package ru.FaralMC.FaralTab;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.List;

/**
 * FaralTab — компактный скорборд (таб) для Geyser/Floodgate сервера FaralMC.
 * Purpur 26.2 / Java 25.
 */
public final class FaralTab extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Hooks hooks;
    private SidebarManager sidebar;
    private BukkitTask task;
    private boolean warnedNoFloodgate;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        hooks = new Hooks(getLogger());
        sidebar = new SidebarManager(this);

        getServer().getPluginManager().registerEvents(this, this);
        if (getCommand("faraltab") != null) {
            getCommand("faraltab").setExecutor(this);
            getCommand("faraltab").setTabCompleter(this);
        }

        startTask();
        // игроки, уже онлайн при /reload
        Bukkit.getOnlinePlayers().forEach(p -> sidebar.update(p, true));
        getLogger().info("FaralTab включён (Bedrock-only). Floodgate: " + (hooks.hasFloodgate() ? "найден, Java-игроки будут кикаться" : "НЕ найден — bedrock-only не сможет отличать Java, поставьте Floodgate!"));
    }

    @Override
    public void onDisable() {
        if (task != null) {
            task.cancel();
        }
    }

    public Hooks hooks() {
        return hooks;
    }

    public SidebarManager sidebar() {
        return sidebar;
    }

    private void startTask() {
        if (task != null) {
            task.cancel();
        }
        long interval = Math.max(10L, getConfig().getLong("update-interval-ticks", 40L));
        task = Bukkit.getScheduler().runTaskTimer(this, () -> sidebar.updateAll(), interval, interval);
    }

    // ------------------------------------------------------------- события
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (isJavaPlayer(player)) {
            getLogger().info("Bedrock-only: кикнут Java-игрок " + player.getName() + " (" + player.getUniqueId() + ")");
            player.kick(LegacyComponentSerializer.legacySection().deserialize(
                    TextUtil.color(getConfig().getString("bedrock-only.kick-message",
                            "&cFaralMC — сервер только для Bedrock!"))));
            return;
        }
        long delay = Math.max(0L, getConfig().getLong("join-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(this, () -> {
            sidebar.update(player, true);
            showJoinTitle(player);
        }, delay);
    }

    /** Большой синий FaralMC при входе (как логотип на экране загрузки у референса). */
    private void showJoinTitle(Player player) {
        if (!getConfig().getBoolean("join-title.enabled", true)) {
            return;
        }
        Component title = component(sidebar.render(player,
                getConfig().getString("join-title.title", "<gradient:#55C8FF:#1B6FFF>FaralMC</gradient>")));
        Component subtitle = component(sidebar.render(player,
                getConfig().getString("join-title.subtitle", "&fДобро пожаловать!")));
        long fadeIn = getConfig().getLong("join-title.fade-in-ticks", 10L);
        long stay = getConfig().getLong("join-title.stay-ticks", 70L);
        long fadeOut = getConfig().getLong("join-title.fade-out-ticks", 20L);
        player.showTitle(Title.title(title, subtitle, Title.Times.times(
                Duration.ofMillis(fadeIn * 50L), Duration.ofMillis(stay * 50L), Duration.ofMillis(fadeOut * 50L))));
    }

    private Component component(String legacySectionText) {
        return LegacyComponentSerializer.legacySection().deserialize(legacySectionText == null ? "" : legacySectionText);
    }

    /**
     * Сервер только для Bedrock. Java-игрок = зашёл напрямую на Purpur, мимо Geyser/Floodgate.
     * Если Floodgate на сервере не стоит, отличить Java от Bedrock нельзя — никого не кикаем.
     */
    private boolean isJavaPlayer(Player player) {
        if (!getConfig().getBoolean("bedrock-only.enabled", true)) {
            return false;
        }
        if (player.hasPermission("faraltab.bedrock-bypass")) {
            return false;
        }
        if (!hooks.hasFloodgate()) {
            if (!warnedNoFloodgate) {
                warnedNoFloodgate = true;
                getLogger().warning("bedrock-only включён, но Floodgate не найден: Java-игроки НЕ кикаются. Поставьте Floodgate на этот сервер.");
            }
            return false;
        }
        return !hooks.isBedrock(player.getUniqueId());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        sidebar.remove(event.getPlayer().getUniqueId());
    }

    // ------------------------------------------------------------- команды
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String prefix = TextUtil.color(getConfig().getString("messages.prefix", "&#FFB340FaralMC &8» &f"));
        if (args.length >= 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("faraltab.admin")) {
                sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                return true;
            }
            reloadConfig();
            startTask();
            sidebar.updateAll();
            sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.reloaded", "&aКонфиг перезагружен.")));
            return true;
        }
        if (args.length >= 1 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            Player target;
            if (args.length >= 2) {
                if (!sender.hasPermission("faraltab.admin")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
                target = Bukkit.getPlayerExact(args[1]);
                if (target == null) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.player-not-found", "&cИгрок не найден.")));
                    return true;
                }
            } else if (sender instanceof Player) {
                target = (Player) sender;
                if (!sender.hasPermission("faraltab.toggle")) {
                    sender.sendMessage(prefix + TextUtil.color(getConfig().getString("messages.no-permission", "&cНет прав.")));
                    return true;
                }
            } else {
                sender.sendMessage(prefix + "Использование: /faraltab <reload|toggle [игрок]>");
                return true;
            }
            boolean visible;
            if (args[0].equalsIgnoreCase("on")) {
                sidebar.show(target);
                visible = true;
            } else if (args[0].equalsIgnoreCase("off")) {
                sidebar.hide(target);
                visible = false;
            } else {
                visible = sidebar.toggle(target);
            }
            String key = visible ? "messages.shown" : "messages.hidden";
            String def = visible ? "&aСкорборд включён." : "&eСкорборд скрыт.";
            target.sendMessage(prefix + TextUtil.color(getConfig().getString(key, def)));
            return true;
        }
        sender.sendMessage(prefix + "Использование: /faraltab <reload|toggle [игрок]|on|off>");
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("reload", "toggle", "on", "off");
        }
        return List.of();
    }
}
