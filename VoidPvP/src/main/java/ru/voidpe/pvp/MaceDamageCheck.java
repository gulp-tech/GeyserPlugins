package ru.voidpe.pvp;

/** Pure mace damage reduction, kept separate for the standalone regression test. */
final class MaceDamageCheck {
    private MaceDamageCheck() {}

    static double reduce(double originalDamage, double reduction, double minimum) {
        double safeReduction = Math.max(0.0, reduction);
        double safeMinimum = Math.max(0.0, minimum);
        return Math.max(safeMinimum, originalDamage - safeReduction);
    }
}
