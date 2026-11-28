package ru.voidpe.pnx.gifts;

import org.powernukkitx.Server;
import org.powernukkitx.resourcepacks.ResourcePack;
import java.io.IOException;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.zip.ZipFile;
import com.google.gson.JsonParser;
import java.nio.charset.StandardCharsets;

/** Installs a candidate only during cold server startup, never while clients can be connected. */
public final class GiftPackActivation {
    private GiftPackActivation() {}

    /** Return only the sound IDs actually loaded by PNX, never IDs in a pending candidate. */
    public static List<String> loadedSoundIds(Server server, java.util.UUID packId, Path target) throws IOException {
        Path expected = Path.of(server.getDataPath(), "resource_packs", "VoidGiftsPack.mcpack").toAbsolutePath().normalize();
        Path active = target.toAbsolutePath().normalize();
        if (!active.equals(expected) || Files.isSymbolicLink(active) || !Files.isRegularFile(active, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Active gift pack unavailable");
        var manager = server.getResourcePackManager();
        ResourcePack loaded = manager.getPackById(packId);
        long count = Arrays.stream(manager.getResourceStack()).filter(p -> p.getPackId().equals(packId)).count();
        if (loaded == null || count != 1 || Files.size(active) != loaded.getPackSize() || Files.size(active) > 300L * 1024 * 1024)
            throw new IOException("PNX has not loaded this gift pack");
        MessageDigest digest;
        try { digest = MessageDigest.getInstance("SHA-256"); } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
        try (var in = Files.newInputStream(active)) {
            byte[] buffer = new byte[8192]; int n;
            while ((n = in.read(buffer)) != -1) digest.update(buffer,0,n);
        }
        if (!Arrays.equals(digest.digest(), loaded.getSha256())) throw new IOException("Active pack changed after PNX loaded it; full restart required");
        try (ZipFile zip = new ZipFile(active.toFile())) {
            var entry = zip.getEntry("sounds/sound_definitions.json");
            if (entry == null || entry.getSize() > 1024 * 1024) throw new IOException("Missing gift sound definitions");
            byte[] data;
            try (var in = zip.getInputStream(entry)) { data = in.readNBytes(1024 * 1024 + 1); }
            if (data.length > 1024 * 1024) throw new IOException("Gift sound definitions too large");
            var definitions = JsonParser.parseString(new String(data, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonObject("sound_definitions");
            if (definitions == null || definitions.size() > 1024) throw new IOException("Invalid gift sound definitions");
            List<String> ids = definitions.keySet().stream().sorted().toList();
            for (String id : ids) if (!id.matches("voidpe:gift_[a-z0-9_]+")) throw new IOException("Invalid gift sound ID");
            return ids;
        } catch (IOException e) { throw e; }
        catch (RuntimeException e) { throw new IOException("Invalid gift sound definitions", e); }
    }

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
