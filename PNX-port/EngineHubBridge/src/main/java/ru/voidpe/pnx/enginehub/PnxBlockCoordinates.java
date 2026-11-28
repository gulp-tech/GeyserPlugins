package ru.voidpe.pnx.enginehub;

import com.sk89q.worldedit.math.BlockVector3;
import org.powernukkitx.math.Vector3;

import java.util.Objects;

/** Converts PNX positions into real WorldEdit block coordinates, without truncating negatives. */
public final class PnxBlockCoordinates {
    public static BlockVector3 toWorldEdit(Vector3 position) {
        Objects.requireNonNull(position, "position");
        return BlockVector3.at(floor(position.getX()), floor(position.getY()), floor(position.getZ()));
    }

    private static int floor(double coordinate) {
        if (!Double.isFinite(coordinate)) throw new IllegalArgumentException("Non-finite position");
        double floored = Math.floor(coordinate);
        if (floored < Integer.MIN_VALUE || floored > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Position outside WorldEdit integer range");
        }
        return (int) floored;
    }

    private PnxBlockCoordinates() {}
}
