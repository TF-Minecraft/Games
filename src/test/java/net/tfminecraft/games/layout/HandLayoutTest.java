package net.tfminecraft.games.layout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.layout.TableLayout.PileSlot;
import net.tfminecraft.games.table.Table;

class HandLayoutTest {
    private static final String GAME = "layout-unit-test";
    private float[] previous;
    private int previousStackMax;
    private double previousBoardOffset;
    private Map<Integer, Integer> previousRanks;
    private TableLayout previousLayout;

    @BeforeEach
    void setUp() {
        previous = new float[] {Cache.handDistance, Cache.handSelectedDistance, Cache.handSitInset,
                Cache.handSpread, Cache.handLayerGap, Cache.handYawLimit, Cache.cardScale};
        previousStackMax = Cache.stackVisibleMax;
        previousBoardOffset = Cache.tableBoardOffset;
        previousRanks = Cache.gameRankValues.remove(GAME);
        previousLayout = Cache.tableLayouts.remove(GAME);
        Cache.handDistance = 1;
        Cache.handSelectedDistance = 2;
        Cache.handSitInset = 0.25f;
        Cache.handSpread = 0.5f;
        Cache.handLayerGap = 0.01f;
        Cache.handYawLimit = 30;
        Cache.cardScale = 0.4f;
        Cache.stackVisibleMax = 10;
        Cache.tableBoardOffset = 2;
    }

    @AfterEach
    void tearDown() {
        Cache.handDistance = previous[0];
        Cache.handSelectedDistance = previous[1];
        Cache.handSitInset = previous[2];
        Cache.handSpread = previous[3];
        Cache.handLayerGap = previous[4];
        Cache.handYawLimit = previous[5];
        Cache.cardScale = previous[6];
        Cache.stackVisibleMax = previousStackMax;
        Cache.tableBoardOffset = previousBoardOffset;
        Cache.gameRankValues.remove(GAME);
        Cache.tableLayouts.remove(GAME);
        if (previousRanks != null) Cache.gameRankValues.put(GAME, previousRanks);
        if (previousLayout != null) Cache.tableLayouts.put(GAME, previousLayout);
    }

    @Test
    void handOrderUsesMappedRanksThenSuitWithJokersLast() {
        Card ace = card("ace", 1, "clubs", false);
        Card clubs = card("clubs", 2, "clubs", false);
        Card hearts = card("hearts", 2, "hearts", false);
        Card unsuited = card("unsuited", 2, null, false);
        Card joker = card("joker", 0, null, true);
        Cache.gameRankValues.put(GAME, Map.of(1, 14));
        List<Card> cards = new ArrayList<>(List.of(joker, unsuited, ace, hearts, clubs));
        HandLayout.sort(cards, GAME);
        assertEquals(List.of(clubs, hearts, unsuited, ace, joker), cards);
    }

    @Test
    void fanCentersCardsAtAnchorWithLayerGapAndRelativeTranslation() {
        Location origin = new Location(null, 10, 20, 30);
        Location anchor = new Location(null, 11, 22, 33);
        DisplayPose left = HandLayout.fanSlot(0, 3, origin, anchor, 0, false, false, 0);
        DisplayPose middle = HandLayout.fanSlot(1, 3, origin, anchor, 0, false, false, 0);
        DisplayPose right = HandLayout.fanSlot(2, 3, origin, anchor, 0, false, false, 0);
        assertVector(1.5f, 2, 4, left.translation());
        assertVector(1, 2.01f, 4, middle.translation());
        assertVector(0.5f, 2.02f, 4, right.translation());
        assertTrue(middle.matches(HandLayout.fanSlot(1, 3, origin, anchor, 0,
                false, false, 0, HandLayout.FACE_UP_PITCH)));
    }

    @Test
    void selectionSittingAndExtraDistanceControlReach() {
        Location origin = new Location(null, 0, 0, 0);
        assertVector(-2.5f, 0, 0, HandLayout.fanSlot(0, 1, origin, origin,
                90, false, true, 0.5f).translation());
        assertVector(0, 0, 0.25f, HandLayout.fanSlot(0, 1, origin, origin,
                0, true, false, 0).translation());
        assertVector(0, 0, 1.25f, HandLayout.fanSlot(0, 1, origin, origin,
                0, true, true, 0).translation());
        Cache.handSelectedDistance = 0.5f;
        assertEquals(0.25f, HandLayout.sitDistance(true));
    }

    @Test
    void deckYawUsesHorizontalDirectionAndFallsBackWhenDirectlyAbove() {
        Location origin = new Location(null, 0, 0, 0);
        assertEquals(42, HandLayout.deckYaw(origin, new Location(null, 0, 8, 0), 42));
        assertEquals(-90, HandLayout.deckYaw(origin, new Location(null, -2, 8, 0), 42));
        assertEquals(90, HandLayout.deckYaw(origin, new Location(null, 2, 8, 0), 42));
        assertEquals(0, HandLayout.deckYaw(origin, new Location(null, 0, 8, -2), 42), 1e-6);
        assertEquals(30, HandLayout.candidatePlaceYaw(origin, new Location(null, 0, 8, -2), 42, 90));
    }

