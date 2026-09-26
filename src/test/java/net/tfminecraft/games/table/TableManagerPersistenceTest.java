package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.CsvSource;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.layout.TableLayout.PileSlot;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.guild.income.Ledger;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Bank;
import net.tfminecraft.simplefactions.objects.Faction;
import org.mockito.MockedStatic;

@SuppressWarnings("deprecation")
class TableManagerPersistenceTest extends TableManagerFixture {
    private final Gson json = new Gson();

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void legacyHouseFieldsUseConfiguredDefaultsWithoutInventingAnOwner(boolean automatic) throws Exception {
        TableLayout previous = Cache.tableLayouts.put("freeplay", new TableLayout(
                Cache.pokerCardSet, "Cards", "icon", 6, Map.of(), null, null, null, 0,
                false, automatic, 5, 80, 10, TableLayout.VoiceLines.defaults(), null, 0, 10,
                null, 4, 2, 4, 4, false));
        try {
            UUID id = UUID.randomUUID();
            JsonObject saved = document(id);
            for (String field : List.of("autoDealer", "staffMint", "minBet", "maxBet", "maxBoxes",
                    "smallBlind", "bigBlind", "ownerPlayer")) saved.remove(field);
            write(id + ".json", saved);
            manager.loadAll();
            Table loaded = manager.table(id);
            assertNotNull(loaded);
            assertNull(loaded.ownerPlayer());
            assertEquals(automatic, loaded.autoDealer());
            assertEquals(automatic, loaded.staffMint());
            assertEquals(5, loaded.minBet());
            assertEquals(80, loaded.maxBet());
            assertEquals(4, loaded.maxBoxes());
            assertEquals(2, loaded.smallBlind());
            assertEquals(4, loaded.bigBlind());
            assertEquals(0, loaded.houseFloat());
            assertEquals(ShufflePolicy.SHOE, loaded.shufflePolicy());
            assertEquals(List.of("one", "two"), loaded.getDeck().remainingIds());
        } finally {
            if (previous == null) Cache.tableLayouts.remove("freeplay");
            else Cache.tableLayouts.put("freeplay", previous);
        }
    }

