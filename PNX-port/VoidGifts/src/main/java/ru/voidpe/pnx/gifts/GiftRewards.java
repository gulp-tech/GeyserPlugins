package ru.voidpe.pnx.gifts;

/** Independent per-item reward rolls as in the original VoidGifts. */
public final class GiftRewards {
    private GiftRewards() {}
    public static double clampChance(double chance) {
        return Double.isFinite(chance) ? Math.max(0.0, Math.min(100.0, chance)) : 0.0;
    }
    /** sample must be in [0,1), e.g. from RandomGenerator.nextDouble(). */
    public static boolean roll(double percent, double sample) {
        if (!Double.isFinite(sample) || sample < 0.0 || sample >= 1.0) throw new IllegalArgumentException("Random sample must be in [0,1)");
        double chance = clampChance(percent);
        return chance > 0.0 && (chance >= 100.0 || sample < chance / 100.0);
    }
}
