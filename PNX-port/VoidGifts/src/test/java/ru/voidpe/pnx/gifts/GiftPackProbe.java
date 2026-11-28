package ru.voidpe.pnx.gifts;

import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.plugin.PluginBase;
import java.nio.file.Path;

/** CI-only probe: not the VoidGifts gameplay plugin. */
public final class GiftPackProbe extends PluginBase {
    private Path music, active, staging;
    private java.util.UUID loadedId;
    @Override public void onEnable() {
        Path root = Path.of(getServer().getDataPath());
        music = root.resolve("plugins/VoidGifts/music");
        active = root.resolve("resource_packs/VoidGiftsPack.mcpack");
        staging = getDataFolder().toPath().resolve("staging");
        try {
            GiftLootStoreTest.run();
            getLogger().info("GIFT_LOOT_STORAGE_TEST_OK");
            var result = GiftMusicPack.prepare(music, active, staging);
            GiftPackActivation.activateBeforeNetwork(getServer(), result, active);
            var loaded = getServer().getResourcePackManager().getPackById(result.uuid());
            if (loaded == null || !loaded.getPackVersion().equals("1.0." + result.version())) throw new IllegalStateException("Pack missing from PNX manager");
            if (GiftPackActivation.loadedSoundIds(getServer(), result.uuid(), active).size() != result.tracks().size()) throw new IllegalStateException("Active sound list mismatch");
            loadedId = result.uuid();
            getLogger().info("GIFT_PROBE_READY changed="+result.changed()+" version="+result.version()+" tracks="+result.tracks().size());
        } catch (Exception e) { throw new IllegalStateException("GIFT_PROBE_FAILED", e); }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equals("verify")) {
            try { GiftPackActivation.loadedSoundIds(getServer(), loadedId, active); sender.sendMessage("GIFT_PROBE_VERIFY_OK"); }
            catch (java.io.IOException expected) { sender.sendMessage("GIFT_PROBE_CHANGED_FILE_REFUSED"); }
            return true;
        }
        try {
            var result = GiftMusicPack.prepare(music, active, staging);
            if (!result.changed()) { sender.sendMessage("GIFT_PROBE_NO_CHANGES"); return true; }
            try { GiftPackActivation.activateBeforeNetwork(getServer(), result, active); sender.sendMessage("GIFT_PROBE_UNSAFE_ACTIVATION"); }
            catch (java.io.IOException expected) {
                int loadedCount = GiftPackActivation.loadedSoundIds(getServer(), result.uuid(), active).size();
                sender.sendMessage("GIFT_PROBE_RESTART_REQUIRED active="+loadedCount+" pending="+result.tracks().size());
            }
        } catch (Exception e) { sender.sendMessage("GIFT_PROBE_ERROR: "+e.getMessage()); }
        return true;
    }
}
