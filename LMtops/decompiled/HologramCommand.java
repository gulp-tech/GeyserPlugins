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
import java.util.ArrayList;
import java.util.List;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

public class HologramCommand
implements CommandExecutor,
TabCompleter {
    private final LMtops plugin;

    public HologramCommand(LMtops plugin) {
        this.plugin = plugin;
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("lmtops.admin")) {
            sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.no-permission")));
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
            this.plugin.performReload();
            sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.holograms-updated", "&a\u0413\u043e\u043b\u043e\u0433\u0440\u0430\u043c\u043c\u044b \u043e\u0431\u043d\u043e\u0432\u043b\u0435\u043d\u044b!")));
            return true;
        }
        if (args[0].equalsIgnoreCase("purge")) {
            int count = this.plugin.getHologramManager().purgeAllHologramsInWorlds();
            sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.holo-purged", "&e\u0423\u0434\u0430\u043b\u0435\u043d\u043e {count} \u0433\u043e\u043b\u043e\u0433\u0440\u0430\u043c\u043c").replace("{count}", String.valueOf(count))));
            return true;
        }
        if (args.length < 2) {
            return true;
        }
        if (args[0].equalsIgnoreCase("create") && sender instanceof Player) {
            String type;
            Player p = (Player)sender;
            String id = args[1];
            String string = type = args.length > 2 ? args[2] : "balance";
            if (this.plugin.getHologramManager().createHologram(id, p.getLocation(), type)) {
                sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.hologram-created").replace("{id}", id)));
            }
        } else if (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove")) {
            String id = args[1];
            if (this.plugin.getHologramManager().deleteHologram(id)) {
                sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.hologram-deleted").replace("{id}", id)));
            } else {
                sender.sendMessage(ColorUtil.colorize(this.plugin.getConfig().getString("messages.prefix") + this.plugin.getConfig().getString("messages.hologram-not-found").replace("{id}", id)));
            }
        }
        return true;
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            return List.of("create", "delete", "purge", "reload").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("delete") || args[0].equalsIgnoreCase("remove"))) {
            return new ArrayList<String>(this.plugin.getHologramManager().getHologramIds()).stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("create")) {
            return List.of("balance", "kills", "deaths", "shards", "blocks", "playtime").stream().filter(s -> s.startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}

