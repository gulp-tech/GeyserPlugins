/*
 * Decompiled with CFR 0.152.
 * 
 * Could not load the following classes:
 *  org.bukkit.command.Command
 *  org.bukkit.command.CommandExecutor
 *  org.bukkit.command.CommandSender
 *  org.bukkit.command.TabCompleter
 *  org.bukkit.entity.Player
 */
package com.musd348.lmtops;

import com.musd348.lmtops.ColorUtil;
import com.musd348.lmtops.LMtops;
import com.musd348.lmtops.TopType;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class TopCommand
implements CommandExecutor,
TabCompleter {
    private final LMtops plugin;

    public TopCommand(LMtops plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("lmtops.admin")) {
                sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.no-permission")));
                return true;
            }
            this.plugin.performReload();
            sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.config-reloaded")));
            return true;
        }
        if (!(sender instanceof Player)) {
            sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.player-only")));
            return true;
        }
        Player player = (Player)sender;
        if (args.length == 0) {
            this.plugin.getTopGUI().openMainMenu(player);
            return true;
        }
        TopType type = TopType.fromString(args[0]);
        if (type != null) {
            this.plugin.getTopGUI().openTopList(player, type);
        } else {
            this.plugin.getTopGUI().openMainMenu(player);
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            ArrayList<String> completions = new ArrayList<String>(List.of("balance", "kills", "deaths", "shards", "blocks", "playtime"));
            if (sender.hasPermission("lmtops.admin")) {
                completions.add("reload");
            }
            return completions.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        return List.of();
    }
}

