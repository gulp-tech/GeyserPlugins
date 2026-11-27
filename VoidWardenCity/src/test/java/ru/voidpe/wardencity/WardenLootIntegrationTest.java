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
