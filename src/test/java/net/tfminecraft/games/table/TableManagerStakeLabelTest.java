package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;

/** With chips hidden, each bucket shows its amount as a floating label instead. */
class TableManagerStakeLabelTest extends TableManagerFixture {
    private final Map<UUID, TextDisplay> entities = new LinkedHashMap<>();
    /** Stake labels only; the table's own name label is spawned the same way. */
    private final Map<UUID, TextDisplay> spawned = new LinkedHashMap<>();
    private final Map<UUID, String> texts = new LinkedHashMap<>();
    private MockedStatic<Bukkit> bukkit;
    private boolean refuseSpawn;

    @BeforeEach void hideChips() {
        Cache.wagerShowChips = false;
        messages.when(() -> Messages.get(anyString(), any(String[].class))).thenAnswer(call ->
                call.getArgument(0) + Arrays.toString((String[]) call.getRawArguments()[1]));
        anchors.when(() -> WorldAnchors.spawnLabel(any(Location.class), anyString())).thenAnswer(call -> {
            if (refuseSpawn) return null;
            TextDisplay label = mock(TextDisplay.class);
            UUID id = UUID.randomUUID();
            when(label.getUniqueId()).thenReturn(id);
            entities.put(id, label);
            if (((String) call.getArgument(1)).startsWith("label.stake")) spawned.put(id, label);
            texts.put(id, call.getArgument(1));
            return label;
        });
        anchors.when(() -> WorldAnchors.setText(any(UUID.class), anyString())).thenAnswer(call -> {
            texts.put(call.getArgument(0), call.getArgument(1));
            return null;
        });
        // Labels are real entities on a server; here they are found through the spawned mocks.
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(() -> Bukkit.getEntity(any(UUID.class))).thenAnswer(call -> entities.get(call.getArgument(0)));
    }

    @AfterEach void closeServerLookup() {
        bukkit.close();
    }

    @Test void eachStakeGetsOneLabelThatFollowsItsAmountAndGoesWhenTheBucketEmpties() {
        Table table = place(false);
        stakeCoin(player, table);
        UUID mine = onlyLabel();
        assertEquals("label.stake[n, 1]", texts.get(mine));
        PlayerMock other = opponent();
        stakeCoin(other, table);
        assertEquals(2, spawned.size());
        UUID theirs = spawned.keySet().stream().filter(id -> !id.equals(mine)).findFirst().orElseThrow();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        clickFelt(player, table);
        assertEquals(2, spawned.size(), "a growing stake keeps its label");
        assertEquals("label.stake[n, 2]", texts.get(mine));
        anchors.verify(() -> WorldAnchors.move(eq(mine), any(Location.class)), atLeastOnce());
        manager.refundOwnedPiles(table, other);
        anchors.verify(() -> WorldAnchors.remove(theirs));
        anchors.verify(() -> WorldAnchors.remove(mine), never());
        manager.refundOwnedPiles(table, player);
        anchors.verify(() -> WorldAnchors.remove(mine));
        assertTrue(table.ledger().isEmpty());
    }

    @Test void aLabelThatWasKilledOrCouldNotSpawnIsDrawnAgainOnTheNextChange() {
        Table table = place(false);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        clickFelt(player, table);
        UUID first = onlyLabel();
        when(spawned.get(first).isDead()).thenReturn(true);
        clickFelt(player, table);
        anchors.verify(() -> WorldAnchors.remove(first));
        UUID second = newest();
        assertEquals("label.stake[n, 2]", texts.get(second));
        when(spawned.get(second).isDead()).thenReturn(true);
        refuseSpawn = true;
        clickFelt(player, table);
        anchors.verify(() -> WorldAnchors.remove(second));
        assertEquals(2, spawned.size(), "the replacement could not be drawn");
        refuseSpawn = false;
        clickFelt(player, table);
        assertEquals(3, spawned.size());
        assertEquals("label.stake[n, 4]", texts.get(newest()));
        assertEquals(4, table.ledger().total());
    }

    @Test void showingChipsAgainTakesTheFloatingAmountsDown() {
        Table table = place(false);
        stakeCoin(player, table);
        UUID label = onlyLabel();
        Cache.wagerShowChips = true;
        manager.redrawAllChips();
        anchors.verify(() -> WorldAnchors.remove(label));
        assertFalse(table.getPiles().isEmpty(), "the chips are drawn instead");
        Cache.wagerShowChips = false;
        manager.redrawAllChips();
        assertEquals(2, spawned.size(), "hiding them again brings a fresh label back");
        assertEquals(1, table.ledger().total());
    }

    private UUID newest() {
        return spawned.keySet().stream().reduce((first, second) -> second).orElseThrow();
    }

    private UUID onlyLabel() {
        assertEquals(1, spawned.size());
        return spawned.keySet().iterator().next();
    }
}
