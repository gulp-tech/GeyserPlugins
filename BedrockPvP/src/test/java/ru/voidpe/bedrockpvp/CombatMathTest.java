package ru.voidpe.bedrockpvp;
import java.util.Random;

public final class CombatMathTest {
    public static void main(String[] args) {
        assert CombatMath.limitedUpwardImpulse(0, .4, .36) == .36;
        assert Math.abs(CombatMath.limitedUpwardImpulse(.3, .3, .36) - .06) < 1e-9;
        assert CombatMath.limitedUpwardImpulse(.9, .3, .36) == 0;
        assert CombatMath.limitedUpwardImpulse(.9, -.1, .36) == -.1;
        assert CombatMath.limitedUpwardImpulse(-.5, .2, .36) == .2;
        assert CombatMath.reducedWear(1, 2.0 / 3, () -> .1) == 1;
        assert CombatMath.reducedWear(1, 2.0 / 3, () -> .9) == 0;
        assert CombatMath.reducedWear(5, 0, () -> .1) == 0;
        assert CombatMath.reducedWear(5, 1, () -> .9) == 5;
        assert CombatMath.safeClamp(Double.NaN, 1, 0, 5) == 1;
        assert CombatMath.safeClamp(7, 1, 0, 5) == 5;
        Random random = new Random(42);
        int total = 0;
        for (int i = 0; i < 100000; i++) total += CombatMath.reducedWear(1, 2.0 / 3, random::nextDouble);
        assert total > 65500 && total < 67800 : total;
        System.out.println("CombatMath tests OK; retained wear " + total + "/100000");
    }
}
