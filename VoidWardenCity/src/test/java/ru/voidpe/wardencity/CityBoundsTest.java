package ru.voidpe.wardencity;

import java.util.Random;

public final class CityBoundsTest {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    public static void main(String[] args) {
        var b = CityBounds.covering(-656, -29, -545, 120, 45, 45, -64, 320);
        check(b.minX() == -776 && b.maxX() == -536, "X range");
        check(b.minY() == -64 && b.maxY() == 16, "height clipping");
        check(b.minZ() == -665 && b.maxZ() == -425, "Z range");
        Random random = new Random(41);
        for (int i = 0; i < 10_000; i++) {
            double x = random.nextDouble() * 2000 - 1000;
            double z = random.nextDouble() * 2000 - 1000;
            double radius = random.nextDouble() * 128;
            var box = CityBounds.covering(x, 0, z, radius, 20, 20, -64, 320);
            double angle = random.nextDouble() * 2 * Math.PI;
            double r = Math.sqrt(random.nextDouble()) * radius;
            int bx = (int) Math.floor(x + Math.cos(angle) * r);
            int bz = (int) Math.floor(z + Math.sin(angle) * r);
            check(bx >= box.minX() && bx <= box.maxX() && bz >= box.minZ() && bz <= box.maxZ(), "circle coverage");
        }
        for (double radius : new double[] { -1, Double.NaN, Double.POSITIVE_INFINITY, 1_000_001 }) {
            try { CityBounds.covering(0, 0, 0, radius, 10, 10, -64, 320); throw new AssertionError("invalid radius"); }
            catch (IllegalArgumentException expected) { /* expected */ }
        }
        System.out.println("CityBounds: all tests passed");
    }
}
