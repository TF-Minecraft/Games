package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.layout.FeltClearance;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.Stake;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.WagerItemOverride;

/** Chips are drawn beside the cards on the felt, never over them. */
class TableManagerCardGapTest extends TableManagerFixture {
    private static final double GAP = 0.1;
    private final Map<UUID, Location> origins = new HashMap<>();
    private final Map<UUID, DisplayPose> poses = new HashMap<>();
    private Map<String, TableLayout> previousLayouts;
    private double previousGap;

    @BeforeEach void trackDisplays() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
        previousGap = Cache.wagerCardGap;
        Cache.wagerCardGap = GAP;
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of("board", new TableLayout.PileSlot(0.55, 0)),
                new TableLayout.FeltRing(0.3, 1.2), null, null, 0));
        // Remember where every display is drawn, as the packet renderer does.
        doAnswer(call -> {
            origins.put(call.getArgument(0), ((Location) call.getArgument(1)).clone());
            poses.put(call.getArgument(0), call.getArgument(3));
            return true;
        }).when(display).spawn(any(), any(), any(), any());
        doAnswer(call -> {
            origins.remove(call.getArgument(0));
            poses.remove(call.getArgument(0));
            return null;
        }).when(display).despawn(any());
        doAnswer(call -> {
            if (origins.containsKey(call.getArgument(0))) {
                poses.put(call.getArgument(0), call.getArgument(1));
            }
            return null;
        }).when(display).setTransform(any(), any(), anyInt());
        when(display.worldLocation(any())).thenAnswer(call -> {
            Location at = origins.get(call.getArgument(0));
            if (at == null) return null;
            var t = poses.get(call.getArgument(0)).translation();
            return at.clone().add(t.x, t.y, t.z);
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
        Cache.wagerCardGap = previousGap;
    }

    private Location boardCard(Table table) {
        manager.dealToTable(table, "board", 1, true);
        UUID token = table.tablePile("board").getFirst().tokenId();
        return display.worldLocation(token);
    }

    private FeltClearance.Footprint footprint(Table table) {
        UUID token = table.tablePile("board").getFirst().tokenId();
        Location at = display.worldLocation(token);
        return FeltClearance.footprint(at.getX(), at.getZ(), display.poseOf(token));
    }

    private void stakeAt(PlayerMock actor, Location hit) {
        actor.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        doReturn(new RayTraceResult(hit.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent click = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                actor.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(click);
        assertTrue(click.isCancelled());
    }

    private PotPile onlyPile(Table table) {
        List<PotPile> piles = new ArrayList<>(table.getPiles());
        assertEquals(1, piles.size());
        return piles.getFirst();
    }

    /** Display translations are floats, so a card's spot is only that close to the click. */
    private static void assertAt(Location expected, double x, double z) {
        assertEquals(expected.getX(), x, 1e-6);
        assertEquals(expected.getZ(), z, 1e-6);
    }

    @Test void coinsPutOnABoardCardAreDrawnBesideItAndGoBackOnceTheCardIsGone() {
        Table table = place(false);
        Location card = boardCard(table);
        stakeAt(player, card);
        PotPile pile = onlyPile(table);
        assertAt(card, pile.homeX(), pile.homeZ());
        Stake stake = table.ledger().stakes(player.getUniqueId()).getFirst();
        assertAt(card, stake.x(), stake.z());
        assertTrue(FeltClearance.clear(pile.x(), pile.z(), List.of(footprint(table)), GAP),
                "The coins leave the card showing");
        Location drawn = display.worldLocation(pile.tokens().getFirst());
        assertEquals(pile.x(), drawn.getX(), 1e-9);
        assertEquals(pile.z(), drawn.getZ(), 1e-9);
        Location probe = card.clone();
        probe.setX(pile.x());
        probe.setZ(pile.z());
        assertTrue(Cache.layoutOf("freeplay").onFelt(table, probe), "The coins stay on the felt");

        manager.muckTable(table, "board");
        PotPile back = onlyPile(table);
        assertAt(card, back.x(), back.z());
    }

    @Test void aCardDealtOntoCoinsPushesThemAsideWithoutRedrawingTheirChips() {
        Table table = place(false);
        Location spot = table.getOrigin().clone().add(-0.55, 0, 0);
        stakeAt(player, spot);
        PotPile pile = onlyPile(table);
        assertAt(spot, pile.x(), pile.z());
        List<Float> yaws = List.copyOf(pile.layerYaws());

        Location card = boardCard(table);
        assertAt(spot, card.getX(), card.getZ());
        assertSame(pile, onlyPile(table));
        assertTrue(FeltClearance.clear(pile.x(), pile.z(), List.of(footprint(table)), GAP));
        assertEquals(yaws, pile.layerYaws(), "The chips keep how they lie");
        assertEquals(1, pile.tokens().size());
        assertNotNull(display.worldLocation(pile.tokens().getFirst()));
    }

    @Test void clickingCoinsDrawnBesideACardAddsToThatSameHeap() {
        Table table = place(false);
        Location card = boardCard(table);
        stakeAt(player, card);
        PotPile pile = onlyPile(table);
        Location visible = card.clone();
        visible.setX(pile.x());
        visible.setZ(pile.z());

        stakeAt(player, visible);
        List<Stake> stakes = table.ledger().stakes(player.getUniqueId());
        assertEquals(1, stakes.size());
        assertEquals(2, stakes.getFirst().count());
        assertAt(card, stakes.getFirst().x(), stakes.getFirst().z());
        PotPile merged = onlyPile(table);
        assertEquals(2, merged.pieces());
        assertEquals(pile.x(), merged.x(), 1e-9);
        assertEquals(pile.z(), merged.z(), 1e-9);
    }

    @Test void trayCoinsDrawnBesideACardStillHoldTheirTraySlot() {
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of("board", new TableLayout.PileSlot(0.55, 0), "tray", new TableLayout.PileSlot(0.55, 0)),
                new TableLayout.FeltRing(0.3, 1.2), null, null, 0.4));
        Cache.wagerSilver = new WagerItemOverride("IRON_NUGGET", 5, null, null, null, null,
                null, null, false, null);
        Table table = place(false);
        boardCard(table);
        WagerEngine.get().restore(table, table.getId(), new ItemStack(Material.GOLD_NUGGET), "GOLD_NUGGET", 1,
                1, 2, null, null);
        WagerEngine.get().restore(table, table.getId(), new ItemStack(Material.IRON_NUGGET), "IRON_NUGGET", 5,
                1, 2, null, null);
        manager.syncAllChips(table);
        List<PotPile> tray = new ArrayList<>(table.getPiles());
        assertEquals(2, tray.size());
        assertNotEquals(List.of(tray.get(0).homeX(), tray.get(0).homeZ()),
                List.of(tray.get(1).homeX(), tray.get(1).homeZ()), "Each kind keeps its own slot");
        for (PotPile pile : tray) {
            assertTrue(FeltClearance.clear(pile.x(), pile.z(), List.of(footprint(table)), GAP));
        }
    }

    @Test void aZeroGapLetsCoinsSitOnCardsAsBefore() {
        Cache.wagerCardGap = 0;
        Table table = place(false);
        Location card = boardCard(table);
        stakeAt(player, card);
        PotPile pile = onlyPile(table);
        assertAt(card, pile.x(), pile.z());
    }
}
