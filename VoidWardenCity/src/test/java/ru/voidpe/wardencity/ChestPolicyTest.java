package ru.voidpe.wardencity;

public final class ChestPolicyTest {
    public static void main(String[] args) {
        for (int bits = 0; bits < 16; bits++) {
            boolean single = (bits & 1) != 0, inside = (bits & 2) != 0;
            boolean player = (bits & 4) != 0, evidence = (bits & 8) != 0;
            boolean expected = single && inside && !player && evidence;
            if (ChestPolicy.canUse(single, inside, player, evidence) != expected) throw new AssertionError(bits);
            if (ChestPolicy.canDiscover(single, inside, player, evidence) != expected) throw new AssertionError(bits);
        }
        System.out.println("ChestPolicy: all 16 combinations passed");
    }
}
