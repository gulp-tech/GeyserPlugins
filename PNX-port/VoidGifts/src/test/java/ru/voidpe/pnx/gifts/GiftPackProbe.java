package ru.voidpe.pnx.gifts;

import org.powernukkitx.command.Command;
import org.powernukkitx.command.CommandSender;
import org.powernukkitx.plugin.PluginBase;
import java.nio.file.Path;

/** CI-only probe: not the VoidGifts gameplay plugin. */
public final class GiftPackProbe extends PluginBase {
    private Path music, active, staging;
    @Override public void onEnable() {
        Path root = Path.of(getServer().getDataPath());
        music = root.resolve("plugins/VoidGifts/music");
        active = root.resolve("resource_packs/VoidGiftsPack.mcpack");
        staging = getDataFolder().toPath().resolve("staging");
        try {
            var result = GiftMusicPack.prepare(music, active, staging);
            GiftPackActivation.activateBeforeNetwork(getServer(), result, active);
            var loaded = getServer().getResourcePackManager().getPackById(result.uuid());
            if (loaded == null || !loaded.getPackVersion().equals("1.0." + result.version())) throw new IllegalStateException("Pack missing from PNX manager");
            getLogger().info("GIFT_PROBE_READY changed="+result.changed()+" version="+result.version()+" tracks="+result.tracks().size());
        } catch (Exception e) { throw new IllegalStateException("GIFT_PROBE_FAILED", e); }
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        try {
            var result = GiftMusicPack.prepare(music, active, staging);
            if (!result.changed()) { sender.sendMessage("GIFT_PROBE_NO_CHANGES"); return true; }
            try { GiftPackActivation.activateBeforeNetwork(getServer(), result, active); sender.sendMessage("GIFT_PROBE_UNSAFE_ACTIVATION"); }
            catch (java.io.IOException expected) { sender.sendMessage("GIFT_PROBE_RESTART_REQUIRED"); }
        } catch (Exception e) { sender.sendMessage("GIFT_PROBE_ERROR: "+e.getMessage()); }
        return true;
    }
}
