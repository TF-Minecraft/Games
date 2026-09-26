package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.Messages;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.table.HandCard;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.wager.WagerEngine;

/**
 * Hold'em seats, button, holes, streets, and board deal.
 */
public final class PokerGame implements Game {

    public static final String PREFLOP = "preflop";
    public static final String FLOP = "flop";
    public static final String TURN = "turn";
    public static final String RIVER = "river";
    public static final String SHOWDOWN = "showdown";

    private static final class Street {
        int currentBet;
        final Set<UUID> folded = new HashSet<>();
        final Set<UUID> acted = new HashSet<>();
        final Set<UUID> capped = new HashSet<>();
    }

    private final Map<UUID, Street> streets = new HashMap<>();

    @Override
    public boolean showStockDealer() {
        return false;
    }

    @Override
    public boolean allowFreeDraw(Table table, Player player) {
        return !table.live();
    }

    @Override
    public boolean allowReturnSelected(Table table, Player player) {
        return !table.live();
    }

    /** TableManager only offers the shoe for claiming while the table is idle. */
    @Override
    public boolean tryClaimDealer(Table table, Player player) {
        if (!table.actives().contains(player.getUniqueId())) {
            return false;
        }
        if (table.actives().size() < 2) {
            return false;
        }
        TableManager.get().beginSession(table);
        return true;
    }

    @Override
    public void onSessionStart(Table table) {
        List<Player> online = seatedOnline(table);
        if (online.size() < 2) {
            for (Player player : online) {
                player.sendMessage(Messages.get("poker.need_players"));
            }
            TableManager.get().endSession(table);
            return;
        }
        if (table.shufflePolicy() == ShufflePolicy.ROUND) {
            TableManager.get().reshuffleFull(table);
        }
        table.setPhase(PREFLOP);
        table.setStreet(1);
        table.setActor(null);
        TableManager.get().refreshLabel(table);
        dealHoles(table, holeQueue(table), 0);
    }

    @Override
    public void onSessionEnd(Table table) {
        streets.remove(table.getId());
        TableManager manager = TableManager.get();
        for (UUID id : new ArrayList<>(table.getHands().keySet())) {
            manager.muckPlayer(table, id);
        }
        manager.refreshLabel(table);
    }

    @Override
    public void onTableRemoved(Table table) {
        streets.remove(table.getId());
    }

    /**
     * A street exists from the first bet until the hand is settled, and the turn only ever
     * belongs to a seat while it does. An idle table has no phase.
     */
    @Override
    public boolean allowPlayChat(Table table, Player player) {
        UUID id = player.getUniqueId();
        // A leaver stays the actor until their refund lands, but can no longer act.
        return bettingPhase(table.phase()) && id.equals(table.actor()) && table.actives().contains(id);
    }

    /** TableManager hands words only to the actor that allowPlayChat accepted. */
    @Override
    public void onPlayWord(Table table, Player player, String word) {
        Street street = streets.get(table.getId());
        UUID id = player.getUniqueId();
        int contrib = streetContrib(table, id);
        switch (word) {
            case "check" -> {
                if (contrib < street.currentBet) {
                    player.sendMessage(Messages.get("poker.cannot_check"));
                    return;
                }
                player.sendMessage(Messages.get("poker.checked"));
            }
            case "call" -> {
                if (contrib < street.currentBet) {
                    street.capped.add(id);
                } else {
                    street.capped.remove(id);
                }
                player.sendMessage(Messages.get("poker.called"));
            }
            case "raise" -> {
                if (contrib <= street.currentBet) {
                    player.sendMessage(Messages.get("poker.need_chips"));
                    return;
                }
                street.currentBet = contrib;
                street.acted.clear();
                street.capped.remove(id);
                player.sendMessage(Messages.get("poker.raised", "n", String.valueOf(street.currentBet)));
            }
            case "fold" -> {
                street.folded.add(id);
                TableManager.get().muckPlayer(table, id);
                player.sendMessage(Messages.get("poker.folded"));
            }
            default -> {
                return;
            }
        }
        street.acted.add(id);
        finishOrAdvance(table, new ArrayList<>(table.actives()), true);
    }

