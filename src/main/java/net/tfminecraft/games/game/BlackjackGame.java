package net.tfminecraft.games.game;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.table.HandCard;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.ShufflePolicy;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.games.voice.RpNames;
import net.tfminecraft.games.voice.RpVoice;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.MoneyLog;
import net.tfminecraft.games.wager.MoneyTx;
import net.tfminecraft.games.wager.PayWinResult;
import net.tfminecraft.games.wager.TxResult;
import net.tfminecraft.games.wager.WagerEngine;

/**
 * Layout, claim, deal, 21, double/split, and settle. Engines stay dumb.
 */
public final class BlackjackGame implements Game {

    public static final String WAIT_DEAL = "wait_deal";
    public static final String DEAL = "deal";
    public static final String PLAY = "play";
    public static final String DEALER = "dealer";
    public static final String SETTLE = "settle";

    private final Map<UUID, List<BjHand>> rounds = new HashMap<>();
    private final Map<UUID, BukkitTask> betTimers = new HashMap<>();
    private final Map<UUID, BukkitTask> lingerTimers = new HashMap<>();
    private final Map<UUID, UUID> dealerHolos = new HashMap<>();
    private final Map<UUID, UUID> trayHolos = new HashMap<>();

    /**
     * Auto dealing is the house setting minus any human at the shoe. Claiming the shoe never
     * clears the setting, so unsetting or leaving hands the table straight back to auto.
     */
    static boolean auto(Table table) {
        return table.autoDealer() && table.dealerId() == null;
    }

    /**
     * Where the house money comes from, which is a separate question from who turns the cards.
     * A guild table is bank funded even with a human at the shoe, because a non-member cannot
     * deal it, so the tray is the guild's float and its winnings are the guild's. Only a table
     * with no guild behind it is stocked by its dealer.
     */
    private static boolean houseFunded(Table table) {
        return GuildTables.houseBacked(table);
    }

    /** The configured layout, which a server may have removed from its games config. */
    private static TableLayout layout(Table table) {
        return Cache.layoutOf(table.getGameId());
    }

    private static Location trayLocation(Table table) {
        TableLayout layout = layout(table);
        return layout != null ? layout.trayLocation(table) : null;
    }

    private static void speak(Table table, Player player, String key) {
        RpVoice.say(player, layout(table), key);
    }

    /** Tells a box owner something, if they are still online to hear it. */
    private static void tell(Player player, String message) {
        if (player != null) {
            player.sendMessage(message);
        }
    }

    /** The table if it is still playing, or null once it was removed or its round ended. */
    private static Table stillLive(UUID tableId) {
        Table table = TableManager.get().table(tableId);
        return table != null && table.live() ? table : null;
    }

    /** TableManager only offers the shoe for claiming while the table is idle. */
    @Override
    public boolean tryClaimDealer(Table table, Player player) {
        if (auto(table)) {
            if (!inStandRange(table, player)) {
                return true;
            }
            if (table.staffMint()) {
                player.sendMessage(Messages.get("dealer.staff_table"));
                return true;
            }
            if (!GuildTables.mayDeal(table, player)) {
                player.sendMessage(Messages.get("dealer.denied"));
                return true;
            }
            // The float in the tray belongs to the guild, not to whoever takes the shoe.
            TableManager.get().bankAutoTray(table);
            table.setDealerId(player.getUniqueId());
            TableManager.get().persistHouseChange(table);
            player.sendMessage(Messages.get("dealer.takeover"));
            return true;
        }
        UUID have = table.dealerId();
        if (have == null) {
            if (!GuildTables.mayDeal(table, player)) {
                player.sendMessage(Messages.get("dealer.denied"));
                return true;
            }
            table.setDealerId(player.getUniqueId());
            TableManager.get().persistHouseChange(table);
            player.sendMessage(Messages.get("dealer.claimed"));
            return true;
        }
        if (have.equals(player.getUniqueId())) {
            table.setDealerId(null);
            TableManager.get().persistHouseChange(table);
            player.sendMessage(Messages.get("dealer.unset"));
            return true;
        }
        player.sendMessage(Messages.get("dealer.taken"));
        return true;
    }

    private static boolean inStandRange(Table table, Player player) {
        TableLayout layout = layout(table);
        if (layout == null || layout.stand() == null) {
            return false;
        }
        Location stand = layout.standLocation(table);
        if (!player.getWorld().equals(stand.getWorld())) {
            return false;
        }
        double radius = layout.noBetRadius() > 0 ? layout.noBetRadius() : 0.5;
        Location feet = player.getLocation();
        return Math.hypot(feet.getX() - stand.getX(), feet.getZ() - stand.getZ()) <= radius;
    }

    @Override
    public String extraLabel(Table table) {
        if (!table.live()) {
            return GuildTables.frozen(table) ? Messages.get("label.no_slots") : "";
        }
        List<String> lines = new ArrayList<>();
        if (PLAY.equals(table.phase()) && table.actor() != null) {
            lines.add(Messages.get("label.turn", "name", RpNames.of(table.actor())));
        } else if (DEALER.equals(table.phase())) {
            lines.add(Messages.get("label.turn", "name", "Dealer"));
        }
        int total = 0;
        List<BjHand> hands = rounds.getOrDefault(table.getId(), List.of());
        for (UUID box : table.boxes()) {
            int stake = 0;
            for (BjHand hand : hands) {
                if (box.equals(hand.owner)) {
                    stake += hand.bet;
                }
            }
            lines.add(Messages.get("label.box", "name", RpNames.of(box), "stake", String.valueOf(stake)));
            total += stake;
        }
        if (!table.boxes().isEmpty()) {
            lines.add(Messages.get("label.action", "total", String.valueOf(total)));
        }
        return String.join("\n", lines);
    }

    @Override
    public boolean sortHeldCards() {
        return false;
    }

