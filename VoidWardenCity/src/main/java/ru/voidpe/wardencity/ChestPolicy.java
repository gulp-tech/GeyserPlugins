package ru.voidpe.wardencity;

/** Never treat a coordinate or an arbitrary player container as evidence of an event chest. */
final class ChestPolicy {
    private ChestPolicy() { }
    static boolean canUse(boolean single, boolean inside, boolean playerPlaced, boolean marked) {
        return single && inside && !playerPlaced && marked;
    }
    static boolean canDiscover(boolean single, boolean inside, boolean playerPlaced, boolean nativeCityTable) {
        return single && inside && !playerPlaced && nativeCityTable;
    }
}
