package net.tfminecraft.games.layout;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Location;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.layout.TableLayout.*;

class TableLayoutTest {
    private final World world = mock(World.class);
    private final Table table = new Table(java.util.UUID.randomUUID(), "test", new Location(world, 10, 20, 30), 0, null);

    private TableLayout layout(FeltRing ring, FeltBox box, double noBet, BetZone bet) {
        return new TableLayout("cards", "label", "icon", 6, Map.of("tray", new PileSlot(3, 0)),
                ring, box, new PileSlot(2, 1), noBet, true, true, 2, 100, 12,
                null, bet, 9, 11, null, 4, 1, 2, 3, true);
    }
    private Location local(double forward, double right) { return TableLayout.fromLocal(table, forward, right); }

    @Test
    void configurationRetainsValuesAndNormalizesLimits() {
        var layout = layout(new FeltRing(1, 3), null, .5, new BetZone(.5));
        assertEquals("cards", layout.cardSet());
        assertEquals("label", layout.label());
        assertEquals("icon", layout.icon());
        assertEquals(6, layout.leaveDistance());
        assertEquals(new PileSlot(2, 1), layout.stand());
        assertEquals(.5, layout.noBetRadius());
        assertTrue(layout.dealerHitsSoft17());
        assertTrue(layout.autoDealer());
        assertEquals(2, layout.minBet());
        assertEquals(100, layout.maxBet());
        assertEquals(12, layout.betSeconds());
        assertEquals(9, layout.resultDelayTicks());
        assertEquals(11, layout.roundEndSeconds());
        assertEquals(4, layout.maxBoxes());
        assertEquals(1, layout.smallBlind());
        assertEquals(2, layout.bigBlind());
        assertEquals(3, layout.maxHandsPerBox());
        assertTrue(layout.resplitAces());
        assertEquals(new BetZone(.5), layout.betZone());
        assertEquals("rp", layout.voice().channel());
        assertNull(layout.chipFx());
        var clamped = new TableLayout("", "", "", 1, null, null, null, null, 0,
                false, false, -1, -2, -3, null, null, -4, -5, null, -6, -1, -1, -1, false);
        assertEquals(0, clamped.minBet());
        assertEquals(0, clamped.maxBet());
        assertEquals(1, clamped.betSeconds());
        assertEquals(0, clamped.resultDelayTicks());
        assertEquals(1, clamped.roundEndSeconds());
        assertEquals(0, clamped.maxBoxes());
        assertEquals(0, clamped.smallBlind());
        assertEquals(0, clamped.bigBlind());
        assertEquals(1, clamped.maxHandsPerBox());
        assertFalse(clamped.resplitAces());
        assertNull(clamped.standLocation(table));
        assertNull(clamped.trayLocation(table));
        assertFalse(clamped.inTrayZone(table, local(0, 0)));
    }

    @Test
    void shorterConstructorsPreserveTheirDefaultsAndCopyPiles() {
        Map<String, PileSlot> piles = new HashMap<>();
        piles.put("tray", new PileSlot(1, 2));
        var base = new TableLayout("a", "b", "c", 2, piles, null, null, null, .2);
        piles.clear();
        assertEquals(new PileSlot(1, 2), base.pile("TRAY"));
        assertNull(base.pile(null));
        assertNull(base.pile("missing"));
        assertThrows(UnsupportedOperationException.class, () -> base.piles().clear());
        assertFalse(base.dealerHitsSoft17());
        assertFalse(base.autoDealer());
        assertEquals(10, base.betSeconds());
        assertEquals(4, base.maxHandsPerBox());
        assertEquals(local(1, 2), base.trayLocation(table));
        var soft = new TableLayout("", "", "", 0, null, null, null, null, 0, true);
        assertTrue(soft.dealerHitsSoft17());
        var auto = new TableLayout("", "", "", 0, null, null, null, null, 0, true, true, 3, 4, 5);
        assertTrue(auto.autoDealer());
        var voice = new VoiceLines("ooc", "h", "s", "d", "p");
        var voiced = new TableLayout("", "", "", 0, null, null, null, null, 0, false, false, 0, 0, 1, voice);
        assertSame(voice, voiced.voice());
        var delayed = new TableLayout("", "", "", 0, null, null, null, null, 0, false, false, 0, 0, 1,
                voice, new BetZone(1), 4, 5);
        assertEquals(4, delayed.resultDelayTicks());
        var fx = new SoundFx(null, .4f, .8f);
        var sounded = new TableLayout("", "", "", 0, null, null, null, null, 0, false, false, 0, 0, 1,
                voice, null, 4, 5, fx);
        assertSame(fx, sounded.chipFx());
        assertNull(fx.sound());
        assertEquals(.4f, fx.volume());
        assertEquals(.8f, fx.pitch());
    }

