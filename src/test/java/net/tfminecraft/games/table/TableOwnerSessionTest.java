package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.ownership.*;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

class TableOwnerSessionTest extends TableManagerFixture {
    @Test
    void duplicateRawIdsAreHeldBeforeAnyOwnerOrGuildMigrationPublishes() throws Exception {
        UUID id = UUID.randomUUID();
        java.nio.file.Path folder = data.resolve("Data/tables");
        java.nio.file.Files.createDirectories(folder);
        String one =
                "{\"id\":\""
                        + id
                        + "\",\"ownerPlayer\":\""
                        + player.getUniqueId()
                        + "\",\"ownerGuildId\":\"guild\",\"unknown\":null}";
        String two =
                "{\"id\":\""
                        + id
                        + "\",\"ownerPlayer\":\""
                        + UUID.randomUUID()
                        + "\",\"unknown\":7}";
        java.nio.file.Files.writeString(folder.resolve("one.json"), one);
        java.nio.file.Files.writeString(folder.resolve("two.json"), two);
        manager.loadAll();
        assertEquals(one, java.nio.file.Files.readString(folder.resolve("one.json")));
        assertEquals(two, java.nio.file.Files.readString(folder.resolve("two.json")));
        assertFalse(java.nio.file.Files.exists(data.resolve("migrations")));
        assertTrue(manager.tables().isEmpty());
        assertTrue(manager.ownershipStore().held(id));
    }

    @Test
    void missedEndDoesNotLetSameAccountBUseAsPlacementOrPrivateOwner() {
        var a = new OwnerSession(player.getUniqueId(), UUID.randomUUID(), 1);
        var b = new OwnerSession(player.getUniqueId(), UUID.randomUUID(), 2);
        var active = new AtomicReference<>(a);
        try (var access = mockStatic(CharacterAccess.class, CALLS_REAL_METHODS)) {
            access.when(() -> CharacterAccess.capture(player)).thenAnswer(call -> active.get());
            player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 3));
            manager.armPlace(player, "freeplay", true);
            active.set(b);
            assertTrue(manager.tryPlace(player, player.getLocation()));
            assertTrue(manager.tables().isEmpty());
            assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
            active.set(a);
            Table table = place(false);
            assertTrue(manager.canEditHouse(player, table));
            assertTrue(GuildTables.mayDeal(table, player));
            assertTrue(table.hostedBy(player.getUniqueId()));
            active.set(b);
            assertFalse(manager.canEditHouse(player, table));
            assertFalse(GuildTables.mayDeal(table, player));
            assertFalse(table.hostedBy(player.getUniqueId()));
        }
    }

    @Test
    void staffSnapshotCannotRestoreOwnerAcrossTransferOrSameRefCycle() {
        Table table = place(false);
        TableHouse stale = TableHouse.from(table);
        stale.setAutoDealer(true);
        long original = table.ownerGeneration();
        UUID account = table.ownerPlayer(), character = table.ownerCharacterId();
        manager.estateOwner(table.getId(), UUID.randomUUID(), UUID.randomUUID());
        manager.estateOwner(table.getId(), account, character);
        assertTrue(table.ownerGeneration() > original);
        assertFalse(stale.matchesOwner(table));
        manager.applyHouse(table, stale);
        assertFalse(table.autoDealer());
        var staff = opponent();
        staff.addAttachment(Games.plugin, "games.admin", true);
        assertTrue(manager.canEditHouse(staff, table));
        TableHouse fresh = TableHouse.from(table);
        fresh.setAutoDealer(true);
        manager.applyHouse(table, fresh);
        assertTrue(table.autoDealer());
    }

    @Test
    void placementTemplateAlwaysBelongsToItsNewCapturedPlacer() {
        Table old = place(false);
        TableHouse template = TableHouse.from(old);
        var placer = opponent();
        manager.armPlace(placer, "freeplay", false, template);
        assertTrue(manager.tryPlace(placer, old.getOrigin().clone().add(10, 0, 0)));
        Table created =
                manager.tables().stream().filter(table -> table != old).findFirst().orElseThrow();
        assertEquals(placer.getUniqueId(), created.ownerPlayer());
        assertEquals(
                net.tfminecraft.games.support.TestCharacters.ref(placer).characterId(),
                created.ownerCharacterId());
    }

    @Test
    void pendingTableRejectsNewRoundAndDealingWithoutBlockingExistingSessionEnd() {
        Table table = place(false);
        manager.estatePending(List.of(table.getId()), true);
        manager.beginSession(table);
        assertFalse(table.live());
        assertFalse(GuildTables.mayDeal(table, player));
        assertFalse(manager.canEditHouse(player, table));
        table.startSession();
        manager.endSession(table);
        assertFalse(table.live());
        assertTrue(table.estatePending());
    }

    @Test
    void failedDurableSessionCheckpointCannotStartGameEffects() throws Exception {
        Table table = place(false);
        var file = data.resolve("Data/tables/" + table.getId() + ".json");
        String source = java.nio.file.Files.readString(file);
        try (var writer =
                mockStatic(
                        net.tfminecraft.games.persistence.DurableFiles.class, CALLS_REAL_METHODS)) {
            writer.when(
                            () ->
                                    net.tfminecraft.games.persistence.DurableFiles.write(
                                            eq(file), anyString()))
                    .thenThrow(new java.io.IOException("session checkpoint cut"));
            manager.beginSession(table);
            assertTrue(table.ownershipHeld());
            assertTrue(table.moneyBlocked());
            verify(game, never()).onSessionStart(table);
            assertEquals(source, java.nio.file.Files.readString(file));
        }
    }
}
