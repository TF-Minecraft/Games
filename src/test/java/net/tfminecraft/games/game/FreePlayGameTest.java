package net.tfminecraft.games.game;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.wager.WagerEngine;

class FreePlayGameTest {
    private final FreePlayGame game = new FreePlayGame();
    private final Table table = new Table(UUID.randomUUID(), "freeplay", new Location(null, 0, 0, 0), 0, null);
    private final Player player = mock(Player.class);

    @Test
    void sandboxActionsAreAvailableOnlyWhileTableIsIdle() {
        assertFalse(game.allowFreeDraw(null, player));
        assertFalse(game.allowReturnSelected(null, player));
        assertFalse(game.allowManualPotFlush(null, player));
        assertTrue(game.allowFreeDraw(table, player));
        assertTrue(game.allowReturnSelected(table, player));
        assertTrue(game.allowManualPotFlush(table, player));
        table.startSession();
        assertFalse(game.allowFreeDraw(table, player));
        assertFalse(game.allowReturnSelected(table, player));
        assertFalse(game.allowManualPotFlush(table, player));
    }

    @Test
    void leavingRefundsPlayersMoneyAndRemovesTheirSeat() {
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        table.actives().add(id);
        TableManager manager = mock(TableManager.class);
        try (MockedStatic<TableManager> managers = mockStatic(TableManager.class)) {
            managers.when(TableManager::get).thenReturn(manager);
            game.onLeave(table, player);
            verify(manager).refundOwnedPiles(table, player);
            assertFalse(table.actives().contains(id));
            game.onFeltPilesChanged(table);
            game.onChipIn(table, player, 3, null);
            verify(manager, times(2)).refreshLabel(table);
        }
    }

    @Test
    void freePlayPotLabelShowsEveryContributorAndCombinedTotal() {
        assertEquals("", game.extraLabel(null));
        WagerEngine engine = mock(WagerEngine.class);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        try (MockedStatic<WagerEngine> engines = mockStatic(WagerEngine.class);
                MockedStatic<RpNames> names = mockStatic(RpNames.class);
                MockedStatic<Messages> messages = mockStatic(Messages.class)) {
            engines.when(WagerEngine::get).thenReturn(engine);
            when(engine.totalsExcept(table, table.getId())).thenReturn(Map.of());
            assertEquals("", game.extraLabel(table));
            Map<UUID, Integer> pot = new LinkedHashMap<>();
            pot.put(alice, 10);
            pot.put(bob, 25);
            when(engine.totalsExcept(table, table.getId())).thenReturn(pot);
            names.when(() -> RpNames.of(alice)).thenReturn("Alice");
            names.when(() -> RpNames.of(bob)).thenReturn("Bob");
            messages.when(() -> Messages.get("label.pot_seat", "name", "Alice", "n", "10")).thenReturn("Alice: 10");
            messages.when(() -> Messages.get("label.pot_seat", "name", "Bob", "n", "25")).thenReturn("Bob: 25");
            messages.when(() -> Messages.get("label.pot", "n", "35")).thenReturn("Pot: 35");
            assertEquals("Alice: 10\nBob: 25\nPot: 35", game.extraLabel(table));
        }
    }

    @Test
    void inheritedChatGateRequiresLivePlayAndTheCurrentActor() {
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        assertFalse(game.allowPlayChat(null, player));
        assertFalse(game.allowPlayChat(table, null));
        assertFalse(game.allowPlayChat(table, player));
        table.startSession();
        assertFalse(game.allowPlayChat(table, player));
        table.setPhase("play");
        table.setActor(UUID.randomUUID());
        assertFalse(game.allowPlayChat(table, player));
        table.setActor(id);
        assertTrue(game.allowPlayChat(table, player));
    }

    @Test
    void revealKeepsCountAndTableSlotsSeparateFromPlayerFan() {
        Location origin = table.getOrigin();
        List<DisplayPose> poses = game.revealSlots(table, player, 3, origin, origin.clone().add(0, 1, 0), 0, false);
        assertEquals(3, poses.size());
        assertNotEquals(poses.get(0).translation(), poses.get(2).translation());
        assertNotEquals(poses.get(0).translation(), game.tablePileSlot(table, "board", 0, 3, true).translation());
        assertTrue(game.sortHeldCards());
        assertTrue(game.allowRevealToggle(table, player));
        assertTrue(game.showRevealDust(table));
        assertEquals(0, game.minActives());
        assertEquals(0, game.denarsToMatch(table, player));
        assertTrue(game.showStockDealer());
        assertFalse(game.tryClaimDealer(table, player));
    }

    @Test
    void sessionStartIsLoggedOnlyWithDebugEnabled() {
        Games previous = Games.plugin;
        boolean debug = Cache.debug;
        Logger logger = mock(Logger.class);
        try {
            Games.plugin = mock(Games.class);
            when(Games.plugin.getLogger()).thenReturn(logger);
            Cache.debug = false;
            game.onSessionStart(table);
            verifyNoInteractions(logger);
            Cache.debug = true;
            game.onSessionStart(table);
            verify(logger).info("[Games] onSessionStart table=" + table.getId());
        } finally {
            Games.plugin = previous;
            Cache.debug = debug;
        }
    }

    @Test
    void registryResolvesSupportedGamesCaseInsensitivelyAndRejectsUnknownIds() {
        assertNull(GamesRegistry.of(null));
        assertNull(GamesRegistry.of("unknown"));
        assertInstanceOf(PokerGame.class, GamesRegistry.of("POKER"));
        assertInstanceOf(DrawGame.class, GamesRegistry.of("Draw"));
        assertInstanceOf(BlackjackGame.class, GamesRegistry.of("BLACKJACK"));
        assertInstanceOf(FreePlayGame.class, GamesRegistry.of("FreePlay"));
        assertSame(GamesRegistry.of("poker"), GamesRegistry.of("POKER"));
    }
}
