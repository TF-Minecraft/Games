package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.command.CommandManager;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.game.PokerGame;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.wager.Accounts;

class TableManagerPokerOverhaulTest extends TableManagerFixture {
    private Table poker() {
        List<Card> deck = new ArrayList<>();
        for (String suit : List.of("clubs", "hearts", "diamonds", "spades")) {
            for (int rank = 1; rank <= 13; rank++) {
                Card card = new Card(suit + rank, suit, rank, false, "face");
                deck.add(card);
                cards.when(() -> CardLoader.get(card.getId())).thenReturn(card);
            }
        }
        String set = Cache.cardSetOf("poker");
        cards.when(() -> CardLoader.hasSet(set)).thenReturn(true);
        cards.when(() -> CardLoader.getSet(set)).thenReturn(deck);
        games.when(() -> GamesRegistry.of("poker")).thenReturn(new PokerGame());
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER));
        manager.armPlace(player, "poker", true);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        return manager.tables().iterator().next();
    }

    private void command(org.mockbukkit.mockbukkit.entity.PlayerMock who, String... args) {
        who.addAttachment(Games.plugin, "games.bet", true);
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("games");
        assertTrue(new CommandManager().onCommand(who, command, "games", args));
    }

    private void buyIn(PlayerMock who) {
        who.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 10));
        command(who, "poker", "buyin");
    }

    private void shoe(Table table, PlayerMock who) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        var event = new PlayerInteractAtEntityEvent(who, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(event);
        assertTrue(event.isCancelled());
    }

    @Test void deckPlacerCanConfigureAndStartWithoutBeingADealerOrBuyingIn() {
        Table table = poker();
        assertEquals(player.getUniqueId(), table.ownerPlayer());
        assertFalse(player.isOp());
        assertFalse(player.hasPermission("games.admin"));
        assertNull(table.dealerId());
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        assertEquals(10, table.poker().buyIn());
        var first = opponent();
        var second = opponent();
        buyIn(first); buyIn(second);
        assertFalse(table.actives().contains(player.getUniqueId()));
        command(player, "poker", "start"); tick(30);
        assertTrue(table.live());
        assertTrue(table.poker().started());
        assertEquals(20, table.ledger().total());
        assertEquals(0, table.poker().stack(player.getUniqueId()));
    }

    @Test void tournamentSeatsCannotTakeHostControlsEvenWhenTheyHoldTheButton() {
        Table table = poker();
        var first = opponent();
        var second = opponent();
        command(first, "poker", "configure", "10", "100", "1", "2", "5");
        assertFalse(table.poker().enabled());
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        buyIn(first); buyIn(second);
        assertEquals(first.getUniqueId(), table.dealerId());
        command(first, "poker", "start");
        shoe(table, first);
        assertFalse(table.live());
        command(first, "poker", "kick", second.getName());
        assertTrue(table.poker().registered(second.getUniqueId()));
        table.setSmallBlind(5); table.setBigBlind(10);
        shoe(table, player); tick(30);
        assertTrue(table.live(), "the unseated deck placer can start from the shoe");
        manager.applyPlayCall(first, "fold"); tick(30);
        assertFalse(table.live());
        assertEquals(second.getUniqueId(), table.dealerId());
        assertEquals(player.getUniqueId(), table.ownerPlayer());
        command(player, "poker", "kick", first.getName()); tick(30);
        command(second, "poker", "finish");
        assertEquals(20, table.ledger().total(), "the button cannot pay itself the prize");
        command(player, "poker", "finish"); tick(30);
        assertEquals(20, Accounts.pockets(table, second).available());
        assertTrue(table.ledger().isEmpty());
    }

    @Test void hostStartWaitsForPlayersAndPayoutsAndCannotRestartALiveHand() {
        Table table = poker();
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        command(player, "poker", "start");
        assertFalse(table.live());
        var first = opponent();
        var second = opponent();
        buyIn(first);
        command(player, "poker", "start");
        assertFalse(table.live());
        buyIn(second);
        table.beginPayout(List.of(), null);
        command(player, "poker", "start");
        assertFalse(table.live());
        assertFalse(GamesRegistry.of("poker").tryClaimDealer(table, player));
        table.endPayout();
        command(player, "poker", "start"); tick(30);
        assertTrue(table.live());
        var actor = table.actor();
        int stack = table.poker().stack(first.getUniqueId());
        command(player, "poker", "start");
        assertTrue(table.live());
        assertEquals(actor, table.actor());
        assertEquals(stack, table.poker().stack(first.getUniqueId()));
    }

    @Test void savedDeckOwnerKeepsTournamentControlsAfterReload() {
        Table table = poker();
        var id = table.getId();
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        manager.despawnWorldAll();
        manager.loadAll();
        Table loaded = manager.table(id);
        assertNotSame(table, loaded);
        assertEquals(player.getUniqueId(), loaded.ownerPlayer());
        assertEquals(10, loaded.poker().buyIn());
        command(player, "poker", "configure", "20", "200", "2", "4", "10");
        assertEquals(20, loaded.poker().buyIn());
        assertNull(loaded.dealerId());
    }

    @Test void staffCanStartAnotherPlayersTournamentWithoutASeat() {
        Table table = poker();
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        buyIn(opponent()); buyIn(opponent());
        var staff = opponent();
        staff.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        command(staff, "poker", "start"); tick(30);
        assertTrue(table.live());
        assertEquals(player.getUniqueId(), table.ownerPlayer());
        assertFalse(table.actives().contains(staff.getUniqueId()));
    }

    @Test void cashSeatsCanStillStartFromTheShoeWithoutOwningTheDeck() {
        Table table = poker();
        var first = opponent();
        var second = opponent();
        stakeCoin(first, table); stakeCoin(second, table);
        shoe(table, first); tick(30);
        assertTrue(table.live());
        assertEquals(player.getUniqueId(), table.ownerPlayer());
    }

    @Test void automaticHeadsUpBlindsAndAllInsRunOutTheBoardWithoutMoreTurns() {
        Table table = poker();
        var other = opponent();
        stakeCoin(player, table); stakeCoin(other, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 19));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 19));
        table.setSmallBlind(2); table.setBigBlind(4);
        manager.beginSession(table);
        tick(30);
        assertEquals(6, table.ledger().total());
        assertEquals(2, table.ledger().total(player.getUniqueId(), 1));
        assertEquals(4, table.ledger().total(other.getUniqueId(), 1));
        assertEquals(player.getUniqueId(), table.actor(), "the button acts first heads up");
        assertFalse(GamesRegistry.of("poker").allowStake(table, other));
        manager.applyPlayCall(player, "allin");
        assertEquals(20, table.ledger().total(player.getUniqueId()));
        assertEquals(0, Accounts.pockets(table, player).available());
        manager.applyPlayCall(other, "allin");
        tick(200);
        assertFalse(table.live());
        assertTrue(table.ledger().isEmpty());
        assertEquals(40, Accounts.pockets(table, player).available() + Accounts.pockets(table, other).available());
    }

    @Test void tournamentChipsSettleBetweenHandsWhileDenarsRemainInPrizeLedger() {
        Table table = poker();
        var other = opponent();
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 10));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 10));
        command(player, "poker", "buyin"); command(other, "poker", "buyin");
        assertEquals(20, table.ledger().total());
        table.setSmallBlind(5); table.setBigBlind(10);
        manager.beginSession(table); tick(30);
        assertEquals(93, table.poker().stack(player.getUniqueId()));
        assertEquals(88, table.poker().stack(other.getUniqueId()));
        manager.applyPlayCall(player, "call");
        assertEquals(88, table.poker().stack(player.getUniqueId()));
        manager.applyPlayCall(other, "check"); tick(30);
        assertEquals(PokerGame.FLOP, table.phase());
        assertEquals(other.getUniqueId(), table.actor());
        manager.applyPlayCall(other, "fold"); tick(30);
        assertFalse(table.live());
        assertEquals(112, table.poker().stack(player.getUniqueId()));
        assertEquals(88, table.poker().stack(other.getUniqueId()));
        assertEquals(20, table.ledger().total(), "chips must never pay Denars between hands");
        assertEquals(10, table.roundMoney().moneyIn(player.getUniqueId()), "buy-in principal survives hands");
        assertEquals(0, Accounts.pockets(table, player).available());
        assertEquals(0, Accounts.pockets(table, other).available());
        command(player, "poker", "kick", other.getName());
        command(player, "poker", "finish"); tick(30);
        assertEquals(20, Accounts.pockets(table, player).available());
        assertTrue(table.ledger().isEmpty());
        assertFalse(table.poker().occupied());
    }

    @Test void shutdownRefundsTournamentEntriesAndKeepsSettingsForNextStart() {
        Table table = poker();
        var other = opponent();
        command(player, "poker", "configure", "10", "100", "1", "2", "5");
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 10));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 10));
        command(player, "poker", "buyin"); command(other, "poker", "buyin");
        manager.beginSession(table); tick(30);
        assertTrue(table.live());
        command(player, "poker", "rebuy"); // Buying during play is refused.
        assertEquals(20, table.ledger().total());
        manager.despawnWorldAll();
        assertEquals(10, Accounts.pockets(table, player).available());
        assertEquals(10, Accounts.pockets(table, other).available());
        assertTrue(table.ledger().isEmpty());
        assertFalse(table.poker().occupied());
        assertEquals(10, table.poker().buyIn());
        assertEquals(2, table.poker().ante());
    }

    @Test void failedBlindCollectionMovesNothingAndLeavesTheTableIdle() {
        Table table = poker();
        var other = opponent();
        stakeCoin(player, table); stakeCoin(other, table);
        table.setSmallBlind(2); table.setBigBlind(4);
        // One seat has enough, the other has too few. A zero-pocket seat instead goes all in.
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        Cache.wagerGold = new net.tfminecraft.games.wager.WagerItemOverride("GOLD_NUGGET", 5,
                null, null, null, null, null, null, false, null);
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        manager.beginSession(table);
        assertFalse(table.live());
        assertEquals(2, table.ledger().total());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(2, other.getInventory().getItemInMainHand().getAmount());
    }
}
