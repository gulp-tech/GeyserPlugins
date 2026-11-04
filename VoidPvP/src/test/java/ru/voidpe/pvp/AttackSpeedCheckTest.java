package ru.voidpe.pvp;

/** Standalone regression test, run with assertions enabled. */
public final class AttackSpeedCheckTest {
    public static void main(String[] args) {
        // Item modifier -3.4 produces 3.1 effective, but the base remains 6.5.
        assert AttackSpeedCheck.matches(6.5, 6.5);
        assert AttackSpeedCheck.matches(6.5, 6.5000001);
        assert !AttackSpeedCheck.matches(6.5, 3.1);
        assert !AttackSpeedCheck.matches(6.5, Double.NaN);
        assert !AttackSpeedCheck.matches(6.5, Double.POSITIVE_INFINITY);
    }
}