    @Test
    void malformedHouseOwnerDoesNotDiscardValidTableCardsOrRefundableStakes() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        saved.addProperty("ownerPlayer", "old-owner-is-not-a-uuid");
        saved.getAsJsonArray("ledger").add(stake(player.getUniqueId(), 3));
        write(id + ".json", saved);
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotNull(loaded);
        assertNull(loaded.ownerPlayer());
        assertFalse(loaded.autoDealer());
        assertFalse(loaded.staffMint());
        assertEquals(1, loaded.minBet());
        assertEquals(20, loaded.maxBet());
        assertEquals(2, loaded.getDeck().remaining() + loaded.getDeck().discarded());
        assertEquals(3, inventoryGold());
        assertEquals(0, droppedGold());
        assertTrue(loaded.ledger().isEmpty());
    }

    @Test
    void publicStakeAndHouseChangesPersistExactMoneyItemsAndOwnership() throws Exception {
        Table table = place(false);
        stakeCoin(player, table);
        TableHouse house = TableHouse.from(table);
        house.setOwnerPlayer(player.getUniqueId());
        house.setMinBet(5);
        house.setMaxBet(50);
        house.setMaxBoxes(4);
        house.setShufflePolicy(ShufflePolicy.ROUND);
        house.setSmallBlind(2);
        house.setBigBlind(4);
        manager.applyHouse(table, house);
        JsonObject saved = read(table.getId());
        assertEquals(table.getId().toString(), saved.get("id").getAsString());
        assertEquals(player.getUniqueId().toString(), saved.get("ownerPlayer").getAsString());
        assertEquals(world.getName(), saved.get("world").getAsString());
        assertEquals("ROUND", saved.get("shufflePolicy").getAsString());
        assertEquals(5, saved.get("minBet").getAsInt());
        assertEquals(50, saved.get("maxBet").getAsInt());
        assertEquals(4, saved.get("maxBoxes").getAsInt());
        assertEquals(2, saved.get("smallBlind").getAsInt());
        assertEquals(4, saved.get("bigBlind").getAsInt());
        JsonArray ledger = saved.getAsJsonArray("ledger");
        assertEquals(1, ledger.size());
        JsonObject stake = ledger.get(0).getAsJsonObject();
        assertEquals(player.getUniqueId().toString(), stake.get("owner").getAsString());
        assertEquals(1, stake.get("unit").getAsInt());
        assertEquals(1, stake.get("count").getAsInt());
        assertEquals(1, stake.get("streetId").getAsInt());
        assertEquals(0.75, stake.get("x").getAsDouble());
        assertEquals(0, stake.get("z").getAsDouble());
        assertEquals(new ItemStack(Material.GOLD_NUGGET), decode(stake.get("item").getAsString()));
        assertEquals(0, inventoryGold());
        assertEquals(1, table.ledger().total());
    }

    @Test
    void idleSavedTableRestoresDeckOrderHouseSettingsAndOwnership() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        saved.add("remaining", json.toJsonTree(List.of("two")));
        saved.add("discarded", json.toJsonTree(List.of("one")));
        saved.addProperty("ownerPlayer", player.getUniqueId().toString());
        saved.addProperty("ownerGuildId", "guild");
        saved.addProperty("autoDealer", true);
        saved.addProperty("staffMint", false);
        saved.addProperty("houseFloat", 17);
        saved.addProperty("minBet", 5);
        saved.addProperty("maxBet", 40);
        saved.addProperty("maxBoxes", 3);
        saved.addProperty("shufflePolicy", "ROUND");
        saved.addProperty("smallBlind", 2);
        saved.addProperty("bigBlind", 4);
        write(id + ".json", saved);
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotNull(loaded);
        assertEquals(List.of("two"), loaded.getDeck().remainingIds());
        assertEquals(List.of("one"), loaded.getDeck().discardedIds());
        assertEquals(player.getUniqueId(), loaded.ownerPlayer());
        assertEquals("guild", loaded.ownerGuildId());
        assertTrue(loaded.autoDealer());
        assertFalse(loaded.staffMint());
        assertEquals(17, loaded.houseFloat());
        assertEquals(5, loaded.minBet());
        assertEquals(40, loaded.maxBet());
        assertEquals(3, loaded.maxBoxes());
        assertEquals(ShufflePolicy.ROUND, loaded.shufflePolicy());
        assertEquals(2, loaded.smallBlind());
        assertEquals(4, loaded.bigBlind());
        assertFalse(loaded.live());
        verify(game).onTableReady(loaded);
    }

    @Test
    void savedActiveBetsAreRefundedOnStartupAndRewrittenAsIdleWithoutLosingCards() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        saved.addProperty("street", 2);
        saved.add("actives", json.toJsonTree(List.of(player.getUniqueId().toString())));
        saved.add("remaining", json.toJsonTree(List.of("two")));
        saved.add("discarded", json.toJsonTree(List.of("one")));
        JsonObject stake = stake(player.getUniqueId(), 3);
        stake.addProperty("streetId", 2);
        stake.addProperty("anchorX", 1.0);
        stake.addProperty("anchorZ", 2.0);
        stake.addProperty("x", 1.25);
        stake.addProperty("z", 2.25);
        saved.getAsJsonArray("ledger").add(stake);
        write(id + ".json", saved);
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotNull(loaded);
        assertEquals(3, inventoryGold());
        assertEquals(0, droppedGold());
        assertTrue(loaded.ledger().isEmpty());
        assertTrue(loaded.actives().isEmpty());
        assertFalse(loaded.live());
        assertEquals(1, loaded.street());
        assertEquals(List.of("one", "two"), loaded.getDeck().remainingIds().stream().sorted().toList());
        assertEquals(0, loaded.getDeck().discarded());
        JsonObject rewritten = read(id);
        assertTrue(rewritten.getAsJsonArray("ledger").isEmpty());
        assertTrue(rewritten.getAsJsonArray("actives").isEmpty());
        verify(game).onTableRemoved(loaded);
        manager.loadAll();
        assertEquals(3, inventoryGold(), "loading the rewritten idle file must not refund twice");
    }

    @Test
    void legacyPilesRefundPlayersAndDropUnownedOrTrayChipsWithoutLosingValue() throws Exception {
        TableLayout previous = Cache.tableLayouts.put("freeplay", new TableLayout(
                Cache.pokerCardSet, "Free play", "icon", 6,
                Map.of("tray", new PileSlot(0, 2)), null, null, null, 0.5));
        try {
            UUID id = UUID.randomUUID();
            JsonObject saved = document(id);
            JsonArray piles = new JsonArray();
            piles.add(legacyPile(player.getUniqueId(), 2, 1, 1));
            piles.add(legacyPile(null, 3, 4, 4));
            piles.add(legacyPile(player.getUniqueId(), 1, 2, 0));
            saved.add("piles", piles);
            write(id + ".json", saved);
            manager.loadAll();
            Table loaded = manager.table(id);
            assertNotNull(loaded);
            assertEquals(2, inventoryGold());
            assertEquals(4, droppedGold(), "unowned chips and the dealer tray must not go to a bettor");
            assertEquals(6, inventoryGold() + droppedGold());
            assertTrue(loaded.ledger().isEmpty());
            assertTrue(read(id).getAsJsonArray("piles").isEmpty());
            assertTrue(read(id).getAsJsonArray("ledger").isEmpty());
        } finally {
            if (previous == null) Cache.tableLayouts.remove("freeplay");
            else Cache.tableLayouts.put("freeplay", previous);
        }
    }

    @Test
    void modernLedgerTakesPrecedenceOverRetainedLegacyPiles() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        saved.getAsJsonArray("ledger").add(stake(player.getUniqueId(), 3));
        JsonArray oldPiles = new JsonArray();
        oldPiles.add(legacyPile(player.getUniqueId(), 3, 1, 1));
        saved.add("piles", oldPiles);
        write(id + ".json", saved);
        manager.loadAll();
        assertEquals(3, inventoryGold(), "a migrated file containing both formats must not duplicate chips");
        assertEquals(0, droppedGold());
        assertTrue(manager.table(id).ledger().isEmpty());
    }

    @Test
    void unavailableWorldOrRemovedCardSetDoesNotPreventOtherTablesLoading() throws Exception {
        UUID missingWorld = UUID.randomUUID();
        JsonObject unavailable = document(missingWorld);
        unavailable.addProperty("world", "unloaded-world");
        write(missingWorld + ".json", unavailable);
        UUID missingSet = UUID.randomUUID();
        JsonObject unknownCards = document(missingSet);
        unknownCards.addProperty("setName", "deleted-card-set");
        write(missingSet + ".json", unknownCards);
        UUID valid = UUID.randomUUID();
        JsonObject fallbackSet = document(valid);
        fallbackSet.remove("setName");
        write(valid + ".json", fallbackSet);
        manager.loadAll();
        assertNull(manager.table(missingWorld));
        assertNull(manager.table(missingSet));
        assertNotNull(manager.table(valid));
        assertEquals(1, manager.tables().size());
        verify(Games.plugin.getLogger()).warning(contains("Table world missing: unloaded-world"));
        assertTrue(Files.exists(folder().resolve(missingWorld + ".json")), "temporarily unavailable tables remain on disk");
    }

    @Test
    void malformedJsonIsIsolatedAndRetainedWhileValidFilesStillLoad() throws Exception {
        Files.createDirectories(folder());
        Path broken = folder().resolve("broken.json");
        Files.writeString(broken, "{\"id\":");
        UUID valid = UUID.randomUUID();
        write(valid + ".json", document(valid));
        assertDoesNotThrow(manager::loadAll);
        assertNotNull(manager.table(valid));
        assertEquals(1, manager.tables().size());
        assertEquals("{\"id\":", Files.readString(broken));
        verify(Games.plugin.getLogger()).warning(contains("Failed to load table broken.json"));
    }

    @Test
    void invalidTableIdentifierIsIsolatedWithoutPreventingOtherTablesLoading() throws Exception {
        JsonObject invalid = document(UUID.randomUUID());
        invalid.addProperty("id", "not-a-uuid");
        write("invalid-id.json", invalid);
        UUID valid = UUID.randomUUID();
        write(valid + ".json", document(valid));
        assertDoesNotThrow(manager::loadAll);
        assertNotNull(manager.table(valid));
        assertEquals(1, manager.tables().size());
        assertEquals("not-a-uuid", JsonParser.parseString(Files.readString(folder().resolve("invalid-id.json")))
                .getAsJsonObject().get("id").getAsString());
        verify(Games.plugin.getLogger()).warning(contains("invalid-id.json"));
    }

    @Test
    void staleSeatListCanContainMissingOrInvalidIdentitiesWithoutLosingValidTable() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        JsonArray seats = new JsonArray();
        seats.add((String) null);
        seats.add("not-a-player-uuid");
        seats.add(player.getUniqueId().toString());
        saved.add("actives", seats);
        write(id + ".json", saved);
        assertDoesNotThrow(manager::loadAll);
        assertNotNull(manager.table(id));
        assertTrue(manager.table(id).actives().isEmpty());
        assertEquals(2, manager.table(id).getDeck().remaining());
        assertTrue(read(id).getAsJsonArray("actives").isEmpty());
    }

    @Test
    void unreadableStakeItemsAreReportedWhileRecoverableStakesAreRefunded() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        JsonObject valid = stake(player.getUniqueId(), 2);
        JsonObject damaged = stake(player.getUniqueId(), 4);
        damaged.addProperty("item", "not valid base64!");
        saved.getAsJsonArray("ledger").add(valid);
        saved.getAsJsonArray("ledger").add(damaged);
        write(id + ".json", saved);
        assertDoesNotThrow(manager::loadAll);
        assertNotNull(manager.table(id));
        assertEquals(2, inventoryGold());
        verify(Games.plugin.getLogger()).warning(contains("Failed to decode pot item"));
        verify(Games.plugin.getLogger()).warning(contains("loaded holding 2 but the file says 6"));
    }

    @Test
    void failedLoadedDisplayKeepsSavedTableForRetryAndDoesNotBlockOtherTables() throws Exception {
        UUID failed = UUID.randomUUID();
        JsonObject blocked = document(failed);
        blocked.addProperty("x", 32);
        write(failed + ".json", blocked);
        String originalFile = Files.readString(folder().resolve(failed + ".json"));
        UUID healthy = UUID.randomUUID();
        write(healthy + ".json", document(healthy));
        when(display.spawn(any(), argThat(location -> location != null && location.getX() == 32), any(), any()))
                .thenReturn(false);
        assertDoesNotThrow(manager::loadAll);
        assertNull(manager.table(failed));
        assertNotNull(manager.table(healthy));
        assertEquals(1, manager.tables().size());
        assertEquals(originalFile, Files.readString(folder().resolve(failed + ".json")));
        verify(Games.plugin.getLogger()).warning(contains("Failed to spawn loaded table " + failed + ".json"));
        when(display.spawn(any(), argThat(location -> location != null && location.getX() == 32), any(), any()))
                .thenReturn(true);
        manager.loadAll();
        assertNotNull(manager.table(failed));
        assertEquals(List.of("one", "two"), manager.table(failed).getDeck().remainingIds());
        assertEquals(2, manager.tables().size());
    }

    @Test
    void legacyPileWithUnknownOwnerDropsRecoverableCoinsAndReportsUnreadableItems() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        JsonObject unknownOwner = legacyPile(player.getUniqueId(), 5, 1, 1);
        unknownOwner.addProperty("owner", "damaged-owner-id");
        JsonObject damagedItem = legacyPile(player.getUniqueId(), 2, 1, 1);
        damagedItem.addProperty("item", "not valid base64!");
        JsonArray piles = new JsonArray();
        piles.add(unknownOwner);
        piles.add(damagedItem);
        saved.add("piles", piles);
        write(id + ".json", saved);
        assertDoesNotThrow(manager::loadAll);
        assertNotNull(manager.table(id));
        assertEquals(5, droppedGold(), "coins with lost ownership must remain recoverable in the world");
        assertEquals(0, inventoryGold());
        assertTrue(manager.table(id).ledger().isEmpty());
        verify(Games.plugin.getLogger()).warning(contains("Failed to decode pot item"));
        verify(Games.plugin.getLogger()).warning(contains("loaded holding 5 but the file says 7"));
    }

    @Test
    void failedSaveKeepsCommittedStakeAndCanPersistItAfterFilesystemRecovery() throws Exception {
        Table table = place(false);
        Path path = folder().resolve(table.getId() + ".json");
        Files.delete(path);
        Files.createDirectory(path);
        Path obstruction = path.resolve("occupied");
        Files.writeString(obstruction, "unrelated contents");
        stakeCoin(player, table);
        assertEquals(1, table.ledger().total());
        assertEquals(0, inventoryGold());
        assertEquals("unrelated contents", Files.readString(obstruction));
        verify(Games.plugin.getLogger(), atLeastOnce()).warning(contains("Failed to save table " + table.getId()));
        Files.delete(obstruction);
        Files.delete(path);
        manager.applyHouse(table, TableHouse.from(table));
        JsonArray saved = read(table.getId()).getAsJsonArray("ledger");
        assertEquals(1, saved.size());
        assertEquals(1, saved.get(0).getAsJsonObject().get("count").getAsInt());
        assertEquals(player.getUniqueId().toString(), saved.get(0).getAsJsonObject().get("owner").getAsString());
        assertEquals(1, table.ledger().total() + inventoryGold());
    }

    @Test
    void failedFileDeletionReportsObstructionWhilePickupReturnsPlayersMoney() throws Exception {
        Table table = place(false);
        stakeCoin(player, table);
        Path path = folder().resolve(table.getId() + ".json");
        Files.delete(path);
        Files.createDirectory(path);
        Path obstruction = path.resolve("occupied");
        Files.writeString(obstruction, "unrelated contents");
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent pickup = mock(EntityDamageByEntityEvent.class);
        when(pickup.getEntity()).thenReturn(anchor);
        when(pickup.getDamager()).thenReturn(player);
        manager.onHitEntity(pickup);
        verify(pickup).setCancelled(true);
        assertNull(manager.table(table.getId()));
        assertTrue(table.ledger().isEmpty());
        assertEquals(1, inventoryGold());
        assertEquals(0, droppedGold());
        assertEquals("unrelated contents", Files.readString(obstruction));
        verify(Games.plugin.getLogger()).warning("[Games] Could not delete table file " + table.getId() + ".json");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "damaged-owner-id")
    void modernStakeWithLostOwnershipDropsRecoverableItemsInsteadOfDiscardingThem(String owner) throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        saved.getAsJsonArray("ledger").add(stake(player.getUniqueId(), 2));
        JsonObject orphan = stake(player.getUniqueId(), 3);
        if (owner == null) orphan.remove("owner");
        else orphan.addProperty("owner", owner);
        saved.getAsJsonArray("ledger").add(orphan);
        write(id + ".json", saved);
        manager.loadAll();
        assertNotNull(manager.table(id));
        assertEquals(2, inventoryGold());
        assertEquals(3, droppedGold(), "Decoded items with unknown ownership must remain recoverable");
        assertTrue(manager.table(id).ledger().isEmpty());
        assertTrue(read(id).getAsJsonArray("ledger").isEmpty());
        manager.loadAll();
        assertEquals(2, inventoryGold());
        assertEquals(3, droppedGold(), "The rewritten table must not recover the same items twice");
    }

    @ParameterizedTest
    @CsvSource({"false, false", "true, false", "false, true", "true, true"})
    void orphanedPlayerItemsNeverBecomeGuildIncomeOrStaffMintMoney(boolean staffMint, boolean legacy) throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        saved.addProperty("autoDealer", true);
        saved.addProperty("staffMint", staffMint);
        if (!staffMint) saved.addProperty("ownerGuildId", "recovery-guild");
        saved.addProperty("houseFloat", 5);
        JsonArray stakes = legacy ? new JsonArray() : saved.getAsJsonArray("ledger");
        stakes.add(legacy ? legacyPile(player.getUniqueId(), 2, 1, 1) : stake(player.getUniqueId(), 2));
        stakes.add(legacy ? legacyPile(id, 5, 2, 2) : stake(id, 5));
        JsonObject orphan = legacy ? legacyPile(player.getUniqueId(), 3, 3, 3) : stake(player.getUniqueId(), 3);
        orphan.addProperty("owner", "damaged-player-owner");
        stakes.add(orphan);
        if (legacy) saved.add("piles", stakes);
        write(id + ".json", saved);

        PluginManager plugins = mock(PluginManager.class);
        Plugin plugin = mock(Plugin.class);
        when(plugin.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(plugin);
        Guild guild = mock(Guild.class);
        Bank bank = mock(Bank.class);
        Ledger ledger = mock(Ledger.class);
        when(guild.getId()).thenReturn("recovery-guild");
        when(guild.getBank()).thenReturn(bank);
        when(guild.getLedger()).thenReturn(ledger);
        Faction faction = mock(Faction.class, RETURNS_DEEP_STUBS);
        when(faction.getGuildHandler().getGuilds()).thenReturn(List.of(guild));
        List<Faction> previousFactions = FactionManager.factions;
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            FactionManager.factions = List.of(faction);
            manager.loadAll();
            assertNotNull(manager.table(id));
            assertEquals(2, inventoryGold());
            assertEquals(3, droppedGold(), "Unknown player items must survive either house settlement policy");
            assertTrue(manager.table(id).ledger().isEmpty());
            assertTrue(read(id).getAsJsonArray("ledger").isEmpty());
            manager.loadAll();
            assertEquals(2, inventoryGold());
            assertEquals(3, droppedGold(), "Recovery must not repeat after the idle file is saved");
            if (staffMint) {
                verifyNoInteractions(bank, ledger);
            } else {
                verify(bank).deposit(5.0);
                verifyNoMoreInteractions(bank);
                verifyNoInteractions(ledger);
                assertEquals(0, manager.table(id).houseFloat());
            }
        } finally {
            FactionManager.factions = previousFactions;
        }
    }

    @Test
    void modernOrphanRecoveryDoesNotReviveRetainedLegacyMoney() throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = document(id);
        JsonObject orphan = stake(player.getUniqueId(), 3);
        orphan.remove("owner");
        saved.getAsJsonArray("ledger").add(orphan);
        JsonArray stalePiles = new JsonArray();
        stalePiles.add(legacyPile(player.getUniqueId(), 7, 1, 1));
        saved.add("piles", stalePiles);
        write(id + ".json", saved);
        manager.loadAll();
        assertNotNull(manager.table(id));
        assertEquals(0, inventoryGold(), "A nonempty modern ledger supersedes stale legacy piles");
        assertEquals(3, droppedGold());
        assertTrue(manager.table(id).ledger().isEmpty());
        assertTrue(read(id).getAsJsonArray("ledger").isEmpty());
        assertTrue(read(id).getAsJsonArray("piles").isEmpty());
        manager.loadAll();
        assertEquals(0, inventoryGold());
        assertEquals(3, droppedGold());
    }

    private JsonObject document(UUID id) {
        JsonObject document = new JsonObject();
        document.addProperty("id", id.toString());
        document.addProperty("gameId", "freeplay");
        document.addProperty("world", world.getName());
        document.addProperty("x", 0);
        document.addProperty("y", 65);
        document.addProperty("z", 0);
        document.addProperty("yaw", 0);
        document.addProperty("setName", Cache.pokerCardSet);
        document.add("remaining", json.toJsonTree(List.of("one", "two")));
        document.add("discarded", new JsonArray());
        document.add("actives", new JsonArray());
        document.add("ledger", new JsonArray());
        document.addProperty("autoDealer", false);
        document.addProperty("staffMint", false);
        document.addProperty("street", 1);
        document.addProperty("minBet", 1);
        document.addProperty("maxBet", 20);
        return document;
    }

    private JsonObject stake(UUID owner, int count) throws Exception {
        JsonObject stake = new JsonObject();
        stake.addProperty("owner", owner.toString());
        stake.addProperty("item", encode(new ItemStack(Material.GOLD_NUGGET)));
        stake.addProperty("typeKey", "coin:gold");
        stake.addProperty("unit", 1);
        stake.addProperty("count", count);
        stake.addProperty("streetId", 1);
        return stake;
    }

    private JsonObject legacyPile(UUID owner, int count, double x, double z) throws Exception {
        JsonObject pile = new JsonObject();
        if (owner != null) pile.addProperty("owner", owner.toString());
        pile.addProperty("item", encode(new ItemStack(Material.GOLD_NUGGET)));
        pile.addProperty("typeKey", "coin:gold");
        pile.addProperty("denars", 1);
        pile.addProperty("count", count);
        pile.addProperty("streetId", 1);
        pile.addProperty("x", x);
        pile.addProperty("z", z);
        return pile;
    }

    private void write(String filename, JsonObject document) throws Exception {
        Files.createDirectories(folder());
        Files.writeString(folder().resolve(filename), json.toJson(document));
    }

    private JsonObject read(UUID id) throws Exception {
        return JsonParser.parseString(Files.readString(folder().resolve(id + ".json"))).getAsJsonObject();
    }

    private Path folder() { return data.resolve("Data/tables"); }

    private int inventoryGold() {
        return player.getInventory().all(Material.GOLD_NUGGET).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    private int droppedGold() {
        return world.getEntitiesByClass(Item.class).stream().map(Item::getItemStack)
                .filter(item -> item.getType() == Material.GOLD_NUGGET).mapToInt(ItemStack::getAmount).sum();
    }

    private static String encode(ItemStack item) throws Exception {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream output = new BukkitObjectOutputStream(bytes)) {
            output.writeObject(item);
            output.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        }
    }

    private static ItemStack decode(String encoded) throws Exception {
        try (BukkitObjectInputStream input = new BukkitObjectInputStream(
                new ByteArrayInputStream(Base64.getDecoder().decode(encoded)))) {
            return (ItemStack) input.readObject();
        }
    }
}