    @Override
    public void onFeltPilesChanged(Table table) {
        TableManager.get().refreshLabel(table);
    }

    @Override
    public void onChipIn(Table table, Player player) {
        if (table.dealerId() == null) {
            table.setDealerId(player.getUniqueId());
        }
        ensureButton(table);
        TableManager.get().refreshLabel(table);
    }

    @Override
    public void onChipIn(Table table, Player player, int denars, ItemStack item) {
        onChipIn(table, player);
    }

    @Override
    public void onTableReady(Table table) {
        ensureButton(table);
        TableManager.get().refreshLabel(table);
    }

    @Override
    public String extraLabel(Table table) {
        List<String> lines = new ArrayList<>();
        int small = table.smallBlind();
        int big = table.bigBlind();
        if (small > 0 || big > 0) {
            lines.add(Messages.get("label.blinds",
                    "small", String.valueOf(small),
                    "big", String.valueOf(big)));
        }
        lines.add(Messages.get(table.shufflePolicy() == ShufflePolicy.ROUND
                ? "label.shuffle_round" : "label.shuffle_shoe"));
        UUID button = table.dealerId();
        if (button != null) {
            lines.add(Messages.get("label.button", "name", RpNames.of(button)));
        }
        if (table.live()) {
            lines.add(streetLabel(table.phase()));
            UUID actor = table.actor();
            if (actor != null) {
                lines.add(Messages.get("label.turn", "name", RpNames.of(actor)));
                Street street = streets.get(table.getId());
                int toCall = Math.max(0, street.currentBet - streetContrib(table, actor));
                lines.add(Messages.get("label.holdem_tocall", "n", String.valueOf(toCall)));
            }
        }
        String pot = PotLabel.lines(table);
        if (!pot.isEmpty()) {
            lines.add(pot);
        }
        return String.join("\n", lines);
    }

    void passButton(Table table) {
        table.setDealerId(SeatOrder.next(table, table.dealerId()));
    }

    private void ensureButton(Table table) {
        if (!table.actives().contains(table.dealerId())) {
            table.setDealerId(SeatOrder.next(table, null));
        }
    }

    @Override
    public void onLeave(Table table, Player player) {
        TableManager manager = TableManager.get();
        UUID leaver = player.getUniqueId();
        List<UUID> before = new ArrayList<>(table.actives());
        boolean live = table.live();
        Street street = streets.get(table.getId());
        if (street != null) {
            street.folded.remove(leaver);
            street.acted.remove(leaver);
            street.capped.remove(leaver);
        }
        table.actives().remove(leaver);
        if (leaver.equals(table.dealerId())) {
            // The button goes to the next seat round from where the leaver sat.
            table.setDealerId(SeatOrder.first(SeatOrder.after(before, leaver), table.actives()::contains));
        }
        TableManager.get().refreshLabel(table);
        int streetId = table.street();
        List<PayoutFlight> flights = new ArrayList<>();
        // A leaver gets this street's bet back; earlier streets stay in the pot.
        WagerEngine.get().refundStreet(table, leaver, streetId, flights, "player left");
        UUID rest = null;
        if (table.actives().size() == 1) {
            rest = table.actives().iterator().next();
        } else if (table.actives().isEmpty()) {
            rest = leaver;
        }
        if (rest != null) {
            // Nobody left to play for it, so the pot goes to the last seat.
            WagerEngine.get().sweepPot(table, Bukkit.getPlayer(rest), rest, flights, "hand abandoned");
        }
        boolean stop = live && table.actives().size() < 2;
        manager.flushPiles(table, flights, () -> {
            if (stop) {
                TableManager.get().endSession(table);
            } else {
                finishOrAdvance(table, before, false);
            }
        });
    }

