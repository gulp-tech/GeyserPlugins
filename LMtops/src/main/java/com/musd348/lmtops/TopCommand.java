package com.musd348.lmtops;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;

public class TopCommand implements CommandExecutor, TabCompleter {
    private final LMtops plugin;

    public TopCommand(LMtops plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("lmtops.admin")) {
                sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.no-permission")));
                return true;
            }
            plugin.performReload();
            sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.config-reloaded")));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix") + plugin.getConfig().getString("messages.player-only")));
            return true;
        }

        if (!player.hasPermission("lmtops.use")) {
            player.sendMessage(ColorUtil.colorize(plugin.getConfig().getString("messages.prefix", "") + plugin.getConfig().getString("messages.no-permission", "&cНет прав")));
            return true;
        }
        if (args.length == 0) {
            plugin.getTopGUI().openMainMenu(player);
            return true;
        }

        TopType type = TopType.fromString(args[0]);
        if (type != null) {
            plugin.getTopGUI().openTopList(player, type);
        } else {
            plugin.getTopGUI().openMainMenu(player);
        }

        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> completions = new ArrayList<>(List.of("balance", "kills", "deaths", "shards", "blocks", "playtime"));
            if (sender.hasPermission("lmtops.admin")) {
                completions.add("reload");
            }
            return completions.stream()
                    .filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase()))
                    .toList();
        }
        return List.of();
    }
}