    @Test
    void yawWrappingAndClampingRespectBothBoundariesAndZeroLimit() {
        assertEquals(-179, HandLayout.wrapDegrees(181));
        assertEquals(179, HandLayout.wrapDegrees(-181));
        assertEquals(180, HandLayout.wrapDegrees(540));
        assertEquals(-180, HandLayout.wrapDegrees(-540));
        assertEquals(20, HandLayout.wrapDegrees(740));
        assertEquals(30, HandLayout.clampLookToDeck(90, 0));
        assertEquals(-30, HandLayout.clampLookToDeck(-90, 0));
        assertEquals(15, HandLayout.clampLookToDeck(15, 0));
        assertEquals(181, HandLayout.clampLookToDeck(-179, 179));
        Cache.handYawLimit = -1;
        assertEquals(10, HandLayout.clampLookToDeck(90, 10));
    }

    @Test
    void revealLineMatchesHandSlotsAndBackPreservesPosition() {
        Location origin = new Location(null, 0, 0, 0);
        assertTrue(RevealLayout.line(-1, origin, origin, 0, false, 0).isEmpty());
        assertTrue(RevealLayout.line(0, origin, origin, 0, false, 0).isEmpty());
        List<DisplayPose> line = RevealLayout.line(3, origin, origin, 45, true, 180);
        assertEquals(3, line.size());
        for (int i = 0; i < line.size(); i++) {
            assertTrue(line.get(i).matches(HandLayout.fanSlot(i, 3, origin, origin,
                    45, true, false, 0, 180)));
        }
        DisplayPose face = line.getFirst();
        DisplayPose back = RevealLayout.sandwichBack(face, 45, 180);
        assertEquals(face.translation(), back.translation());
        assertEquals(new Vector3f(Cache.cardScale + RevealLayout.SANDWICH_SCALE), back.scale());
        DisplayPose flipped = RevealLayout.withPitch(face, 45, 360);
        assertEquals(flipped.leftRotation(), back.leftRotation());
        assertFalse(face.matches(back));
    }

    @Test
    void stackLayersRoundUpAndRespectConfiguredMaximum() {
        assertEquals(0, StackLayout.visibleLayers(0, 52));
        assertEquals(0, StackLayout.visibleLayers(-1, 52));
        assertEquals(0, StackLayout.visibleLayers(1, 0));
        assertEquals(0, StackLayout.visibleLayers(1, -1));
        assertEquals(1, StackLayout.visibleLayers(1, 52));
        assertEquals(5, StackLayout.visibleLayers(26, 52));
        assertEquals(6, StackLayout.visibleLayers(27, 52));
        assertEquals(10, StackLayout.visibleLayers(104, 52));
        Cache.stackVisibleMax = 0;
        assertEquals(1, StackLayout.visibleLayers(52, 52));
    }

    @Test
    void pileFallbackRowsHaveDistinctOffsetsAndDealerFacesPlayers() {
        Table table = table(0);
        assertEquals(1, TablePileLayout.row("DEALER"));
        assertEquals(0, TablePileLayout.row("BoArD"));
        assertEquals(2, TablePileLayout.row(null));
        assertEquals(2, TablePileLayout.row("unknown"));
        assertVector(-0.5f, 0, 2, TablePileLayout.slot(table, "board", 0, 3, true).translation());
        DisplayPose dealer = TablePileLayout.slot(table, "DEALER", 0, 1, false);
        assertTrue(dealer.matches(DisplayPose.flatOnTable(Cache.cardScale, 0, 180,
                HandLayout.FACE_DOWN_PITCH).withTranslation(0, 0, 4)));
        assertVector(0, 0, 6, TablePileLayout.slot(table, null, 0, 0, true).translation());
    }

    @Test
    void configuredPileOriginRotatesWithTableAndUnknownPilesUseFallback() {
        Cache.tableLayouts.put(GAME, new TableLayout(null, null, null, 0,
                Map.of("board", new PileSlot(3, 2)), null, null, null, 0));
        Table table = table(90);
        assertEquals(new PileSlot(3, 2), TablePileLayout.pileOrigin(table, "BOARD"));
        assertVector(-3, 0, 2, TablePileLayout.slot(table, "board", 0, 1, true).translation());
        assertEquals(new PileSlot(6, 0), TablePileLayout.pileOrigin(table, "other"));
        assertEquals(new PileSlot(6, 0), TablePileLayout.pileOrigin(table, null));
    }

    private static Table table(float yaw) {
        return new Table(UUID.randomUUID(), GAME, new Location(null, 0, 0, 0), yaw, null);
    }

    private static Card card(String id, int rank, String suit, boolean joker) {
        return new Card(id, suit, rank, joker, "item");
    }

    private static void assertVector(float x, float y, float z, Vector3f actual) {
        assertEquals(x, actual.x, 1e-6);
        assertEquals(y, actual.y, 1e-6);
        assertEquals(z, actual.z, 1e-6);
    }
}
