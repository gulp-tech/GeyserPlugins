package org.bukkit.command;
import java.util.List;
public interface CommandMap {
    List<String> tabComplete(CommandSender sender, String commandLine);
}
