package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.util.Vector;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.DrawGame;
import net.tfminecraft.games.game.Game;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.game.PokerGame;
import net.tfminecraft.games.loader.CardLoader;

/** A complete poker or Five-Draw round across the real game, table, deck and money implementations. */
class TableManagerGameRoundTest extends TableManagerFixture {
    @ParameterizedTest
    @CsvSource({"poker, 2", "draw, 5"})
    void shoeToShowdownConservesCardsAndMoneyAcrossRealComponents(String gameId, int cardsPerPlayer) {
        List<Card> fullDeck = new ArrayList<>();
        for (String suit : List.of("oseni", "cerrith", "clubs", "hearts")) {
            for (int rank = 1; rank <= 13; rank++) {
                Card card = new Card(suit + "_" + rank, suit, rank, false, "face");
                fullDeck.add(card);
                cards.when(() -> CardLoader.get(card.getId())).thenReturn(card);
            }
        }
        String set = Cache.cardSetOf(gameId);
        cards.when(() -> CardLoader.hasSet(set)).thenReturn(true);
        cards.when(() -> CardLoader.getSet(set)).thenReturn(fullDeck);
        Game rules = gameId.equals("poker") ? new PokerGame() : new DrawGame();
        games.when(() -> GamesRegistry.of(gameId)).thenReturn(rules);
        manager.armPlace(player, gameId, false);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        var other = opponent();
        stakeCoin(player, table);
        assertEquals(player.getUniqueId(), table.dealerId());
        stakeCoin(other, table);
        assertEquals(2, table.ledger().total());
        assertFalse(table.live());
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent click = new PlayerInteractAtEntityEvent(player, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(click);
        assertTrue(click.isCancelled());
        tick(30);
        assertTrue(table.live());
        assertEquals(cardsPerPlayer, table.handOf(player.getUniqueId()).size());
        assertEquals(cardsPerPlayer, table.handOf(other.getUniqueId()).size());
        Set<String> phases = new HashSet<>();
        for (int action = 0; table.live() && action < 12; action++) {
            phases.add(table.phase());
            assertNotNull(table.actor(), "A finished deal must nominate the next actor");
            var actor = org.bukkit.Bukkit.getPlayer(table.actor());
            assertNotNull(actor);
            assertTrue(rules.allowPlayChat(table, actor));
            assertTrue(rules.extraLabel(table).contains("label.turn"));
            manager.applyPlayCall(actor, "draw".equals(table.phase()) ? "draw" : "check");
            tick(40);
        }
        assertFalse(table.live(), "Checking/standing pat through the whole round must settle");
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.getHands().isEmpty());
        assertTrue(table.tablePilesEmpty());
        assertEquals(52, table.getDeck().remaining() + table.getDeck().discarded());
        assertEquals(52, java.util.stream.Stream.concat(table.getDeck().remainingIds().stream(),
                table.getDeck().discardedIds().stream()).distinct().count());
        assertEquals(2, java.util.stream.Stream.of(player, other)
                .flatMap(p -> p.getInventory().all(Material.GOLD_NUGGET).values().stream())
                .mapToInt(item -> item.getAmount()).sum());
        assertEquals(other.getUniqueId(), table.dealerId(), "The button advances after settlement");
        assertEquals(gameId.equals("poker") ? Set.of("preflop", "flop", "turn", "river") : Set.of("bet", "draw"), phases);
    }
}