    @Test
    void voiceKeysIgnoreCaseAndMissingLinesAreEmpty() {
        var defaults = VoiceLines.defaults();
        for (String key : new String[] {"hit", "stand", "double", "split"}) {
            assertFalse(defaults.line(key.toUpperCase(java.util.Locale.ROOT)).isEmpty());
            assertEquals("", new VoiceLines(null, null, null, null, null).line(key));
        }
        assertEquals("", defaults.line(null));
        assertEquals("", defaults.line("other"));
        assertFalse(new BetZone(0).present());
        assertFalse(new BetZone(-1).present());
        assertTrue(new BetZone(.1).present());
    }

    @Test
    void localCoordinatesRoundTripForEveryTableOrientation() {
        for (float yaw : new float[] {0, 90, 180, 270, -45, 720}) {
            var rotated = new Table(table.getId(), "", table.getOrigin(), yaw, null);
            Location hit = TableLayout.fromLocal(rotated, 2, -3);
            assertEquals(2, TableLayout.localForward(rotated, hit), 1e-10);
            assertEquals(-3, TableLayout.localRight(rotated, hit), 1e-10);
            assertEquals(20, hit.getY());
            assertEquals(new Location(world, 10, 20, 30), table.getOrigin());
        }
        assertEquals(local(2, 1), layout(null, null, 0, null).standLocation(table));
    }

    @Test
    void shoeAndTrayExclusionsRespectWorldAndStrictRadius() {
        var layout = layout(null, null, .5, null);
        assertTrue(layout.inShoeZone(table, local(0, 0)));
        assertFalse(layout.inShoeZone(table, local(.5, 0)));
        assertTrue(layout.inTrayZone(table, local(3, 0)));
        assertFalse(layout.inTrayZone(table, local(3.5, 0)));
        assertTrue(layout.inNoBetZone(table, local(0, 0)));
        assertTrue(layout.inNoBetZone(table, local(3, 0)));
        assertFalse(layout.inNoBetZone(table, local(5, 0)));
        assertFalse(layout.inShoeZone(null, local(0, 0)));
        assertFalse(layout.inTrayZone(null, local(0, 0)));
        assertFalse(layout.inShoeZone(table, null));
        assertFalse(layout.inTrayZone(table, null));
        var noWorld = new Table(table.getId(), "", new Location(null, 10, 20, 30), 0, null);
        for (var hit : new Location[] {new Location(null, 10, 20, 30), new Location(mock(World.class), 10, 20, 30)}) {
            assertFalse(layout.inShoeZone(table, hit));
            assertFalse(layout.inTrayZone(table, hit));
        }
        assertFalse(layout.inShoeZone(noWorld, local(0, 0)));
        assertFalse(layout.inTrayZone(noWorld, local(3, 0)));
        assertFalse(layout(null, null, 0, null).inShoeZone(table, local(0, 0)));
        assertFalse(layout(null, null, 0, null).inTrayZone(table, local(3, 0)));
    }

    @Test
    void feltUsesBoxWhenValidOtherwiseRingOrGlobalBounds() {
        var box = layout(null, new FeltBox(2, 1, 4, 6), 0, null);
        assertTrue(box.onFelt(table, local(5, 3)));
        assertFalse(box.onFelt(table, local(5.01, 1)));
        assertFalse(box.onFelt(table, local(2, 3.01)));
        assertEquals(local(2, 1), box.feltCenter(table));
        for (var ring : new TableLayout[] {layout(new FeltRing(1, 3), null, 0, null),
                layout(new FeltRing(1, 3), new FeltBox(0, 0, 0, 2), 0, null),
                layout(new FeltRing(1, 3), new FeltBox(0, 0, 2, 0), 0, null)}) {
            assertTrue(ring.onFelt(table, local(1, 0)));
            assertTrue(ring.onFelt(table, local(3, 0)));
            assertFalse(ring.onFelt(table, local(.9, 0)));
            assertFalse(ring.onFelt(table, local(3.1, 0)));
        }
        assertEquals(local(2, 0), layout(new FeltRing(1, 3), null, 0, null).feltCenter(table));
        var fallback = layout(null, null, 0, null);
        double mid = (Cache.wagerMinRange + Cache.wagerMaxRange) / 2;
        assertEquals(local(mid, 0), fallback.feltCenter(table));
        assertTrue(fallback.onFelt(table, local(mid, 0)));
    }

    @Test
    void betPadIsOffsetFromHandAndUsesInclusiveDisk() {
        var layout = layout(null, null, 0, new BetZone(.5));
        Location center = layout.betPadCenter(table, 10, 30, 0);
        assertEquals(local(Cache.handDistance + .5, 0), center);
        assertTrue(layout.inBetZone(center, 10, 30, 0));
        assertTrue(layout.inBetZone(center.clone().add(.5, 0, 0), 10, 30, 0));
        assertFalse(layout.inBetZone(center.clone().add(.51, 0, 0), 10, 30, 0));
        assertFalse(layout.inBetZone(null, 10, 30, 0));
        assertNull(layout.betPadCenter(null, 0, 0, 0));
        for (var disabled : new TableLayout[] {layout(null, null, 0, null), layout(null, null, 0, new BetZone(0))}) {
            assertFalse(disabled.inBetZone(center, 10, 30, 0));
            assertEquals(local(Cache.handDistance, 0), disabled.betPadCenter(table, 10, 30, 0));
        }
    }
}
