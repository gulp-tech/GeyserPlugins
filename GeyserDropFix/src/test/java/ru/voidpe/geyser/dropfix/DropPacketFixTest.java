package ru.voidpe.geyser.dropfix;

import org.cloudburstmc.protocol.bedrock.data.inventory.ItemData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryActionData;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventorySource;
import org.cloudburstmc.protocol.bedrock.data.inventory.transaction.InventoryTransactionType;
import org.cloudburstmc.protocol.bedrock.packet.InventoryTransactionPacket;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DropPacketFixTest {
    InventoryTransactionPacket packet(int fromSlot) {
        InventoryTransactionPacket packet = new InventoryTransactionPacket();
        packet.setTransactionType(InventoryTransactionType.NORMAL);
        packet.getActions().add(new InventoryActionData(InventorySource.fromContainerWindowId(0), fromSlot, ItemData.AIR, ItemData.AIR));
        packet.getActions().add(new InventoryActionData(InventorySource.fromWorldInteraction(InventorySource.Flag.DROP_ITEM), 0, ItemData.AIR, ItemData.AIR));
        return packet;
    }

    @Test void usesRealSelectedSlotOnlyDuringDropTranslation() {
        var packet = packet(5);
        assertTrue(DropPacketFix.isDrop(packet));
        packet.setHotbarSlot(0);
        int[] calls = {0};
        DropPacketFix.withSelectedSlot(packet, 5, () -> { calls[0]++; assertEquals(5, packet.getHotbarSlot()); });
        assertEquals(1, calls[0]);
        assertEquals(0, packet.getHotbarSlot());
    }

    @Test void nonSelectedDropAndNormalNonDropKeepDelegatePath() {
        var packet = packet(2);
        DropPacketFix.withSelectedSlot(packet, 7, () -> assertEquals(7, packet.getHotbarSlot()));
        packet.getActions().remove(1);
        assertFalse(DropPacketFix.isDrop(packet));
        packet.setHotbarSlot(3);
        DropPacketFix.withSelectedSlot(packet, 7, () -> assertEquals(3, packet.getHotbarSlot()));
        var other = packet(9);
        assertFalse(DropPacketFix.isDrop(other));
    }

    @Test void restoresPacketOnErrorAndIgnoresInvalidSlots() {
        var packet = packet(4);
        packet.setHotbarSlot(0);
        assertThrows(IllegalStateException.class, () -> DropPacketFix.withSelectedSlot(packet, 4, () -> { throw new IllegalStateException(); }));
        assertEquals(0, packet.getHotbarSlot());
        DropPacketFix.withSelectedSlot(packet, -1, () -> assertEquals(0, packet.getHotbarSlot()));
        packet.setTransactionType(InventoryTransactionType.ITEM_USE);
        assertFalse(DropPacketFix.isDrop(packet));
    }
}
