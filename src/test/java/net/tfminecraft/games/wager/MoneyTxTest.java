package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;

class MoneyTxTest {
    private WagerHost host;
    private Table table;
    private MoneyAccount source;
    private MoneyAccount destination;
    private final java.util.Map<Withdrawal, List<Stake>> expectedCoins = new java.util.IdentityHashMap<>();

    @BeforeEach
    void setUp() {
        host = mock(WagerHost.class);
        table = new Table(UUID.randomUUID(), "test", null, 0, null);
        source = mock(MoneyAccount.class);
        destination = mock(MoneyAccount.class);
        when(destination.canAccept(anyInt())).thenReturn(true);
        when(destination.accept(anyList())).thenAnswer(call -> TableLedger.valueOf(call.getArgument(0)));
    }

    private MoneyTx tx() { return new MoneyTx(host, table, "test transfer"); }

    private Withdrawal plan(MoneyAccount account, int amount) {
        Withdrawal plan = mock(Withdrawal.class);
        List<Stake> coins = List.of(new Stake(null, "loot", 1, amount, 1));
        expectedCoins.put(plan, coins);
        when(plan.denars()).thenReturn(amount);
        when(plan.take()).thenReturn(coins);
        when(account.planTake(amount, null)).thenReturn(plan);
        when(account.largestTakeUpTo(amount, null)).thenReturn(amount);
        return plan;
    }

    @Test
    void missingHostOrTableRefusesBeforePlanning() {
        assertEquals(TxResult.Reason.NO_TABLE, new MoneyTx(null, table, "").commit().reason());
        assertEquals(TxResult.Reason.NO_TABLE, new MoneyTx(host, null, "").commit().reason());
        verifyNoInteractions(source, host);
    }

    @Test
    void invalidLegsAndEmptyTransactionsDoNothing() {
        MoneyTx tx = tx().move(null, destination, 1).move(source, null, 1)
                .move(source, destination, 0).moveUpTo(null, destination, 1)
                .moveUpTo(source, null, 1).moveUpTo(source, destination, -1)
                .moveAll(null, destination).moveAll(source, null)
                .spread(null, 1, List.of(source)).spread(destination, 1, null)
                .spread(destination, 0, List.of(source)).spread(destination, 1, List.of());
        assertEquals(TxResult.Reason.NOTHING, tx.commit().reason());
        verifyNoInteractions(source, host);
    }

    @Test
    void failedLaterLegLeavesEarlierWithdrawalUntouched() {
        Withdrawal first = plan(source, 10);
        MoneyAccount shortAccount = mock(MoneyAccount.class);
        when(shortAccount.available()).thenReturn(3);
        when(shortAccount.largestTakeUpTo(5, null)).thenReturn(3);
        TxResult result = tx().move(source, destination, 10).move(shortAccount, destination, 5).commit();
        assertFalse(result.ok());
        assertEquals(TxResult.Reason.FELT_SHORT, result.reason());
        assertEquals(5, result.wanted());
        assertEquals(3, result.best());
        assertEquals(2, result.shortfall());
        verify(first, never()).take();
        verify(destination, never()).accept(anyList());
    }

    @Test
    void missingChangeDiffersFromMissingFunds() {
        when(source.available()).thenReturn(10);
        assertEquals(TxResult.Reason.NO_CHANGE, tx().move(source, destination, 5).commit().reason());
        PlayerAccount player = mock(PlayerAccount.class);
        assertEquals(TxResult.Reason.PLAYER_SHORT, tx().move(player, destination, 5).commit().reason());
        when(player.available()).thenReturn(10);
        assertEquals(TxResult.Reason.NO_CHANGE, tx().move(player, destination, 5).commit().reason());
    }

    @Test
    void bankAndMintFailuresIdentifyTemplateAndFundingProblems() {
        BankAccount bank = mock(BankAccount.class);
        MintAccount mint = mock(MintAccount.class);
        ItemStack coin = mock(ItemStack.class);
        try (MockedStatic<ChipItems> chips = mockStatic(ChipItems.class)) {
            assertEquals(TxResult.Reason.NO_TEMPLATE, tx().move(bank, destination, 5).commit().reason());
            assertEquals(TxResult.Reason.NO_TEMPLATE, tx().move(mint, destination, 5).commit().reason());
            chips.when(() -> ChipItems.unitDenars(coin)).thenReturn(1);
            assertEquals(TxResult.Reason.MINT_REFUSED, tx().move(mint, destination, 5, coin).commit().reason());
            assertEquals(TxResult.Reason.BANK_SHORT, tx().move(bank, destination, 5, coin).commit().reason());
            when(bank.available()).thenReturn(10);
            assertEquals(TxResult.Reason.NO_CHANGE, tx().move(bank, destination, 5, coin).commit().reason());
        }
    }

    @Test
    void destinationRefusalDoesNotWithdraw() {
        Withdrawal plan = plan(source, 10);
        when(destination.canAccept(10)).thenReturn(false);
        assertEquals(TxResult.Reason.BANK_SHORT, tx().move(source, destination, 10).commit().reason());
        verify(plan, never()).take();
    }

