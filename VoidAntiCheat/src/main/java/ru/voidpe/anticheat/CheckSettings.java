package ru.voidpe.anticheat;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.List;

/** Настройки одной проверки из секции checks.<key>. */
public record CheckSettings(
        boolean enabled,
        double maxVl,
        double decayPerSecond,
        double setbackVl,
        double cancelVl,
        double punishVl,
        List<String> punishCommands
) {

    public static CheckSettings load(FileConfiguration config, CheckId check) {
        String base = "checks." + check.key();
        boolean enabled = config.getBoolean(base + ".enabled",
                config.getBoolean("checks.default.enabled", true));
        double maxVl = positive(config.getDouble(base + ".max-vl",
                config.getDouble("checks.default.max-vl", 20.0)), 20.0);
        double decay = Math.max(0.0, config.getDouble(base + ".decay-per-second",
                config.getDouble("checks.default.decay-per-second", 0.12)));
        double setback = config.getDouble(base + ".setback-vl",
                config.getDouble("checks.default.setback-vl", 0.0));
        double cancel = config.getDouble(base + ".cancel-vl",
                config.getDouble("checks.default.cancel-vl", 0.0));
        double punish = config.getDouble(base + ".punish-vl",
                config.getDouble("checks.default.punish-vl", 0.0));
        List<String> commands = config.getStringList(base + ".punish-commands");
        if (commands.isEmpty()) {
            commands = config.getStringList("punishments.commands");
        }
        return new CheckSettings(enabled, maxVl, decay, setback, cancel, punish, List.copyOf(commands));
    }

    private static double positive(double value, double fallback) {
        return value > 0 ? value : fallback;
    }

    public boolean shouldSetback(double vl) {
        return setbackVl > 0 && vl >= setbackVl;
    }

    public boolean shouldCancel(double vl) {
        return cancelVl > 0 && vl >= cancelVl;
    }

    public boolean shouldPunish(double vl) {
        return punishVl > 0 && vl >= punishVl && punishVl <= maxVl + 0.0001;
    }
}
