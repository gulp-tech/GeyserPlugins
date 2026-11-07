package ru.voidpe.shulker;

import org.bukkit.GameMode;
import org.bukkit.NamespacedKey;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.block.ShulkerBox;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import static org.junit.jupiter.api.Assertions.*;

/** API-level simulation; the real Bedrock client and Purpurbedrock still need testing. */
class VoidShulkerMockTest {
    private ServerMock server;
    private VoidShulker plugin;
    private PlayerMock player;

    @BeforeEach void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VoidShulker.class);
        player = server.addPlayer();
        player.addAttachment(plugin, "voidshulker.use", true);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    private ItemStack box(int diamonds) {
        ItemStack result = new ItemStack(Material.PURPLE_SHULKER_BOX);
        BlockStateMeta meta = (BlockStateMeta) result.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        if (diamonds != 0) box.getSnapshotInventory().setItem(0, new ItemStack(Material.DIAMOND, diamonds));
        meta.setBlockState(box);
        result.setItemMeta(meta);
        return result;
    }
    private void putStored(ItemStack stack, int slot, ItemStack value) {
        BlockStateMeta meta = (BlockStateMeta) stack.getItemMeta();
        ShulkerBox box = (ShulkerBox) meta.getBlockState();
        box.getSnapshotInventory().setItem(slot, value);
        meta.setBlockState(box);
        stack.setItemMeta(meta);
    }
    private ItemStack storedAt(ItemStack stack, int slot) {
        ShulkerBox box = (ShulkerBox) ((BlockStateMeta) stack.getItemMeta()).getBlockState();
        return box.getSnapshotInventory().getItem(slot);
    }
    private int storedDiamonds(ItemStack stack) {
        ShulkerBox box = (ShulkerBox) ((BlockStateMeta) stack.getItemMeta()).getBlockState();
        return java.util.Arrays.stream(box.getSnapshotInventory().getContents())
                .filter(i -> i != null && i.getType() == Material.DIAMOND)
                .mapToInt(ItemStack::getAmount).sum();
    }
    private PlayerInteractEvent open(EquipmentSlot hand) {
        ItemStack item = hand == EquipmentSlot.HAND ? player.getInventory().getItemInMainHand()
                : player.getInventory().getItemInOffHand();
        PlayerInteractEvent event = new PlayerInteractEvent(player, Action.RIGHT_CLICK_AIR, item, null, BlockFace.SELF, hand);
        plugin.onInteract(event);
        return event;
    }
    private InventoryClickEvent click(int rawSlot, ClickType type, InventoryAction action) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, rawSlot, type, action);
        plugin.onClick(event);
        assertTrue(event.isCancelled());
        return event;
    }
    private int playerDiamonds() {
        return java.util.Arrays.stream(player.getInventory().getStorageContents())
                .filter(i -> i != null && i.getType() == Material.DIAMOND).mapToInt(ItemStack::getAmount).sum();
    }

    @Test void withdrawIsImmediatelyRecordedAndCloseDoesNotDuplicate() {
        player.getInventory().setItem(0, box(8));
        assertTrue(open(EquipmentSlot.HAND).isCancelled());
        assertEquals(Material.PURPLE_SHULKER_BOX, player.getInventory().getItem(0).getType());
        assertEquals(8, storedDiamonds(player.getInventory().getItem(0)));
        click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        assertEquals(0, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(8, playerDiamonds());
        player.closeInventory();
        assertEquals(0, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(8, playerDiamonds());
    }

    @Test void depositOneOffhandAndRejectNestedShulker() {
        player.getInventory().setItemInOffHand(box(0));
        player.getInventory().setItem(9, new ItemStack(Material.DIAMOND, 5));
        player.getInventory().setItem(10, box(0));
        open(EquipmentSlot.OFF_HAND);
        click(27, ClickType.RIGHT, InventoryAction.PICKUP_HALF); // bottom slot 9
        assertEquals(1, storedDiamonds(player.getInventory().getItemInOffHand()));
        assertEquals(4, playerDiamonds());
        click(28, ClickType.LEFT, InventoryAction.PICKUP_ALL); // bottom slot 10 is a shulker
        assertEquals(Material.PURPLE_SHULKER_BOX, player.getInventory().getItem(10).getType());
        assertEquals(1, storedDiamonds(player.getInventory().getItemInOffHand()));
        player.closeInventory();
        assertEquals(1, storedDiamonds(player.getInventory().getItemInOffHand()));
    }

    @Test void depositPrefersClickedDestinationSlot() {
        ItemStack box = box(60); // an earlier matching stack must not win over slot 5
        player.getInventory().setItem(0, box);
        player.getInventory().setItem(5, new ItemStack(Material.DIAMOND, 8));
        open(EquipmentSlot.HAND);
        click(59, ClickType.LEFT, InventoryAction.PICKUP_ALL); // bottom hotbar slot 5
        ItemStack anchor = player.getInventory().getItem(0);
        assertEquals(60, storedAt(anchor, 0).getAmount());
        assertEquals(Material.DIAMOND, storedAt(anchor, 5).getType());
        assertEquals(8, storedAt(anchor, 5).getAmount());
        assertNull(player.getInventory().getItem(5));
    }

    @Test void occupiedPreferredSlotUsesPreviousTransferOrder() {
        ItemStack box = box(60);
        putStored(box, 5, new ItemStack(Material.DIRT, 1));
        player.getInventory().setItem(0, box);
        player.getInventory().setItem(5, new ItemStack(Material.DIAMOND, 8));
        open(EquipmentSlot.HAND);
        click(59, ClickType.LEFT, InventoryAction.PICKUP_ALL); // bottom hotbar slot 5
        ItemStack anchor = player.getInventory().getItem(0);
        assertEquals(64, storedAt(anchor, 0).getAmount(), "matching stack is filled first");
        assertEquals(Material.DIAMOND, storedAt(anchor, 1).getType());
        assertEquals(4, storedAt(anchor, 1).getAmount(), "remainder uses the first empty slot");
        assertEquals(Material.DIRT, storedAt(anchor, 5).getType());
        assertEquals(1, storedAt(anchor, 5).getAmount());
        assertNull(player.getInventory().getItem(5));
    }

    @Test void blockedInputsAndMissingAnchorNeverCreateGuiCopies() {
        player.getInventory().setItem(0, box(6));
        open(EquipmentSlot.HAND);
        InventoryView view = player.getOpenInventory();
        click(0, ClickType.DROP, InventoryAction.DROP_ONE_SLOT);
        click(0, ClickType.DOUBLE_CLICK, InventoryAction.COLLECT_TO_CURSOR);
        assertEquals(6, storedDiamonds(player.getInventory().getItem(0)));
        PlayerInteractEvent rapid = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItem(0), null, BlockFace.SELF, EquipmentSlot.HAND);
        plugin.onInteract(rapid);
        assertTrue(rapid.isCancelled());
        player.getInventory().setItem(0, null); // emulate another plugin moving/removing the anchor
        click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getScheduler().performTicks(1);
        assertEquals(0, playerDiamonds(), "never return GUI copies when anchor is missing");
        assertNotSame(view, player.getOpenInventory());
    }

    @Test void cancelledOpenAndPluginDisableLeaveTheItemInInventory() {
        player.getInventory().setItem(0, box(4));
        server.getPluginManager().registerEvents(new org.bukkit.event.Listener() {
            @org.bukkit.event.EventHandler public void cancel(InventoryOpenEvent event) { event.setCancelled(true); }
        }, plugin);
        open(EquipmentSlot.HAND);
        assertEquals(4, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(0, playerDiamonds());
        server.getPluginManager().disablePlugin(plugin);
        assertEquals(4, storedDiamonds(player.getInventory().getItem(0)));
    }

    /** Loose diamonds plus diamonds stored inside any shulker in the list. */
    private int totalDiamonds(java.util.List<ItemStack> items) {
        int total = 0;
        for (ItemStack item : items) {
            if (item == null || item.getType().isAir()) continue;
            if (item.getType() == Material.DIAMOND) total += item.getAmount();
            else if (isBox(item)) total += storedDiamonds(item);
        }
        return total;
    }
    private static boolean isBox(ItemStack item) {
        return item.getItemMeta() instanceof BlockStateMeta && Material.PURPLE_SHULKER_BOX == item.getType();
    }

    @Test void deathInBothInventoryModesAndQuitDoNotAddCopies() {
        for (boolean keep : new boolean[] {false, true}) {
            player.getInventory().clear();
            player.getInventory().setItem(0, box(7));
            open(EquipmentSlot.HAND);
            click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            java.util.List<ItemStack> before = new java.util.ArrayList<>();
            for (ItemStack item : player.getInventory().getContents()) if (item != null) before.add(item.clone());
            assertEquals(7, totalDiamonds(before), "before death: exactly 7 diamonds exist");
            java.util.List<ItemStack> drops = new java.util.ArrayList<>(before);
            org.bukkit.damage.DamageSource source = org.bukkit.damage.DamageSource.builder(org.bukkit.damage.DamageType.GENERIC).build();
            org.bukkit.event.entity.PlayerDeathEvent death = new org.bukkit.event.entity.PlayerDeathEvent(player, source, drops, 0,
                    (net.kyori.adventure.text.Component) null, true);
            death.setKeepInventory(keep);
            plugin.onDeath(death);
            assertEquals(7, totalDiamonds(drops), "after death (keepInventory=" + keep + "): still exactly 7 diamonds");
            assertEquals(1, drops.stream().filter(VoidShulkerMockTest::isBox).count(), "only one shulker is dropped");
        }
        player.getInventory().clear();
        player.getInventory().setItem(0, box(5));
        open(EquipmentSlot.HAND);
        plugin.onQuit(new org.bukkit.event.player.PlayerQuitEvent(player, (net.kyori.adventure.text.Component) null,
                org.bukkit.event.player.PlayerQuitEvent.QuitReason.DISCONNECTED));
        player.closeInventory();
        assertEquals(5, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(5, totalDiamonds(java.util.Arrays.asList(player.getInventory().getContents())));
    }

    @Test void heldSwapDragAndPlacementAreCancelledWhileOpen() {
        player.getInventory().setItem(0, box(3));
        open(EquipmentSlot.HAND);
        var swap = new org.bukkit.event.player.PlayerSwapHandItemsEvent(player, player.getInventory().getItemInMainHand(), player.getInventory().getItemInOffHand());
        plugin.onSwapHands(swap);
        assertTrue(swap.isCancelled());
        var drag = new org.bukkit.event.inventory.InventoryDragEvent(player.getOpenInventory(), null,
                new ItemStack(Material.DIAMOND), false, java.util.Map.of(0, new ItemStack(Material.DIAMOND)));
        plugin.onDrag(drag);
        assertTrue(drag.isCancelled());
        var block = player.getLocation().getBlock();
        block.setType(Material.PURPLE_SHULKER_BOX);
        var place = new org.bukkit.event.block.BlockPlaceEvent(block, block.getState(), block,
                player.getInventory().getItemInMainHand(), player, true, EquipmentSlot.HAND);
        plugin.onPlace(place);
        assertTrue(place.isCancelled());
        assertEquals(3, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(0, playerDiamonds());
    }

    @Test void creativeCannotOpen() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItem(0, box(2));
        assertTrue(open(EquipmentSlot.HAND).isCancelled());
        assertEquals(2, storedDiamonds(player.getInventory().getItem(0)));
        var top = player.getOpenInventory().getTopInventory();
        assertTrue(top == null || top.getSize() != 27, "no shulker GUI opened in creative");
    }

    /**
     * Simulates a crash: only the serialized items that were on disk at that moment survive.
     * Shutdown hooks of the old server are deliberately not relied on; a fresh server then joins the player.
     */
    private void crashAndRestart() {
        ItemStack[] onDisk = player.getInventory().getContents();
        byte[][] saved = new byte[onDisk.length][];
        for (int i = 0; i < onDisk.length; i++) if (onDisk[i] != null) saved[i] = onDisk[i].serializeAsBytes();
        MockBukkit.unmock();
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VoidShulker.class);
        player = server.addPlayer();
        player.addAttachment(plugin, "voidshulker.use", true);
        for (int i = 0; i < saved.length; i++) {
            if (saved[i] != null) player.getInventory().setItem(i, ItemStack.deserializeBytes(saved[i]));
        }
        plugin.onJoin(new PlayerJoinEvent(player, (net.kyori.adventure.text.Component) null));
    }

    private boolean hasSessionMarker(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return false;
        NamespacedKey key = new NamespacedKey(plugin, "open_session");
        return item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.STRING);
    }

    private void assertNoSessionMarkers() {
        for (ItemStack item : player.getInventory().getContents()) {
            assertFalse(hasSessionMarker(item), "temporary open_session marker must not survive a crash");
        }
    }

    @Test void crashWhileDiamondsStayInsideKeepsOneShulkerAndEveryDiamond() {
        player.getInventory().setItem(0, box(8));
        open(EquipmentSlot.HAND);
        assertTrue(hasSessionMarker(player.getInventory().getItem(0)), "session is open before the crash");
        crashAndRestart();
        assertNoSessionMarkers();
        assertEquals(1, java.util.Arrays.stream(player.getInventory().getContents())
                .filter(i -> i != null && i.getType() == Material.PURPLE_SHULKER_BOX).count(), "exactly one shulker");
        assertEquals(8, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(0, playerDiamonds());
        assertEquals(8, totalDiamonds(java.util.Arrays.asList(player.getInventory().getContents())));
    }

    @Test void crashAfterWithdrawalKeepsEveryDiamondExactlyOnce() {
        player.getInventory().setItem(0, box(8));
        open(EquipmentSlot.HAND);
        click(0, ClickType.LEFT, InventoryAction.PICKUP_ALL); // all 8 leave the shulker
        assertEquals(8, playerDiamonds());
        assertTrue(hasSessionMarker(player.getInventory().getItem(0)), "session is still open at the crash");
        crashAndRestart();
        assertNoSessionMarkers();
        assertEquals(0, storedDiamonds(player.getInventory().getItem(0)));
        assertEquals(8, playerDiamonds(), "withdrawn diamonds are not duplicated by a restart");
        assertEquals(8, totalDiamonds(java.util.Arrays.asList(player.getInventory().getContents())));
    }
}