    @Override
    public boolean allowRevealToggle(Table table, Player player) {
        return false;
    }

    @Override
    public boolean showRevealDust(Table table) {
        return false;
    }

    @Override
    public void onTablePilesChanged(Table table) {
        syncDealerHolo(table);
    }

    @Override
    public void onFeltPilesChanged(Table table) {
        syncTrayHolo(table);
    }

    /** The dealer's pile is the only table pile blackjack deals, and its total floats above it. */
    private void syncDealerHolo(Table table) {
        List<HandCard> cards = table.tablePiles().get(DEALER);
        if (cards == null || cards.isEmpty()) {
            WorldAnchors.remove(dealerHolos.remove(table.getId()));
            return;
        }
        showLabel(dealerHolos, table.getId(), dealerHoloLocation(table, cards.size()), formatPileTotal(cards));
    }

    private void syncTrayHolo(Table table) {
        int total = trayTotal(table);
        Location at = trayLocation(table);
        if (total < 1 || at == null) {
            WorldAnchors.remove(trayHolos.remove(table.getId()));
            return;
        }
        showLabel(trayHolos, table.getId(), at, Messages.get("label.tray_total", "n", String.valueOf(total)));
    }

    /** Moves and rewrites a table's label, spawning a fresh one if it is missing or was killed. */
    private static void showLabel(Map<UUID, UUID> holos, UUID tableId, Location at, String text) {
        UUID id = holos.get(tableId);
        Entity entity = id != null ? Bukkit.getEntity(id) : null;
        if (entity == null || entity.isDead()) {
            WorldAnchors.remove(id);
            TextDisplay spawned = WorldAnchors.spawnLabel(at, text);
            if (spawned != null) {
                holos.put(tableId, spawned.getUniqueId());
            } else {
                holos.remove(tableId);
            }
            return;
        }
        WorldAnchors.setText(id, text);
        WorldAnchors.move(id, at);
    }

    private Location dealerHoloLocation(Table table, int count) {
        Location origin = table.getOrigin().clone();
        DisplayPose pose = tablePileSlot(table, DEALER, (count - 1) / 2, count, true);
        origin.add(pose.translation().x, 0, pose.translation().z);
        return origin;
    }

    private static String formatPileTotal(List<HandCard> cards) {
        boolean hole = false;
        List<HandCard> up = new ArrayList<>();
        for (HandCard held : cards) {
            if (held.faceUp()) {
                up.add(held);
            } else {
                hole = true;
            }
        }
        if (hole) {
            return Messages.get("label.total_hole", "up", String.valueOf(bestTotal(up)));
        }
        int best = bestTotal(cards);
        if (best > 21) {
            return Messages.get("label.total_bust");
        }
        int hard = hardTotal(cards);
        if (best != hard) {
            return Messages.get("label.total_soft", "hard", String.valueOf(hard), "soft", String.valueOf(best));
        }
        return Messages.get("label.total", "n", String.valueOf(best));
    }

    @Override
    public void onSessionStart(Table table) {
        if (table.shufflePolicy() == ShufflePolicy.ROUND) {
            TableManager.get().reshuffleFull(table);
        }
        snapshotBoxes(table);
        table.setPhase(WAIT_DEAL);
        table.setActor(null);
        table.setBoxIndex(0);
        table.setHandIndex(0);
        List<BjHand> hands = new ArrayList<>();
        TableManager manager = TableManager.get();
        for (UUID box : table.boxes()) {
            hands.add(new BjHand(box, manager.ownedDenars(table, box)));
        }
        rounds.put(table.getId(), hands);
        TableManager.get().refreshLabel(table);
        if (auto(table)) {
            startDeal(table);
        }
    }

    @Override
    public void onTableReady(Table table) {
        prepareIdle(table);
        syncTrayHolo(table);
    }

    @Override
    public void onTableRemoved(Table table) {
        cancelBetTimer(table);
        cancelLinger(table);
        WorldAnchors.remove(dealerHolos.remove(table.getId()));
        WorldAnchors.remove(trayHolos.remove(table.getId()));
    }

    @Override
    public void onSessionEnd(Table table) {
        cancelLinger(table);
        cancelBetTimer(table);
        WorldAnchors.remove(dealerHolos.remove(table.getId()));
        // Covers both exits: a settled round, and one abandoned when every box walked away.
        drainAutoTray(table);
        prepareIdle(table);
        syncTrayHolo(table);
    }

    /** TableManager reports each coin staked on the felt, so {@code denars} is at least one. */
    @Override
    public void onChipIn(Table table, Player player, int denars, ItemStack item) {
        if (!coverAutoTray(table, item)) {
            // The house cannot back this bet, so it goes back rather than sitting on the felt
            // looking covered. The chip that just landed is its own coin, so this is exact.
            List<PayoutFlight> flights = new ArrayList<>();
            WagerEngine.get().refund(table, player.getUniqueId(), player, denars, flights, "bet not covered");
            TableManager.get().flushPiles(table, flights, null);
            player.sendMessage(Messages.get("bet.bank_short"));
            return;
        }
        onChipIn(table, player);
    }

    @Override
    public void onChipIn(Table table, Player player) {
        if (!auto(table) || table.live() || !table.betOpen()) {
            return;
        }
        if (!TableManager.get().hasLegalBlackjackBox(table)) {
            cancelBetTimer(table);
            table.setAutoCountdown(0);
            TableManager.get().refreshLabel(table);
            return;
        }
        if (betTimers.containsKey(table.getId())) {
            return;
        }
        startBetTimer(table);
    }

    @Override
    public void onLeave(Table table, Player player) {
        TableManager manager = TableManager.get();
        int felt = 0;
        boolean peel = auto(table) && !table.live() && table.betOpen();
        if (peel) {
            felt = manager.ownedDenars(table, player.getUniqueId());
        }
        Game.super.onLeave(table, player);
        if (peel && felt > 0) {
            peelAutoTray(table, felt);
        }
        onChipIn(table, player);
        if (table.live()) {
            // A dealer can leave as a seat too, when they had their own chips on the felt.
            UUID id = player.getUniqueId();
            continueWithout(table, id, id.equals(table.dealerId()));
        }
    }

