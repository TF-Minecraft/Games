package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.ownership.CharacterAccess;
import net.tfminecraft.games.support.TestCharacters;
import net.tfminecraft.games.wager.*;
import net.tfminecraft.tlibs.character.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Item;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;

class OutgoingRefundTest extends TableManagerFixture {
    private CharacterSession ending;

    private Table held(int value) {
        Table table = place(false);
        ending = new CharacterSession(TestCharacters.ref(player), 1);
        table.wagerCharacters().put(player.getUniqueId(), ending.ref());
        Accounts.bucket(table, player.getUniqueId()).accept(List.of(new Stake(new ItemStack(Material.GOLD_NUGGET), "gold", 1, value, 1)));
        table.actives().add(player.getUniqueId());
        games.when(() -> net.tfminecraft.games.game.GamesRegistry.of(table.getGameId())).thenReturn(null);
        CharacterAccess.start(MockBukkit.createMockPlugin());
        player.openInventory(Bukkit.createInventory(null, 9));
        return table;
    }

    private void clearSession() {
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.empty());
        characters.when(() -> Characters.isCurrent(ending)).thenReturn(false);
    }

    private void end() {
        Bukkit.getPluginManager().callEvent(new CharacterSessionEndEvent(player, ending,
                CharacterSessionEndEvent.Reason.SWITCH));
    }

    private int coins() { return player.getInventory().all(Material.GOLD_NUGGET).values()
            .stream().mapToInt(ItemStack::getAmount).sum(); }

    private void noDrops() { assertTrue(world.getEntitiesByClass(Item.class).isEmpty()); }

    private void heldUnchanged(Table table, int felt, int pockets) throws Exception {
        assertEquals(felt, table.ledger().total());
        assertEquals(pockets, coins());
        assertTrue(table.moneyBlocked());
        var review = net.tfminecraft.games.ownership.RawJson.read(data.resolve("Data/wager-intents/" + table.getId() + ".json"));
        assertTrue(review.toString().contains(table.wagerCharacters().get(player.getUniqueId()).characterId().toString()));
        noDrops();
    }

    @Test void idlePrivateDealerFloatReturnsToOutgoingBody() {
        Table table = held(0);
        table.actives().clear();
        table.setDealerId(player.getUniqueId());
        table.setFloatOwner(player.getUniqueId());
        Accounts.tray(table).accept(List.of(new Stake(new ItemStack(Material.GOLD_NUGGET), "gold", 1, 10, 1)));
        clearSession();
        end();
        assertEquals(10, coins());
        assertEquals(0, table.ledger().total());
        assertNull(table.floatOwner());
        assertFalse(table.moneyBlocked());
        assertFalse(WagerJournal.blocked(table));
        noDrops();
    }

    @Test void differentCapturedCharacterCannotReceiveEndingCharacterRefund() throws Exception {
        Table table = held(7);
        table.wagerCharacters().put(player.getUniqueId(), new CharacterRef(player.getUniqueId(), UUID.randomUUID()));
        clearSession();
        end();
        heldUnchanged(table, 7, 0);
    }

    @Test void successorDuringEndGetsNoOldCoins() throws Exception {
        Table table = held(7);
        clearSession();
        characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(
                new CharacterSession(new CharacterRef(player.getUniqueId(), UUID.randomUUID()), 2)));
        end();
        heldUnchanged(table, 7, 0);
    }

    @Test void deadBodyCannotReceiveNewDeathDrops() throws Exception {
        Table table = held(7);
        clearSession();
        player.setHealth(0);
        assertTrue(player.isDead());
        player.openInventory(Bukkit.createInventory(null, 9));
        end();
        heldUnchanged(table, 7, 0);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void fullStorageHoldsUnlessMatchingPartialStacksCanFitEverything(boolean matching) throws Exception {
        Table table = held(7);
        ItemStack[] storage = player.getInventory().getStorageContents();
        java.util.Arrays.setAll(storage, slot -> new ItemStack(Material.DIAMOND, 64));
        if (matching) storage[0] = new ItemStack(Material.GOLD_NUGGET, 57);
        player.getInventory().setStorageContents(storage);
        clearSession();
        end();
        if (matching) {
            assertEquals(64, coins());
            assertEquals(0, table.ledger().total());
            assertFalse(table.moneyBlocked());
            assertFalse(WagerJournal.blocked(table));
            noDrops();
        } else heldUnchanged(table, 7, 0);
    }

    @Test void multipleRecipientInstancesShareOneCapacityReservation() throws Exception {
        Table table = held(5);
        Accounts.tray(table).accept(List.of(new Stake(new ItemStack(Material.GOLD_NUGGET), "gold", 1, 3, 1)));
        ItemStack[] storage = player.getInventory().getStorageContents();
        java.util.Arrays.setAll(storage, slot -> new ItemStack(Material.DIAMOND, 64));
        storage[0] = new ItemStack(Material.GOLD_NUGGET, 58);
        player.getInventory().setStorageContents(storage);
        clearSession();
        OutgoingRefund.during(player, ending, () -> {
            var result = WagerEngine.get().begin(table, "compound refund")
                    .moveAll(Accounts.bucket(table, player.getUniqueId()), Accounts.payee(table, player, player.getUniqueId()))
                    .moveAll(Accounts.tray(table), Accounts.payee(table, player, player.getUniqueId())).commit();
            assertFalse(result.ok());
        });
        heldUnchanged(table, 8, 58);
    }

    @Test void endingPocketsCannotFundAnyNewSourceLeg() {
        Table table = held(0);
        player.getInventory().setItem(0, new ItemStack(Material.GOLD_NUGGET, 7));
        clearSession();
        OutgoingRefund.during(player, ending, () -> {
            var result = WagerEngine.get().begin(table, "new stake")
                    .move(Accounts.coins(table, player), Accounts.bucket(table, player.getUniqueId()), 7).commit();
            assertFalse(result.ok());
            assertEquals(TxResult.Reason.PLAYER_SHORT, result.reason());
        });
        assertEquals(7, coins());
        assertEquals(0, table.ledger().total());
        noDrops();
    }

    @Test void outgoingScopeDoesNotAuthorizeAnotherAccount() {
        Table table = held(7);
        var other = opponent();
        table.wagerCharacters().put(other.getUniqueId(), TestCharacters.ref(other));
        clearSession();
        characters.when(() -> Characters.currentSession(other)).thenReturn(Optional.empty());
        OutgoingRefund.during(player, ending, () -> assertFalse(WagerEngine.get().begin(table, "other refund")
                .moveAll(Accounts.bucket(table, player.getUniqueId()), Accounts.payee(table, other, other.getUniqueId())).commit().ok()));
        assertEquals(7, table.ledger().total());
        assertEquals(0, coins());
        assertFalse(other.getInventory().contains(Material.GOLD_NUGGET));
        assertTrue(table.moneyBlocked());
        noDrops();
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void deferredPayoutHasNoEndingAuthorityAfterScopeCloses(boolean successor) {
        Table table = held(7);
        clearSession();
        AtomicReference<MoneyTx> deferred = new AtomicReference<>();
        assertThrows(IllegalStateException.class, () -> OutgoingRefund.during(player, ending, () -> {
            deferred.set(WagerEngine.get().begin(table, "deferred refund")
                    .moveAll(Accounts.bucket(table, player.getUniqueId()), Accounts.payee(table, player, player.getUniqueId())));
            throw new IllegalStateException("cleanup cut");
        }));
        if (successor) characters.when(() -> Characters.currentSession(player)).thenReturn(Optional.of(
                new CharacterSession(new CharacterRef(player.getUniqueId(), UUID.randomUUID()), 2)));
        assertFalse(deferred.get().commit().ok());
        assertEquals(7, table.ledger().total());
        assertEquals(0, coins());
        assertTrue(table.moneyBlocked());
        noDrops();
    }

    @Test void capturedTaxedPotPreservesFullJournalEnvelopeAndConservation() throws Exception {
        Table table = held(10);
        clearSession();
        AtomicReference<List<net.tfminecraft.denareconomy.api.Leg>> posting = new AtomicReference<>();
        denars.when(() -> net.tfminecraft.denareconomy.api.Denars.post(anyString(), anyString(), anyList())).thenAnswer(call -> {
            var intent = net.tfminecraft.games.ownership.RawJson.read(data.resolve("Data/wager-intents/" + table.getId() + ".json"));
            var ref = intent.getAsJsonObject("plan").getAsJsonObject("characters").getAsJsonObject(player.getUniqueId().toString());
            assertEquals(ending.ref().ownerId().toString(), ref.get("ownerId").getAsString());
            assertEquals(ending.ref().characterId().toString(), ref.get("characterId").getAsString());
            var tax = intent.getAsJsonObject("plan").getAsJsonObject("taxQuote");
            assertEquals("guildtax:captured", tax.get("account").getAsString());
            assertEquals(ref, tax.getAsJsonObject("earner"));
            posting.set(List.copyOf(call.getArgument(2)));
            return net.tfminecraft.denareconomy.api.Result.APPLIED;
        });
        try (var tax = mockStatic(CitizenTax.class)) {
            tax.when(() -> CitizenTax.quote(ending.ref(), 10)).thenReturn(new CitizenTax.Levy(2, 2.0, ending.ref(), "guildtax:captured"));
            OutgoingRefund.during(player, ending, () -> assertEquals(10, WagerEngine.get()
                    .payFromPot(table, player, player.getUniqueId(), 10, null, "outgoing win").moved()));
        }
        assertEquals(8, coins());
        assertEquals(0, table.ledger().total());
        assertEquals(0, posting.get().stream().mapToLong(net.tfminecraft.denareconomy.api.Leg::cents).sum());
        assertEquals(200, posting.get().stream().filter(leg -> leg.account().equals("guildtax:captured"))
                .mapToLong(net.tfminecraft.denareconomy.api.Leg::cents).sum());
        assertFalse(table.moneyBlocked());
        assertFalse(WagerJournal.blocked(table));
        noDrops();
    }

    @Test @SuppressWarnings("removal")
    void quitBeforeProviderEndUsesOrdinaryAuthorityAndEndDoesNotPayTwice() {
        Table table = held(7);
        manager.onQuit(new PlayerQuitEvent(player, "quit"));
        assertEquals(7, coins());
        assertEquals(0, table.ledger().total());
        clearSession();
        end();
        assertEquals(7, coins());
        assertEquals(0, table.ledger().total());
        assertFalse(table.moneyBlocked());
        assertFalse(WagerJournal.blocked(table));
        noDrops();
    }
}
