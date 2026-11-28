package ru.voidpe.geyser.dropfix;

import org.cloudburstmc.protocol.bedrock.packet.BedrockPacket;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostInitializeEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserPostReloadEvent;
import org.geysermc.geyser.api.event.lifecycle.GeyserShutdownEvent;
import org.geysermc.geyser.api.extension.Extension;
import org.geysermc.geyser.registry.PacketTranslatorRegistry;
import org.geysermc.geyser.registry.Registries;
import org.geysermc.geyser.session.GeyserSession;
import org.geysermc.geyser.translator.protocol.PacketTranslator;

/** Version-sensitive Geyser-internal hook. No direct inventory edits or synthetic drops. */
public final class GeyserDropFix implements Extension {
    private PacketTranslator<InventoryTransactionPacket> original;
    private PacketTranslator<InventoryTransactionPacket> wrapper;
    private PacketTranslatorRegistry<BedrockPacket> registry;

    @Subscribe public void initialize(GeyserPostInitializeEvent event) { install(); }
    @Subscribe public void reload(GeyserPostReloadEvent event) { uninstall(); install(); }
    @Subscribe public void shutdown(GeyserShutdownEvent event) { uninstall(); }

    @SuppressWarnings("unchecked")
    private void install() {
        registry = Registries.BEDROCK_PACKET_TRANSLATORS;
        PacketTranslator<?> candidate = registry.get(InventoryTransactionPacket.class);
        if (candidate == wrapper && wrapper != null) return;
        // Refuse to wrap an unknown translator: its assumptions and lifecycle may differ.
        if (candidate == null || !candidate.getClass().getName().equals(
                "org.geysermc.geyser.translator.protocol.bedrock.BedrockInventoryTransactionTranslator")) {
            logger().warning("Inventory translator differs from the tested Geyser version; drop fix was not installed.");
            return;
        }
        original = (PacketTranslator<InventoryTransactionPacket>) candidate;
        PacketTranslator<InventoryTransactionPacket> target = original;
        wrapper = new PacketTranslator<>() {
            @Override public void translate(GeyserSession session, InventoryTransactionPacket packet) {
                if (!DropPacketFix.isDrop(packet)) {
                    target.translate(session, packet);
                    return;
                }
                int slot = session.getPlayerInventory().getHeldItemSlot();
                DropPacketFix.withSelectedSlot(packet, slot, () -> target.translate(session, packet));
            }
            @Override public boolean shouldExecuteInEventLoop() { return target.shouldExecuteInEventLoop(); }
        };
        registry.register(InventoryTransactionPacket.class, wrapper);
        logger().info("Experimental hotbar drop compatibility hook installed; test on Bedrock clients.");
    }

    private void uninstall() {
        if (registry != null && original != null && wrapper != null
                && registry.get(InventoryTransactionPacket.class) == wrapper) {
            registry.register(InventoryTransactionPacket.class, original);
        }
        original = null;
        wrapper = null;
        registry = null;
    }
}
