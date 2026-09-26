package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.entity.ArmorStand;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.game.GamesRegistry;

/** Where held cards sit in front of the player, and how the fan follows them around. */
class TableManagerHandLayoutTest extends TableManagerHandFixture {
    private double oldPosStick;
    private float oldYawStick;

    @BeforeEach void rememberSticks() {
        oldPosStick = Cache.handPosStick;
        oldYawStick = Cache.handYawStick;
    }

    @AfterEach void restoreSticks() {
        Cache.handPosStick = oldPosStick;
        Cache.handYawStick = oldYawStick;
    }

    /** Save a table whose shoe deals in exactly this order. Call loadAll once they are all saved. */
    private UUID save(String gameId, double x, String... order) throws Exception {
        UUID id = UUID.randomUUID();
        JsonObject saved = new JsonObject();
        saved.addProperty("id", id.toString());
        saved.addProperty("gameId", gameId);
        saved.addProperty("world", world.getName());
        saved.addProperty("x", x);
        saved.addProperty("y", 65);
        saved.addProperty("z", 0);
        saved.addProperty("yaw", 90);
        saved.addProperty("setName", Cache.pokerCardSet);
        JsonArray remaining = new JsonArray();
        for (String card : order) remaining.add(card);
        saved.add("remaining", remaining);
        Path folder = data.resolve("Data/tables");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), saved.toString());
        return id;
    }

    private Table load(String gameId, double x, String... order) throws Exception {
        UUID id = save(gameId, x, order);
        manager.loadAll();
        return manager.table(id);
    }

    private HandCard card(Table table, String id) {
        return table.handOf(player.getUniqueId()).stream()
                .filter(held -> held.card().getId().equals(id)).findFirst().orElseThrow();
    }

    private Vector3f at(HandCard held) {
        return new Vector3f(poses.get(held.tokenId()).translation());
    }

    private static void assertSameSpot(Vector3f expected, Vector3f actual, String message) {
        assertEquals(0, expected.distance(actual), 1e-4, message);
    }

    // --------------------------------------------------------------------------- card order

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void heldCardsFanOutInRankOrderUnlessTheGameKeepsThemInDealOrder(boolean sorted) throws Exception {
        Cache.interpolationTicks = 0;
        // An unregistered game sorts like the default; the mock game keeps its deal order.
        String gameId = sorted ? "retired-game" : "unsorted-game";
        if (!sorted) games.when(() -> GamesRegistry.of("unsorted-game")).thenReturn(game);
        UUID lowId = save(gameId, 0, "one", "two");
        UUID highId = save(gameId, 20, "two", "one");
        manager.loadAll();
        Table lowFirst = manager.table(lowId);
        Table highFirst = manager.table(highId);
        player.teleport(new Location(world, 2, 65, 0, 90, 0));
        manager.dealToPlayer(lowFirst, player, 2);
        tick(3);
        Vector3f lowFirstSpot = at(card(lowFirst, "one"));
        manager.muckPlayer(lowFirst, player);
        player.teleport(new Location(world, 22, 65, 0, 90, 0));
        manager.dealToPlayer(highFirst, player, 2);
        tick(3);
        Vector3f highFirstSpot = at(card(highFirst, "one"));
        assertEquals(sorted, lowFirstSpot.distance(highFirstSpot) < 1e-4,
                sorted ? "the low card takes the same place whatever the deal order"
                        : "the first card dealt takes the first place");
    }

    // ------------------------------------------------------------- room for an incoming card

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void theFanOpensRoomExactlyWhereTheIncomingCardWillLand(boolean lowCardFirst) throws Exception {
        Cache.interpolationTicks = 0;
        Table table = load("freeplay", 0, lowCardFirst ? "one" : "two", lowCardFirst ? "two" : "one");
        player.teleport(new Location(world, 2, 65, 0, 90, 0));
        manager.dealToPlayer(table, player, 1);
        tick(3);
        HandCard first = table.handOf(player.getUniqueId()).getFirst();
        Cache.handDealTicks = 4;
        manager.dealToPlayer(table, player, 1);
        tick(2);
        Vector3f whileFlying = at(first);
        tick(10);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertSameSpot(at(first), whileFlying, "the held card does not jump when the new one lands");
    }

    @Test void aCardDealtIntoASecondHandGroupShiftsTheFirstGroupBeforeItArrives() throws Exception {
        Cache.interpolationTicks = 0;
        Table table = load("freeplay", 0, "one", "two");
        player.teleport(new Location(world, 2, 65, 0, 90, 0));
        manager.dealToPlayer(table, player, 1);
        tick(3);
        HandCard first = table.handOf(player.getUniqueId()).getFirst();
        Vector3f alone = at(first);
        Cache.handDealTicks = 4;
        manager.dealToPlayer(table, player, 1, 1, null);
        tick(2);
        Vector3f whileFlying = at(first);
        assertTrue(alone.distance(whileFlying) > 1e-4, "the first group moves aside for the second");
        tick(10);
        assertEquals(List.of(0, 1), table.handOf(player.getUniqueId()).stream().map(HandCard::slot).toList());
        assertSameSpot(at(first), whileFlying, "and stays there once the second group's card lands");
    }

    // ----------------------------------------------------------------------- following along

    @Test void theHandWaitsForTheDealAnimationBeforeFollowingThePlayer() throws InterruptedException {
        Cache.interpolationTicks = 6;
        Table table = dealt(1);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        Vector3f dealt = at(held);
        manager.startClock();
        player.teleport(player.getLocation().add(1, 0, 0));
        tick(1);
        assertSameSpot(dealt, at(held), "still settling from the deal");
        Thread.sleep(400);
        tick(1);
        manager.stopClock();
        assertEquals(dealt.x + 1, at(held).x, 1e-4, "then it follows the player");
    }

    private ArmorStand seat(Location where) {
        ArmorStand seat = world.spawn(where, ArmorStand.class);
        assertTrue(seat.addPassenger(player));
        assertTrue(player.isInsideVehicle());
        return seat;
    }

    /** Move without the dismount a teleport would cause. */
    private void shift(double dx, float yaw) {
        Location to = player.getLocation().add(dx, 0, 0);
        to.setYaw(yaw);
        player.setLocation(to);
    }

    @Test void aSeatedPlayersHandStaysPutThroughSmallShiftsAndFollowsLargerOnes() {
        Cache.interpolationTicks = 0;
        Table table = placeAndClearMessage();
        player.teleport(new Location(world, 1.5, 65, 0, 90, 0));
        seat(player.getLocation());
        manager.dealToPlayer(table, player, 1);
        tick(3);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        Vector3f seated = at(held);
        manager.startClock();
        shift(0.1, 90);
        tick(1);
        assertSameSpot(seated, at(held), "a fidget in the seat does not move the cards");
        shift(1, 90);
        tick(1);
        manager.stopClock();
        assertTrue(seated.distance(at(held)) > 0.5, "moving to another seat takes the cards along");
    }

    @Test void standingUpFromASeatReanchorsTheHandInsteadOfKeepingTheSeatedSpot() {
        Cache.interpolationTicks = 0;
        Table table = placeAndClearMessage();
        player.teleport(new Location(world, 1.5, 65, 0, 90, 0));
        ArmorStand chair = seat(player.getLocation());
        manager.dealToPlayer(table, player, 1);
        tick(3);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        Vector3f seated = at(held);
        chair.removePassenger(player);
        assertFalse(player.isInsideVehicle());
        manager.startClock();
        shift(0.05, 95);
        tick(1);
        manager.stopClock();
        assertTrue(seated.distance(at(held)) > 1e-3, "a standing hand is held at chest height, not the seat");
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void aStandingPlayersSmallTurnsAndStepsOnlyMoveTheHandWhenStickinessIsOff(boolean sticky) {
        Cache.interpolationTicks = 0;
        Cache.handPosStick = sticky ? 0.25 : 0;
        Cache.handYawStick = sticky ? 12f : 0f;
        Table table = dealt(1);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        Vector3f before = at(held);
        manager.startClock();
        shift(0.1, 95);
        tick(1);
        manager.stopClock();
        assertEquals(sticky, before.distance(at(held)) < 1e-4);
    }

    @Test void aStandingPlayerTurningFarEnoughSwingsTheHandRound() {
        Cache.interpolationTicks = 0;
        Table table = dealt(1);
        HandCard held = table.handOf(player.getUniqueId()).getFirst();
        Vector3f before = at(held);
        manager.startClock();
        shift(0, 90 + 40);
        tick(1);
        manager.stopClock();
        assertTrue(before.distance(at(held)) > 1e-3);
    }
}
