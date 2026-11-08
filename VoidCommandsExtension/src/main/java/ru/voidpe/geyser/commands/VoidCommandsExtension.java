package ru.voidpe.geyser.commands;

import org.cloudburstmc.protocol.bedrock.data.command.SoftEnumUpdateType;
import org.cloudburstmc.protocol.bedrock.packet.UpdateSoftEnumPacket;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.connection.GeyserConnection;
import org.geysermc.geyser.api.event.bedrock.SessionDisconnectEvent;
import org.geysermc.geyser.api.event.downstream.ServerDefineCommandsEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Adds real per-player Bukkit tab completions to free-form Bedrock command arguments.
 * No command is executed while suggestions are being collected.
 */
public final class VoidCommandsExtension implements Extension {
    private final Map<UUID, SuggestionSession> sessions = new ConcurrentHashMap<>();
    private volatile CommandConfig config = new CommandConfig(true, 4, 1024, 100, 8, 2, 6000, false);
    private volatile BukkitBridge bridge;
    private volatile boolean enabled;
    private volatile Method sendUpstreamPacket;
    private volatile boolean sendFailureLogged;

    @Subscribe
    public void onPostInitialize(GeyserPostInitializeEvent event) {
        config = CommandConfig.load(this);
        enabled = config.enabled();
        if (!enabled) {
            logger().info("VoidCommands выключен в config.properties.");
            return;
        }
        try {
            bridge = new BukkitBridge(this);
            logger().info("VoidCommands включён: Bukkit tab-completion для аргументов Bedrock-команд, "
                    + "глубина " + config.maxDepth() + ", до " + config.maxSuggestions() + " значений.");
        } catch (Throwable t) {
            enabled = false;
            logger().error("VoidCommands требует Geyser-Spigot на том же Purpur-сервере; расширение отключено: "
                    + describe(t));
        }
    }

    @Subscribe
    public void onDefineCommands(ServerDefineCommandsEvent event) {
        if (!enabled || bridge == null) return;
        GeyserConnection connection = event.connection();
        UUID playerId = connection.javaUuid();
        if (playerId == null) return;

        SuggestionSession session = sessions.computeIfAbsent(playerId, id -> new SuggestionSession(id, connection));
        if (session.closed()) return;
        session.connection(connection);
        List<DynamicParam> targets = CommandGraph.attach(event.commands(), session.values, config, this);
        session.targets(targets);
        if (config.verbose()) {
            logger().info("VoidCommands: " + connection.bedrockUsername() + ": "
                    + targets.size() + " динамических аргументов.");
        }
        bridge.scheduleRefresh(session, 1);
    }

    @Subscribe
    public void onDisconnect(SessionDisconnectEvent event) {
        UUID playerId = event.connection().javaUuid();
        if (playerId != null) close(playerId);
    }

    @Subscribe
    public void onShutdown(GeyserShutdownEvent event) {
        enabled = false;
        BukkitBridge current = bridge;
        bridge = null;
        if (current != null) current.close();
        for (UUID playerId : sessions.keySet()) close(playerId);
    }

    CommandConfig config() {
        return config;
    }

    void schedule(UUID playerId, long delay) {
        if (!enabled || bridge == null) return;
        SuggestionSession session = sessions.get(playerId);
        if (session != null && !session.targets().isEmpty()) bridge.scheduleRefresh(session, delay);
    }

    void scheduleAll() {
        if (!enabled || bridge == null) return;
        for (SuggestionSession session : sessions.values()) {
            if (!session.targets().isEmpty()) bridge.scheduleRefresh(session, 0);
        }
    }

    void refresh(SuggestionSession session, int generation) {
        BukkitBridge current = bridge;
        if (enabled && current != null) current.refresh(session, generation);
    }

    void close(UUID playerId) {
        SuggestionSession session = sessions.remove(playerId);
        if (session != null) session.close();
    }

    /** Sends one changed enum; AvailableCommands already declared its name. */
    void sendSoftEnum(SuggestionSession session, String enumName, List<String> values) {
        GeyserConnection connection = session.connection();
        if (connection == null || session.closed()) return;
        try {
            Method method = sendUpstreamPacket;
            if (method == null) {
                method = findSendMethod(connection.getClass());
                sendUpstreamPacket = method;
            }
            UpdateSoftEnumPacket packet = new UpdateSoftEnumPacket();
            packet.setType(SoftEnumUpdateType.SET);
            packet.setSoftEnum(new org.cloudburstmc.protocol.bedrock.data.command.CommandEnumData(
                    enumName, CommandGraph.enumValues(values), true));
            method.invoke(connection, packet);
        } catch (Throwable t) {
            if (!sendFailureLogged) {
                sendFailureLogged = true;
                logger().warning("VoidCommands: не удалось отправить UpdateSoftEnumPacket: " + describe(t));
            }
        }
    }

    private static Method findSendMethod(Class<?> type) throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals("sendUpstreamPacket") && method.getParameterCount() == 1
                    && method.getParameterTypes()[0].isAssignableFrom(UpdateSoftEnumPacket.class)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new NoSuchMethodException("sendUpstreamPacket(BedrockPacket) on " + type.getName());
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable instanceof InvocationTargetException invocation
                && invocation.getCause() != null ? invocation.getCause() : throwable;
        return current.getClass().getSimpleName() + ": " + current.getMessage();
    }
}
