package ru.voidpe.pvp;

/** Compare the requested base attribute, not the effective value after item modifiers. */
final class AttackSpeedCheck {
    private AttackSpeedCheck() {}

    static boolean matches(double requested, double actualBase) {
        return Double.isFinite(requested) && Double.isFinite(actualBase)
                && Math.abs(requested - actualBase) <= 1.0e-6;
    }
}
