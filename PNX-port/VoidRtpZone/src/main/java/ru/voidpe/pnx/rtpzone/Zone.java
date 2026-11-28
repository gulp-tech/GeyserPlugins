package ru.voidpe.pnx.rtpzone;

import java.util.Objects;
import java.util.regex.Pattern;

/** Immutable inclusive cuboid. Zone IDs must be safe as YAML keys. */
public record Zone(String name, String world, int dimension, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    private static final Pattern NAME = Pattern.compile("[a-z0-9_-]{1,64}");
    public Zone {
        Objects.requireNonNull(name); Objects.requireNonNull(world);
        if (!NAME.matcher(name).matches()) throw new IllegalArgumentException("Недопустимое имя зоны: " + name);
        if (world.isBlank()) throw new IllegalArgumentException("Пустое имя мира");
        if (minX > maxX || minY > maxY || minZ > maxZ) throw new IllegalArgumentException("Неверные границы зоны " + name);
    }
    public static Zone between(String name, String world, int dimension, int x1, int y1, int z1, int x2, int y2, int z2) {
        return new Zone(name, world, dimension, Math.min(x1,x2), Math.min(y1,y2), Math.min(z1,z2), Math.max(x1,x2), Math.max(y1,y2), Math.max(z1,z2));
    }
    public boolean contains(int x, int y, int z) { return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ; }
    public String volume() {
        // BigInteger avoids overflow even for malformed/extreme imported dimensions.
        return java.math.BigInteger.valueOf((long)maxX - minX + 1)
            .multiply(java.math.BigInteger.valueOf((long)maxY - minY + 1))
            .multiply(java.math.BigInteger.valueOf((long)maxZ - minZ + 1)).toString();
    }
}
