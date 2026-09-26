package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.FreePlayGame;
import net.tfminecraft.games.game.GamesRegistry;

/**
 * Hand scenarios against a display boundary that remembers what every viewer sees: the public
 * item, the owner's private item and the pose of each token.
 */
abstract class TableManagerHandFixture extends TableManagerFixture {
    final Map<UUID, DisplayPose> poses = new HashMap<>();
    final Map<UUID, ItemStack> publicItems = new HashMap<>();
    final Map<UUID, ItemStack> privateItems = new HashMap<>();
    private int oldFlip, oldStagger, oldSelect, oldInterpolation, oldFollow;
    private int spawnFailures;
    private int handSpawnFailures;

    @BeforeEach final void configureHandDisplays() {
        oldFlip = Cache.handRevealFlip;
        oldStagger = Cache.handRevealStagger;
        oldSelect = Cache.handSelectTicks;
        oldInterpolation = Cache.interpolationTicks;
        oldFollow = Cache.handFollowTicks;
        Cache.handRevealFlip = 2;
        Cache.handRevealStagger = 1;
        Cache.handSelectTicks = 3;
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(new FreePlayGame());
        when(items.getCreator().getItemFromPath("face")).thenReturn(new ItemStack(Material.DIAMOND));
        when(items.getCreator().getItemFromPath("back")).thenReturn(new ItemStack(Material.PAPER));
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            UUID token = call.getArgument(0);
            if (spawnFailures > 0 || (handSpawnFailures > 0 && token.version() == 4)) {
                if (spawnFailures > 0) spawnFailures--;
                else handSpawnFailures--;
                return false;
            }
            publicItems.put(token, call.getArgument(2));
            poses.put(token, call.getArgument(3));
            return true;
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        when(display.otherPoseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        doAnswer(call -> { poses.computeIfPresent(call.getArgument(0), (token, old) -> call.getArgument(1)); return null; })
                .when(display).setTransform(any(), any(), anyInt());
        doAnswer(call -> { poses.computeIfPresent(call.getArgument(0), (token, old) -> call.getArgument(1)); return null; })
                .when(display).setTransformSplit(any(), any(), any(), anyInt());
        doAnswer(call -> { publicItems.computeIfPresent(call.getArgument(0), (token, old) -> call.getArgument(1)); return null; })
                .when(display).setItem(any(), any());
        doAnswer(call -> {
            if (publicItems.containsKey(call.getArgument(0))) privateItems.put(call.getArgument(0), call.getArgument(2));
            return null;
        }).when(display).setItemFor(any(), any(), any());
        doAnswer(call -> { privateItems.remove(call.getArgument(0)); return null; })
                .when(display).clearItemFor(any(), any());
        doAnswer(call -> {
            UUID token = call.getArgument(0);
            poses.remove(token);
            publicItems.remove(token);
            privateItems.remove(token);
            return null;
        }).when(display).despawn(any());
        // Card picking asks where each token is in the world, which follows its current pose.
        when(display.worldLocation(any())).thenAnswer(call -> null);
    }

    @AfterEach final void restoreHandDisplays() {
        Cache.handRevealFlip = oldFlip;
        Cache.handRevealStagger = oldStagger;
        Cache.handSelectTicks = oldSelect;
        Cache.interpolationTicks = oldInterpolation;
        Cache.handFollowTicks = oldFollow;
    }

    /** Replace the two-card test set with a larger one before a table is placed. */
    void useCards(int count) {
        List<net.tfminecraft.games.card.Card> set = new java.util.ArrayList<>();
        for (int i = 1; i <= count; i++) {
            net.tfminecraft.games.card.Card card = new net.tfminecraft.games.card.Card("c" + i, "oseni", i, false, "face");
            set.add(card);
            cards.when(() -> net.tfminecraft.games.loader.CardLoader.get(card.getId())).thenReturn(card);
        }
        cards.when(() -> net.tfminecraft.games.loader.CardLoader.getSet(Cache.pokerCardSet)).thenReturn(set);
    }

    /** The renderer refuses the next display it is asked for, then recovers. */
    void failNextSpawn() {
        spawnFailures = 1;
    }

    /**
     * The renderer refuses the next hand card or courier. Those get random ids, unlike the shoe
     * and discard stacks, whose ids are derived from the table.
     */
    void failNextHandSpawn() {
        handSpawnFailures = 1;
    }

    Table placeAndClearMessage() {
        Table table = place(false);
        assertEquals("place.done", player.nextMessage());
        return table;
    }

    Table dealt(int count) {
        Table table = placeAndClearMessage();
        manager.dealToPlayer(table, player, count);
        tick(5);
        assertEquals(count, table.handOf(player.getUniqueId()).size());
        return table;
    }

    /** Put this card half a block along the player's look ray, so it is the one they point at. */
    void aimAt(HandCard held) {
        Location hit = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.5));
        when(display.worldLocation(held.tokenId())).thenReturn(hit);
    }

    void lookAway(HandCard held) {
        when(display.worldLocation(held.tokenId())).thenReturn(null);
    }

    PlayerInteractEvent handClick(Action action) {
        Player input = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(input).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(input, action, null, null, null, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        return event;
    }

    /** Toggle selection on a card, waiting out the per-click cooldown first. */
    void select(HandCard held) throws InterruptedException {
        waitOutClickCooldown();
        aimAt(held);
        assertTrue(handClick(Action.RIGHT_CLICK_AIR).isCancelled());
        lookAway(held);
        tick(4);
    }

    /** The select and inspect cooldown is measured in wall-clock time, not server ticks. */
    static void waitOutClickCooldown() throws InterruptedException {
        Thread.sleep(220);
    }

    PlayerSwapHandItemsEvent swap() {
        return swap(player);
    }

    PlayerSwapHandItemsEvent swap(Player who) {
        PlayerSwapHandItemsEvent event = new PlayerSwapHandItemsEvent(who,
                new ItemStack(Material.AIR), new ItemStack(Material.AIR));
        manager.onSwapHands(event);
        return event;
    }

    PlayerInteractAtEntityEvent shoeClick(Table table) {
        return shoeClick(player, table);
    }

    PlayerInteractAtEntityEvent shoeClick(Player who, Table table) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(who, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(event);
        return event;
    }

    boolean isPublic(HandCard held) {
        return publicItems.get(held.tokenId()) != null
                && publicItems.get(held.tokenId()).getType() == Material.DIAMOND;
    }

    void assertPrivate(List<HandCard> hand) {
        for (HandCard held : hand) {
            assertEquals(Material.PAPER, publicItems.get(held.tokenId()).getType());
            assertEquals(Material.DIAMOND, privateItems.get(held.tokenId()).getType());
        }
    }

    void assertAllCardsAccountedFor(Table table, int total) {
        int held = table.getHands().values().stream().mapToInt(List::size).sum();
        assertEquals(total, held + table.getDeck().remaining() + table.getDeck().discarded(),
                "every card is in a hand, the shoe or the discard pile");
    }
}
