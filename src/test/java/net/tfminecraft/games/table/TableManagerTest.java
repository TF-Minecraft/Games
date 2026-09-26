package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.PotPile;

class TableManagerTest extends TableManagerFixture {
    @Test void placementRequiresArmingWorldAndDeckThenConsumesExactlyOneItem() throws Exception {
        Location at = new Location(world, 0, 65, 0);
        assertFalse(manager.tryPlace(player, at));
        manager.armPlace(player, "freeplay");
        assertFalse(manager.tryPlace(player, null));
        assertFalse(manager.tryPlace(player, new Location(null, 0, 0, 0)));
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(false);
        assertTrue(manager.tryPlace(player, at));
        assertTrue(manager.tables().isEmpty());
        assertEquals("place.need_deck", player.nextMessage());
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 3));
        Table table = place(true);
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(player.getUniqueId(), table.ownerPlayer());
        assertEquals("freeplay", table.getGameId());
        assertEquals(90, table.getYaw());
        assertEquals(65 + Cache.tableYOffset, table.getOrigin().getY());
        assertEquals(2, table.getDeck().remaining());
        assertEquals(Cache.stackVisibleMax, table.getStackTokens().size());
        assertSame(table, manager.table(table.getId()));
        verify(game).onTableReady(table);
        assertTrue(Files.exists(data.resolve("Data/tables/" + table.getId() + ".json")));
        assertFalse(manager.tryPlace(player, at), "Placement arm must be consumed");
    }

    @Test void overlappingPlacementAndFailedSpawnDoNotConsumeInventoryOrPersistTables() throws Exception {
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 3));
        Table first = place(false);
        manager.armPlace(player, "freeplay", true);
        assertTrue(manager.tryPlace(player, first.getOrigin()));
        assertEquals(1, manager.tables().size());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        when(display.spawn(any(), any(), any(), any())).thenReturn(false);
        assertTrue(manager.tryPlace(player, new Location(world, 10, 65, 0)));
        assertEquals(1, manager.tables().size());
        assertEquals(3, player.getInventory().getItemInMainHand().getAmount());
        try (var paths = Files.list(data.resolve("Data/tables"))) { assertEquals(1, paths.count()); }
    }

    @Test void missingCardSetDoesNotCreateTableAndArmCanBeRetried() {
        cards.when(() -> CardLoader.hasSet(Cache.pokerCardSet)).thenReturn(false);
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        assertTrue(manager.tables().isEmpty());
        assertEquals("place.no_deck", player.nextMessage());
        cards.when(() -> CardLoader.hasSet(Cache.pokerCardSet)).thenReturn(true);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        assertEquals(1, manager.tables().size());
    }

    @Test void savedIdleTableRestoresIdentityOwnershipAndDeckOrder() throws Exception {
        Table original = place(false);
        UUID id = original.getId();
        List<String> order = original.getDeck().remainingIds();
        // Simulate a fresh process's empty registry; persisted JSON is untouched.
        Field tables = TableManager.class.getDeclaredField("tables");
        tables.setAccessible(true);
        ((Map<?, ?>) tables.get(manager)).clear();
        manager.loadAll();
        Table restored = manager.table(id);
        assertNotNull(restored);
        assertNotSame(original, restored);
        assertEquals(original.getOrigin(), restored.getOrigin());
        assertEquals(original.ownerPlayer(), restored.ownerPlayer());
        assertEquals(order, restored.getDeck().remainingIds());
        assertEquals(ShufflePolicy.SHOE, restored.shufflePolicy());
        assertFalse(restored.live());
        verify(game).onTableReady(restored);
    }

    @Test void nearbyLookupRespectsDistanceWorldAndHeight() {
        Table table = place(false);
        player.teleport(table.getOrigin().clone().add(0, 0, 1));
        assertSame(table, manager.tableNearby(player));
        player.teleport(table.getOrigin().clone().add(0, 10, 0));
        assertNull(manager.tableNearby(player));
        player.teleport(table.getOrigin().clone().add(100, 0, 0));
        assertNull(manager.tableNearby(player));
        player.teleport(new Location(MockBukkit.getMock().addSimpleWorld("other-" + UUID.randomUUID()), 0, 65, 0));
        assertNull(manager.tableNearby(player));
        assertNull(manager.table(null));
        assertNull(manager.table(UUID.randomUUID()));
    }

    @Test void sessionLifecycleCallsGameHooksAndClearsRoundProgress() {
        Table table = place(false);
        manager.beginSession(table);
        assertTrue(table.live());
        verify(game).onSessionStart(table);
        manager.beginSession(table);
        verify(game, times(1)).onSessionStart(table);
        table.setActor(player.getUniqueId());
        table.setPhase("play");
        manager.endSession(table);
        assertFalse(table.live());
        assertNull(table.actor());
        assertNull(table.phase());
        verify(game).onSessionEnd(table);
    }

    @Test void dealtCardsStartPrivateThenPublishingRevealsFacesWithoutChangingCards() {
        Table table = place(false);
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 2, complete);
        tick(5);
        List<HandCard> hand = table.handOf(player.getUniqueId());
        assertEquals(2, hand.size());
        assertEquals(0, table.getDeck().remaining());
        assertTrue(hand.stream().noneMatch(HandCard::faceUp));
        assertEquals(catalog.stream().map(Card::getId).sorted().toList(),
                hand.stream().map(held -> held.card().getId()).sorted().toList());
        verify(complete).run();
        for (HandCard card : hand) verify(display).setItemFor(eq(card.tokenId()), eq(player), any());
        List<UUID> tokens = hand.stream().map(HandCard::tokenId).toList();
        manager.publishHand(table, player);
        assertTrue(hand.stream().allMatch(HandCard::faceUp));
        assertEquals(tokens, hand.stream().map(HandCard::tokenId).toList());
        for (UUID token : tokens) verify(display).clearItemFor(token, player);
        manager.relayoutHand(table, player);
        assertEquals(2, hand.size());
    }

    @Test void revealingAndMuckingPublicPilesRecyclesExactlyTheDeck() {
        Table table = place(false);
        manager.dealToTable(table, "BOARD", 1, false);
        tick(2);
        HandCard dealt = table.tablePile("board").getFirst();
        assertFalse(dealt.faceUp());
        manager.revealTablePile(table, "BOARD");
        assertTrue(dealt.faceUp());
        manager.muckTable(table, "BOARD");
        assertTrue(table.tablePilesEmpty());
        verify(display).despawn(dealt.tokenId());
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
        manager.dealToTable(table, "board", 2, true);
        tick(10);
        assertEquals(2, table.tablePile("board").size());
        assertEquals(0, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        manager.muckPlayer(table, player);
        manager.muckTable(table, "board");
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        assertTrue(table.tablePilesEmpty());
        assertFalse(table.isRecycling());
    }

    @Test void displayFailureReturnsDrawnCardToDiscardAndCompletesDealCallback() {
        Table table = place(false);
        ItemStack rejectedFace = new ItemStack(Material.DIAMOND);
        when(items.getCreator().getItemFromPath("face")).thenReturn(rejectedFace);
        when(display.spawn(any(), any(), eq(rejectedFace), any())).thenReturn(false);
        Runnable complete = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, complete);
        assertTrue(table.tablePile("board").isEmpty());
        assertEquals(1, table.getDeck().remaining());
        assertEquals(1, table.getDeck().discarded());
        verify(complete).run();
        when(display.spawn(any(), any(), any(), any())).thenReturn(true);
        manager.reshuffleFull(table);
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void animatedDealsFinishOnceAndDoNotDuplicateCardsWhileQueued() {
        Cache.handDealTicks = 2;
        Table table = place(false);
        Runnable first = mock(Runnable.class);
        Runnable second = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, first);
        manager.dealToTable(table, "dealer", 1, false, second);
        assertEquals(1, table.getDeck().remaining());
        assertTrue(table.tablePile("board").isEmpty());
        tick(20);
        assertEquals(1, table.tablePile("board").size());
        assertEquals(1, table.tablePile("dealer").size());
        assertTrue(table.tablePile("board").getFirst().faceUp());
        assertFalse(table.tablePile("dealer").getFirst().faceUp());
        assertNotEquals(table.tablePile("board").getFirst().card().getId(),
                table.tablePile("dealer").getFirst().card().getId());
        verify(first).run();
        verify(second).run();
    }

    @Test void failedArrivalDoesNotLeaveTheTableDealQueueBlocked() {
        Cache.handDealTicks = 2;
        Table table = place(false);
        ItemStack face = new ItemStack(Material.DIAMOND);
        when(items.getCreator().getItemFromPath("face")).thenReturn(face);
        java.util.concurrent.atomic.AtomicBoolean rejectArrival = new java.util.concurrent.atomic.AtomicBoolean(true);
        when(display.spawn(any(), any(), eq(face), any())).thenAnswer(call ->
                !(((Location) call.getArgument(1)).equals(table.getOrigin()) && rejectArrival.getAndSet(false)));
        Runnable failedRequestDone = mock(Runnable.class);
        Runnable nextRequestDone = mock(Runnable.class);
        manager.dealToTable(table, "board", 2, true, failedRequestDone);
        tick(10);
        assertTrue(table.tablePile("board").isEmpty());
        assertEquals(1, table.getDeck().discarded());
        manager.dealToTable(table, "dealer", 1, true, nextRequestDone);
        tick(20);
        assertEquals(1, table.tablePile("dealer").size(), "A failed display must not permanently block later deals");
        assertEquals(1, table.getDeck().discarded());
        verify(failedRequestDone).run();
        verify(nextRequestDone).run();
    }

    @Test void missingItemAfterRecycleCompletesTheRequestAndDoesNotBlockFutureDeals() {
        Table table = place(false);
        // A valid exhausted-shoe state, also obtainable when restoring a saved table.
        while (table.getDeck().remaining() > 0) table.getDeck().discard(table.getDeck().draw().orElseThrow());
        manager.rebuildAllStacks();
        Cache.tableRecycleTicks = 3;
        Runnable complete = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, complete);
        assertTrue(table.isRecycling());
        when(items.getCreator().getItemFromPath("back")).thenReturn(null);
        tick(10);
        verify(complete).run();
        assertEquals(2, table.getDeck().remaining());
        assertTrue(table.tablePile("board").isEmpty());
        when(items.getCreator().getItemFromPath("back")).thenReturn(new ItemStack(Material.PAPER));
        Runnable next = mock(Runnable.class);
        manager.dealToTable(table, "board", 1, true, next); tick(5);
        assertEquals(1, table.tablePile("board").size());
        verify(next).run();
    }

    @Test void animatedRecycleRestoresTheShoeForTheNextDealWithoutLosingCards() {
        Table table = place(false);
        Cache.tableRecycleTicks = 3;
        manager.dealToPlayer(table, player, 2); tick(5);
        manager.muckPlayer(table, player);
        assertTrue(table.isRecycling());
        assertEquals(2, table.getDeck().discarded());
        Runnable complete = mock(Runnable.class);
        tick(8);
        assertFalse(table.isRecycling());
        assertEquals(2, table.getDeck().remaining());
        manager.dealToTable(table, "board", 2, true, complete); tick(10);
        assertEquals(2, table.tablePile("board").size());
        assertEquals(0, table.getDeck().discarded());
        assertEquals(0, table.getDeck().remaining());
        verify(complete).run();
    }

    @Test void animatedPlayerDealKeepsCardsInRequestedHandGroupAndCompletesOnce() {
        Cache.handDealTicks = 2;
        Table table = place(false);
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 2, 1, complete);
        assertTrue(table.handOf(player.getUniqueId()).isEmpty());
        assertEquals(1, table.getDeck().remaining());
        tick(25);
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertTrue(table.handOf(player.getUniqueId()).stream().allMatch(card -> card.slot() == 1));
        assertEquals(0, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
        verify(complete).run();
    }

    @Test void muckingDuringADealReturnsPendingCardsAndLateAnimationCannotResurrectThem() {
        Cache.handDealTicks = 5;
        Table table = place(false);
        manager.dealToPlayer(table, player, 1);
        assertEquals(1, table.getDeck().remaining());
        manager.muckPlayer(table, player);
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
        assertEquals(2, table.getDeck().remaining());
        tick(20);
        assertFalse(table.getHands().containsKey(player.getUniqueId()));
        assertEquals(2, table.getDeck().remaining());
        assertEquals(0, table.getDeck().discarded());
    }

    @Test void aDealStoppedMidFlightStillCompletesOnceAndDealsNoMoreCards() {
        Cache.handDealTicks = 5;
        Table table = place(false);
        Runnable complete = mock(Runnable.class);
        manager.dealToPlayer(table, player, 2, complete);
        assertEquals(1, table.getDeck().remaining(), "the first card is in the air");
        manager.muckPlayer(table, player);
        tick(20);
        verify(complete, times(1)).run();
        assertFalse(table.getHands().containsKey(player.getUniqueId()), "the second card is not dealt");
        assertEquals(2, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void clickingCoinStakesOneItemAndRefundClearsBothLedgerAndChipDisplays() {
        Table table = place(false);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 3));
        clickFelt(player, table);
        assertEquals(2, Accounts.coins(table, player).available());
        assertEquals(1, manager.ownedDenars(table, player.getUniqueId()));
        assertTrue(table.actives().contains(player.getUniqueId()));
        assertEquals(1, table.getPiles().stream().mapToInt(PotPile::pieces).sum());
        assertEquals(0.75, table.ledger().stakes(player.getUniqueId()).getFirst().x());
        manager.refundOwnedPiles(table, player);
        assertEquals(3, Accounts.coins(table, player).available());
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.getPiles().isEmpty());
        assertFalse(table.ledger().hasAnchor(player.getUniqueId()));
        assertFalse(table.isPaying());
    }

    @Test void streetCommitFoldsOnlyCurrentStreetWhenBetCannotMatchThenAllowsMatchedCommit() {
        Table table = place(false);
        stakeCoin(player, table);
        table.setStreet(2);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        clickFelt(player, table);
        when(game.denarsToMatch(table, player)).thenReturn(2);
        manager.commitStreet(player);
        verify(game).onStreetCommit(table, player, 1, true);
        assertEquals(1, table.ledger().total(player.getUniqueId(), 1));
        assertEquals(0, table.ledger().total(player.getUniqueId(), 2));
        assertEquals(1, Accounts.coins(table, player).available());
        clickFelt(player, table);
        when(game.denarsToMatch(table, player)).thenReturn(1);
        manager.commitStreet(player);
        verify(game).onStreetCommit(table, player, 1, false);
        assertEquals(2, table.ledger().total());
        assertEquals(0, Accounts.coins(table, player).available());
    }

    @Test void acceptedLootVoteRequiresMatchingItemsThenStakesTheirDeclaredValue() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        assertNotNull(table.getVote());
        assertEquals(2, table.ledger().total());
        manager.voteWager(other, true);
        assertNull(table.getVote());
        player.getInventory().setItemInMainHand(new ItemStack(Material.EMERALD, 2));
        clickFelt(player, table);
        assertEquals(2, table.ledger().total());
        assertEquals(Material.EMERALD, player.getInventory().getItemInMainHand().getType());
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        clickFelt(player, table);
        assertEquals(21, table.ledger().total(player.getUniqueId()));
        assertEquals(22, table.ledger().total());
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        manager.payout(table, player);
        assertEquals(2, Accounts.coins(table, player).available());
        assertEquals(2, player.getInventory().all(Material.DIAMOND).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.actives().isEmpty());
    }

    @Test void declinedOrExpiredLootVoteNeverRemovesProposersItems() {
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 10);
        manager.voteWager(other, false);
        assertNull(table.getVote());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        manager.proposeLoot(player, 10);
        assertNotNull(table.getVote());
        tick(21);
        assertNull(table.getVote());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(2, table.ledger().total());
    }

    @Test void payoutAnimationsMoveOnlyVisualChipsAfterMoneyHasAlreadyReachedWinner() {
        Cache.wagerPayoutTicks = 3;
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        manager.payout(table, player);
        assertEquals(2, Accounts.coins(table, player).available());
        assertTrue(table.ledger().isEmpty());
        assertTrue(table.isPaying());
        assertFalse(table.payoutFlying().isEmpty());
        manager.payout(table, player);
        assertEquals(2, Accounts.coins(table, player).available());
        tick(20);
        assertFalse(table.isPaying());
        assertTrue(table.payoutFlying().isEmpty());
        assertEquals(2, Accounts.coins(table, player).available());
        assertTrue(table.getPiles().isEmpty());
    }
}