    @Override
    public void onDealerGone(Table table) {
        if (!table.live()) {
            onTableReady(table);
            return;
        }
        continueWithout(table, null, true);
    }

    private void continueWithout(Table table, UUID gone, boolean dealerLeft) {
        boolean currentBox = false;
        int idx = table.boxes().indexOf(gone);
        if (idx >= 0) {
            currentBox = idx == table.boxIndex();
            table.boxes().remove(idx);
            if (idx < table.boxIndex()) {
                table.setBoxIndex(table.boxIndex() - 1);
            } else if (currentBox) {
                table.setHandIndex(0);
            }
        }
        String phase = table.phase();
        if (table.boxes().isEmpty() && (WAIT_DEAL.equals(phase) || DEAL.equals(phase) || PLAY.equals(phase))) {
            TableManager.get().endSession(table);
            return;
        }
        // A round waits for a dealer's click only while that dealer is still at the table.
        if (WAIT_DEAL.equals(phase) && dealerLeft) {
            startDeal(table);
            return;
        }
        if (PLAY.equals(phase) && currentBox) {
            table.setActor(null);
            advancePlay(table);
        }
    }

    /**
     * Top the tray up so it can pay every bet on the felt. Says whether the house is good for
     * the action, and never touches the bet that prompted it: the caller decides what to do about
     * a shortfall while its own stake is still safely where the player put it.
     */
    private static boolean coverAutoTray(Table table, ItemStack item) {
        if (!houseFunded(table)) {
            return true;
        }
        int need = Math.max(0, actionTotal(table) - trayTotal(table));
        if (need < 1) {
            return true;
        }
        TableManager manager = TableManager.get();
        ItemStack template = manager.chipUnitDenars(item) > 0 ? item : houseTemplate(table);
        int unit = manager.chipUnitDenars(template);
        // Nothing here the house could pay in, such as a loot wager on an empty table. The round
        // reserve at closeBets is the real gate, and it refunds everyone if the bank is short.
        if (unit < 1) {
            return true;
        }
        // The tray only holds whole coins, so a gap smaller than one coin waits for settle.
        int fund = (need / unit) * unit;
        if (fund < 1) {
            return true;
        }
        TxResult result = WagerEngine.get().fundFromHouse(table, table.getId(), template, fund,
                trayLocation(table), "tray cover");
        return result.ok();
    }

    /**
     * After the bet window closes: refund under-min boxes, then session if a legal box remains.
     * The window only closes on an idle table: the dealer's command refuses a live one, and the
     * bet clock stops when a round starts.
     */
    public void closeBets(Table table) {
        refundUnderMinBoxes(table);
        if (TableManager.get().hasLegalBlackjackBox(table)) {
            if (!GuildTables.canStartGuildAutoRound(table)) {
                refundOpenBoxes(table);
                prepareIdle(table);
                return;
            }
            if (houseFunded(table) && !reserveRound(table)) {
                messageBoxes(table, Messages.get("bet.house_short"));
                refundOpenBoxes(table);
                drainAutoTray(table);
                prepareIdle(table);
                return;
            }
            TableManager.get().beginSession(table);
        } else if (auto(table)) {
            prepareIdle(table);
        }
    }

    private static void messageBoxes(Table table, String message) {
        for (UUID owner : TableManager.get().boxOwners(table)) {
            tell(Bukkit.getPlayer(owner), message);
        }
    }

    /**
     * The most the house can be asked for this round: every box split to the cap, every hand
     * doubled, every one of them won. A win pays 1:1, so this is also what the tray must hold.
     */
    private static int roundLiability(Table table) {
        TableManager manager = TableManager.get();
        int hands = maxHandsPerBox(table);
        int liability = 0;
        for (UUID owner : manager.boxOwners(table)) {
            liability += manager.ownedDenars(table, owner) * hands * 2;
        }
        return liability;
    }

    /**
     * Put the whole worst case in the tray before the first card, so no split or double later in
     * the round has to ask the bank for anything. What is not won goes back at round end.
     */
    private static boolean reserveRound(Table table) {
        TableManager manager = TableManager.get();
        int need = roundLiability(table) - trayTotal(table);
        if (need < 1) {
            return true;
        }
        ItemStack template = houseTemplate(table);
        int unit = manager.chipUnitDenars(template);
        if (unit < 1) {
            return false;
        }
        // Round up: over-reserving is banked again at round end, under-reserving is the hole we are closing.
        int fund = ((need + unit - 1) / unit) * unit;
        TxResult result = WagerEngine.get().fundFromHouse(table, table.getId(), template, fund,
                trayLocation(table), "round reserve");
        // A move is exact, so a reserve that went through holds the whole rounded-up amount.
        return result.ok();
    }

    /**
     * Between rounds nothing is staked, so the whole float goes back to the bank. This takes the
     * pickup path rather than a peel, because that one drops the coins at the table if the owning
     * guild has gone away instead of destroying them.
     */
    private static void drainAutoTray(Table table) {
        if (!houseFunded(table) || trayTotal(table) < 1) {
            return;
        }
        TableManager.get().bankAutoTray(table);
    }

    /**
     * True if the house can take {@code extra} more action. The round reserve normally covers it
     * outright, so this only bites if the reserve came up short. A table stocked by its own dealer
     * is their problem, not the tray's.
     */
    private static boolean houseCanCover(Table table, int extra) {
        if (!houseFunded(table)) {
            return true;
        }
        return trayTotal(table) >= actionTotal(table) + extra;
    }

