package ru.voidpe.shulker;

import org.bukkit.GameMode;
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

    @Test void creativeCannotOpen() {
        player.setGameMode(GameMode.CREATIVE);
        player.getInventory().setItem(0, box(2));
        assertTrue(open(EquipmentSlot.HAND).isCancelled());
        assertEquals(2, storedDiamonds(player.getInventory().getItem(0)));
        assertNotEquals(27, player.getOpenInventory().getTopInventory().getSize());
    }
}
