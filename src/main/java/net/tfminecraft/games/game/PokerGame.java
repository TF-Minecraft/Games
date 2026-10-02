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
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.MoneyTx;
import net.tfminecraft.games.wager.TxResult;

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
        boolean dealing = true;
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

    /** The host runs tournaments; cash players may also deal the next hand. */
    @Override
    public boolean tryClaimDealer(Table table, Player player) {
        if (table.live() || table.isPaying()) {
            return false;
        }
        boolean host = TableManager.get().canEditHouse(player, table);
        if (!host && (table.poker().enabled() || !table.actives().contains(player.getUniqueId()))) {
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
        if (table.poker().enabled()) {
            table.actives().removeIf(id -> table.poker().stack(id) < 1);
        }
        ensureButton(table);
        List<Player> online = seatedOnline(table);
        if (online.size() < 2) {
            for (Player player : online) {
                player.sendMessage(Messages.get("poker.need_players"));
            }
            TableManager.get().endSession(table);
            return;
        }
        Street street = new Street();
        streets.put(table.getId(), street);
        table.setStreet(1);
        if (!postForcedBets(table, street)) {
            streets.remove(table.getId());
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
        dealHoles(table, holeQueue(table), 0, street);
    }

    @Override
    public void onSessionEnd(Table table) {
        streets.remove(table.getId());
        table.poker().abortHand();
        TableManager manager = TableManager.get();
        for (UUID id : new ArrayList<>(table.getHands().keySet())) {
            manager.muckPlayer(table, id);
        }
        manager.refreshLabel(table);
    }

    @Override
    public void onTableRemoved(Table table) {
        table.poker().reset();
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
        Street street = streets.get(table.getId());
        return !table.isPaying() && street != null && !street.capped.contains(id)
                && !street.folded.contains(id) && bettingPhase(table.phase())
                && id.equals(table.actor()) && table.actives().contains(id);
    }

    /** TableManager hands words only to the actor that allowPlayChat accepted. */
    @Override
    public void onPlayWord(Table table, Player player, String word) {
        if (!allowPlayChat(table, player)) return;
        Street street = streets.get(table.getId());
        UUID id = player.getUniqueId();
        int contrib = streetContrib(table, id);
        if (contrib > street.currentBet && ("check".equals(word) || "call".equals(word))) {
            // Chips past the bet are a bet whatever it is called, so the others have to answer it.
            word = "raise";
        }
        switch (word) {
            case "check" -> {
                if (contrib < street.currentBet) {
                    player.sendMessage(Messages.get("poker.cannot_check"));
                    return;
                }
                announceAction(table, player, "checked");
            }
            case "call" -> {
                if (contrib < street.currentBet) {
                    // Calling short is only an all in: chips still in pockets have to go down first.
                    if (table.poker().enabled()) {
                        table.poker().bet(id, street.currentBet - contrib);
                        contrib = streetContrib(table, id);
                    }
                    if (contrib < street.currentBet && !isAllIn(table, player)) {
                        player.sendMessage(Messages.get("poker.need_call",
                                "n", String.valueOf(street.currentBet - contrib)));
                        return;
                    }
                    if (isAllIn(table, player)) street.capped.add(id);
                } else {
                    street.capped.remove(id);
                }
                if (isAllIn(table, player)) street.capped.add(id);
                announceAction(table, player, "called");
            }
            case "raise" -> {
                if (contrib <= street.currentBet) {
                    player.sendMessage(Messages.get("poker.need_chips"));
                    return;
                }
                street.currentBet = contrib;
                street.acted.clear();
                if (isAllIn(table, player)) street.capped.add(id);
                else street.capped.remove(id);
                tellSeated(table, Messages.get("poker.action_raised", "name", RpNames.of(id),
                        "n", String.valueOf(street.currentBet)));
            }
            case "allin" -> {
                if (table.poker().enabled()) {
                    table.poker().bet(id, table.poker().stack(id));
                } else {
                    var pockets = Accounts.pockets(table, player);
                    TxResult result = WagerEngine.get().begin(table, "poker all in")
                            .move(pockets, Accounts.bucket(table, id), pockets.available()).commit();
                    if (!result.ok()) {
                        player.sendMessage(Messages.get(result.messageKey()));
                        return;
                    }
                }
                contrib = streetContrib(table, id);
                if (contrib > street.currentBet) {
                    street.currentBet = contrib;
                    street.acted.clear();
                }
                street.capped.add(id);
                announceAction(table, player, "allin");
            }
            case "fold" -> {
                street.folded.add(id);
                TableManager.get().muckPlayer(table, id);
                announceAction(table, player, "folded");
            }
            default -> {
                return;
            }
        }
        street.acted.add(id);
        finishOrAdvance(table, new ArrayList<>(table.actives()), true);
    }

    /** A live hand takes money only from seats still in it; anyone else waits for the next one. */
    @Override
    public boolean allowStake(Table table, Player player) {
        if (table.poker().enabled()) return false;
        if (!table.live()) return true;
        return allowPlayChat(table, player);
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
        int small = blind(table, table.smallBlind());
        int big = blind(table, table.bigBlind());
        if (table.poker().enabled()) {
            for (UUID id : table.actives()) {
                lines.add(Messages.get("poker.stack", "name", RpNames.of(id),
                        "n", String.valueOf(table.poker().stack(id))));
            }
            lines.add(Messages.get("poker.chip_pot", "n", String.valueOf(
                    table.poker().invested().values().stream().mapToInt(Integer::intValue).sum())));
        }
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
                int toCall = street == null ? 0 : Math.max(0, street.currentBet - streetContrib(table, actor));
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
        if (table.poker().enabled()) {
            leaveTournament(table, player);
            return;
        }
        TableManager manager = TableManager.get();
        UUID leaver = player.getUniqueId();
        List<UUID> before = new ArrayList<>(table.actives());
        boolean live = table.live();
        Street street = streets.get(table.getId());
        boolean folded = street != null && street.folded.contains(leaver);
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
        // Earlier streets stay in the pot whatever happens. Before the hand, nothing on the felt has
        // been bet against anyone, so it all goes back. Once it is dealt, only the part of this
        // street's bet nobody has called goes back, and a folded hand has given up even that.
        if (!live) {
            WagerEngine.get().refundStreet(table, leaver, streetId, flights, "player left");
        } else if (!folded) {
            WagerEngine.get().refundUncalled(table, leaver, streetId, flights, "player left");
        }
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
        if (stop) streets.remove(table.getId());
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

    private void dealHoles(Table table, List<Player> queue, int index, Street street) {
        Table still = TableManager.get().table(table.getId());
        if (still == null || !still.live() || streets.get(table.getId()) != street) {
            return;
        }
        if (index >= queue.size()) {
            startStreet(still);
            return;
        }
        Player player = queue.get(index);
        if (!still.actives().contains(player.getUniqueId())) {
            dealHoles(still, queue, index + 1, street);
            return;
        }
        TableManager.get().dealToPlayer(still, player, 1, () -> dealHoles(still, queue, index + 1, street));
    }

    private void startStreet(Table table) {
        Street street = streets.get(table.getId());
        if (street == null) return;
        street.dealing = false;
        UUID first = leftOfButton(table, street);
        if (table.smallBlind() > 0 || table.bigBlind() > 0) {
            UUID big = bigBlindSeat(table);
            first = SeatOrder.first(SeatOrder.after(table, big), id -> betting(street, id));
        }
        table.setActor(first);
        finishOrAdvance(table, new ArrayList<>(table.actives()), false);
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
        street.dealing = false;
        street.currentBet = 0;
        street.acted.clear();
        table.poker().nextStreet();
        table.setActor(leftOfButton(table, street));
        finishOrAdvance(table, new ArrayList<>(table.actives()), false);
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
        street.dealing = true;
        table.setPhase(nextPhase);
        table.setStreet(nextStreet);
        street.currentBet = 0;
        street.acted.clear();
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
        for (String line : HandTalk.allHands(table.getGameId(), live, id -> cardsOf(table.heldBy(id)))) {
            tellSeated(table, line);
        }
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
        // Economy stakes and virtual chip investments both report only positive totals.
        Map<UUID, Integer> invested = table.poker().enabled() ? table.poker().invested()
                : WagerEngine.get().totalsExcept(table, table.getId());
        TreeSet<Integer> levels = new TreeSet<>(invested.values());
        if (levels.isEmpty()) {
            finishHand(table);
            return;
        }
        List<PayoutFlight> flights = new ArrayList<>();
        // Showdown supplies at least two live seats, all still in the seating order.
        UUID leftover = SeatOrder.leftOfButton(table, live).getFirst();
        int previous = 0;
        int awarded = 0;
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
            awarded += amount;
            if (table.poker().enabled()) {
                int share = amount / winners.size();
                int rem = amount % winners.size();
                for (UUID winner : winners) table.poker().award(winner, share + (rem-- > 0 ? 1 : 0));
            } else payEven(table, flights, winners, amount);
        }
        // Whatever the levels could not split in whole coins goes to one seat.
        if (table.poker().enabled()) {
            int total = invested.values().stream().mapToInt(Integer::intValue).sum();
            table.poker().award(leftover, total - awarded);
            table.poker().finishHand();
            finishHand(table);
            return;
        }
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
        table.poker().finishHand();
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
        return table.poker().enabled() ? table.poker().contribution(owner)
                : WagerEngine.get().owned(table, owner, table.street());
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
            if (!street.capped.contains(id) && (!street.acted.contains(id) || streetContrib(table, id) < street.currentBet)) {
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
        // Dealing animations cannot start another street, and a settled hand has no street.
        if (street == null || street.dealing) {
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
        if (table.poker().enabled()) {
            int pot = table.poker().invested().values().stream().mapToInt(Integer::intValue).sum();
            if (winner != null) table.poker().award(winner, pot);
            else table.poker().abortHand();
            table.poker().finishHand();
            finishHand(table);
            return;
        }
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

    private static boolean isAllIn(Table table, Player player) {
        return table.poker().enabled() ? table.poker().stack(player.getUniqueId()) == 0
                : WagerEngine.get().allIn(table, player);
    }

    private static int blind(Table table, int base) {
        return table.poker().blind(base, System.currentTimeMillis());
    }

    private static UUID bigBlindSeat(Table table) {
        return table.actives().size() == 2 ? SeatOrder.next(table, table.dealerId())
                : SeatOrder.next(table, SeatOrder.next(table, table.dealerId()));
    }

    private boolean postForcedBets(Table table, Street street) {
        PokerTournament tournament = table.poker();
        int small = blind(table, table.smallBlind());
        int big = blind(table, table.bigBlind());
        UUID smallSeat = table.actives().size() == 2 ? table.dealerId() : SeatOrder.next(table, table.dealerId());
        UUID bigSeat = bigBlindSeat(table);
        if (tournament.enabled()) {
            tournament.start(System.currentTimeMillis());
            for (UUID id : table.actives()) tournament.bet(id, tournament.ante());
            tournament.nextStreet(); // Antes are dead money, not a call credit.
            tournament.bet(smallSeat, small);
            tournament.bet(bigSeat, big);
        } else if (small > 0 || big > 0) {
            MoneyTx tx = WagerEngine.get().begin(table, "poker blinds");
            for (UUID id : table.actives()) {
                int required = id.equals(smallSeat) ? small : id.equals(bigSeat) ? big : 0;
                int need = Math.max(0, required - streetContrib(table, id));
                Player player = Bukkit.getPlayer(id);
                if (player == null) { tellSeated(table, Messages.get("poker.blind_failed")); return false; }
                if (need > 0) {
                    int available = Accounts.pockets(table, player).available();
                    if (available <= need) tx.move(Accounts.pockets(table, player), Accounts.bucket(table, id), available);
                    else tx.move(Accounts.pockets(table, player), Accounts.bucket(table, id), need);
                }
            }
            TxResult result = tx.commit();
            if (!result.ok()) { tellSeated(table, Messages.get("poker.blind_failed")); return false; }
        }
        street.currentBet = big;
        if (small > big) street.currentBet = small;
        if (small > 0 || big > 0 || tournament.enabled()) {
            for (Player player : seatedOnline(table)) {
                if (isAllIn(table, player)) street.capped.add(player.getUniqueId());
            }
        }
        return true;
    }

    public boolean betChips(Table table, Player player, int amount) {
        if (!table.poker().enabled() || !allowPlayChat(table, player) || amount < 1
                || amount > table.poker().stack(player.getUniqueId())) return false;
        table.poker().bet(player.getUniqueId(), amount);
        TableManager.get().refreshLabel(table);
        return true;
    }

    private void leaveTournament(Table table, Player player) {
        UUID id = player.getUniqueId();
        if (!table.poker().started()) {
            List<PayoutFlight> flights = new ArrayList<>();
            WagerEngine.get().refund(table, id, player, 0, flights, "tournament withdrawal");
            TableManager.get().flushPiles(table, flights, null);
        }
        Street street = streets.get(table.getId());
        if (street != null) street.folded.add(id);
        table.poker().remove(id);
        List<UUID> before = new ArrayList<>(table.actives());
        table.actives().remove(id);
        if (id.equals(table.dealerId())) {
            table.setDealerId(SeatOrder.first(SeatOrder.after(before, id), table.actives()::contains));
        }
        finishOrAdvance(table, before, false);
    }

    private static void announceAction(Table table, Player player, String action) {
        tellSeated(table, Messages.get("poker.action_" + action, "name", RpNames.of(player.getUniqueId())));
    }

    private static void tellSeated(Table table, String message) {
        for (Player player : seatedOnline(table)) {
            player.sendMessage(message);
        }
    }
}
