package ru.voidpe.bedrockpvp;

import java.util.function.DoubleSupplier;

/** Pure helpers: finite bounds and unbiased integer wear thinning. */
final class CombatMath {
    private CombatMath() { }

    static double safeClamp(double value, double fallback, double low, double high) {
        return Double.isFinite(value) ? Math.max(low, Math.min(high, value)) : fallback;
    }

    static double limitedUpwardImpulse(double currentY, double impulseY, double limit) {
        if (!Double.isFinite(currentY) || !Double.isFinite(impulseY) || impulseY <= 0) return impulseY;
        // Do not pull down a player who is already moving faster than the cap.
        return Math.min(impulseY, Math.max(0, limit - Math.max(0, currentY)));
    }

    static int reducedWear(int damage, double factor, DoubleSupplier random) {
        if (damage <= 0) return damage;
        // Unbiased fractional rounding, constant time even for huge wear amounts.
        double scaled = damage * factor;
        int whole = (int) Math.floor(scaled);
        return whole + (random.getAsDouble() < scaled - whole ? 1 : 0);
    }
}
