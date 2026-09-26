package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.denareconomy.DenarEconomy;
import net.tfminecraft.denareconomy.loaders.MessageLoader;
import net.tfminecraft.denareconomy.managers.MoneyManager;

class CitizenTaxIntegrationTest {
    @Test
    void profitTaxDelegatesOnceAndRoundsChipWithholdingSeparatelyFromMessageAmount() {
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alice");
        MoneyManager money = mock(MoneyManager.class);
        when(money.doTaxes("Alice", 25)).thenReturn(2.6);
        try (MockedStatic<ChipItems> chips = mockStatic(ChipItems.class);
                MockedStatic<DenarEconomy> economy = mockStatic(DenarEconomy.class)) {
            chips.when(ChipItems::denarEconomyPresent).thenReturn(true);
            economy.when(DenarEconomy::getMoneyManager).thenReturn(money);
            assertEquals(new CitizenTax.Levy(3, 2.6), CitizenTax.levy(player, 25));
            verify(money).doTaxes("Alice", 25);
            assertEquals(3, CitizenTax.due(player, 25));
            verify(money, times(2)).doTaxes("Alice", 25);
        }
    }

    @Test
    void absentPlayerEconomyOrProfitNeverCallsTaxService() {
        Player player = mock(Player.class);
        try (MockedStatic<ChipItems> chips = mockStatic(ChipItems.class);
                MockedStatic<DenarEconomy> economy = mockStatic(DenarEconomy.class)) {
            assertSame(CitizenTax.Levy.NONE, CitizenTax.levy(null, 10));
            assertSame(CitizenTax.Levy.NONE, CitizenTax.levy(player, 0));
            assertSame(CitizenTax.Levy.NONE, CitizenTax.levy(player, -1));
            assertSame(CitizenTax.Levy.NONE, CitizenTax.levy(player, 10));
            economy.verifyNoInteractions();
        }
    }

    @Test
    void taxNotificationOnlyReachesOnlinePlayersWithPositiveTax() {
        Player player = mock(Player.class);
        try (MockedStatic<MessageLoader> messages = mockStatic(MessageLoader.class)) {
            CitizenTax.tell(null, 2);
            CitizenTax.tell(player, 2);
            when(player.isOnline()).thenReturn(true);
            CitizenTax.tell(player, 0);
            CitizenTax.tell(player, -1);
            messages.verifyNoInteractions();
            CitizenTax.tell(player, 2.6);
            messages.verify(() -> MessageLoader.send(player, "money.tax", "tax", 2.6));
        }
    }
}
