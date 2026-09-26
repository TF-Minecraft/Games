package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Map;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.layout.TableLayout;

/** The options a player picks before placing a table, and how they are kept consistent. */
class TableHouseTest {

    private static TableLayout layout(boolean autoDealer) {
        return new TableLayout("cards", "Game", "icon", 6, Map.of(), null, null, null, 0,
                false, autoDealer, 5, 100, 2, TableLayout.VoiceLines.defaults(), null, 0, 2,
                null, 4, 2, 4, 4, false);
    }

    private static Player player(boolean staff) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.hasPermission(TableHouse.STAFF_PERM)).thenReturn(staff);
        return player;
    }

    @Test
    void onlyStaffAtAnAutomaticGameStartWithTheMint() {
        assertTrue(TableHouse.forPlace(player(true), layout(true)).staffMint());
        TableHouse staffManual = TableHouse.forPlace(player(true), layout(false));
        assertFalse(staffManual.autoDealer());
        assertFalse(staffManual.staffMint());
        TableHouse playerAuto = TableHouse.forPlace(player(false), layout(true));
        assertFalse(playerAuto.autoDealer());
        assertFalse(playerAuto.staffMint());
    }

    @Test
    void gameWithoutLayoutStartsAtOneDenarWithNoBlinds() {
        Player owner = player(true);
        TableHouse house = TableHouse.forPlace(owner, null);
        assertEquals(owner.getUniqueId(), house.ownerPlayer());
        assertEquals(1, house.minBet());
        assertEquals(1, house.maxBet());
        assertEquals(0, house.maxBoxes());
        assertEquals(0, house.smallBlind());
        assertEquals(0, house.bigBlind());
        assertEquals(ShufflePolicy.SHOE, house.shufflePolicy());
    }

    @Test
    void raisingTheMinimumAboveTheMaximumRaisesTheMaximumButNotTheOtherWayRound() {
        TableHouse house = TableHouse.forPlace(player(false), layout(false));
        house.setMinBet(150);
        assertEquals(150, house.maxBet());
        house.setMinBet(20);
        assertEquals(20, house.minBet());
        assertEquals(150, house.maxBet());
        house.setMaxBet(10);
        assertEquals(20, house.maxBet());
    }
}
