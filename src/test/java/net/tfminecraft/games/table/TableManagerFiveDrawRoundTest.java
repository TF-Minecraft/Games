package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.game.DrawGame;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.loader.CardLoader;

/** Five-Draw hands across the real game, table, deck and money implementations. */
class TableManagerFiveDrawRoundTest extends TableManagerHandFixture {
    private final DrawGame rules = new DrawGame();

    @BeforeEach void useAFullDeck() {
        List<Card> deck = new ArrayList<>();
        for (String suit : List.of("oseni", "cerrith", "clubs", "hearts")) {
            for (int rank = 1; rank <= 13; rank++) {
                Card card = new Card(suit + "_" + rank, suit, rank, false, "face");
                deck.add(card);
                cards.when(() -> CardLoader.get(card.getId())).thenReturn(card);
            }
        }
        String set = Cache.cardSetOf("draw");
        cards.when(() -> CardLoader.hasSet(set)).thenReturn(true);
        cards.when(() -> CardLoader.getSet(set)).thenReturn(deck);
        games.when(() -> GamesRegistry.of("draw")).thenReturn(rules);
    }

    @Test void aReloadWhileAReplacementCardIsLandingLetsTheDrawCarryOn() throws InterruptedException {
        manager.armPlace(player, "draw", false);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        PlayerMock other = opponent();
        // The first stake takes the button, so the placer acts first.
        stakeCoin(other, table);
        stakeCoin(player, table);
        assertTrue(shoeClick(table).isCancelled());
        advanceUntil(() -> player.getUniqueId().equals(table.actor()));
        manager.applyPlayCall(player, "check");
        manager.applyPlayCall(other, "check");
        assertEquals(DrawGame.DRAW, table.phase());
        assertEquals(player.getUniqueId(), table.actor());

        select(table.heldBy(player.getUniqueId()).getFirst());
        Cache.handDealTicks = 4;
        int shoe = table.getDeck().remaining();
        assertTrue(shoeClick(table).isCancelled());
        advanceUntil(() -> table.getDeck().remaining() == shoe - 1);
        assertEquals(4, table.heldBy(player.getUniqueId()).size(), "the replacement is still in the air");
        // What /games reload does to every table's cards.
        manager.wipeHands();
        tick(10);
        assertEquals(other.getUniqueId(), table.actor(), "the draw passes on once the replacement is gone");
        assertTrue(rules.allowPlayChat(table, other));

        manager.applyPlayCall(other, "draw");
        assertEquals(DrawGame.BET, table.phase());
        for (int action = 0; table.live() && action < 4; action++) {
            manager.applyPlayCall(org.bukkit.Bukkit.getPlayer(table.actor()), "check");
            tick(20);
        }
        assertFalse(table.live(), "the hand still reaches its showdown and settles");
        assertTrue(table.ledger().isEmpty());
        assertEquals(2, gold(player) + gold(other));
        assertEquals(52, table.getDeck().remaining() + table.getDeck().discarded());
    }

    private void advanceUntil(BooleanSupplier complete) {
        for (int elapsed = 0; !complete.getAsBoolean() && elapsed < 100; elapsed++) tick(1);
        assertTrue(complete.getAsBoolean(), "Hand callbacks must finish within the scheduler budget");
    }

    private static int gold(PlayerMock owner) {
        return owner.getInventory().all(Material.GOLD_NUGGET).values().stream().mapToInt(ItemStack::getAmount).sum();
    }
}
