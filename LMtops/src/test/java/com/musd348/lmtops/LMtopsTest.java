package com.musd348.lmtops;

import be.seeseemelk.mockbukkit.MockBukkit;
import be.seeseemelk.mockbukkit.ServerMock;
import org.bukkit.Material;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.UUID;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class LMtopsTest {
    @TempDir Path dataFolder;
    @Test void newDataIsCreatedAndExistingValuesSurvive() throws Exception {
        LMtops plugin = mock(LMtops.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        when(plugin.getLogger()).thenReturn(Logger.getAnonymousLogger());
        UUID id = UUID.randomUUID();
        DataManager d = new DataManager(plugin);
        d.addStat(id, "kills", 7);
        d.addStat(id, "blocks", 12);
        d.saveAll();
        assertTrue(Files.exists(dataFolder.resolve("playerdata.yml")));
        DataManager loaded = new DataManager(plugin);
        assertEquals(7, loaded.getStat(id, "kills"));
        loaded.addStat(id, "deaths", 2);
        loaded.saveAll();
        DataManager again = new DataManager(plugin);
        assertEquals(7, again.getStat(id, "kills"));
        assertEquals(12, again.getStat(id, "blocks"));
        assertEquals(2, again.getStat(id, "deaths"));
    }
    @Test void malformedDataIsNotReplaced() throws Exception {
        LMtops plugin = mock(LMtops.class);
        when(plugin.getDataFolder()).thenReturn(dataFolder.toFile());
        Path file = dataFolder.resolve("playerdata.yml");
        Files.writeString(file, "broken: [unterminated");
        assertThrows(IllegalStateException.class, () -> new DataManager(plugin));
        assertEquals("broken: [unterminated", Files.readString(file));
    }
    @Test void menuItemsCannotBeTakenAndClickNavigates() {
        ServerMock server = MockBukkit.mock();
        try {
            MockBukkit.createMockPlugin("Vault");
            LMtops plugin = MockBukkit.load(LMtops.class);
            var player = server.addPlayer();
            plugin.getTopGUI().openMainMenu(player);
            var view = player.getOpenInventory();
            assertEquals(Material.DIAMOND_SWORD, view.getTopInventory().getItem(12).getType());
            var click = new InventoryClickEvent(view, InventoryType.SlotType.CONTAINER, 12, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            plugin.getTopGUI().onClick(click);
            assertTrue(click.isCancelled());
            server.getScheduler().performOneTick();
            assertEquals(54, player.getOpenInventory().getTopInventory().getSize());
            var top = player.getOpenInventory();
            var back = new InventoryClickEvent(top, InventoryType.SlotType.CONTAINER, 49, ClickType.LEFT, InventoryAction.PICKUP_ALL);
            plugin.getTopGUI().onClick(back);
            assertTrue(back.isCancelled());
            server.getScheduler().performOneTick();
            assertEquals(27, player.getOpenInventory().getTopInventory().getSize());
        } finally { MockBukkit.unmock(); }
    }
}
