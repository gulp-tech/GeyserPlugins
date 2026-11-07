package ru.voidpe.pvp;

/** Standalone mace damage regression test, run with assertions enabled. */
public final class MaceDamageCheckTest {
    public static void main(String[] args) {
        assert MaceDamageCheck.reduce(12.0, 5.0, 0.0) == 7.0;
        assert MaceDamageCheck.reduce(7.5, 5.0, 0.0) == 2.5;
        assert MaceDamageCheck.reduce(4.0, 5.0, 0.0) == 0.0;
        assert MaceDamageCheck.reduce(4.0, 5.0, 1.0) == 1.0;
        assert MaceDamageCheck.reduce(6.0, 0.0, 0.0) == 6.0;
        assert MaceDamageCheck.reduce(6.0, -2.0, 0.0) == 6.0;
    }
}
