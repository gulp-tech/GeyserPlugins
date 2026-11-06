package ru.voidpe.wardencity;

/** Geometry independent of Bukkit/WG, so bounds can be checked in CI. */
final class CityBounds {
    record Cuboid(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {}
    private CityBounds() {}

    static Cuboid covering(double x, double y, double z, double radius, double below, double above,
                           int worldMinY, int worldMaxYExclusive) {
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                || !Double.isFinite(radius) || !Double.isFinite(below) || !Double.isFinite(above)
                || radius < 0 || radius > 1_000_000 || below < 0 || above < 0
                || Math.abs(x) + radius > 30_000_000 || Math.abs(z) + radius > 30_000_000
                || worldMinY >= worldMaxYExclusive) {
            throw new IllegalArgumentException("некорректные границы города");
        }
        int minX = (int) Math.floor(x - radius), maxX = (int) Math.ceil(x + radius);
        int minZ = (int) Math.floor(z - radius), maxZ = (int) Math.ceil(z + radius);
        int minY = (int) Math.max(worldMinY, Math.floor(y - below));
        int maxY = (int) Math.min(worldMaxYExclusive - 1, Math.ceil(y + above));
        if (minY > maxY) throw new IllegalArgumentException("город находится вне высоты мира");
        return new Cuboid(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
