package net.tfminecraft.games.layout;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.layout.FeltClearance.Footprint;

class FeltClearanceTest {
    private static final float SCALE = 0.35f;
    private static final double HALF_WIDTH = SCALE * 5 / 16.0;
    private static final double HALF_HEIGHT = SCALE * 7 / 16.0;
    private float[] previous;

    @BeforeEach
    void flatCards() {
        previous = new float[] {Cache.tableCardYawOffset, Cache.tableCardPitch, Cache.tableCardRoll};
        Cache.tableCardYawOffset = 0;
        Cache.tableCardPitch = -90;
        Cache.tableCardRoll = 0;
    }

    @AfterEach
    void restore() {
        Cache.tableCardYawOffset = previous[0];
        Cache.tableCardPitch = previous[1];
        Cache.tableCardRoll = previous[2];
    }

    private static Footprint card(double x, double z, float yaw) {
        return FeltClearance.footprint(x, z, DisplayPose.flatOnTable(SCALE, 0f, yaw));
    }

    private static void assertSpan(double[] values, double min, double max) {
        assertEquals(min, Arrays.stream(values).min().orElseThrow(), 1e-5);
        assertEquals(max, Arrays.stream(values).max().orElseThrow(), 1e-5);
    }

    @Test
    void aFlatCardCoversItsFaceAndTurnsWithTheTable() {
        Footprint ahead = card(2, 3, 0);
        assertSpan(ahead.xs(), 2 - HALF_WIDTH, 2 + HALF_WIDTH);
        assertSpan(ahead.zs(), 3 - HALF_HEIGHT, 3 + HALF_HEIGHT);
        Footprint turned = card(0, 0, 90);
        assertSpan(turned.xs(), -HALF_HEIGHT, HALF_HEIGHT);
        assertSpan(turned.zs(), -HALF_WIDTH, HALF_WIDTH);
    }

    @Test
    void distanceIsZeroOnTheCardAndMeasuredToTheNearestEdgeOrCorner() {
        Footprint card = card(0, 0, 0);
        assertEquals(0, FeltClearance.distance(0, 0, card));
        assertEquals(0, FeltClearance.distance(HALF_WIDTH * 0.9, -HALF_HEIGHT * 0.9, card));
        assertEquals(0.3 - HALF_WIDTH, FeltClearance.distance(0.3, 0, card), 1e-5);
        assertEquals(0.4 - HALF_HEIGHT, FeltClearance.distance(0, -0.4, card), 1e-5);
        assertEquals(Math.hypot(0.3 - HALF_WIDTH, 0.4 - HALF_HEIGHT), FeltClearance.distance(0.3, 0.4, card), 1e-5);
    }

    @Test
    void aCardSquashedToAPointCoversNothingButThatPoint() {
        Footprint dot = FeltClearance.footprint(1, 1, DisplayPose.flatOnTable(0f, 0f, 0));
        assertEquals(0.5, FeltClearance.distance(1.5, 1, dot), 1e-6);
        assertEquals(0, FeltClearance.distance(1, 1, dot), 1e-6);
    }

    @Test
    void clearNeedsTheGapFromEveryCard() {
        List<Footprint> cards = List.of(card(0, 0, 0), card(1, 0, 0));
        double between = 0.5;
        assertTrue(FeltClearance.clear(between, 0, cards, 0.1));
        assertFalse(FeltClearance.clear(between, 0, cards, 0.5 - HALF_WIDTH + 0.01));
        assertFalse(FeltClearance.clear(1, 0, cards, 0.1));
    }

    @Test
    void aPileOnACardStepsJustPastItsEdge() {
        List<Footprint> cards = List.of(card(0, 0, 0));
        double[] spot = FeltClearance.nearestClear(0, 0, cards, 0.1, (x, z) -> true);
        assertTrue(FeltClearance.clear(spot[0], spot[1], cards, 0.1));
        assertTrue(Math.hypot(spot[0], spot[1]) <= HALF_WIDTH + 0.1 + FeltClearance.STEP + 1e-9,
                "The pile moves no further than it has to: " + Arrays.toString(spot));
    }

    @Test
    void theFilterDecidesWhichSideThePileStepsTo() {
        List<Footprint> cards = List.of(card(0, 0, 0));
        double[] spot = FeltClearance.nearestClear(0, 0, cards, 0.1, (x, z) -> x < 0);
        assertTrue(spot[0] < -HALF_WIDTH);
        assertTrue(FeltClearance.clear(spot[0], spot[1], cards, 0.1));
    }

    @Test
    void aPileStaysWhereItIsWhenItIsClearOrHasNowhereToGo() {
        List<Footprint> cards = List.of(card(0, 0, 0));
        assertArrayEquals(new double[] {0.5, 0}, FeltClearance.nearestClear(0.5, 0, cards, 0.1, (x, z) -> false));
        assertArrayEquals(new double[] {0, 0}, FeltClearance.nearestClear(0, 0, cards, 0.1, (x, z) -> false));
        assertArrayEquals(new double[] {0, 0}, FeltClearance.nearestClear(0, 0, cards, 0, (x, z) -> true));
        assertArrayEquals(new double[] {0, 0}, FeltClearance.nearestClear(0, 0, List.of(), 0.1, (x, z) -> true));
    }
}
