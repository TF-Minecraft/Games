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
    void realEndEventAfterSessionClearsRefundsOnlyCapturedOutgoingInventory() {
        Table table = place(false);
        var ended = new net.tfminecraft.tlibs.character.CharacterSession(
                net.tfminecraft.games.support.TestCharacters.ref(player), 1);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 7));
        assertEquals(7, net.tfminecraft.games.wager.WagerEngine.get().begin(table, "stake")
                .move(net.tfminecraft.games.wager.Accounts.coins(table, player),
                        net.tfminecraft.games.wager.Accounts.bucket(table, player.getUniqueId()), 7).commit().moved());
        table.actives().add(player.getUniqueId());
        games.when(() -> net.tfminecraft.games.game.GamesRegistry.of(table.getGameId())).thenReturn(null);
        CharacterAccess.start(org.mockbukkit.mockbukkit.MockBukkit.createMockPlugin());
        player.openInventory(org.bukkit.Bukkit.createInventory(null, 9));
        characters.when(() -> net.tfminecraft.tlibs.character.Characters.currentSession(player))
                .thenReturn(Optional.empty());
        characters.when(() -> net.tfminecraft.tlibs.character.Characters.isCurrent(ended)).thenReturn(false);
        org.bukkit.Bukkit.getPluginManager().callEvent(new net.tfminecraft.tlibs.character.CharacterSessionEndEvent(
                player, ended, net.tfminecraft.tlibs.character.CharacterSessionEndEvent.Reason.SWITCH));
        assertEquals(7, net.tfminecraft.games.wager.Accounts.coins(table, player).available());
        assertEquals(0, table.ledger().total());
        assertFalse(table.moneyBlocked());
        assertFalse(net.tfminecraft.games.wager.WagerJournal.blocked(table));
        assertFalse(table.actives().contains(player.getUniqueId()));
        assertNull(CharacterAccess.published(player.getUniqueId()));
        var next = new net.tfminecraft.tlibs.character.CharacterSession(
                new net.tfminecraft.tlibs.character.CharacterRef(player.getUniqueId(), UUID.randomUUID()), 2);
        // Model the provider saving A's seven refunded coins before loading B's empty inventory.
        player.getInventory().clear();
        characters.when(() -> net.tfminecraft.tlibs.character.Characters.currentSession(player))
                .thenReturn(Optional.of(next));
        assertEquals(0, net.tfminecraft.games.wager.Accounts.coins(table, player).available());
        assertEquals(0, table.ledger().total());
    }
    @Test
    void hotOwnershipChecksDoNotReadSourcesButDeferredPassDetectsSameSizeSameTimeEdits() throws Exception {
        Table table = place(false);
        var file = data.resolve("Data/tables/" + table.getId() + ".json");
        var store = manager.ownershipStore();
        try (var raw = mockStatic(java.nio.file.Files.class, CALLS_REAL_METHODS)) {
            for (int i = 0; i < 100; i++) assertFalse(table.ownershipHeld());
            raw.verify(() -> java.nio.file.Files.readAllBytes(file), never());
        }
        String original = java.nio.file.Files.readString(file);
        var time = java.nio.file.Files.getLastModifiedTime(file);
        String changed = original.replace(table.ownerCharacterId().toString(), UUID.randomUUID().toString());
        assertNotEquals(original, changed);
        java.nio.file.Files.writeString(file, changed);
        java.nio.file.Files.setLastModifiedTime(file, time);
        manager.loadDeferred();
        assertTrue(table.ownershipHeld());
        assertTrue(store.held(table.getId()));
        assertEquals(changed, java.nio.file.Files.readString(file));
    }

    @Test
    void deferredPassHoldsDeletedSourcesEvenWhenFolderInventoryIsEmpty() throws Exception {
        Table table = place(false);
        var file = data.resolve("Data/tables/" + table.getId() + ".json");
        java.nio.file.Files.delete(file);
        manager.loadDeferred();
        assertTrue(table.ownershipHeld());
        assertFalse(java.nio.file.Files.exists(file));
        assertThrows(java.io.IOException.class, () -> manager.checkpoint(table));
        assertFalse(java.nio.file.Files.exists(file));
    }
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
