package ru.voidpe.gifts;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class VoidGiftsLootTest {
    private ServerMock server;
    private VoidGifts plugin;
    private PlayerMock player;

    @BeforeEach void setup() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VoidGifts.class);
        player = server.addPlayer();
        player.setOp(true);
    }
    @AfterEach void cleanup() { MockBukkit.unmock(); }

    private ItemStack prize() {
        ItemStack item = new ItemStack(Material.DIAMOND, 7);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("Особый алмаз");
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "test_id"), PersistentDataType.STRING, "kept");
        item.setItemMeta(meta);
        return item;
    }

    @Test void chancesAreIndependentAndBoundarySafe() {
        assertFalse(LootChance.roll(0, 0));
        assertTrue(LootChance.roll(100, 0.999999));
        assertTrue(LootChance.roll(25, 0.249999));
        assertFalse(LootChance.roll(25, 0.25));
        assertEquals(0, LootChance.clamp(Double.NaN));
        assertEquals(100, LootChance.clamp(120));
        assertEquals(0, LootChance.clamp(-5));
    }

    @Test void itemMetadataAmountAndChanceSurviveSaveAndReload() throws Exception {
        String oldGifts = Files.readString(plugin.getDataFolder().toPath().resolve("gifts.yml"));
        GiftLootStore store = new GiftLootStore(plugin);
        store.load();
        assertTrue(store.add("present", prize()));
        String id = store.rewards("present").getFirst().id();
        assertTrue(store.setChance("present", id, 37));
        GiftLootStore loaded = new GiftLootStore(plugin);
        loaded.load();
        assertTrue(loaded.isReady());
        GiftLootStore.Reward reward = loaded.reward("present", id);
        assertNotNull(reward);
        assertEquals(37, reward.chance());
        assertEquals(7, reward.item().getAmount());
        assertEquals("Особый алмаз", reward.item().getItemMeta().getDisplayName());
        assertEquals("kept", reward.item().getItemMeta().getPersistentDataContainer()
                .get(new NamespacedKey(plugin, "test_id"), PersistentDataType.STRING));
        assertEquals(oldGifts, Files.readString(plugin.getDataFolder().toPath().resolve("gifts.yml")));
        ItemStack copy = reward.item(); copy.setAmount(1);
        assertEquals(7, loaded.reward("present", id).item().getAmount());
        assertTrue(loaded.remove("present", id));
        GiftLootStore afterRemove = new GiftLootStore(plugin); afterRemove.load();
        assertTrue(afterRemove.rewards("present").isEmpty());
    }

    @Test void corruptFileIsNotOverwrittenAndGiftsAreUnavailable() throws Exception {
        Path file = plugin.getDataFolder().toPath().resolve("loot.yml");
        String broken = "gifts: [unterminated";
        Files.writeString(file, broken);
        GiftLootStore store = new GiftLootStore(plugin); store.load();
        assertFalse(store.isReady());
        assertFalse(store.add("present", prize()));
        assertEquals(broken, Files.readString(file));
    }

    private void click(int rawSlot) {
        InventoryClickEvent event = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER,
                rawSlot, ClickType.LEFT, InventoryAction.PICKUP_ALL);
        server.getPluginManager().callEvent(event);
        assertTrue(event.isCancelled());
        server.getScheduler().performTicks(2L);
    }

    @Test void adminMenuCopiesItemsAndEditsChancesWithoutMovingInventory() {
        assertTrue(player.performCommand("gift menu"));
        click(0); // first gift type
        ItemStack original = prize();
        player.getInventory().setItem(9, original);
        click(54); // first slot of bottom inventory
        assertEquals(7, player.getInventory().getItem(9).getAmount());
        GiftLootStore stored = new GiftLootStore(plugin); stored.load();
        assertEquals(1, stored.rewards("present").size());
        assertEquals(100, stored.rewards("present").getFirst().chance());
        click(0);  // reward editor
        click(10); // -10%
        stored.load();
        assertEquals(90, stored.rewards("present").getFirst().chance());
        click(22); // delete
        stored.load();
        assertTrue(stored.rewards("present").isEmpty());
        assertEquals(7, player.getInventory().getItem(9).getAmount());
    }

    @Test void nonAdminCannotOpenMenu() {
        player.setOp(false);
        player.performCommand("gift menu");
        assertEquals(InventoryType.CRAFTING, player.getOpenInventory().getTopInventory().getType());
    }

    @SuppressWarnings("unchecked")
    @Test void existingCommandsAndConfiguredItemsAreBothGivenOnlyOnce() throws Exception {
        GiftLootStore store = (GiftLootStore) field("lootStore");
        assertTrue(store.add("present", new ItemStack(Material.EMERALD, 5)));
        AtomicInteger commandCalls = new AtomicInteger();
        server.getCommandMap().register("giftstest", new Command("giftstest") {
            @Override public boolean execute(CommandSender sender, String label, String[] args) {
                assertEquals(player.getName(), args[0]); commandCalls.incrementAndGet(); return true;
            }
        });
        FileConfiguration config = (FileConfiguration) field("giftsConfig");
        config.set("gifts.present.commands", List.of("giftstest %player%"));
        Block block = player.getWorld().getBlockAt(10, 64, 10);
        block.setType(Material.PLAYER_HEAD);
        Map<String, String> placed = (Map<String, String>) field("placed");
        placed.put(block.getWorld().getName() + ";10;64;10", "present");
        PlayerInteractEvent first = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        plugin.onInteract(first);
        assertEquals(1, commandCalls.get());
        assertEquals(5, player.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
        PlayerInteractEvent second = new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, null, block, BlockFace.UP, EquipmentSlot.HAND);
        plugin.onInteract(second);
        assertEquals(1, commandCalls.get());
        assertEquals(5, player.getInventory().all(Material.EMERALD).values().stream().mapToInt(ItemStack::getAmount).sum());
    }

    private Object field(String name) throws Exception {
        Field field = VoidGifts.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(plugin);
    }
}
