package ru.voidpe.pnx.enginehub;

import org.powernukkitx.level.Level;

import java.util.Objects;

/** Both level folder and dimension are required: a Bedrock world can contain multiple dimensions. */
public record PnxWorldKey(String folderName, int dimension) {
    public PnxWorldKey {
        Objects.requireNonNull(folderName, "folderName");
        if (folderName.isBlank()) throw new IllegalArgumentException("World folder must not be empty");
    }

    public static PnxWorldKey of(Level level) {
        Objects.requireNonNull(level, "level");
        return new PnxWorldKey(level.getFolderName(), level.getDimension());
    }
}