    /** Box owners always hold money, so a box under the minimum still holds at least one coin. */
    private void refundUnderMinBoxes(Table table) {
        TableManager manager = TableManager.get();
        int min = table.minBet();
        List<PayoutFlight> flights = new ArrayList<>();
        int released = 0;
        for (UUID owner : manager.boxOwners(table)) {
            if (manager.ownedDenars(table, owner) >= min) {
                continue;
            }
            Player player = Bukkit.getPlayer(owner);
            tell(player, Messages.get("bet.under_min", "min", String.valueOf(min)));
            released += WagerEngine.get().refund(table, owner, player, 0, flights, "under min refund")
                    .moved();
        }
        refundAndPeel(table, flights, released);
    }

    /** One wave for every owner, then one peel, so no refund is dropped mid-flight. */
    private void refundAndPeel(Table table, List<PayoutFlight> flights, int released) {
        if (released < 1) {
            return;
        }
        TableManager.get().flushPiles(table, flights, null);
        if (houseFunded(table)) {
            peelAutoTray(table, released);
        }
    }

    private void refundOpenBoxes(Table table) {
        List<PayoutFlight> flights = new ArrayList<>();
        int released = 0;
        for (UUID owner : TableManager.get().boxOwners(table)) {
            Player player = Bukkit.getPlayer(owner);
            tell(player, Messages.get("bet.no_slots"));
            released += WagerEngine.get().refund(table, owner, player, 0, flights, "box refund")
                    .moved();
        }
        refundAndPeel(table, flights, released);
    }

    /**
     * The action shrank by {@code released}, so give the tray excess back to the bank.
     * Callers refund first, so the piles are already gone from the felt here.
     */
    private static void peelAutoTray(Table table, int released) {
        int peel = Math.max(0, Math.min(released, trayTotal(table) - actionTotal(table)));
        if (peel < 1) {
            return;
        }
        WagerEngine.get().peelToHouse(table, peel, "peel");
    }

    private static int trayTotal(Table table) {
        return TableManager.get().trayDenars(table);
    }

    /** Every denar on the felt: the whole ledger minus the tray. */
    private static int actionTotal(Table table) {
        return WagerEngine.get().felt(table);
    }

    private void prepareIdle(Table table) {
        if (table.live()) {
            return;
        }
        if (!auto(table) || GuildTables.frozen(table)) {
            cancelBetTimer(table);
            if (GuildTables.frozen(table) && table.betOpen()) {
                refundUnderMinBoxes(table);
                refundOpenBoxes(table);
            }
            table.setBetOpen(false);
            table.setAutoCountdown(0);
            TableManager.get().refreshLabel(table);
            syncTrayHolo(table);
            return;
        }
        table.setBetOpen(true);
        table.setAutoCountdown(0);
        TableManager.get().refreshLabel(table);
        syncTrayHolo(table);
    }

    /**
     * Counts the open bet window down. Removing the table or ending the round cancels the timer,
     * but a round can still be started by hand while it runs.
     */
    private void startBetTimer(Table table) {
        TableLayout layout = layout(table);
        int seconds = layout != null ? layout.betSeconds() : 10;
        table.setAutoCountdown(seconds);
        TableManager.get().refreshLabel(table);
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(Games.plugin, () -> {
            if (table.live()) {
                cancelBetTimer(table);
                return;
            }
            int left = table.autoCountdown() - 1;
            table.setAutoCountdown(left);
            if (left > 0) {
                TableManager.get().refreshLabel(table);
                return;
            }
            cancelBetTimer(table);
            table.setBetOpen(false);
            TableManager.get().refreshLabel(table);
            closeBets(table);
        }, 20L, 20L);
        betTimers.put(table.getId(), task);
    }

    private void cancelLinger(Table table) {
        BukkitTask task = lingerTimers.remove(table.getId());
        if (task != null) {
            task.cancel();
        }
    }

    private static int resultDelay(Table table) {
        TableLayout layout = layout(table);
        int cfg = layout != null ? layout.resultDelayTicks() : 8;
        return Math.max(Cache.interpolationTicks, cfg);
    }

