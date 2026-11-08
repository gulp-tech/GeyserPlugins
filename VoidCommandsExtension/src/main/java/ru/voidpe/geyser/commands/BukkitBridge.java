package ru.voidpe.geyser.commands;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.List;
import java.util.UUID;

/** All Bukkit API and tab-complete calls are confined to the server main thread. */
final class BukkitBridge implements Listener {
    private final VoidCommandsExtension extension;
    private final Plugin schedulerOwner;
    private BukkitTask periodicTask;
    private volatile boolean closed;

    BukkitBridge(VoidCommandsExtension extension) {
        this.extension = extension;
        Plugin plugin = Bukkit.getPluginManager().getPlugin("Geyser-Spigot");
        if (plugin == null) plugin = Bukkit.getPluginManager().getPlugin("Geyser");
        if (plugin == null) throw new IllegalStateException("Geyser-Spigot plugin not found");
        this.schedulerOwner = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        long period = extension.config().periodicRefreshTicks();
        if (period > 0) {
            periodicTask = Bukkit.getScheduler().runTaskTimer(plugin, extension::scheduleAll, period, period);
        }
    }

    void scheduleRefresh(SuggestionSession session, long delay) {
        if (closed || session.closed()) return;
        int generation = session.nextGeneration();
        Bukkit.getScheduler().runTaskLater(schedulerOwner,
                () -> extension.refresh(session, generation), Math.max(0, delay));
    }

    void refresh(SuggestionSession session, int generation) {
        if (closed || !session.isCurrent(generation)) return;
        runBatch(session, generation, session.targets(), 0);
    }

    private void runBatch(SuggestionSession session, int generation, List<DynamicParam> targets, int offset) {
        if (closed || !session.isCurrent(generation)) return;
        Player player = Bukkit.getPlayer(session.playerId);
        if (player == null || !player.isOnline()) {
            extension.close(session.playerId);
            return;
        }

        int end = Math.min(targets.size(), offset + extension.config().paramsPerTick());
        for (int i = offset; i < end; i++) {
            DynamicParam target = targets.get(i);
            String commandLine = target.completionLine(session.values);
            if (commandLine == null) continue;

            List<String> completed;
            try {
                completed = Bukkit.getCommandMap().tabComplete(player, commandLine);
            } catch (RuntimeException e) {
                if (extension.config().verbose()) {
                    extension.logger().warning("VoidCommands: tabComplete failed for " + commandLine + ": " + e);
                }
                continue;
            }
            List<String> values = SuggestionText.clean(completed, extension.config().maxSuggestions());
            if (session.updateValues(target.enumName(), values)) {
                extension.sendSoftEnum(session, target.enumName(), values);
            }
        }

        if (end < targets.size()) {
            Bukkit.getScheduler().runTaskLater(schedulerOwner,
                    () -> runBatch(session, generation, targets, end), 1);
        } else if (extension.config().verbose()) {
            extension.logger().info("VoidCommands: обновлено " + targets.size() + " аргументов для "
                    + player.getName() + ".");
        }
    }

    @EventHandler
    public void onCommand(PlayerCommandPreprocessEvent event) {
        extension.schedule(event.getPlayer().getUniqueId(), extension.config().refreshAfterCommandTicks());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        extension.schedule(event.getPlayer().getUniqueId(), 20);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        extension.schedule(event.getPlayer().getUniqueId(), 5);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        extension.close(event.getPlayer().getUniqueId());
    }

    void close() {
        closed = true;
        HandlerList.unregisterAll(this);
        if (periodicTask != null) periodicTask.cancel();
    }
}
