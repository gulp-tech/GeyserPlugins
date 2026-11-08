package org.bukkit.plugin;
import org.bukkit.event.Listener;
public interface PluginManager {
    Plugin getPlugin(String name);
    void registerEvents(Listener listener, Plugin plugin);
}