    /** Runs {@code then} after the result pause, while this table is still playing its round. */
    private void afterResultDelay(Table table, Runnable then) {
        int ticks = resultDelay(table);
        if (ticks <= 0) {
            then.run();
            return;
        }
        UUID tableId = table.getId();
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (stillLive(tableId) != null) {
                then.run();
            }
        }, ticks);
    }

    private void cancelBetTimer(Table table) {
        BukkitTask task = betTimers.remove(table.getId());
        if (task != null) {
            task.cancel();
        }
        table.setAutoCountdown(0);
    }

    /** TableManager only passes shoe clicks on while the table is live. */
    @Override
    public void onShoeClick(Table table, Player player) {
        String phase = table.phase();
        if (WAIT_DEAL.equals(phase)) {
            if (!player.getUniqueId().equals(table.dealerId())) {
                return;
            }
            startDeal(table);
            return;
        }
        if (PLAY.equals(phase) && player.getUniqueId().equals(table.actor())) {
            hit(table, player);
        }
    }

    @Override
    public void onBetHit(Table table, Player player) {
        hit(table, player);
    }

    @Override
    public void onBetStand(Table table, Player player) {
        stand(table, player);
    }

    @Override
    public void onBetDouble(Table table, Player player) {
        doubleDown(table, player);
    }

    @Override
    public void onBetSplit(Table table, Player player) {
        split(table, player);
    }

    void hit(Table table, Player player) {
        if (!canAct(table, player)) {
            return;
        }
        BjHand hand = currentHand(table);
        // A doubled hand keeps the turn while its one card is showing, but takes no more, and
        // nor does a hand whose twenty-one or bust is showing before the turn moves on.
        if (hand.doubled || hand.splitAces || bestTotal(cardsOf(table, hand)) >= 21) {
            return;
        }
        speak(table, player, "hit");
        dealToHand(table, player, hand);
    }

    void stand(Table table, Player player) {
        if (!canAct(table, player)) {
            return;
        }
        speak(table, player, "stand");
        nextHand(table);
    }

    void doubleDown(Table table, Player player) {
        if (!canAct(table, player)) {
            return;
        }
        BjHand hand = currentHand(table);
        List<HandCard> cards = cardsOf(table, hand);
        // A doubled hand normally holds three cards, but not if its card failed to spawn.
        if (hand.doubled || hand.splitAces || cards.size() != 2) {
            player.sendMessage(Messages.get("bet.no_double"));
            return;
        }
        // Before the chips move, not after: a refused cover used to leave the bet doubled anyway.
        if (!houseCanCover(table, hand.bet)) {
            player.sendMessage(Messages.get("bet.bank_short"));
            return;
        }
        if (!stakeExtra(table, player, hand.bet)) {
            return;
        }
        hand.bet *= 2;
        hand.doubled = true;
        checkBoxBets(table, "double");
        player.sendMessage(Messages.get("bet.doubled"));
        speak(table, player, "double");
        TableManager.get().refreshLabel(table);
        dealToHand(table, player, hand);
    }

    void split(Table table, Player player) {
        if (!canAct(table, player)) {
            return;
        }
        BjHand hand = currentHand(table);
        List<BjHand> boxHands = boxHands(table);
        List<HandCard> cards = cardsOf(table, hand);
        if (boxHands.size() >= maxHandsPerBox(table) || cards.size() != 2 || !isPair(cards)
                || (hand.splitAces && !resplitAces(table))) {
            player.sendMessage(Messages.get("bet.no_split"));
            return;
        }
        if (!houseCanCover(table, hand.bet)) {
            player.sendMessage(Messages.get("bet.bank_short"));
            return;
        }
        if (!stakeExtra(table, player, hand.bet)) {
            return;
        }
        // A pair holding one ace holds two.
        boolean aces = isAce(cards.get(0));
        hand.fromSplit = true;
        hand.splitAces = aces;
        List<BjHand> round = rounds.get(table.getId());
        // A free slot until renumberBox assigns the real one, so no two hands claim the same cards.
        BjHand second = new BjHand(hand.owner, hand.bet);
        second.slot = boxHands.size();
        second.fromSplit = true;
        second.splitAces = aces;
        round.add(round.indexOf(hand) + 1, second);
        TableManager manager = TableManager.get();
        // The card that leaves belongs to this hand, which on a resplit is not the box's second card.
        HandCard moving = cards.get(1);
        renumberBox(table, hand.owner);
        moving.setSlot(second.slot);
        manager.relayoutHand(table, player);
        checkBoxBets(table, "split");
        player.sendMessage(Messages.get("bet.split"));
        speak(table, player, "split");
        manager.refreshLabel(table);
        table.setPhase(DEAL);
        UUID tableId = table.getId();
        int first = hand.slot;
        int next = second.slot;
        dealToBox(table, hand.owner, first, () -> {
            if (stillLive(tableId) == null) {
                return;
            }
            dealToBox(table, hand.owner, next, () -> {
                if (stillLive(tableId) == null) {
                    return;
                }
                table.setPhase(PLAY);
                // Resume on the hand that was split, never back at the box's first hand.
                table.setHandIndex(first);
                advancePlay(table);
            });
        });
    }

    /**
     * Give a box's hands slots 0..n-1 in play order, moving their cards with them. The new hand
     * from a split already sits next to its parent in the round list, so it lands next to it in
     * the fan too. Every card belongs to one of the box's hands, and each takes its new slot from
     * its own old one, so no move can land on a card that has not moved yet.
     */
    private void renumberBox(Table table, UUID owner) {
        List<BjHand> hands = handsOf(table, owner);
        Map<Integer, Integer> moves = new HashMap<>();
        for (int i = 0; i < hands.size(); i++) {
            moves.put(hands.get(i).slot, i);
        }
        for (HandCard card : table.heldBy(owner)) {
            card.setSlot(moves.get(card.slot()));
        }
        for (int i = 0; i < hands.size(); i++) {
            hands.get(i).slot = i;
        }
    }

    /** Every hand this owner holds, in play order. */
    private List<BjHand> handsOf(Table table, UUID owner) {
        List<BjHand> out = new ArrayList<>();
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (owner.equals(hand.owner)) {
                out.add(hand);
            }
        }
        return out;
    }

    private static int maxHandsPerBox(Table table) {
        TableLayout layout = layout(table);
        return layout != null ? layout.maxHandsPerBox() : 4;
    }

    private static boolean resplitAces(Table table) {
        TableLayout layout = layout(table);
        return layout != null && layout.resplitAces();
    }

    /**
     * The extra bet a double or a split needs, out of the player's coins. Either the whole bet is
     * on the felt when this returns true, or nothing left their pockets and it returns false.
     * Callers have already checked that the house can cover the bigger action, and every hand
     * bets at least the one coin its box was opened with.
     */
    private boolean stakeExtra(Table table, Player player, int bet) {
        TableManager manager = TableManager.get();
        UUID owner = player.getUniqueId();
        MoneyTx tx = WagerEngine.get().begin(table, "extra bet");
        // Placed on the box, so a double grows the heap already sitting there instead of
        // starting a second one on top of it.
        tx.move(Accounts.coins(table, player),
                Accounts.bucket(table, owner).placedAt(manager.boxLocation(table, owner)), bet);
        TxResult result = tx.commit();
        if (!result.ok()) {
            player.sendMessage(refusal(result, bet));
            return false;
        }
        manager.playChipSound(table, manager.boxLocation(table, owner));
        return true;
    }

    /**
     * A box should hold exactly what its hands are betting, nothing more and nothing less.
     *
     * <p>This is the check that would have caught the double-down bug the instant it happened:
     * the hand's bet doubled while the felt still held the original, because the extra stake had
     * been handed back by a step whose answer nobody looked at.
     */
    private void checkBoxBets(Table table, String stage) {
        if (!Cache.wagerAuditLog) {
            return;
        }
        Map<UUID, Integer> betting = new LinkedHashMap<>();
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            betting.merge(hand.owner, hand.bet, Integer::sum);
        }
        for (Map.Entry<UUID, Integer> entry : betting.entrySet()) {
            int held = WagerEngine.get().owned(table, entry.getKey());
            if (held != entry.getValue()) {
                MoneyLog.mismatch(table, stage + " box " + entry.getKey() + " is betting "
                        + entry.getValue() + " but holds " + held);
            }
        }
    }

    /**
     * A coin the house can pay in: the tray's own, or else the first box's, since every box
     * holding money holds a coin. Null when neither has one, such as a dealer's own loot on an
     * otherwise empty felt.
     */
    private static ItemStack houseTemplate(Table table) {
        TableManager manager = TableManager.get();
        ItemStack template = manager.feltItem(table, table.getId());
        List<UUID> boxes = manager.boxOwners(table);
        if (template == null && !boxes.isEmpty()) {
            template = manager.feltItem(table, boxes.getFirst());
        }
        return template;
    }

    /** What to tell a player whose bet was refused, naming the actual reason. */
    private static String refusal(TxResult result, int bet) {
        if (result.reason() == TxResult.Reason.NO_CHANGE) {
            return Messages.get("bet.no_change", "amount", String.valueOf(bet), "best",
                    String.valueOf(result.best()));
        }
        // Every refused transaction names its reason.
        return Messages.get(result.messageKey());
    }

    private void dealToHand(Table table, Player player, BjHand hand) {
        table.setPhase(DEAL);
        UUID tableId = table.getId();
        dealToBox(table, player.getUniqueId(), hand.slot, () -> {
            if (stillLive(tableId) == null) {
                return;
            }
            table.setPhase(PLAY);
            if (currentHand(table) != hand) {
                // The box left while its card was in the air, so the turn has already moved on.
                advancePlay(table);
                return;
            }
            afterPlayerCard(table, player, hand);
        });
    }

    private void startDeal(Table table) {
        table.setPhase(DEAL);
        List<DealStep> steps = new ArrayList<>();
        for (UUID box : table.boxes()) {
            steps.add(new DealStep(box, false, true));
        }
        steps.add(new DealStep(null, true, true));
        for (UUID box : table.boxes()) {
            steps.add(new DealStep(box, false, true));
        }
        steps.add(new DealStep(null, true, false));
        runDeal(table, steps, 0);
    }

    private void runDeal(Table table, List<DealStep> steps, int index) {
        Table live = stillLive(table.getId());
        if (live == null) {
            return;
        }
        if (index >= steps.size()) {
            live.setBoxIndex(0);
            live.setHandIndex(0);
            advancePlay(live);
            return;
        }
        DealStep step = steps.get(index);
        Runnable next = () -> runDeal(live, steps, index + 1);
        if (step.dealer()) {
            TableManager.get().dealToTable(live, DEALER, 1, step.faceUp(), next);
            return;
        }
        // A box that walked away mid-deal is no longer seated, and gets no more cards.
        if (!live.boxes().contains(step.box())) {
            next.run();
            return;
        }
        dealToBox(live, step.box(), 0, next);
    }

    /**
     * One card to a box. Quitting unseats a box before anything else happens, so a seated box's
     * owner is always online; the card itself may still be stopped if they leave while it flies.
     */
    private void dealToBox(Table table, UUID box, int slot, Runnable after) {
        Player player = Bukkit.getPlayer(box);
        UUID tableId = table.getId();
        TableManager.get().dealToPlayer(table, player, 1, slot, () -> {
            Table still = TableManager.get().table(tableId);
            Player online = Bukkit.getPlayer(box);
            if (still != null && online != null) {
                TableManager.get().publishHand(still, online);
            }
            after.run();
        });
    }

    private void afterPlayerCard(Table table, Player player, BjHand hand) {
        afterResultDelay(table, () -> {
            // The box left during the pause, so its result no longer moves the turn.
            if (hand != currentHand(table)) {
                return;
            }
            finishPlayerCard(table, player, hand);
        });
    }

    /** A card from a hit or a double has landed. Split aces are never hit. */
    private void finishPlayerCard(Table table, Player player, BjHand hand) {
        int total = bestTotal(cardsOf(table, hand));
        if (total > 21) {
            player.sendMessage(Messages.get("bet.bust"));
            nextHand(table);
            return;
        }
        if (hand.doubled || total == 21) {
            nextHand(table);
            return;
        }
        table.setActor(hand.owner);
        table.setPhase(PLAY);
        TableManager.get().refreshLabel(table);
    }

    /**
     * Finds the next hand that needs a decision. A hand arrives here with at most the two cards
     * it was dealt, so twenty-one is a natural or a split ace with a ten, and it is never over.
     */
    private void advancePlay(Table table) {
        while (table.boxIndex() < table.boxes().size()) {
            List<BjHand> boxHands = boxHands(table);
            while (table.handIndex() < boxHands.size()) {
                BjHand hand = boxHands.get(table.handIndex());
                List<HandCard> cards = cardsOf(table, hand);
                // A box whose cards all failed to spawn holds nothing to play.
                if (cards.isEmpty()) {
                    table.setHandIndex(table.handIndex() + 1);
                    continue;
                }
                // Split aces take one card each, unless the rules offer to split a new pair again.
                // An ace whose card failed to spawn still holds the turn, but can only stand.
                boolean done = bestTotal(cards) >= 21 || (hand.splitAces && cards.size() == 2
                        && !(isPair(cards) && resplitAces(table) && boxHands.size() < maxHandsPerBox(table)));
                if (done) {
                    table.setActor(null);
                    table.setPhase(PLAY);
                    afterResultDelay(table, () -> {
                        if (currentHand(table) != hand) {
                            return;
                        }
                        table.setHandIndex(table.handIndex() + 1);
                        advancePlay(table);
                    });
                    return;
                }
                table.setActor(hand.owner);
                table.setPhase(PLAY);
                TableManager.get().refreshLabel(table);
                return;
            }
            table.setBoxIndex(table.boxIndex() + 1);
            table.setHandIndex(0);
        }
        startDealer(table);
    }

    private void nextHand(Table table) {
        table.setActor(null);
        table.setHandIndex(table.handIndex() + 1);
        advancePlay(table);
    }

    private void startDealer(Table table) {
        table.setPhase(DEALER);
        table.setActor(null);
        TableManager.get().refreshLabel(table);
        TableManager.get().revealTablePile(table, DEALER);
        afterResultDelay(table, () -> {
            if (allBusted(table)) {
                settle(table);
                return;
            }
            dealerHit(table, -1);
        });
    }

    /**
     * Draws for the dealer until they stand. {@code held} is how many cards the dealer had when the
     * last card was asked for: a pile that did not grow means the shoe is dry until the next
     * round, so the dealer stands on what they hold instead of asking again forever.
     */
    private void dealerHit(Table table, int held) {
        Table live = stillLive(table.getId());
        if (live == null) {
            return;
        }
        TableLayout layout = layout(live);
        boolean hitSoft = layout != null && layout.dealerHitsSoft17();
        List<HandCard> pile = live.tablePile(DEALER);
        if (pile.size() == held || !shouldDealerHit(pile, hitSoft)) {
            afterResultDelay(live, () -> settle(live));
            return;
        }
        int size = pile.size();
        TableManager.get().dealToTable(live, DEALER, 1, true, () -> dealerHit(live, size));
    }

    private void settle(Table table) {
        table.setPhase(SETTLE);
        checkBoxBets(table, "settle");
        TableManager manager = TableManager.get();
        int dealerTotal = bestTotal(table.tablePile(DEALER));
        boolean dealerBust = dealerTotal > 21;
        UUID dealerId = table.dealerId();
        Player dealer = dealerId != null ? Bukkit.getPlayer(dealerId) : null;
        Map<UUID, Integer> collect = new HashMap<>();
        Map<UUID, Integer> pay = new HashMap<>();
        Map<UUID, ItemStack> templates = new HashMap<>();
        List<BjHand> hands = rounds.getOrDefault(table.getId(), List.of());
        for (BjHand hand : hands) {
            templates.putIfAbsent(hand.owner, manager.feltItem(table, hand.owner));
            List<HandCard> cards = cardsOf(table, hand);
            int playerTotal = bestTotal(cards);
            boolean bust = playerTotal > 21 || cards.isEmpty();
            boolean natural = isNatural(hand, cards);
            Player player = Bukkit.getPlayer(hand.owner);
            if (bust || (!dealerBust && playerTotal < dealerTotal)) {
                collect.merge(hand.owner, hand.bet, Integer::sum);
                tell(player, Messages.get("bet.lose"));
                continue;
            }
            if (!dealerBust && playerTotal == dealerTotal) {
                tell(player, Messages.get("bet.push"));
                continue;
            }
            int amount = natural ? (hand.bet * 3) / 2 : hand.bet;
            pay.merge(hand.owner, amount, Integer::sum);
            tell(player, Messages.get(natural ? "bet.natural" : "bet.win"));
        }
        // Who is behind the house here. On a table with no guild that is the dealer out of their
        // own pocket; on a guild table it is the bank, whether or not a human is turning the cards.
        boolean dealerBacked = !houseFunded(table);
        List<PayoutFlight> flights = new ArrayList<>();
        int held = trayTotal(table) + actionTotal(table);
        // Nothing comes into the table during a settle. A dealer or a bank covering a win pays the
        // winner directly, so that money is never table money for even an instant.
        int wentOut = 0;
        // Losing bets leave the box: a private dealer takes them, the house tray keeps them.
        for (Map.Entry<UUID, Integer> entry : collect.entrySet()) {
            UUID owner = entry.getKey();
            if (dealerBacked) {
                wentOut += WagerEngine.get()
                        .refund(table, owner, dealer, entry.getValue(), flights, "loss to dealer")
                        .moved();
            } else {
                WagerEngine.get().toTray(table, owner, entry.getValue(), flights, "loss");
            }
        }
        // A winning box still holds the coins it staked, so they show what to pay it in.
        for (Map.Entry<UUID, Integer> entry : pay.entrySet()) {
            UUID owner = entry.getKey();
            int amount = entry.getValue();
            Player winner = Bukkit.getPlayer(owner);
            PayWinResult result = WagerEngine.get().payWin(table, winner, owner, amount, dealer,
                    dealerBacked, templates.get(owner), flights);
            wentOut += result.trayMoved();
            if (result.owe() > 0) {
                tell(dealerBacked ? dealer : winner,
                        Messages.get("bet.owe", "amount", String.valueOf(result.owe())));
            }
        }
        WagerEngine.get().announceWins(table, "blackjack");
        // Pushes and anything a winner still has on the felt goes straight back to them.
        for (UUID owner : new ArrayList<>(manager.boxOwners(table))) {
            Player back = Bukkit.getPlayer(owner);
            wentOut += WagerEngine.get().refund(table, owner, back, 0, flights, "bet returned")
                    .moved();
        }
        manager.checkFeltEmpty(table, "settle");
        int expected = held - wentOut;
        int now = trayTotal(table) + actionTotal(table);
        if (Cache.wagerAuditLog && now != expected) {
            MoneyLog.mismatch(table, "settle held " + held + " paid out " + wentOut
                    + " so it should hold " + expected + " but holds " + now);
        }
        manager.flushPiles(table, flights, () -> startLinger(table));
    }

    /**
     * Shows the result for a while. Removing the table or ending the round cancels the timer, but
     * an admin can stop the round, and even start the next, while its payout is still landing. So
     * only a round still showing its result lingers, or the timer would end whatever round came next.
     */
    private void startLinger(Table table) {
        if (stillLive(table.getId()) == null || !SETTLE.equals(table.phase())) {
            return;
        }
        cancelLinger(table);
        TableLayout layout = layout(table);
        int seconds = layout != null ? layout.roundEndSeconds() : 10;
        table.setAutoCountdown(seconds);
        TableManager.get().refreshLabel(table);
        String msg = Messages.get("bet.round_end", "seconds", String.valueOf(seconds));
        for (UUID box : table.boxes()) {
            tell(Bukkit.getPlayer(box), msg);
        }
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(Games.plugin, () -> {
            int left = table.autoCountdown() - 1;
            table.setAutoCountdown(left);
            if (left > 0) {
                TableManager.get().refreshLabel(table);
                return;
            }
            cancelLinger(table);
            finishRound(table);
        }, 20L, 20L);
        lingerTimers.put(table.getId(), task);
    }

    private void finishRound(Table table) {
        TableManager manager = TableManager.get();
        for (UUID box : new ArrayList<>(table.boxes())) {
            manager.muckPlayer(table, box);
        }
        manager.muckTable(table, DEALER);
        rounds.remove(table.getId());
        manager.endSession(table);
    }

    /** Boxes play left to right, ordered by where each bucket sits on the felt. */
    private void snapshotBoxes(Table table) {
        table.boxes().clear();
        TableManager manager = TableManager.get();
        List<UUID> order = new ArrayList<>(manager.boxOwners(table));
        Map<UUID, double[]> spots = new HashMap<>();
        for (UUID owner : order) {
            Location at = manager.boxLocation(table, owner);
            spots.put(owner, new double[] {TableLayout.localRight(table, at), TableLayout.localForward(table, at)});
        }
        order.sort(Comparator.comparingDouble((UUID id) -> spots.get(id)[0])
                .thenComparingDouble(id -> spots.get(id)[1])
                .thenComparing(UUID::toString));
        table.boxes().addAll(order);
    }

    /** The turn is always handed out together with the hand it is for. */
    private boolean canAct(Table table, Player player) {
        return PLAY.equals(table.phase()) && player.getUniqueId().equals(table.actor());
    }

    private BjHand currentHand(Table table) {
        List<BjHand> boxHands = boxHands(table);
        int index = table.handIndex();
        if (index >= boxHands.size()) {
            return null;
        }
        return boxHands.get(index);
    }

    private List<BjHand> boxHands(Table table) {
        if (table.boxIndex() >= table.boxes().size()) {
            return List.of();
        }
        return handsOf(table, table.boxes().get(table.boxIndex()));
    }

    private List<HandCard> cardsOf(Table table, BjHand hand) {
        List<HandCard> out = new ArrayList<>();
        for (HandCard held : table.heldBy(hand.owner)) {
            if (held.slot() == hand.slot) {
                out.add(held);
            }
        }
        return out;
    }

    private boolean allBusted(Table table) {
        for (BjHand hand : rounds.getOrDefault(table.getId(), List.of())) {
            if (bestTotal(cardsOf(table, hand)) <= 21) {
                return false;
            }
        }
        return true;
    }

    /** Two cards making twenty-one straight from the deal. A doubled hand always has three. */
    private static boolean isNatural(BjHand hand, List<HandCard> cards) {
        return !hand.fromSplit && cards.size() == 2 && bestTotal(cards) == 21;
    }

    /** Two cards of one rank, or two ten-value cards. Only ever asked about two cards. */
    private static boolean isPair(List<HandCard> cards) {
        Card a = cards.get(0).card();
        Card b = cards.get(1).card();
        if (a.getRank() == b.getRank()) {
            return true;
        }
        return tenValue(a) && tenValue(b);
    }

    private static boolean isAce(HandCard held) {
        return held.card().getRank() == 1;
    }

    private static boolean tenValue(Card card) {
        return !card.isJoker() && card.getRank() >= 10;
    }

    static int bestTotal(List<HandCard> cards) {
        int total = 0;
        int aces = 0;
        for (HandCard held : cards) {
            Card card = held.card();
            if (card.isJoker()) {
                continue;
            }
            int rank = card.getRank();
            if (rank == 1) {
                aces++;
                total += 1;
            } else if (rank >= 11) {
                total += 10;
            } else {
                total += rank;
            }
        }
        while (aces > 0 && total + 10 <= 21) {
            total += 10;
            aces--;
        }
        return total;
    }

    static int hardTotal(List<HandCard> cards) {
        int total = 0;
        for (HandCard held : cards) {
            Card card = held.card();
            if (card.isJoker()) {
                continue;
            }
            int rank = card.getRank();
            if (rank == 1) {
                total += 1;
            } else if (rank >= 11) {
                total += 10;
            } else {
                total += rank;
            }
        }
        return total;
    }

    /** Seventeen with an ace still counting eleven. */
    private static boolean isSoft17(List<HandCard> cards) {
        return bestTotal(cards) == 17 && hardTotal(cards) != 17;
    }

    private static boolean shouldDealerHit(List<HandCard> cards, boolean hitSoft17) {
        int total = bestTotal(cards);
        if (total < 17) {
            return true;
        }
        return hitSoft17 && isSoft17(cards);
    }

    private record DealStep(UUID box, boolean dealer, boolean faceUp) {}

    private static final class BjHand {
        private final UUID owner;
        /** Play order inside the box, and the card fan group. Renumbered on every split. */
        private int slot;
        private int bet;
        private boolean doubled;
        private boolean fromSplit;
        private boolean splitAces;

        private BjHand(UUID owner, int bet) {
            this.owner = owner;
            this.bet = bet;
        }
    }
}
