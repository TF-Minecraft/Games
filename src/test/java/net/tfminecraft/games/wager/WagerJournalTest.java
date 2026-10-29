package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import com.google.gson.JsonParser;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.denareconomy.api.Leg;
import net.tfminecraft.denareconomy.api.Result;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.support.TestCharacters;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.tlibs.character.CharacterRef;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class WagerJournalTest {
    @TempDir Path data;
    private final List<AutoCloseable> scopes = new ArrayList<>();
    private Games previousPlugin;
    private WagerItemOverride oldGold, oldSilver;
    private List<WagerItemOverride> oldItems;
    private Table table;
    private TableManager manager;
    private PlayerMock winner;
    private MockedStatic<Denars> denars;
    private MockedStatic<GuildBank> bank;
    private final List<String> operations = new ArrayList<>();
    private final List<List<Leg>> postings = new ArrayList<>();
    private final AtomicReference<Result> outcome = new AtomicReference<>(Result.APPLIED);
    private final WagerHost host = mock(WagerHost.class);

    private <T extends AutoCloseable> T scope(T item) { scopes.addFirst(item); return item; }

    @BeforeEach void setup() {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        when(Games.plugin.getDataFolder()).thenReturn(data.toFile());
        when(Games.plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        WagerJournal.initialize();
        oldGold = Cache.wagerGold; oldSilver = Cache.wagerSilver;
        oldItems = new ArrayList<>(Cache.wagerItems);
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, null, null, null, null, null, null, false, null);
        Cache.wagerSilver = null; Cache.wagerItems.clear();
        winner = MockBukkit.getMock().addPlayer();
        table = new Table(UUID.randomUUID(), "blackjack", winner.getLocation(), 0, null);
        table.setOwnerGuildId("guild");
        table.setOwnerGuildIncarnation(UUID.randomUUID());
        table.wagerCharacters().put(winner.getUniqueId(), TestCharacters.ref(winner));
        scope(TestCharacters.mockSessions());
        manager = mock(TableManager.class);
        var managers = scope(mockStatic(TableManager.class));
        managers.when(TableManager::get).thenReturn(manager);
        bank = scope(mockStatic(GuildBank.class));
        bank.when(() -> GuildBank.account(table)).thenReturn("guild:" + table.ownerGuildIncarnation());
        bank.when(() -> GuildBank.balance(table)).thenReturn(100);
        bank.when(() -> GuildBank.canHold(table)).thenReturn(true);
        bank.when(() -> GuildBank.declareProfit(eq(table), anyString(), anyInt())).thenReturn(true);
        denars = scope(mockStatic(Denars.class));
        denars.when(Denars::taxReady).thenReturn(true);
        denars.when(() -> Denars.post(anyString(), anyString(), anyList())).thenAnswer(call -> {
            assertTrue(Files.exists(journal()), "Intent must exist before posting");
            operations.add(call.getArgument(0)); postings.add(List.copyOf(call.getArgument(2)));
            return outcome.get();
        });
    }

    @AfterEach void cleanup() throws Exception {
        for (var item : scopes) item.close();
        Games.plugin = previousPlugin;
        Cache.wagerGold = oldGold; Cache.wagerSilver = oldSilver;
        Cache.wagerItems.clear(); Cache.wagerItems.addAll(oldItems);
        WagerJournal.initialize();
    }

    private Path journal() { return data.resolve("Data/wager-intents/" + table.getId() + ".json"); }
    private ItemStack coin() { return new ItemStack(Material.GOLD_NUGGET); }
    private MoneyTx funding(int amount) {
        return new MoneyTx(host, table, "fund").move(Accounts.bank(table), Accounts.tray(table), amount, coin());
    }
    private long credit(String account) {
        return postings.getLast().stream().filter(leg -> leg.account().equals(account)).mapToLong(Leg::cents).sum();
    }

    @Test void bankWinAndActualWithheldTaxAreOneBalancedPosting() throws Exception {
        CharacterRef earner = TestCharacters.ref(winner);
        TaxSink tax = new TaxSink(new CitizenTax.Levy(3, 2.6, earner, "guildtax:province"));
        BankAccount source = Accounts.bank(table);
        MoneyTx tx = new MoneyTx(host, table, "win")
                .spread(Accounts.payee(table, winner, winner.getUniqueId()), 7, List.of(source), coin())
                .spread(tax, 3, List.of(source), coin());
        assertEquals(10, tx.commit().moved());
        assertEquals(1, postings.size());
        assertEquals(-1000, credit("guild:" + table.ownerGuildIncarnation()));
        assertEquals(700, credit(Denars.MINT));
        assertEquals(300, credit("guildtax:province"));
        assertEquals(0, postings.getFirst().stream().mapToLong(Leg::cents).sum());
        assertEquals(7, Accounts.coins(table, winner).available());
        assertEquals(3, tax.received());
        assertEquals(10, table.houseFloat());
        assertFalse(Files.exists(journal()));
        verify(manager).saveWager(table);
        assertEquals(10, tx.commit().moved());
        assertEquals(1, postings.size(), "Repeated commit cannot deliver again");
    }

    @Test void refusedPostingLeavesItemsFloatAndProfitUntouched() {
        table.setHouseFloat(2);
        table.ledger().add(table.getId(), coin(), "gold", 1, 5, 0);
        outcome.set(Result.REFUSED);
        var tx = new MoneyTx(host, table, "peel").moveAll(Accounts.tray(table), Accounts.bank(table));
        assertFalse(tx.commit().ok());
        assertEquals(5, table.ledger().total());
        assertEquals(2, table.houseFloat());
        assertFalse(Files.exists(journal()));
        verifyNoInteractions(manager, host);
        bank.verify(() -> GuildBank.declareProfit(eq(table), anyString(), anyInt()), never());
    }

    @Test void unavailableRetryUsesSamePersistedOperationAndDeliversOnce() throws Exception {
        outcome.set(Result.UNAVAILABLE);
        MoneyTx tx = funding(5);
        assertFalse(tx.commit().ok());
        String before = Files.readString(journal());
        assertTrue(before.contains("prepared"));
        assertEquals(0, table.ledger().total());
        assertEquals(0, table.houseFloat());
        outcome.set(Result.APPLIED);
        assertEquals(5, tx.commit().moved());
        assertEquals(2, operations.size());
        assertEquals(operations.getFirst(), operations.getLast());
        assertEquals(postings.getFirst(), postings.getLast());
        assertEquals(5, table.ledger().total());
        assertFalse(Files.exists(journal()));
    }

    @Test void knownReadinessWaitDoesNotCreateIntent() {
        denars.when(Denars::taxReady).thenReturn(false);
        assertFalse(funding(5).commit().ok());
        assertFalse(Files.exists(journal()));
        assertEquals(0, table.houseFloat());
        assertTrue(postings.isEmpty());
    }

    @Test void restartRetainsPreparedIntentWithoutPostingOrDeliveringAgain() throws Exception {
        outcome.set(Result.UNAVAILABLE);
        assertFalse(funding(5).commit().ok());
        String before = Files.readString(journal());
        WagerJournal.initialize();
        outcome.set(Result.APPLIED);
        assertFalse(funding(5).commit().ok());
        assertEquals(before, Files.readString(journal()));
        assertEquals(1, postings.size());
        assertEquals(0, table.ledger().total());
    }

    @Test void appliedStatusAfterUncertainReplyDoesNotPostAgain() {
        outcome.set(Result.UNAVAILABLE);
        MoneyTx tx = funding(5);
        assertFalse(tx.commit().ok());
        denars.when(() -> Denars.status(operations.getFirst())).thenReturn(Optional.of(Result.APPLIED));
        assertEquals(5, tx.commit().moved());
        assertEquals(1, postings.size());
    }

    @Test void conflictRemainsForReviewAndBlocksFurtherMoney() throws Exception {
        outcome.set(Result.CONFLICT);
        assertFalse(funding(5).commit().ok());
        assertEquals("conflict", JsonParser.parseString(Files.readString(journal())).getAsJsonObject().get("state").getAsString());
        outcome.set(Result.APPLIED);
        assertFalse(funding(5).commit().ok());
        assertEquals(1, postings.size());
        assertEquals(0, table.houseFloat());
    }

    @Test void physicalSourceChangedDuringPostDoesNotConsumeChangedItems() throws Exception {
        table.ledger().add(table.getId(), coin(), "gold", 1, 5, 0);
        denars.when(() -> Denars.post(anyString(), anyString(), anyList())).thenAnswer(call -> {
            table.ledger().add(table.getId(), coin(), "gold", 1, 1, 0);
            return Result.APPLIED;
        });
        assertFalse(new MoneyTx(host, table, "peel").moveAll(Accounts.tray(table), Accounts.bank(table)).commit().ok());
        assertEquals(6, table.ledger().total());
        assertTrue(table.moneyBlocked());
        // The posting happened, but the file keeps its prepared state; it still freezes the table.
        assertEquals("prepared", JsonParser.parseString(Files.readString(journal())).getAsJsonObject().get("state").getAsString());
        assertEquals(0, table.houseFloat());
        table.setMoneyBlocked(false);
        assertFalse(new MoneyTx(host, table, "peel").moveAll(Accounts.tray(table), Accounts.bank(table)).commit().ok());
        denars.verify(() -> Denars.post(anyString(), anyString(), anyList()), times(1));
        assertEquals(6, table.ledger().total(), "this process never posts or delivers an applied operation again");
    }

    @Test void appliedPostingWritesItsIntentOnceAndRemovesIt() throws Exception {
        try (MockedStatic<net.tfminecraft.games.persistence.DurableFiles> files =
                mockStatic(net.tfminecraft.games.persistence.DurableFiles.class, CALLS_REAL_METHODS)) {
            assertEquals(5, funding(5).commit().moved());
            files.verify(() -> net.tfminecraft.games.persistence.DurableFiles.write(eq(journal()), anyString()), times(1));
            files.verify(() -> net.tfminecraft.games.persistence.DurableFiles.delete(journal()), times(1));
        }
        assertFalse(Files.exists(journal()));
        verify(manager).saveWager(table);
    }

    @Test void crashAfterPostingLeavesAPreparedIntentThatFreezesTheTableOnRestart() throws Exception {
        // The table save fails after Denars applied the posting, as a crash at that point would.
        doThrow(new IOException("disk full")).when(manager).saveWager(table);
        assertFalse(funding(5).commit().ok());
        String retained = Files.readString(journal());
        assertEquals("prepared", JsonParser.parseString(retained).getAsJsonObject().get("state").getAsString());
        WagerJournal.initialize();
        assertTrue(WagerJournal.blocked(table));
        assertFalse(WagerJournal.active(table));
        doNothing().when(manager).saveWager(table);
        table.setMoneyBlocked(false);
        assertFalse(funding(5).commit().ok());
        assertEquals(1, postings.size());
        assertEquals(retained, Files.readString(journal()));
    }

    @Test void failedTableSavePreservesAmbiguousDeliveryAndBlocksSecondPayment() throws Exception {
        doThrow(new IOException("disk full")).when(manager).saveWager(table);
        assertFalse(funding(5).commit().ok());
        assertEquals(5, table.ledger().total());
        assertEquals(5, table.houseFloat());
        assertTrue(table.moneyBlocked());
        assertTrue(Files.exists(journal()));
        assertFalse(funding(5).commit().ok());
        assertEquals(1, postings.size());
    }

    @Test void casinoProfitRecordsOnlySurplusAfterFloatWithPostingOperationId() {
        table.setHouseFloat(2);
        table.ledger().add(table.getId(), coin(), "gold", 1, 5, 0);
        assertEquals(5, new MoneyTx(host, table, "peel").moveAll(Accounts.tray(table), Accounts.bank(table)).commit().moved());
        assertEquals(0, table.houseFloat());
        assertEquals(500, credit("guild:" + table.ownerGuildIncarnation()));
        bank.verify(() -> GuildBank.declareProfit(table, operations.getFirst(), 3));
    }

    @Test void failedProfitBookkeepingRetainsAppliedIntentWithoutRepeatingMoney() throws Exception {
        table.ledger().add(table.getId(), coin(), "gold", 1, 5, 0);
        bank.when(() -> GuildBank.declareProfit(eq(table), anyString(), eq(5))).thenReturn(false);
        assertFalse(new MoneyTx(host, table, "peel").moveAll(Accounts.tray(table), Accounts.bank(table)).commit().ok());
        assertEquals(0, table.ledger().total());
        assertTrue(table.moneyBlocked());
        assertTrue(Files.exists(journal()));
        verify(manager).saveWager(table);
        assertEquals(1, postings.size());
    }

    @Test void failureWritingIntentNeverPostsOrMovesItems() throws Exception {
        Files.createDirectories(journal().getParent().getParent());
        Files.writeString(journal().getParent(), "not a directory");
        assertFalse(funding(5).commit().ok());
        assertTrue(postings.isEmpty());
        assertEquals(0, table.houseFloat());
        assertEquals(0, table.ledger().total());
        assertTrue(table.moneyBlocked());
    }

    @Test void freshHandlesToOneSourceCannotOverReserveIt() {
        table.ledger().add(table.getId(), coin(), "gold", 1, 5, 0);
        TaxSink tax = new TaxSink(new CitizenTax.Levy(3, 3, TestCharacters.ref(winner), "guildtax:province"));
        var result = new MoneyTx(host, table, "shared source")
                .move(Accounts.tray(table), Accounts.payee(table, winner, winner.getUniqueId()), 4)
                .move(Accounts.tray(table), tax, 3).commit();
        assertFalse(result.ok());
        assertEquals(5, table.ledger().total());
        assertEquals(0, Accounts.coins(table, winner).available());
        assertTrue(postings.isEmpty());
    }
}