    private static List<Player> seatedOnline(Table table) {
        List<Player> online = new ArrayList<>();
        for (UUID id : table.actives()) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                online.add(player);
            }
        }
        return online;
    }

    private static List<Player> holeQueue(Table table) {
        List<UUID> order = SeatOrder.leftOfButton(table);
        List<Player> queue = new ArrayList<>();
        for (int round = 0; round < 2; round++) {
            for (UUID id : order) {
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    queue.add(player);
                }
            }
        }
        return queue;
    }

    private void dealHoles(Table table, List<Player> queue, int index) {
        Table still = TableManager.get().table(table.getId());
        if (still == null || !still.live()) {
            return;
        }
        if (index >= queue.size()) {
            startStreet(still);
            return;
        }
        Player player = queue.get(index);
        TableManager.get().dealToPlayer(still, player, 1, () -> dealHoles(still, queue, index + 1));
    }

    private void startStreet(Table table) {
        Street street = new Street();
        streets.put(table.getId(), street);
        table.setActor(leftOfButton(table, street));
        TableManager.get().refreshLabel(table);
    }

    private static boolean bettingPhase(String phase) {
        return PREFLOP.equals(phase) || FLOP.equals(phase) || TURN.equals(phase) || RIVER.equals(phase);
    }

    private static String streetLabel(String phase) {
        if (FLOP.equals(phase)) {
            return Messages.get("label.holdem_flop");
        }
        if (TURN.equals(phase)) {
            return Messages.get("label.holdem_turn");
        }
        if (SHOWDOWN.equals(phase)) {
            return Messages.get("label.holdem_showdown");
        }
        if (RIVER.equals(phase)) {
            return Messages.get("label.holdem_river");
        }
        return Messages.get("label.holdem_preflop");
    }

    /** Opens betting on the street whose cards just landed. */
    private void resumeBetting(Table table, Street street) {
        // A hand that was settled, ended or removed during the animation takes no more bets.
        if (streets.get(table.getId()) != street) {
            return;
        }
        street.currentBet = 0;
        street.acted.clear();
        street.capped.clear();
        table.setActor(leftOfButton(table, street));
        TableManager.get().refreshLabel(table);
    }

    /** Deals the next street, or goes to showdown after the river. Only called mid-hand. */
    private void advanceBoard(Table table, Street street) {
        String phase = table.phase();
        if (RIVER.equals(phase)) {
            showdown(table, street);
            return;
        }
        int cards;
        String nextPhase;
        int nextStreet;
        String announce;
        if (PREFLOP.equals(phase)) {
            nextPhase = FLOP;
            nextStreet = 2;
            cards = 3;
            announce = "poker.flop";
        } else if (FLOP.equals(phase)) {
            nextPhase = TURN;
            nextStreet = 3;
            cards = 1;
            announce = "poker.turn";
        } else {
            nextPhase = RIVER;
            nextStreet = 4;
            cards = 1;
            announce = "poker.river";
        }
        table.setPhase(nextPhase);
        table.setStreet(nextStreet);
        street.currentBet = 0;
        street.acted.clear();
        street.capped.clear();
        tellSeated(table, Messages.get(announce));
        TableManager.get().refreshLabel(table);
        TableManager.get().dealToTable(table, "board", cards, true, () -> resumeBetting(table, street));
    }

    /** The first seat after the button still betting this street. */
    private static UUID leftOfButton(Table table, Street street) {
        return SeatOrder.first(SeatOrder.leftOfButton(table), id -> betting(street, id));
    }

    private static boolean betting(Street street, UUID id) {
        return !street.folded.contains(id) && !street.capped.contains(id);
    }

    private void showdown(Table table, Street street) {
        // Betting is over, so nothing late can settle this hand a second time.
        streets.remove(table.getId());
        table.setPhase(SHOWDOWN);
        table.setActor(null);
        TableManager manager = TableManager.get();
        manager.refreshLabel(table);
        tellSeated(table, Messages.get("poker.showdown"));
        List<UUID> live = liveSeats(table, street);
        for (UUID id : live) {
            // A seat restored from disk can still be in the hand while offline.
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                manager.publishHand(table, player);
            }
        }
        List<Card> board = boardCards(table);
        for (String line : HandTalk.bestHand(table.getGameId(), live, id -> {
            List<Card> cards = new ArrayList<>(board);
            cards.addAll(cardsOf(table.heldBy(id)));
            return cards;
        })) {
            tellSeated(table, line);
        }
        payPots(table, live, board, table.getGameId());
    }

    private static List<Card> boardCards(Table table) {
        return cardsOf(table.tablePile("board"));
    }

    private void announceWinners(Table table, List<UUID> winners) {
        if (winners.size() == 1) {
            tellSeated(table, Messages.get("poker.win", "name", RpNames.of(winners.get(0))));
            return;
        }
        List<String> names = new ArrayList<>();
        for (UUID id : winners) {
            names.add(RpNames.of(id));
        }
        tellSeated(table, Messages.get("poker.chop", "names", String.join(", ", names)));
    }

    private void payPots(Table table, List<UUID> live, List<Card> board, String gameId) {
        // The ledger only reports owners with money on the felt, so every total is positive.
        Map<UUID, Integer> invested = WagerEngine.get().totalsExcept(table, table.getId());
        TreeSet<Integer> levels = new TreeSet<>(invested.values());
        if (levels.isEmpty()) {
            finishHand(table);
            return;
        }
        List<PayoutFlight> flights = new ArrayList<>();
        // Showdown supplies at least two live seats, all still in the seating order.
        UUID leftover = SeatOrder.leftOfButton(table, live).getFirst();
        int previous = 0;
        for (int level : levels) {
            int covered = 0;
            for (int put : invested.values()) {
                if (put >= level) {
                    covered++;
                }
            }
            // Levels rise strictly and at least one stake reaches each, so every level holds money.
            int amount = (level - previous) * covered;
            previous = level;
            List<UUID> contestants = new ArrayList<>();
            for (UUID id : live) {
                if (invested.getOrDefault(id, 0) >= level) {
                    contestants.add(id);
                }
            }
            if (contestants.isEmpty()) {
                continue;
            }
            List<UUID> winners = rankSeats(table, contestants, board, gameId);
            leftover = winners.getFirst();
            announceWinners(table, winners);
            payEven(table, flights, winners, amount);
        }
        // Whatever the levels could not split in whole coins goes to one seat.
        WagerEngine.get().sweepPot(table, Bukkit.getPlayer(leftover), leftover, flights, "pot remainder");
        WagerEngine.get().announceWins(table, "poker");
        finishAfterPayout(table, flights);
    }

    private static List<UUID> rankSeats(Table table, List<UUID> contestants, List<Card> board, String gameId) {
        if (contestants.size() == 1) {
            return new ArrayList<>(contestants);
        }
        HoldemRank.Score best = HoldemRank.Score.none();
        List<UUID> tied = new ArrayList<>();
        for (UUID id : contestants) {
            List<Card> cards = new ArrayList<>(board);
            cards.addAll(cardsOf(table.heldBy(id)));
            HoldemRank.Score score = HoldemRank.best(gameId, cards);
            if (tied.isEmpty() || score.compareTo(best) > 0) {
                best = score;
                tied.clear();
                tied.add(id);
            } else if (score.compareTo(best) == 0) {
                tied.add(id);
            }
        }
        return SeatOrder.leftOfButton(table, tied);
    }

    /**
     * Split one pot level between winners, as evenly as the coins on the felt allow. The winners
     * are among the stakes that reach this level, so every share is at least one coin.
     */
    private static void payEven(Table table, List<PayoutFlight> flights, List<UUID> winners, int amount) {
        int n = winners.size();
        int share = amount / n;
        int rem = amount % n;
        for (UUID winner : winners) {
            int need = share;
            if (rem > 0) {
                need++;
                rem--;
            }
            WagerEngine.get().payFromPot(table, Bukkit.getPlayer(winner), winner, need, flights, "pot");
        }
    }

    private void finishHand(Table table) {
        TableManager.get().endSession(table);
        passButton(table);
        TableManager.get().refreshLabel(table);
    }

    /** Ends the hand once the payout has landed, unless the table was removed meanwhile. */
    private void finishAfterPayout(Table table, List<PayoutFlight> flights) {
        UUID tableId = table.getId();
        TableManager.get().flushPiles(table, flights, () -> {
            Table still = TableManager.get().table(tableId);
            if (still != null) {
                finishHand(still);
            }
        });
    }

    private static List<Card> cardsOf(List<HandCard> held) {
        List<Card> cards = new ArrayList<>();
        for (HandCard card : held) {
            cards.add(card.card());
        }
        return cards;
    }

    private static int streetContrib(Table table, UUID owner) {
        return WagerEngine.get().owned(table, owner, table.street());
    }

    private static List<UUID> liveSeats(Table table, Street street) {
        List<UUID> live = new ArrayList<>();
        for (UUID id : table.actives()) {
            if (!street.folded.contains(id)) {
                live.add(id);
            }
        }
        return live;
    }

    /**
     * Every live seat has either acted or is all in. Checking needs the current bet matched, a
     * short call caps the seat, and a raise clears everyone else's action, so a seat that has
     * acted has matched the bet.
     */
    private static boolean streetComplete(Table table, Street street) {
        for (UUID id : liveSeats(table, street)) {
            if (!street.capped.contains(id) && !street.acted.contains(id)) {
                return false;
            }
        }
        return true;
    }

    /**
     * After an action or a departure. {@code seating} is the table as it sat before the change,
     * so a turn held by a player who has just left passes to the seat after theirs.
     */
    private void finishOrAdvance(Table table, List<UUID> seating, boolean advance) {
        Street street = streets.get(table.getId());
        // No street means the cards are still being dealt, or the hand is already settled.
        if (street == null) {
            TableManager.get().refreshLabel(table);
            return;
        }
        List<UUID> live = liveSeats(table, street);
        if (live.size() <= 1) {
            foldWin(table, live.isEmpty() ? null : live.getFirst());
            return;
        }
        if (streetComplete(table, street)) {
            table.setActor(null);
            tellSeated(table, Messages.get("poker.street_done"));
            TableManager.get().refreshLabel(table);
            advanceBoard(table, street);
            return;
        }
        UUID actor = table.actor();
        // The turn moves on after an action, or when the player holding it has left. Nobody
        // holds it while board cards are still landing.
        if (advance || (actor != null && !table.actives().contains(actor))) {
            table.setActor(SeatOrder.first(SeatOrder.after(table, seating, actor), id -> betting(street, id)));
        }
        TableManager.get().refreshLabel(table);
    }

    /** Everyone else folded, so the whole pot is the last player's. */
    private void foldWin(Table table, UUID winner) {
        // Betting is over, so a late departure or board card cannot settle this hand again.
        streets.remove(table.getId());
        table.setActor(null);
        if (winner != null) {
            tellSeated(table, Messages.get("poker.win_fold", "name", RpNames.of(winner)));
        }
        List<PayoutFlight> flights = new ArrayList<>();
        Player dest = winner != null ? Bukkit.getPlayer(winner) : null;
        if (dest != null) {
            WagerEngine.get().sweepPot(table, dest, winner, flights, "fold win");
        } else {
            // With nobody left to win it, every stake goes back where it came from.
            WagerEngine.get().returnStakes(table, flights, "hand abandoned");
        }
        WagerEngine.get().announceWins(table, "poker");
        finishAfterPayout(table, flights);
    }

    private static void tellSeated(Table table, String message) {
        for (Player player : seatedOnline(table)) {
            player.sendMessage(message);
        }
    }
}
