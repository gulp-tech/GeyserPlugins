package ru.voidpe.geyser.commands;

import org.geysermc.geyser.api.connection.GeyserConnection;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

final class SuggestionSession {
    final UUID playerId;
    final Map<String, List<String>> values = new ConcurrentHashMap<>();
    private final AtomicInteger generation = new AtomicInteger();
    private volatile GeyserConnection connection;
    private volatile List<DynamicParam> targets = List.of();
    private volatile boolean closed;

    SuggestionSession(UUID playerId, GeyserConnection connection) {
        this.playerId = playerId;
        this.connection = connection;
    }

    GeyserConnection connection() {
        return connection;
    }

    void connection(GeyserConnection connection) {
        this.connection = connection;
    }

    List<DynamicParam> targets() {
        return targets;
    }

    void targets(List<DynamicParam> targets) {
        this.targets = List.copyOf(targets);
    }

    int nextGeneration() {
        return generation.incrementAndGet();
    }

    boolean isCurrent(int expectedGeneration) {
        return !closed && generation.get() == expectedGeneration;
    }

    boolean updateValues(String enumName, List<String> newValues) {
        List<String> immutable = List.copyOf(newValues);
        List<String> previous = immutable.isEmpty() ? values.remove(enumName) : values.put(enumName, immutable);
        return previous == null ? !immutable.isEmpty() : !previous.equals(immutable);
    }

    void close() {
        closed = true;
        generation.incrementAndGet();
        targets = List.of();
        values.clear();
    }

    boolean closed() {
        return closed;
    }
}
