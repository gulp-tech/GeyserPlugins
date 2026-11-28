package ru.voidpe.geyser.dropfix;

import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;

/** NORMAL transactions have no serialized hotbarSlot; only correct recognized drop packets. */
final class DropPacketFix {
    private DropPacketFix() { }

    static boolean isDrop(InventoryTransactionPacket packet) {
        if (packet.getTransactionType() != InventoryTransactionType.NORMAL || packet.getActions().size() != 2) return false;
        InventoryActionData from = packet.getActions().get(0);
        InventoryActionData world = packet.getActions().get(1);
        return from.getSource().getType() == InventorySource.Type.CONTAINER
                && from.getSlot() >= 0 && from.getSlot() < 9
                && world.getSource().getType() == InventorySource.Type.WORLD_INTERACTION
                && world.getSource().getFlag() == InventorySource.Flag.DROP_ITEM;
    }

    static void withSelectedSlot(InventoryTransactionPacket packet, int selectedSlot, Runnable translate) {
        if (!isDrop(packet) || selectedSlot < 0 || selectedSlot > 8) {
            translate.run();
            return;
        }
        int original = packet.getHotbarSlot();
        try {
            // The original translator still makes all drop, permission, inventory and packet decisions.
            packet.setHotbarSlot(selectedSlot);
            translate.run();
        } finally {
            packet.setHotbarSlot(original);
        }
    }
}
