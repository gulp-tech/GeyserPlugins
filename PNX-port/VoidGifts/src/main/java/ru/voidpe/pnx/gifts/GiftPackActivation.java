package ru.voidpe.pnx.gifts;

import org.powernukkitx.Server;
import org.powernukkitx.resourcepacks.ResourcePack;
import java.io.IOException;
import java.nio.file.*;
import java.util.Arrays;

/** Installs a candidate only during cold server startup, never while clients can be connected. */
public final class GiftPackActivation {
    private GiftPackActivation() {}
    public static void activateBeforeNetwork(Server server, GiftMusicPack.Result result, Path target) throws IOException {
        if (!result.changed()) return;
        if (server.getNetwork() != null || !server.isPrimaryThread() || !server.getOnlinePlayers().isEmpty())
            throw new IOException("Full server restart required to activate the music pack");
        Path expected = Path.of(server.getDataPath(), "resource_packs", "VoidGiftsPack.mcpack").toAbsolutePath().normalize();
        Path active = target.toAbsolutePath().normalize();
        if (!expected.equals(active)) throw new IOException("Unexpected gift pack target");
        GiftMusicPack.verifyCandidate(result);
        if (Files.isSymbolicLink(active)) throw new IOException("Active pack must not be a symlink");
        long sameId = Arrays.stream(server.getResourcePackManager().getResourceStack()).filter(p -> p.getPackId().equals(result.uuid())).count();
        if (sameId > 1 || (sameId > 0 && !Files.exists(active))) throw new IOException("Duplicate gift resource pack UUID");
        Path backup = null; boolean replaced = false;
        try {
            if (Files.exists(active)) {
                backup = Files.createTempFile(result.candidate().getParent(), "gift-pack-backup-", ".tmp");
                Files.copy(active, backup, StandardCopyOption.REPLACE_EXISTING);
            }
            try { Files.move(result.candidate(), active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { throw new IOException("Pack install requires one filesystem and atomic replacement", e); }
            replaced = true;
            server.getResourcePackManager().reloadPacks();
            ResourcePack loaded = server.getResourcePackManager().getPackById(result.uuid());
            long loadedCount = Arrays.stream(server.getResourcePackManager().getResourceStack()).filter(p -> p.getPackId().equals(result.uuid())).count();
            if (loaded == null || loadedCount != 1 || !loaded.getPackVersion().equals("1.0." + result.version()) || loaded.getPackSize() != Files.size(active))
                throw new IOException("PNX did not load the generated gift pack");
        } catch (Exception failure) {
            if (replaced) {
                try {
                    if (backup != null) { Files.move(backup, active, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); backup = null; }
                    else Files.deleteIfExists(active);
                    server.getResourcePackManager().reloadPacks();
                } catch (Exception rollback) { failure.addSuppressed(rollback); }
            }
            if (failure instanceof IOException io) throw io;
            throw new IOException("Failed to activate gift pack", failure);
        } finally { if (backup != null) Files.deleteIfExists(backup); }
    }
}
