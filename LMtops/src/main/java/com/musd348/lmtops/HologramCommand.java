package com.musd348.lmtops;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class HologramCommand implements CommandExecutor, TabCompleter {
    private final LMtops plugin;

    public HologramCommand(LMtops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lmtops.admin")) {
            sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-permission")));
            return true;
        }

        if (args.length == 0) {
            sender.sendMessage(ColorUtil.colorize("&e/topholo create <id> <balance|kills|deaths|shards|blocks|playtime>"));
            sender.sendMessage(ColorUtil.colorize("&e/topholo delete <id>"));
            sender.sendMessage(ColorUtil.colorize("&e/topholo purge"));
            sender.sendMessage(ColorUtil.colorize("&e/topholo reload"));
            return true;
        }

        if (args[0].equalsIgnoreCase("reload")) {
            plugin.performReload();
            sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.holograms-updated", "&aГолограммы обновлены!")));
            return true;
        }

        if (args[0].equalsIgnoreCase("purge")) {
            int count = plugin.getHologramManager().purgeAllHologramsInWorlds();
            sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") +
                    plugin.getConfig().getString("messages.holo-purged", "&eУдалено {count} голограмм").replace("{count}", String.valueOf(count))));
            return true;
        }

        if (args.length < 2) return true;

        if (args[0].equalsIgnoreCase("create") && sender instanceof Player p) {
            String id = args[1];
            String type = args.length > 2 ? args[2] : "balance";
            if (plugin.getHologramManager().createHologram(id, p.getLocation(), type)) {
                sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") +
                        plugin.getConfig().getString("messages.hologram-created").replace("{id}", id)));
            }
        } else if (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove")) {
            String id = args[1];
            if (plugin.getHologramManager().deleteHologram(id)) {
                sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") +
                        plugin.getConfig().getString("messages.hologram-deleted").replace("{id}", id)));
            } else {
                sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") +
                        plugin.getConfig().getString("messages.hologram-not-found").replace("{id}", id)));
            }
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "delete", "purge", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove"))) {
            return new ArrayList<>(plugin.getHologramManager().getHologramIds()).stream()
                    .filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return List.of("balance", "kills", "deaths", "shards", "blocks", "playtime").stream()
                    .filter(s -> s.startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}