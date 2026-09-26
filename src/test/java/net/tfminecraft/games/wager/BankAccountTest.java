package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.table.Table;

class BankAccountTest {
    private Table table;
    private BankAccount account;
    private ItemStack template;
    private MockedStatic<GuildBank> bank;
    private MockedStatic<ChipItems> chips;

    @BeforeEach
    void setUp() {
        table = new Table(UUID.randomUUID(), "poker", null, 0, null);
        table.setOwnerGuildId("guild");
        account = new BankAccount(table, 7);
        template = new ItemStack(Material.GOLD_NUGGET, 16);
        bank = mockStatic(GuildBank.class);
        chips = mockStatic(ChipItems.class);
        chips.when(() -> ChipItems.unitDenars(template)).thenReturn(5);
        chips.when(() -> ChipItems.typeKey(any(ItemStack.class))).thenReturn("coin:gold");
        bank.when(() -> GuildBank.balance("guild")).thenReturn(103);
    }

    @AfterEach
    void tearDown() {
        chips.close();
        bank.close();
    }

    @Test
    void identifiesBankAndCannotSweepBalance() {
        assertEquals("guild bank", account.label());
        assertEquals(103, account.available());
        assertNull(account.planTakeAll(null));
        assertNull(account.planTakeAll(7));
    }

    @Test
    void planningRejectsNonpositiveUnrepresentableAndUnaffordableAmounts() {
        assertNull(account.planTake(0, template));
        assertNull(account.planTake(-5, template));
        assertNull(account.planTake(6, template));
        assertNull(account.planTake(105, template));
        chips.when(() -> ChipItems.unitDenars(template)).thenReturn(0);
        assertNull(account.planTake(5, template));
        bank.verify(() -> GuildBank.withdraw(anyString(), anyInt()), never());
    }

    @Test
    void capsWithdrawalsAtBalanceAndWholeCoinUnits() {
        assertEquals(100, account.largestTakeUpTo(200, template));
        assertEquals(10, account.largestTakeUpTo(14, template));
        assertEquals(0, account.largestTakeUpTo(4, template));
        assertEquals(0, account.largestTakeUpTo(0, template));
        chips.when(() -> ChipItems.unitDenars(template)).thenReturn(0);
        assertEquals(0, account.largestTakeUpTo(20, template));
    }

    @Test
    void plansAndPreviewsWithoutWithdrawingThenAddsSuccessfulWithdrawalToFloat() {
        table.setHouseFloat(12);
        Withdrawal withdrawal = account.planTake(20, template);
        assertNotNull(withdrawal);
        assertEquals(20, withdrawal.denars());
        List<Stake> preview = withdrawal.preview();
        assertCoins(preview);
        assertEquals(12, table.houseFloat());
        bank.verify(() -> GuildBank.withdraw(anyString(), anyInt()), never());
        bank.when(() -> GuildBank.withdraw("guild", 20)).thenReturn(true);
        List<Stake> taken = withdrawal.take();
        assertCoins(taken);
        assertNotSame(preview.getFirst(), taken.getFirst());
        assertEquals(32, table.houseFloat());
        assertEquals(16, template.getAmount());
        bank.verify(() -> GuildBank.withdraw("guild", 20));
    }

    @Test
    void failedWithdrawalPaysNothingAndPreservesFloat() {
        table.setHouseFloat(12);
        Withdrawal withdrawal = account.planTake(20, template);
        assertTrue(withdrawal.take().isEmpty());
        assertEquals(12, table.houseFloat());
    }

    @Test
    void acceptanceChecksBankOnlyForPositiveTransfers() {
        assertTrue(account.canAccept(0));
        assertTrue(account.canAccept(-1));
        bank.verifyNoInteractions();
        assertFalse(account.canAccept(1));
        bank.when(() -> GuildBank.canHold("guild")).thenReturn(true);
        assertTrue(account.canAccept(1));
    }

    @Test
    void emptyOrFailedDepositsLeaveFloatAndProfitUntouched() {
        table.setHouseFloat(12);
        assertEquals(0, account.accept(List.of()));
        bank.verifyNoInteractions();
        assertEquals(0, account.accept(coins(20)));
        assertEquals(12, table.houseFloat());
        bank.verify(() -> GuildBank.declareProfit(anyString(), anyInt()), never());
    }

    @Test
    void depositedWinningsRepayFloatBeforeDeclaringProfit() {
        table.setHouseFloat(12);
        bank.when(() -> GuildBank.deposit("guild", 20)).thenReturn(true);
        assertEquals(20, account.accept(coins(20)));
        assertEquals(0, table.houseFloat());
        bank.verify(() -> GuildBank.declareProfit("guild", 8));
    }

    @Test
    void partialFloatRepaymentDeclaresNoProfit() {
        table.setHouseFloat(30);
        bank.when(() -> GuildBank.deposit("guild", 20)).thenReturn(true);
        assertEquals(20, account.accept(coins(20)));
        assertEquals(10, table.houseFloat());
        bank.verify(() -> GuildBank.declareProfit(anyString(), anyInt()), never());
    }

    private void assertCoins(List<Stake> coins) {
        assertEquals(1, coins.size());
        Stake coin = coins.getFirst();
        assertEquals("coin:gold", coin.typeKey());
        assertEquals(5, coin.unit());
        assertEquals(4, coin.count());
        assertEquals(7, coin.streetId());
        assertEquals(1, coin.item().getAmount());
        assertTrue(template.isSimilar(coin.item()));
        assertNotSame(template, coin.item());
    }

    private List<Stake> coins(int value) {
        return List.of(new Stake(template, "coin:gold", value, 1, 7));
    }
}
