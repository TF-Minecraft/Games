package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.command.CommandManager;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.BlackjackGame;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.loader.CardLoader;

/** Blackjack rounds across the real game, table, deck and money implementations. */
class TableManagerBlackjackRoundTest extends TableManagerFixture {
    private static final String SET = "blackjack-integration";
    private final BlackjackGame rules = new BlackjackGame();
    private TableLayout previousLayout;
    private int previousInterpolation;
    private int previousRevealFlip;
    private int previousRevealStagger;
    private PlayerMock dealer;
    private Entity shoe;
    private Table table;

    @BeforeEach void setUpBlackjack() {
        previousLayout = Cache.tableLayouts.get("blackjack");
        previousInterpolation = Cache.interpolationTicks;
        previousRevealFlip = Cache.handRevealFlip;
        previousRevealStagger = Cache.handRevealStagger;
        Cache.interpolationTicks = 0;
        Cache.handRevealFlip = 0;
        Cache.handRevealStagger = 0;
        Cache.tableLayouts.put("blackjack", new TableLayout(SET, "Blackjack", "icon", 6,
                Map.of("tray", new TableLayout.PileSlot(0, 2)), null, null, new TableLayout.PileSlot(-2, 0),
                0.5, false, false, 1, 20, 2, TableLayout.VoiceLines.defaults(), null, 0, 1,
                null, 0, 0, 0, 4, false));
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(rules);
        dealer = opponent();
        dealer.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 20));
        dealer.addAttachment(Games.plugin, "games.bet", true);
    }

    @AfterEach void restoreBlackjack() {
        if (table != null) rules.onTableRemoved(table);
        Cache.interpolationTicks = previousInterpolation;
        Cache.handRevealFlip = previousRevealFlip;
        Cache.handRevealStagger = previousRevealStagger;
        if (previousLayout == null) Cache.tableLayouts.remove("blackjack");
        else Cache.tableLayouts.put("blackjack", previousLayout);
    }

    @ParameterizedTest
    @ValueSource(strings = {"double", "split"})
    void blackjackExtraStakeAndSettlementConserveRealCardsAndMoney(String action) throws Exception {
        restoreShoe(fullDeck(), action.equals("double")
                ? List.of("oseni_5", "cerrith_10", "oseni_6", "cerrith_7", "oseni_9")
                : List.of("oseni_1", "cerrith_10", "clubs_1", "cerrith_7", "oseni_10", "oseni_9"));
        openBets();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        stake(player, 2, 0);
        assertEquals(2, gold(player));
        dealRound();
        assertEquals(player.getUniqueId(), table.actor());
        assertEquals(2, table.handOf(player.getUniqueId()).size());
        assertEquals(2, table.tablePile("dealer").size());
        assertFalse(table.tablePile("dealer").getLast().faceUp());

        manager.applyPlayCall(player, action);
        assertEquals(4, manager.ownedDenars(table, player.getUniqueId()));
        assertEquals(0, gold(player), "The extra stake must leave the real inventory before the draw");
        advanceUntil(() -> BlackjackGame.SETTLE.equals(table.phase()));
        List<HandCard> hand = table.handOf(player.getUniqueId());
        if (action.equals("double")) {
            assertEquals(List.of(5, 6, 9), hand.stream().map(held -> held.card().getRank()).toList());
        } else {
            assertEquals(List.of(1, 10), hand.stream().filter(held -> held.slot() == 0)
                    .map(held -> held.card().getRank()).toList());
            assertEquals(List.of(1, 9), hand.stream().filter(held -> held.slot() == 1)
                    .map(held -> held.card().getRank()).toList());
        }
        assertTrue(table.tablePile("dealer").stream().allMatch(HandCard::faceUp));
        assertEquals(8, gold(player), "Both double and split-ace wins pay four profit plus four staked");
        assertEquals(16, gold(dealer));
        assertTrue(table.ledger().isEmpty());
        List<String> received = messages(player);
        assertTrue(received.contains(action.equals("double") ? "bet.doubled" : "bet.split"));
        assertTrue(received.contains("bet.win"));
        assertFalse(received.contains("bet.natural"), "Split twenty-one is an even-money win");
        advanceUntil(() -> !table.live());
        assertTrue(table.getHands().isEmpty());
        assertTrue(table.tablePilesEmpty());
        assertEquals(52, table.getDeck().remaining() + table.getDeck().discarded());
        assertEquals(52, java.util.stream.Stream.concat(table.getDeck().remainingIds().stream(),
                table.getDeck().discardedIds().stream()).distinct().count());
        assertEquals(24, gold(player) + gold(dealer));
    }

    @Test
    void aDealerWhoseShoeRunsDryStandsOnWhatTheyHoldAndTheRoundSettles() throws Exception {
        // Five cards and nothing in the discards: the dealer's second hit finds an empty shoe.
        List<Card> set = List.of(new Card("oseni_10", "oseni", 10, false, "face"),
                new Card("cerrith_2", "cerrith", 2, false, "face"),
                new Card("clubs_10", "clubs", 10, false, "face"),
                new Card("hearts_3", "hearts", 3, false, "face"),
                new Card("oseni_4", "oseni", 4, false, "face"));
        restoreShoe(set, List.of("oseni_10", "cerrith_2", "clubs_10", "hearts_3", "oseni_4"));
        openBets();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        stake(player, 2, 0);
        dealRound();
        assertEquals(player.getUniqueId(), table.actor());

        manager.applyPlayCall(player, "stand");
        advanceUntil(() -> BlackjackGame.SETTLE.equals(table.phase()));
        assertEquals(List.of(2, 3, 4), table.tablePile("dealer").stream().map(held -> held.card().getRank()).toList(),
                "the dealer stands on nine once the shoe has nothing left to give");
        assertEquals(0, table.getDeck().remaining());
        assertEquals(6, gold(player), "twenty beats nine: two staked back plus two won");
        assertEquals(18, gold(dealer));
        assertTrue(messages(player).contains("bet.win"));
        advanceUntil(() -> !table.live());
        assertTrue(table.ledger().isEmpty());
        assertEquals(5, table.getDeck().remaining() + table.getDeck().discarded(), "no card is lost");
    }

    @Test
    void aTableCanBePickedUpAfterABoxOwnerQuitMidRound() throws Exception {
        // Both boxes hold eighteen whichever sits first, and the dealer stands on seventeen.
        restoreShoe(fullDeck(), List.of("oseni_10", "cerrith_10", "clubs_10", "oseni_8", "cerrith_8", "clubs_7"));
        PlayerMock other = opponent();
        openBets();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        stake(player, 2, 0.3);
        stake(other, 2, -0.3);
        dealRound();
        assertEquals(2, table.boxes().size());
        PlayerMock leaver = player.getUniqueId().equals(table.actor()) ? player : other;
        PlayerMock stayer = leaver == player ? other : player;
        UUID leaverId = leaver.getUniqueId();

        manager.onQuit(new PlayerQuitEvent(leaver, "quit"));
        leaver.disconnect();
        assertEquals(4, gold(leaver), "a box that quits takes its stake with it");
        assertEquals(stayer.getUniqueId(), table.actor());
        manager.applyPlayCall(stayer, "stand");
        advanceUntil(() -> BlackjackGame.SETTLE.equals(table.phase()));
        assertEquals(6, gold(stayer), "eighteen beats seventeen");
        assertFalse(table.getHands().containsKey(leaverId), "settling reads the leaver's hand without recreating it");
        advanceUntil(() -> !table.live());
        assertTrue(table.getHands().isEmpty(), "no hand is left behind for anyone");

        UUID id = table.getId();
        Path file = data.resolve("Data/tables/" + id + ".json");
        assertTrue(Files.exists(file));
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(id.toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(dealer);
        manager.onHitEntity(hit);
        assertNull(manager.table(id));
        assertFalse(Files.exists(file), "the picked-up table no longer loads on restart");
        assertTrue(messages(dealer).contains("place.picked_up"));
        assertEquals(1, world.getEntitiesByClass(Item.class).stream()
                .filter(drop -> drop.getItemStack().getType() == Material.PAPER).count(), "the deck comes back");
        assertEquals(18, gold(dealer));
        table = null;
    }

    @Test
    void aBoxThatWalksAwayWhileItsCardTurnsOverLeavesNoHandBehind() throws Exception {
        // Both boxes hold eleven whichever sits first, the first hits to twenty, the dealer holds seventeen.
        restoreShoe(fullDeck(), List.of("oseni_5", "cerrith_5", "clubs_10", "oseni_6", "cerrith_6", "clubs_7",
                "oseni_9"));
        PlayerMock other = opponent();
        openBets();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        stake(player, 2, 0.3);
        stake(other, 2, -0.3);
        dealRound();
        PlayerMock walker = player.getUniqueId().equals(table.actor()) ? player : other;
        PlayerMock stayer = walker == player ? other : player;
        messages(walker);
        manager.startClock();
        manager.applyPlayCall(walker, "hit");
        assertEquals(3, table.heldBy(walker.getUniqueId()).size(), "the card lands at once and turns face up");
        walker.teleport(table.getOrigin().clone().add(40, 0, 0));
        advanceUntil(() -> stayer.getUniqueId().equals(table.actor()));
        tick(5);
        assertFalse(table.getHands().containsKey(walker.getUniqueId()), "showing the card recreates no hand");
        assertEquals(List.of("hand.returned"), messages(walker).stream().filter("hand.returned"::equals).toList(),
                "the hand is returned once, and nothing is left to return again");
        manager.applyPlayCall(stayer, "stand");
        advanceUntil(() -> !table.live());
        manager.stopClock();
        assertTrue(table.getHands().isEmpty());
        assertEquals(4, gold(walker), "walking away takes the stake back");
        assertEquals(2, gold(stayer), "eleven loses to seventeen");
        assertEquals(22, gold(dealer));
        assertEquals(52, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test
    void theRoundCarriesOnWhenABoxWalksAwayWhileItsHitIsInTheAir() throws Exception {
        restoreShoe(fullDeck(), List.of("oseni_5", "cerrith_5", "clubs_10", "oseni_6", "cerrith_6", "clubs_7",
                "oseni_9"));
        PlayerMock other = opponent();
        openBets();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        stake(player, 2, 0.3);
        stake(other, 2, -0.3);
        Cache.handDealTicks = 4;
        dealRound();
        PlayerMock walker = player.getUniqueId().equals(table.actor()) ? player : other;
        PlayerMock stayer = walker == player ? other : player;
        manager.startClock();
        manager.applyPlayCall(walker, "hit");
        assertEquals(45, table.getDeck().remaining(), "the hit card has left the shoe");
        assertEquals(2, table.heldBy(walker.getUniqueId()).size(), "and is still in the air");
        walker.teleport(table.getOrigin().clone().add(40, 0, 0));
        advanceUntil(() -> stayer.getUniqueId().equals(table.actor()));
        assertFalse(table.getHands().containsKey(walker.getUniqueId()));
        manager.applyPlayCall(stayer, "stand");
        advanceUntil(() -> !table.live());
        manager.stopClock();
        assertTrue(table.getHands().isEmpty());
        assertEquals(4, gold(walker), "walking away takes the stake back");
        assertEquals(2, gold(stayer), "eleven loses to seventeen");
        assertEquals(22, gold(dealer));
        assertEquals(52, table.getDeck().remaining() + table.getDeck().discarded(), "the card in the air is not lost");
    }

    @Test
    void theDealCarriesOnWithoutABoxThatWalksAwayWhileItsFirstCardIsInTheAir() throws Exception {
        // The first box's first card is in the air when it walks away; the other box plays on alone.
        restoreShoe(fullDeck(), List.of("oseni_5", "cerrith_10", "clubs_10", "cerrith_9", "clubs_7"));
        PlayerMock other = opponent();
        openBets();
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        other.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 4));
        stake(player, 2, 0.3);
        stake(other, 2, -0.3);
        Cache.handDealTicks = 4;
        assertTrue(dealerCommand("bet", "close"));
        PlayerMock walker = Bukkit.getPlayer(table.boxes().getFirst()) == player ? player : other;
        PlayerMock stayer = walker == player ? other : player;
        manager.startClock();
        clickShoe(dealer);
        assertEquals(51, table.getDeck().remaining(), "the first card has left the shoe");
        walker.teleport(table.getOrigin().clone().add(40, 0, 0));
        advanceUntil(() -> BlackjackGame.PLAY.equals(table.phase()));
        assertEquals(List.of(stayer.getUniqueId()), table.boxes());
        assertEquals(stayer.getUniqueId(), table.actor());
        assertFalse(table.getHands().containsKey(walker.getUniqueId()), "no card follows the box that left");
        assertEquals(List.of(10, 9), table.heldBy(stayer.getUniqueId()).stream()
                .map(held -> held.card().getRank()).toList());
        manager.applyPlayCall(stayer, "stand");
        advanceUntil(() -> !table.live());
        manager.stopClock();
        assertEquals(4, gold(walker));
        assertEquals(6, gold(stayer), "nineteen beats seventeen");
        assertEquals(18, gold(dealer));
        assertEquals(52, table.getDeck().remaining() + table.getDeck().discarded());
    }

    private List<Card> fullDeck() {
        List<Card> deck = new ArrayList<>();
        for (String suit : List.of("oseni", "cerrith", "clubs", "hearts")) {
            for (int rank = 1; rank <= 13; rank++) {
                deck.add(new Card(suit + "_" + rank, suit, rank, false, "face"));
            }
        }
        return deck;
    }

    /** Loads an idle blackjack table dealt by {@code dealer} whose shoe starts with these cards. */
    private void restoreShoe(List<Card> set, List<String> firstCards) throws Exception {
        for (Card card : set) cards.when(() -> CardLoader.get(card.getId())).thenReturn(card);
        cards.when(() -> CardLoader.hasSet(SET)).thenReturn(true);
        cards.when(() -> CardLoader.getSet(SET)).thenReturn(set);
        List<String> order = new ArrayList<>(firstCards);
        set.stream().map(Card::getId).filter(id -> !firstCards.contains(id)).forEach(order::add);
        UUID id = UUID.randomUUID();
        JsonObject saved = new JsonObject();
        saved.addProperty("id", id.toString());
        saved.addProperty("gameId", "blackjack");
        saved.addProperty("world", world.getName());
        saved.addProperty("x", player.getLocation().getX());
        saved.addProperty("y", player.getLocation().getY());
        saved.addProperty("z", player.getLocation().getZ());
        saved.addProperty("yaw", player.getLocation().getYaw());
        saved.addProperty("setName", SET);
        saved.addProperty("ownerPlayer", dealer.getUniqueId().toString());
        saved.addProperty("autoDealer", false);
        saved.addProperty("staffMint", false);
        saved.addProperty("minBet", 1);
        saved.addProperty("maxBet", 20);
        saved.add("remaining", new Gson().toJsonTree(order));
        saved.add("discarded", new JsonArray());
        saved.add("ledger", new JsonArray());
        saved.add("actives", new JsonArray());
        Path folder = data.resolve("Data/tables");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(id + ".json"), saved.toString());
        manager.loadAll();
        table = manager.table(id);
        assertNotNull(table);
        assertEquals(order, table.getDeck().remainingIds(), "Idle restore must retain the complete saved shoe order");
        shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
    }

    /** The dealer takes the shoe and opens the betting window. */
    private void openBets() {
        clickShoe(dealer);
        assertEquals(dealer.getUniqueId(), table.dealerId());
        assertTrue(dealerCommand("bet", "open"));
        assertTrue(table.betOpen());
    }

    /** Stakes one coin per click on the felt, {@code side} blocks across from the first box. */
    private void stake(PlayerMock punter, int coins, double side) {
        for (int i = 0; i < coins; i++) clickFeltAt(punter, side);
        assertEquals(coins, manager.ownedDenars(table, punter.getUniqueId()));
    }

    /** Closes the betting, which starts the round, and deals it until the first turn or the settle. */
    private void dealRound() {
        assertTrue(dealerCommand("bet", "close"));
        assertTrue(table.live());
        clickShoe(dealer);
        advanceUntil(() -> BlackjackGame.PLAY.equals(table.phase()) || BlackjackGame.SETTLE.equals(table.phase()));
    }

    private boolean dealerCommand(String... args) {
        Command command = mock(Command.class);
        when(command.getName()).thenReturn("games");
        return new CommandManager().onCommand(dealer, command, "games", args);
    }

    private void clickShoe(PlayerMock actor) {
        PlayerInteractAtEntityEvent click = new PlayerInteractAtEntityEvent(actor, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(click);
        assertTrue(click.isCancelled());
    }

    private void clickFeltAt(PlayerMock actor, double side) {
        // Supply the block-ray result at the input boundary; game state and transfers remain real.
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        Location hit = table.getOrigin().clone().add(0.75, 0, side);
        doReturn(new RayTraceResult(hit.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent click = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                actor.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(click);
        assertTrue(click.isCancelled());
    }

    private void advanceUntil(BooleanSupplier complete) {
        // Five seconds bounds the configured one-second round linger plus the deal and reveal callbacks.
        for (int elapsed = 0; !complete.getAsBoolean() && elapsed < 100; elapsed++) tick(1);
        assertTrue(complete.getAsBoolean(), "Round callbacks must finish within the configured scheduler budget");
    }

    private static List<String> messages(PlayerMock who) {
        List<String> received = new ArrayList<>();
        String message;
        while ((message = who.nextMessage()) != null) received.add(message);
        return received;
    }

    private static int gold(PlayerMock owner) {
        return owner.getInventory().all(Material.GOLD_NUGGET).values().stream().mapToInt(ItemStack::getAmount).sum();
    }
}
