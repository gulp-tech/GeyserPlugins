package ru.voidpe.wardencity;

import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Chest;
import org.bukkit.block.data.type.Chest.Type;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.bukkit.World;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WardenLootIntegrationTest {
    ServerMock server;
    VoidWardenCity plugin;
    World world;

    @BeforeEach void start() {
        server = MockBukkit.mock();
        plugin = MockBukkit.load(VoidWardenCity.class);
        world = server.addSimpleWorld("world");
    }
    @AfterEach void stop() { MockBukkit.unmock(); }

    Object call(String name, Class<?>[] types, Object... args) throws Exception {
        Method m = VoidWardenCity.class.getDeclaredMethod(name, types);
        m.setAccessible(true);
        return m.invoke(plugin, args);
    }

    @SuppressWarnings("unchecked")
    @Test void onlyMarkedSingleChestsCanRefillAndPlayerPlacementWins() throws Exception {
        Chest chest = mock(Chest.class);
        var blockData = mock(org.bukkit.block.data.type.Chest.class);
        var pdc = mock(PersistentDataContainer.class);
        Location loc = new Location(world, 0, -51, 0);
        when(chest.getLocation()).thenReturn(loc);
        when(chest.getBlockData()).thenReturn(blockData);
        when(blockData.getType()).thenReturn(Type.SINGLE);
        when(chest.getPersistentDataContainer()).thenReturn(pdc);
        NamespacedKey marker = new NamespacedKey(plugin, "event_chest_table");
        when(pdc.get(marker, PersistentDataType.STRING)).thenReturn(null);
        assertFalse((boolean) call("isManaged", new Class<?>[]{Chest.class}, chest));
        assertFalse((boolean) call("discoverNatural", new Class<?>[]{Chest.class}, chest));
        verify(chest, never()).update(anyBoolean(), anyBoolean());
        when(pdc.get(marker, PersistentDataType.STRING)).thenReturn("chests/ancient_city");
        assertTrue((boolean) call("isManaged", new Class<?>[]{Chest.class}, chest));
        when(blockData.getType()).thenReturn(Type.LEFT);
        assertFalse((boolean) call("isManaged", new Class<?>[]{Chest.class}, chest));
        when(blockData.getType()).thenReturn(Type.SINGLE);
        Field f = VoidWardenCity.class.getDeclaredField("playerChests"); f.setAccessible(true);
        ((Set<String>) f.get(plugin)).add("world;0;-51;0");
        assertFalse((boolean) call("isManaged", new Class<?>[]{Chest.class}, chest));
    }

    @SuppressWarnings("unchecked")
    @Test void openingUnmarkedOrPlacedChestDoesNotClearContents() throws Exception {
        var player = server.addPlayer();
        Chest chest = mock(Chest.class);
        var data = mock(org.bukkit.block.data.type.Chest.class);
        var pdc = mock(PersistentDataContainer.class);
        var inventory = mock(org.bukkit.inventory.Inventory.class);
        var event = mock(org.bukkit.event.inventory.InventoryOpenEvent.class);
        when(event.getPlayer()).thenReturn(player);
        when(event.getInventory()).thenReturn(inventory);
        when(inventory.getHolder()).thenReturn(chest);
        when(chest.getLocation()).thenReturn(new Location(world, 0, -51, 0));
        when(chest.getBlockData()).thenReturn(data);
        when(data.getType()).thenReturn(Type.SINGLE);
        when(chest.getPersistentDataContainer()).thenReturn(pdc);
        Field old = VoidWardenCity.class.getDeclaredField("chestRefills"); old.setAccessible(true);
        ((java.util.Map<String, Long>) old.get(plugin)).put("world;0;-51;0", 1L);
        plugin.onChestOpen(event);
        verify(inventory, never()).clear();
        NamespacedKey marker = new NamespacedKey(plugin, "event_chest_table");
        when(pdc.get(marker, PersistentDataType.STRING)).thenReturn("chests/ancient_city");
        Field placed = VoidWardenCity.class.getDeclaredField("playerChests"); placed.setAccessible(true);
        ((Set<String>) placed.get(plugin)).add("world;0;-51;0");
        plugin.onChestOpen(event);
        verify(inventory, never()).clear();
    }

    @Test void menuButtonsAdjustAndSaveVanillaChance() throws Exception {
        var player = server.addPlayer(); player.setOp(true);
        call("openChancesGui", new Class<?>[]{org.bukkit.entity.Player.class, int.class}, player, 0);
        var view = player.getOpenInventory();
        var click = new org.bukkit.event.inventory.InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                0, org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        plugin.onChancesClick(click);
        assertTrue(click.isCancelled());
        view = player.getOpenInventory();
        var minus = new org.bukkit.event.inventory.InventoryClickEvent(view, org.bukkit.event.inventory.InventoryType.SlotType.CONTAINER,
                12, org.bukkit.event.inventory.ClickType.LEFT, org.bukkit.event.inventory.InventoryAction.PICKUP_ALL);
        plugin.onChancesClick(minus);
        assertTrue(minus.isCancelled());
        call("loadChanceOverrides", new Class<?>[]{});
        assertEquals(99, call("currentEntryChance", new Class<?>[]{String.class}, "vanilla:ENCHANTED_GOLDEN_APPLE"));
    }

    @Test void vanillaChanceIsStoredAndReloadedWithoutChangingConfig() throws Exception {
        int entries = plugin.getConfig().getMapList("chests.loot").size();
        assertEquals(100, call("currentEntryChance", new Class<?>[]{String.class}, "vanilla:SCULK"));
        assertEquals(true, call("setChance", new Class<?>[]{String.class, boolean.class, int.class}, "vanilla:SCULK", false, 37));
        call("loadChanceOverrides", new Class<?>[]{});
        assertEquals(37, call("currentEntryChance", new Class<?>[]{String.class}, "vanilla:SCULK"));
        assertEquals(entries, plugin.getConfig().getMapList("chests.loot").size());
    }

    @SuppressWarnings("unchecked")
    @Test void customItemMetadataAndIndividualChanceSurviveReload() throws Exception {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.NETHERITE_INGOT, 3);
        org.bukkit.inventory.meta.ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "prize"), PersistentDataType.STRING, "kept");
        item.setItemMeta(meta);
        Field f = VoidWardenCity.class.getDeclaredField("customLoot"); f.setAccessible(true);
        ((java.util.List<org.bukkit.inventory.ItemStack>) f.get(plugin)).add(item);
        assertEquals(true, call("saveCustomLoot", new Class<?>[]{}));
        String sig = (String) call("itemSig", new Class<?>[]{org.bukkit.inventory.ItemStack.class}, item);
        assertEquals(true, call("setChance", new Class<?>[]{String.class, boolean.class, int.class}, sig, false, 42));
        call("loadCustomLoot", new Class<?>[]{});
        call("loadChanceOverrides", new Class<?>[]{});
        org.bukkit.inventory.ItemStack restored = ((java.util.List<org.bukkit.inventory.ItemStack>) f.get(plugin)).getFirst();
        assertEquals(3, restored.getAmount());
        assertEquals("kept", restored.getItemMeta().getPersistentDataContainer().get(new NamespacedKey(plugin, "prize"), PersistentDataType.STRING));
        assertEquals(sig, call("itemSig", new Class<?>[]{org.bukkit.inventory.ItemStack.class}, restored));
        assertEquals(42, call("effectiveItemChance", new Class<?>[]{org.bukkit.inventory.ItemStack.class}, restored));
    }

    @Test void failedSaveRollsBackChance() throws Exception {
        java.nio.file.Path path = plugin.getDataFolder().toPath().resolve("loot-chances.yml");
        java.nio.file.Files.createDirectory(path);
        assertEquals(false, call("setChance", new Class<?>[]{String.class, boolean.class, int.class}, "vanilla:SCULK", false, 37));
        assertEquals(100, call("currentEntryChance", new Class<?>[]{String.class}, "vanilla:SCULK"));
        assertTrue(java.nio.file.Files.isDirectory(path));
    }
}