    @Test
    void exactTransferMovesCoinsAndNotifiesHost() {
        Withdrawal plan = plan(source, 10);
        TxResult result = tx().move(source, destination, 10).commit();
        assertTrue(result.ok());
        assertEquals(10, result.moved());
        assertEquals(10, result.wanted());
        assertEquals(10, result.best());
        assertEquals(0, result.shortfall());
        assertTrue(result.flights().isEmpty());
        verify(destination).accept(expectedCoins.get(plan));
        verify(plan).take();
        verify(host).moneyMoved(table, Set.of());
    }

    @Test
    void partialTransfersSkipEmptyOrUnavailablePlans() {
        assertEquals(TxResult.Reason.NOTHING, tx().moveUpTo(source, destination, 10).commit().reason());
        when(source.largestTakeUpTo(10, null)).thenReturn(7);
        assertEquals(TxResult.Reason.NOTHING, tx().moveUpTo(source, destination, 10, null).commit().reason());
        plan(source, 7);
        assertEquals(7, tx().moveUpTo(source, destination, 10).commit().moved());
    }

    @Test
    void allAndStreetTransfersPassTheirFilterToAccount() {
        Withdrawal plan = plan(source, 4);
        when(source.planTakeAll(null)).thenReturn(plan);
        when(source.planTakeAll(3)).thenReturn(plan);
        assertEquals(4, tx().moveAll(source, destination).commit().moved());
        assertEquals(4, tx().moveStreet(source, destination, 3).commit().moved());
        verify(source).planTakeAll(null);
        verify(source).planTakeAll(3);
        assertEquals(TxResult.Reason.NOTHING, tx().moveStreet(source, destination, 8).commit().reason());
    }

    @Test
    void spreadCapsEachSourceAtTheRemainingRequestedValue() {
        MoneyAccount second = mock(MoneyAccount.class);
        MoneyAccount unused = mock(MoneyAccount.class);
        plan(source, 4);
        when(source.largestTakeUpTo(10, null)).thenReturn(4);
        plan(second, 6);
        TxResult result = tx().spread(destination, 10, List.of(source, second, unused)).commit();
        assertEquals(10, result.moved());
        verify(second).largestTakeUpTo(6, null);
        verifyNoInteractions(unused);
    }

    @Test
    void animationUsesSourceAnchorDestinationAndTrayFlag() {
        Withdrawal plan = plan(source, 5);
        UUID owner = UUID.randomUUID();
        UUID target = UUID.randomUUID();
        Location anchor = new Location(null, 1, 2, 3);
        when(source.feltOwner()).thenReturn(owner);
        when(source.onFelt()).thenReturn(true);
        when(destination.feltOwner()).thenReturn(target);
        when(destination.onFelt()).thenReturn(true);
        when(destination.flightTarget()).thenReturn(target);
        when(destination.isTray()).thenReturn(true);
        when(host.anchorFor(table, owner)).thenReturn(anchor);
        PayoutFlight flight = mock(PayoutFlight.class);
        when(host.flights(table, expectedCoins.get(plan), anchor, target, true)).thenReturn(List.of(flight));
        List<PayoutFlight> flights = new ArrayList<>();
        TxResult result = tx().move(source, destination, 5).animate(flights).commit();
        assertSame(flights, result.flights());
        assertEquals(List.of(flight), flights);
        verify(host).moneyMoved(table, Set.of(owner, target));
    }

    @Test
    void noSourceAnchorMeansNoAnimation() {
        plan(source, 5);
        List<PayoutFlight> flights = new ArrayList<>();
        assertEquals(5, tx().move(source, destination, 5).animate(flights).commit().moved());
        assertTrue(flights.isEmpty());
        verify(host, never()).flights(any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void zeroWithdrawalReportsMismatchAndDoesNotDeposit() {
        Withdrawal plan = plan(source, 5);
        when(plan.take()).thenReturn(List.of());
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            assertEquals(0, tx().move(source, destination, 5).commit().moved());
            log.verify(() -> MoneyLog.mismatch(eq(table), contains("planned 5")));
        }
        verify(destination, never()).accept(anyList());
    }

    @Test
    void unexpectedlyRejectedDepositReturnsCoinsAndReportsMismatch() {
        Withdrawal plan = plan(source, 5);
        when(destination.accept(anyList())).thenReturn(0);
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            assertEquals(0, tx().move(source, destination, 5).commit().moved());
            log.verify(() -> MoneyLog.mismatch(eq(table), contains("returning it")));
        }
        verify(source).accept(expectedCoins.get(plan));
        verify(plan).take();
    }

    @Test
    void inconsistentFeltBalanceIsReported() {
        plan(source, 5);
        when(source.onFelt()).thenReturn(true);
        try (MockedStatic<MoneyLog> log = mockStatic(MoneyLog.class)) {
            assertEquals(5, tx().move(source, destination, 5).commit().moved());
            log.verify(() -> MoneyLog.mismatch(eq(table), contains("legs add up to -5")));
        }
    }
}
