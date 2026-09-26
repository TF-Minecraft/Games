package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.wager.WagerEngine;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.MockedStatic;

class TableManagerLabelTest extends TableManagerFixture {
    private final Map<UUID, String> labels = new LinkedHashMap<>();
    private Map<String, TableLayout> previousLayouts;
    private MockedStatic<GuildTables> guilds;
    private MockedStatic<RpNames> names;

    @BeforeEach
    void configureLabels() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        Cache.tableLayouts.put("freeplay", layout("Cards"));
        Cache.tableLayouts.put("blackjack", layout("Blackjack"));
        messages.when(() -> Messages.get(anyString(), any(String[].class))).thenAnswer(call ->
                call.getArgument(0) + Arrays.toString((String[]) call.getRawArguments()[1]));
        guilds = mockStatic(GuildTables.class);
        names = mockStatic(RpNames.class);
        names.when(() -> RpNames.of(player.getUniqueId())).thenReturn("Dealer Ryan");
        when(game.showStockDealer()).thenReturn(true);
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(game);
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString())).thenAnswer(call -> {
            TextDisplay label = mock(TextDisplay.class);
            UUID id = UUID.randomUUID();
            when(label.getUniqueId()).thenReturn(id);
            labels.put(id, call.getArgument(1));
            return label;
        });
        anchors.when(() -> WorldAnchors.setText(any(UUID.class), anyString())).thenAnswer(call -> {
            labels.put(call.getArgument(0), call.getArgument(1));
            return null;
        });
    }

    @AfterEach
    void restoreLabels() {
        names.close();
        guilds.close();
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
    }

    @Test
    void ordinaryTableLabelUsesConfiguredTitleWithoutBlackjackOnlyLines() {
        Table table = place(false);
        assertEquals("label.title[name, Cards]", shown(table));
        assertFalse(shown(table).contains("shuffle"));
        assertFalse(shown(table).contains("dealer"));
        assertFalse(shown(table).contains("limits"));
    }

    @Test
    void blackjackLabelShowsShuffleOwnershipDealerAndLimitsInOrder() {
        Table table = placeGame("blackjack", 0);
        table.setOwnerGuildId("guild-id");
        guilds.when(() -> GuildTables.displayName("guild-id")).thenReturn("Card Guild");
        table.setAutoDealer(true);
        table.setMinBet(10);
        table.setMaxBet(100);
        assertEquals("label.title[name, Blackjack]\nlabel.shuffle_shoe\nlabel.owner[name, Card Guild]"
                + "\nlabel.dealer[name, Auto]\nlabel.limits[min, 10, max, 100]", shown(table));
        table.setShufflePolicy(ShufflePolicy.ROUND);
        table.setDealerId(player.getUniqueId());
        assertEquals("label.title[name, Blackjack]\nlabel.shuffle_round\nlabel.owner[name, Card Guild]"
                + "\nlabel.dealer[name, Dealer Ryan]\nlabel.limits[min, 10, max, 100]", shown(table));
    }

    @Test
    void gameCanReplaceStockDealerLineWithItsOwnExtraLabel() {
        Table table = place(false);
        table.setDealerId(player.getUniqueId());
        when(game.showStockDealer()).thenReturn(false);
        when(game.extraLabel(table)).thenReturn("Your turn\nPot: 40");
        assertEquals("label.title[name, Cards]\nYour turn\nPot: 40", shown(table));
        when(game.extraLabel(table)).thenReturn(" ");
        assertEquals("label.title[name, Cards]", shown(table));
    }

    @ParameterizedTest
    @CsvSource({"false, false, label.countdown", "false, true, label.countdown_bets", "true, false, label.countdown_round"})
    void countdownLabelDistinguishesOpenBetsFromRoundEnd(boolean live, boolean open, String key) {
        Table table = place(false);
        if (live) table.startSession();
        table.setBetOpen(open);
        table.setAutoCountdown(7);
        assertEquals("label.title[name, Cards]" + (open ? "\nlabel.open" : "") + "\n" + key + "[seconds, 7]",
                shown(table));
        table.setAutoCountdown(0);
        assertEquals("label.title[name, Cards]" + (open ? "\nlabel.open" : ""), shown(table));
    }

    @Test
    void upperLimitWithoutMinimumDisplaysUnspecifiedMinimumClearly() {
        Table table = place(false);
        table.setMaxBet(50);
        assertEquals("label.title[name, Cards]\nlabel.limits[min, -, max, 50]", shown(table));
    }

    @Test
    void clockClearsDistantDealerEvenWithoutHandOrPlayerBox() {
        Table table = place(false);
        table.setDealerId(player.getUniqueId());
        assertTrue(table.actives().isEmpty());
        assertTrue(table.getHands().isEmpty());
        manager.startClock();
        tick(2);
        assertEquals(player.getUniqueId(), table.dealerId());
        player.teleport(table.getOrigin().clone().add(100, 0, 0));
        tick(2);
        assertNull(table.dealerId());
        assertSame(table, manager.table(table.getId()));
        verify(game).onDealerGone(table);
        assertEquals("label.title[name, Cards]", shown(table));
        manager.stopClock();
    }

    @Test
    void oneBrokenStackRendererDoesNotPreventOtherTablesRebuilding() {
        Table broken = placeGame("freeplay", 0);
        Table healthy = placeGame("freeplay", 10);
        clearInvocations(display);
        when(display.spawn(any(), argThat(at -> at.getX() < 5), any(), any()))
                .thenThrow(new IllegalStateException("renderer unavailable"));
        assertDoesNotThrow(manager::rebuildAllStacks);
        verify(display, times(Cache.stackVisibleMax)).spawn(any(), argThat(at -> at.getX() == 10), any(), any());
        assertEquals(Cache.stackVisibleMax, healthy.getStackTokens().size());
        assertEquals(2, broken.getDeck().remaining());
        assertEquals(2, healthy.getDeck().remaining());
        verify(Games.plugin.getLogger()).warning("[Games] Failed to rebuild table stack: renderer unavailable");
    }

    @Test
    void chipRedrawFailureIsIsolatedAndPreservesBothLedgers() {
        Table broken = placeGame("freeplay", 0);
        Table healthy = placeGame("freeplay", 10);
        ItemStack coin = new ItemStack(Material.GOLD_NUGGET);
        WagerEngine.get().restore(broken, player.getUniqueId(), coin, "coin", 1, 3, 1, 0.75, 0.0);
        WagerEngine.get().restore(healthy, player.getUniqueId(), coin, "coin", 1, 5, 1, 10.75, 0.0);
        clearInvocations(game, display);
        when(display.spawn(any(), argThat(at -> at.getX() < 5), any(), any()))
                .thenThrow(new IllegalStateException("chip renderer unavailable"));
        assertDoesNotThrow(manager::redrawAllChips);
        verify(game).onFeltPilesChanged(healthy);
        verify(display, atLeastOnce()).spawn(any(), argThat(at -> at.getX() > 5), any(), any());
        assertEquals(3, broken.ledger().total());
        assertEquals(5, healthy.ledger().total());
        verify(Games.plugin.getLogger()).warning("[Games] Failed to redraw chips for table " + broken.getId()
                + ": chip renderer unavailable");
    }

    private String shown(Table table) {
        manager.refreshLabel(table);
        assertNotNull(table.getLabelId());
        return labels.get(table.getLabelId());
    }

    private Table placeGame(String gameId, double x) {
        manager.armPlace(player, gameId, false);
        assertTrue(manager.tryPlace(player, new Location(world, x, 65, 0)));
        return manager.tables().stream().filter(table -> table.getOrigin().getX() == x).findFirst().orElseThrow();
    }

    private static TableLayout layout(String name) {
        return new TableLayout(Cache.pokerCardSet, name, "icon", 6, Map.of(), null, null, null, 0);
    }
}
