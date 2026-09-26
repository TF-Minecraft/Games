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

class MintAccountTest {
    private Table table;
    private MintAccount account;
    private ItemStack template;
    private MockedStatic<ChipItems> chips;

    @BeforeEach
    void setUp() {
        table = new Table(UUID.randomUUID(), "poker", null, 0, null);
        table.setStaffMint(true);
        account = new MintAccount(table, 3);
        template = new ItemStack(Material.GOLD_NUGGET, 16);
        chips = mockStatic(ChipItems.class);
        chips.when(() -> ChipItems.unitDenars(template)).thenReturn(5);
        chips.when(() -> ChipItems.typeKey(any(ItemStack.class))).thenReturn("coin:gold");
    }

    @AfterEach
    void tearDown() {
        chips.close();
    }

    @Test
    void staffMintHasUnlimitedFundsButNoSweepablePile() {
        assertEquals("mint", account.label());
        assertEquals(Integer.MAX_VALUE, account.available());
        assertNull(account.planTakeAll(null));
        assertNull(account.planTakeAll(3));
        assertTrue(account.canAccept(20));
    }

    @Test
    void disabledMintRefusesPositiveTransfers() {
        table.setStaffMint(false);
        assertEquals(0, account.available());
        assertNull(account.planTake(20, template));
        assertEquals(0, account.largestTakeUpTo(20, template));
        assertFalse(account.canAccept(20));
        assertTrue(account.canAccept(0));
        assertTrue(account.canAccept(-1));
    }

    @Test
    void planningRequiresPositiveAmountAndAnExactNumberOfValuedCoins() {
        assertNull(account.planTake(0, template));
        assertNull(account.planTake(-5, template));
        assertNull(account.planTake(6, template));
        chips.when(() -> ChipItems.unitDenars(template)).thenReturn(0);
        assertNull(account.planTake(5, template));
    }

    @Test
    void largestTakeRoundsDownToWholeCoinUnits() {
        assertEquals(20, account.largestTakeUpTo(24, template));
        assertEquals(0, account.largestTakeUpTo(4, template));
        assertEquals(0, account.largestTakeUpTo(0, template));
        chips.when(() -> ChipItems.unitDenars(template)).thenReturn(0);
        assertEquals(0, account.largestTakeUpTo(20, template));
    }

    @Test
    void previewsAndTakesProduceSeparateStakesWithoutMutatingTemplate() {
        Withdrawal withdrawal = account.planTake(20, template);
        assertNotNull(withdrawal);
        assertEquals(20, withdrawal.denars());
        List<Stake> preview = withdrawal.preview();
        assertCoins(preview);
        preview.getFirst().setCount(0);
        List<Stake> taken = withdrawal.take();
        assertCoins(taken);
        assertNotSame(preview.getFirst(), taken.getFirst());
        assertEquals(16, template.getAmount());
        assertEquals(Integer.MAX_VALUE, account.available());
    }

    @Test
    void acceptingChipsConsumesTheirValueWithoutChangingFunds() {
        assertEquals(0, account.accept(List.of()));
        assertEquals(27, account.accept(List.of(
                new Stake(template, "coin:gold", 5, 5, 3),
                new Stake(template, "coin:silver", 2, 1, 3))));
        assertEquals(Integer.MAX_VALUE, account.available());
    }

    private void assertCoins(List<Stake> coins) {
        assertEquals(1, coins.size());
        Stake coin = coins.getFirst();
        assertEquals("coin:gold", coin.typeKey());
        assertEquals(5, coin.unit());
        assertEquals(4, coin.count());
        assertEquals(3, coin.streetId());
        assertEquals(1, coin.item().getAmount());
        assertTrue(template.isSimilar(coin.item()));
        assertNotSame(template, coin.item());
    }
}
