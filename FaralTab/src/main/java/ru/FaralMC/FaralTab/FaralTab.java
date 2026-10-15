package ru.FaralMC.FaralTab;

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

import java.util.List;

/**
 * FaralTab — компактный скорборд (таб) для Geyser/Floodgate сервера FaralMC.
 * Purpur 26.2 / Java 25.
 */
public final class FaralTab extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {

    private Hooks hooks;
    private SidebarManager sidebar;
    private BukkitTask task;

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
        getLogger().info("FaralTab включён. Иконки Bedrock: " + (hooks.hasFloodgate() ? "Floodgate найден" : "Floodgate не найден (см. icons.* в config.yml)"));
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
        long delay = Math.max(0L, getConfig().getLong("join-delay-ticks", 20L));
        Bukkit.getScheduler().runTaskLater(this, () -> sidebar.update(event.getPlayer(), true), delay);
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
