package net.tfminecraft.games.table;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;

import net.tfminecraft.games.deck.Deck;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.RoundMoney;
import net.tfminecraft.games.wager.TableLedger;
import net.tfminecraft.games.wager.WagerVote;

/**
 * Placed deck / table session. gameId is a stub until Batch 9.
 */
public final class Table {

    private final UUID id;
    private final String gameId;
    private final Location origin;
    private float yaw;
    private Deck deck;
    private final List<UUID> stackTokens = new ArrayList<>();
    private final List<UUID> discardTokens = new ArrayList<>();
    private int recycleGen;
    private int tableDealGen;
    private boolean recycling;
    private final Map<UUID, List<HandCard>> hands = new HashMap<>();
    private final Map<String, List<HandCard>> tablePiles = new HashMap<>();
    private UUID interactionId;
    private UUID labelId;
    private final List<PotPile> piles = new ArrayList<>();
    private final TableLedger ledger = new TableLedger();
    private final RoundMoney roundMoney = new RoundMoney();
    private WagerVote vote;
    private int street = 1;
    private int payoutGen;
    private boolean paying;
    private Runnable payoutOnDone;
    private final List<PayoutFlight> payoutFlying = new ArrayList<>();
    private final LinkedHashSet<UUID> payoutDests = new LinkedHashSet<>();
    private final Set<UUID> actives = new LinkedHashSet<>();
    private boolean live;
    private UUID actor;
    private String phase;
    private final List<UUID> boxes = new ArrayList<>();
    private int boxIndex;
    private int handIndex;
    private UUID dealerId;
    private boolean betOpen;
    private int minBet;
    private int maxBet;
    private int autoCountdown;
    private UUID ownerPlayer;
    private String ownerGuildId;
    private boolean autoDealer;
    private boolean staffMint;
    private int houseFloat;
    private int maxBoxes;
    private ShufflePolicy shufflePolicy = ShufflePolicy.SHOE;
    private int smallBlind;
    private int bigBlind;

    public Table(UUID id, String gameId, Location origin, float yaw, Deck deck) {
        this.id = id;
        this.gameId = gameId;
        this.origin = origin;
        this.yaw = yaw;
        this.deck = deck;
    }

    public UUID getId() {
        return id;
    }

    public String getGameId() {
        return gameId;
    }

    public Location getOrigin() {
        return origin;
    }

    public float getYaw() {
        return yaw;
    }

    public Deck getDeck() {
        return deck;
    }

    public List<UUID> getStackTokens() {
        return stackTokens;
    }

    public List<UUID> getDiscardTokens() {
        return discardTokens;
    }

    public int recycleGen() {
        return recycleGen;
    }

    public int bumpRecycleGen() {
        recycleGen++;
        return recycleGen;
    }

    public int tableDealGen() {
        return tableDealGen;
    }

    public int bumpTableDealGen() {
        tableDealGen++;
        return tableDealGen;
    }

    public boolean isRecycling() {
        return recycling;
    }

    public void setRecycling(boolean recycling) {
        this.recycling = recycling;
    }

    public Map<UUID, List<HandCard>> getHands() {
        return hands;
    }

    /** The hand to deal into, created empty for a player who holds none yet. */
    public List<HandCard> handOf(UUID playerId) {
        return hands.computeIfAbsent(playerId, key -> new ArrayList<>());
    }

    /**
     * The cards a player holds here, or none, without creating a hand. Reading through handOf
     * would leave an empty hand behind for someone who has left, which nothing then returns.
     */
    public List<HandCard> heldBy(UUID playerId) {
        return hands.getOrDefault(playerId, List.of());
    }

    public Map<String, List<HandCard>> tablePiles() {
        return tablePiles;
    }

    public List<HandCard> tablePile(String name) {
        String key = name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
        return tablePiles.computeIfAbsent(key, k -> new ArrayList<>());
    }

