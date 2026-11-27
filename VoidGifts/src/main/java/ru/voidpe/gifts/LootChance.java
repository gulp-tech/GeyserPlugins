package ru.voidpe.gifts;

/** Percentages are independent per entry; roll accepts a random value in [0, 1). */
final class LootChance {
    private LootChance() { }

    static double clamp(double value) {
        return Double.isFinite(value) ? Math.max(0.0, Math.min(100.0, value)) : 0.0;
    }

    static boolean roll(double percent, double sample) {
        double chance = clamp(percent);
        return chance > 0.0 && (chance >= 100.0 || sample < chance / 100.0);
    }
}
