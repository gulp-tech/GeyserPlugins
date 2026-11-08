package org.bukkit;

import org.bukkit.command.CommandMap;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.scheduler.BukkitScheduler;
import java.util.UUID;

public final class Bukkit {
    private Bukkit() {}
    public static PluginManager getPluginManager() { throw new UnsupportedOperationException(); }
    public static BukkitScheduler getScheduler() { throw new UnsupportedOperationException(); }
    public static CommandMap getCommandMap() { throw new UnsupportedOperationException(); }
    public static Player getPlayer(UUID id) { throw new UnsupportedOperationException(); }
}