    public boolean tablePilesEmpty() {
        for (List<HandCard> pile : tablePiles.values()) {
            if (!pile.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public UUID getInteractionId() {
        return interactionId;
    }

    public void setInteractionId(UUID interactionId) {
        this.interactionId = interactionId;
    }

    public UUID getLabelId() {
        return labelId;
    }

    public void setLabelId(UUID labelId) {
        this.labelId = labelId;
    }

    public List<PotPile> getPiles() {
        return piles;
    }

    /** Every denar this table holds. Piles are only a drawing of it. */
    public TableLedger ledger() {
        return ledger;
    }

    public RoundMoney roundMoney() {
        return roundMoney;
    }

    public WagerVote getVote() {
        return vote;
    }

    public void setVote(WagerVote vote) {
        this.vote = vote;
    }

    public int street() {
        return street;
    }

    public void setStreet(int street) {
        this.street = Math.max(1, street);
    }

    public boolean isPaying() {
        return paying;
    }

    public int payoutGen() {
        return payoutGen;
    }

    public int beginPayout(List<PayoutFlight> flying, Runnable onDone) {
        payoutGen++;
        paying = true;
        payoutOnDone = onDone;
        payoutFlying.clear();
        payoutDests.clear();
        if (flying != null) {
            payoutFlying.addAll(flying);
            for (PayoutFlight flight : flying) {
                if (flight.destId() != null) {
                    payoutDests.add(flight.destId());
                }
            }
        }
        return payoutGen;
    }

    public List<PayoutFlight> payoutFlying() {
        return payoutFlying;
    }

    public Runnable takePayoutOnDone() {
        Runnable done = payoutOnDone;
        payoutOnDone = null;
        return done;
    }

    public void clearPayoutOnDone() {
        payoutOnDone = null;
    }

    public LinkedHashSet<UUID> payoutDests() {
        return payoutDests;
    }

    public void endPayout() {
        paying = false;
        payoutOnDone = null;
        payoutFlying.clear();
        payoutDests.clear();
    }

    public int bumpPayoutGen() {
        payoutGen++;
        return payoutGen;
    }

    public Set<UUID> actives() {
        return actives;
    }

    public boolean live() {
        return live;
    }

    public UUID actor() {
        return actor;
    }

    public void setActor(UUID actor) {
        this.actor = actor;
    }

    public String phase() {
        return phase;
    }

    public void setPhase(String phase) {
        this.phase = phase;
    }

    public List<UUID> boxes() {
        return boxes;
    }

    public int boxIndex() {
        return boxIndex;
    }

    public void setBoxIndex(int boxIndex) {
        this.boxIndex = Math.max(0, boxIndex);
    }

    public int handIndex() {
        return handIndex;
    }

    public void setHandIndex(int handIndex) {
        this.handIndex = Math.max(0, handIndex);
    }

    public UUID dealerId() {
        return dealerId;
    }

    public void setDealerId(UUID dealerId) {
        if (!Objects.equals(this.dealerId, dealerId)) {
            clearBetWindow();
        }
        this.dealerId = dealerId;
    }

    public boolean betOpen() {
        return betOpen;
    }

    public void setBetOpen(boolean betOpen) {
        this.betOpen = betOpen;
    }

    public int minBet() {
        return minBet;
    }

    public void setMinBet(int minBet) {
        this.minBet = Math.max(1, minBet);
        if (this.maxBet < this.minBet) {
            this.maxBet = this.minBet;
        }
    }

    public int maxBet() {
        return maxBet;
    }

    public void setMaxBet(int maxBet) {
        this.maxBet = Math.max(this.minBet, Math.max(1, maxBet));
    }

    public int autoCountdown() {
        return autoCountdown;
    }

    public void setAutoCountdown(int autoCountdown) {
        this.autoCountdown = Math.max(0, autoCountdown);
    }

    public UUID ownerPlayer() {
        return ownerPlayer;
    }

    public void setOwnerPlayer(UUID ownerPlayer) {
        this.ownerPlayer = ownerPlayer;
    }

    public String ownerGuildId() {
        return ownerGuildId;
    }

    public void setOwnerGuildId(String ownerGuildId) {
        this.ownerGuildId = ownerGuildId;
    }

    public boolean autoDealer() {
        return autoDealer;
    }

    public void setAutoDealer(boolean autoDealer) {
        this.autoDealer = autoDealer;
        if (!autoDealer) {
            this.staffMint = false;
        }
    }

    public boolean staffMint() {
        return staffMint;
    }

    public void setStaffMint(boolean staffMint) {
        this.staffMint = staffMint;
        if (staffMint) {
            this.autoDealer = true;
        }
    }

    /**
     * Denars taken out of the guild bank that have not been paid back yet. Money coming back in
     * settles this first, so only what a table earns beyond its own float counts as profit.
     */
    public int houseFloat() {
        return houseFloat;
    }

    public void setHouseFloat(int houseFloat) {
        this.houseFloat = Math.max(0, houseFloat);
    }

    public int maxBoxes() {
        return maxBoxes;
    }

    public void setMaxBoxes(int maxBoxes) {
        this.maxBoxes = Math.max(0, maxBoxes);
    }

    public ShufflePolicy shufflePolicy() {
        return shufflePolicy;
    }

    public void setShufflePolicy(ShufflePolicy shufflePolicy) {
        this.shufflePolicy = shufflePolicy;
    }

    public int smallBlind() {
        return smallBlind;
    }

    public void setSmallBlind(int smallBlind) {
        this.smallBlind = Math.max(0, smallBlind);
    }

    public int bigBlind() {
        return bigBlind;
    }

    public void setBigBlind(int bigBlind) {
        this.bigBlind = Math.max(0, bigBlind);
    }

    public void clearBetWindow() {
        betOpen = false;
        autoCountdown = 0;
    }

    public void startSession() {
        live = true;
    }

    public void clearSession() {
        live = false;
        actor = null;
        phase = null;
        boxes.clear();
        boxIndex = 0;
        handIndex = 0;
        autoCountdown = 0;
        roundMoney.clear();
    }
}
