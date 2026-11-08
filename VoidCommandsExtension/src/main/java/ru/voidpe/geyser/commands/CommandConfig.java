package ru.voidpe.geyser.commands;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

record CommandConfig(boolean enabled, int maxDepth, int maxTargets, int maxSuggestions,
                     int paramsPerTick, long refreshAfterCommandTicks,
                     long periodicRefreshTicks, boolean verbose) {

    static CommandConfig load(VoidCommandsExtension extension) {
        CommandConfig defaults = new CommandConfig(true, 4, 1024, 100, 8, 2, 6000, false);
        try {
            Path folder = extension.dataFolder();
            Files.createDirectories(folder);
            Path file = folder.resolve("config.properties");
            if (!Files.exists(file)) writeDefaults(file);
            Properties properties = new Properties();
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            return new CommandConfig(
                    bool(properties, "enabled", defaults.enabled()),
                    bounded(properties, "max-depth", defaults.maxDepth(), 1, 16),
                    bounded(properties, "max-targets", defaults.maxTargets(), 1, 10000),
                    bounded(properties, "max-suggestions", defaults.maxSuggestions(), 1, 1000),
                    bounded(properties, "params-per-tick", defaults.paramsPerTick(), 1, 100),
                    boundedLong(properties, "refresh-after-command-ticks", defaults.refreshAfterCommandTicks(), 0, 200),
                    boundedLong(properties, "periodic-refresh-ticks", defaults.periodicRefreshTicks(), 0, 72000),
                    bool(properties, "verbose", defaults.verbose())
            );
        } catch (IOException | RuntimeException e) {
            extension.logger().warning("VoidCommands: не удалось прочитать config.properties ("
                    + e.getMessage() + "); используются безопасные значения по умолчанию.");
            return defaults;
        }
    }

    private static void writeDefaults(Path file) throws IOException {
        try (BufferedWriter writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            writer.write("# VoidCommandsExtension — настоящие Bukkit tab-completion для Bedrock\n");
            writer.write("enabled=true\n");
            writer.write("# Сколько аргументов каждой ветки команды обогащать (1 = только первый).\n");
            writer.write("max-depth=4\n");
            writer.write("# Общий предохранитель от слишком большого AvailableCommandsPacket.\n");
            writer.write("max-targets=1024\n");
            writer.write("# Максимум значений в одном per-player soft enum.\n");
            writer.write("max-suggestions=100\n");
            writer.write("# Сколько tab-complete запросов выполнять на основном потоке за тик.\n");
            writer.write("params-per-tick=8\n");
            writer.write("# Повтор после выполнения команды: /sethome, /delhome, /warp и т.д.\n");
            writer.write("refresh-after-command-ticks=2\n");
            writer.write("# Фоновое обновление внешних изменений; 0 = выключить.\n");
            writer.write("periodic-refresh-ticks=6000\n");
            writer.write("verbose=false\n");
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static int bounded(Properties properties, String key, int fallback, int min, int max) {
        return (int) boundedLong(properties, key, fallback, min, max);
    }

    private static long boundedLong(Properties properties, String key, long fallback, long min, long max) {
        String value = properties.getProperty(key);
        if (value == null) return fallback;
        try {
            return Math.max(min, Math.min(max, Long.parseLong(value.trim())));
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }
}
