package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.game.PokerGame;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerVote;

/** Loot proposals decided by the other seats at the table. */
class TableManagerVoteTest extends TableManagerFixture {

    @Test void proposerWhoWalksAwayFromAPokerTableIsToldTheirVoteWasCancelled() {
        PokerGame rules = new PokerGame();
        games.when(() -> GamesRegistry.of("poker")).thenReturn(rules);
        manager.armPlace(player, "poker", false);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        try {
            PlayerMock voter = opponent();
            stakeCoin(player, table);
            stakeCoin(voter, table);
            propose(player, 7);
            assertNotNull(table.getVote());
            drain(player);
            drain(voter);
            // Poker takes a leaver off the seats before refunding them, which cancels the vote.
            player.teleport(table.getOrigin().clone().add(100, 0, 0));
            manager.startClock();
            tick(2);
            manager.stopClock();
            assertNull(table.getVote());
            assertFalse(table.actives().contains(player.getUniqueId()));
            assertTrue(messages(voter).contains("wager.cancelled"));
            assertTrue(messages(player).contains("wager.cancelled"),
                    "the proposer must hear that their offer is off even after leaving the seats");
            assertEquals(2, diamonds(player));
            assertEquals(1, Accounts.coins(table, player).available());
        } finally {
            rules.onTableRemoved(table);
        }
    }

    @Test void aVoterCannotChangeTheirMindOnceTheyHaveVoted() {
        Table table = place(false);
        PlayerMock first = opponent();
        PlayerMock second = opponent();
        PlayerMock third = opponent();
        for (PlayerMock seated : List.of(player, first, second, third)) stakeCoin(seated, table);
        propose(player, 7);
        WagerVote vote = table.getVote();
        manager.voteWager(first, false);
        drain(first);
        manager.voteWager(first, true);
        assertEquals("wager.already_voted", first.nextMessage());
        assertTrue(vote.yes().isEmpty());
        assertEquals(1, vote.no().size());
        assertSame(vote, table.getVote());
    }

    @Test void onlookersLeavingDoNotAffectAVoteAndCastVotesAreNotCountedAgain() {
        Table table = place(false);
        PlayerMock yes = opponent();
        PlayerMock undecided = opponent();
        PlayerMock onlooker = opponent();
        doCallRealMethod().when(game).onLeave(any(), any());
        stakeCoin(player, table);
        stakeCoin(yes, table);
        stakeCoin(undecided, table);
        propose(player, 7);
        WagerVote vote = table.getVote();
        manager.onQuit(new PlayerQuitEvent(onlooker, "left"));
        assertSame(vote, table.getVote());
        assertTrue(vote.no().isEmpty());
        manager.voteWager(yes, true);
        drain(player);
        // A counted yes stays a yes, so the vote is not declined; the leave then cancels it.
        manager.onQuit(new PlayerQuitEvent(yes, "left"));
        assertNull(table.getVote());
        assertTrue(vote.no().isEmpty());
        List<String> told = messages(player);
        assertTrue(told.contains("wager.cancelled"));
        assertFalse(told.contains("wager.declined"));
        assertEquals(2, diamonds(player));
    }

    @Test void votingWhenNobodyHasProposedAnythingSaysThereIsNoVote() {
        Table table = place(false);
        stakeCoin(player, table);
        drain(player);
        manager.voteWager(player, true);
        assertEquals("wager.no_vote", player.nextMessage());
        assertNull(table.getVote());
        assertEquals(1, table.ledger().total());
    }

    @Test void shutdownDropsAPendingVoteQuietlyAndKeepsTheOfferedLoot() {
        Table table = place(false);
        PlayerMock voter = opponent();
        stakeCoin(player, table);
        stakeCoin(voter, table);
        propose(player, 7);
        drain(player);
        drain(voter);
        manager.despawnWorldAll();
        assertNull(table.getVote());
        assertFalse(messages(player).contains("wager.cancelled"));
        assertFalse(messages(voter).contains("wager.cancelled"));
        assertEquals(2, diamonds(player));
        tick(25);
        assertNull(player.nextMessage(), "the expiry timer went with the vote");
    }

    @Test void aProposalGoesToTheTableWhereThePlayerSitsWhenAnotherTableIsPlaced() {
        PlayerMock voter = opponent();
        Table first = place(false);
        manager.armPlace(player, "freeplay", false);
        assertTrue(manager.tryPlace(player, first.getOrigin().clone().add(10, 0, 0)));
        Table second = manager.tables().stream().filter(t -> t != first).findFirst().orElseThrow();
        player.teleport(second.getOrigin());
        voter.teleport(second.getOrigin());
        stakeAt(player, second);
        stakeAt(voter, second);
        drain(voter);
        propose(player, 7);
        assertNull(first.getVote(), "the empty table gets no vote");
        assertNotNull(second.getVote());
        assertEquals(java.util.Set.of(voter.getUniqueId()), second.getVote().eligible());
        assertEquals("wager.proposed", voter.nextMessage());
    }

    private void propose(PlayerMock proposer, int denars) {
        proposer.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(proposer, denars);
    }

    private void stakeAt(PlayerMock actor, Table table) {
        actor.teleport(table.getOrigin());
        actor.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        clickFelt(actor, table);
        assertEquals(1, manager.ownedDenars(table, actor.getUniqueId()));
    }

    private static int diamonds(PlayerMock owner) {
        return owner.getInventory().all(Material.DIAMOND).values().stream().mapToInt(ItemStack::getAmount).sum();
    }

    private static List<String> messages(PlayerMock target) {
        List<String> out = new ArrayList<>();
        String message;
        while ((message = target.nextMessage()) != null) out.add(message);
        return out;
    }

    private static void drain(PlayerMock target) {
        messages(target);
    }
}
