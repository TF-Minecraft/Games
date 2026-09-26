package net.tfminecraft.games.table;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntConsumer;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;
import org.joml.Vector3f;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;

import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.card.CardNames;
import net.tfminecraft.games.deck.Deck;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.gui.GameSelectGui;
import net.tfminecraft.games.gui.GuiSounds;
import net.tfminecraft.games.game.Game;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.game.LiveCardReturns;
import net.tfminecraft.games.guild.GuildTables;
import net.tfminecraft.games.select.CardSelector;
import net.tfminecraft.games.layout.HandAnchor;
import net.tfminecraft.games.layout.HandLayout;
import net.tfminecraft.games.layout.RevealLayout;
import net.tfminecraft.games.layout.StackLayout;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.layout.TablePileLayout;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.utils.BodyYaw;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.ChipItems;
import net.tfminecraft.games.wager.LedgerAudit;
import net.tfminecraft.games.wager.MoneyAccount;
import net.tfminecraft.games.wager.MoneyLog;
import net.tfminecraft.games.wager.MoneyTx;
import net.tfminecraft.games.wager.PotLayout;
import net.tfminecraft.games.wager.PotPile;
import net.tfminecraft.games.wager.Stake;
import net.tfminecraft.games.wager.TxResult;
import net.tfminecraft.games.wager.WagerChat;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.WagerHost;
import net.tfminecraft.games.wager.WagerPileStyle;
import net.tfminecraft.games.wager.WagerVote;
import net.tfminecraft.games.voice.RpNames;

public final class TableManager implements Listener, WagerHost {

    private static final TableManager INSTANCE = new TableManager();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final long PLACE_TIMEOUT_MS = 30_000L;
    private static final double MIN_DISTANCE = 1.0;
    private static final double FELT_REACH = 5.0;
    private static final double TABLE_Y_SLOP = 3.0;
    private static final double PLACE_TOP_MIN = 0.9;

    private static final long SELECT_COOLDOWN_MS = 200L;
    private static final float INSPECT_BUMP = 0.22f;
    private static final long INSPECT_TICKS = 5L;

    private final Map<UUID, Table> tables = new HashMap<>();
    /** Table id to owner to floating amount label, only used when chips are hidden. */
    private final Map<UUID, Map<UUID, UUID>> bucketLabels = new HashMap<>();
    private final Map<UUID, PlaceArm> arms = new HashMap<>();
    private final Map<UUID, LootArm> lootArms = new HashMap<>();
    private final Map<UUID, Long> selectCooldown = new HashMap<>();
    private final Map<UUID, HandLock> handLocks = new HashMap<>();
    private final Map<UUID, Long> layoutHoldUntil = new HashMap<>();
    private final Map<UUID, Integer> selectAnimGen = new HashMap<>();
    private final Set<UUID> revealedHands = new HashSet<>();
    private final Set<UUID> revealedCardTokens = new HashSet<>();
    private final Set<UUID> revealBusy = new HashSet<>();
    private final Map<UUID, Integer> revealGen = new HashMap<>();
    private final Map<UUID, Integer> dealGen = new HashMap<>();
    private final Map<UUID, List<UUID>> dealCouriers = new HashMap<>();
    private final Map<UUID, List<Card>> dealPendingCards = new HashMap<>();
    private final Set<UUID> tableDealing = new HashSet<>();
    private BukkitTask handClock;
    private int handTicks;

    private TableManager() {
        WagerEngine.init(this);
    }

    public static TableManager get() {
        return INSTANCE;
    }

    private static WagerEngine wager() {
        return WagerEngine.get();
    }

    // ------------------------------------------------------- WagerHost

    /**
     * Everything the money engine needs from a table. Redrawing and saving happen here, once,
     * after a transaction has finished moving money, rather than inside each movement.
     */
    @Override
    public Location anchorFor(Table table, UUID owner) {
        return boxLocation(table, owner);
    }

    @Override
    public void moneyMoved(Table table, Collection<UUID> buckets) {
        for (UUID owner : buckets) {
            syncBucketChips(table, owner);
        }
        save(table);
        notifyFeltPiles(table);
    }

    @Override
    public List<PayoutFlight> flights(Table table, List<Stake> stakes, Location from, UUID destId,
            boolean toTray) {
        return makeFlights(table, stakes, from, destId, toTray);
    }

    public void startClock() {
        stopClock();
        handClock = Bukkit.getScheduler().runTaskTimer(Games.plugin, this::tickHands, 0L, 1L);
    }

    public void stopClock() {
        if (handClock != null) {
            handClock.cancel();
            handClock = null;
        }
    }

    private void tickHands() {
        handTicks++;
        for (Table table : tables.values()) {
            tickAway(table);
            Map<UUID, List<HandCard>> hands = table.getHands();
            if (hands.isEmpty()) {
                continue;
            }
            for (UUID playerId : new ArrayList<>(hands.keySet())) {
                // Nothing in this loop removes a hand, so every key still has its list. An empty one
                // (its first card still landing) shows no dust and lays out no cards.
                List<HandCard> hand = hands.get(playerId);
                // Hands are only ever made for online players, and quitting returns them.
                Player player = Bukkit.getPlayer(playerId);
                if (handTicks % 3 == 0 && anyPublic(hand) && showRevealDust(table)) {
                    spawnRevealDust(player, hand);
                }
                if (layoutHeld(playerId)) {
                    continue;
                }
                layoutHand(table, player, Cache.handFollowTicks, false, null);
            }
        }
    }

    private void spawnRevealDust(Player owner, List<HandCard> hand) {
        DisplayManager displays = DisplayManager.get();
        // Every dealt card is laid out on arrival, which locks the hand, so a lock is always present.
        double yawRad = Math.toRadians(handLocks.get(owner.getUniqueId()).placeYaw());
        double aheadX = -Math.sin(yawRad) * Cache.handRevealDust;
        double aheadZ = Math.cos(yawRad) * Cache.handRevealDust;
        for (HandCard held : hand) {
            if (!revealedCardTokens.contains(held.tokenId())) {
                continue;
            }
            // A revealed card is always a spawned display in the table's world, and the owner is
            // still in that world because tickAway has already returned the hands of anyone who left.
            Location at = displays.worldLocation(held.tokenId()).add(aheadX, 0, aheadZ);
            Particle.DustOptions dust = new Particle.DustOptions(CardNames.suitDust(held.card()), 0.8f);
            owner.spawnParticle(Particle.DUST, at.getX(), at.getY(), at.getZ(), 1, 0, 0, 0, 0, dust);
        }
    }

    private void clearRevealed(UUID playerId, List<HandCard> hand) {
        revealedHands.remove(playerId);
        if (hand == null) {
            return;
        }
        for (HandCard held : hand) {
            revealedCardTokens.remove(held.tokenId());
        }
    }

    public void armPlace(Player player, String gameId) {
        armPlace(player, gameId, true);
    }

    public void armPlace(Player player, String gameId, boolean requireDeck) {
        armPlace(player, gameId, requireDeck, null);
    }

    public void armPlace(Player player, String gameId, boolean requireDeck, TableHouse house) {
        arms.put(player.getUniqueId(),
                new PlaceArm(gameId, System.currentTimeMillis() + PLACE_TIMEOUT_MS, requireDeck, house));
    }

    public void selectGame(Player player, String gameId, Location at, boolean requireDeck) {
        armPlace(player, gameId, requireDeck);
        if (at != null) {
            tryPlace(player, at);
            return;
        }
        player.sendMessage(Messages.get(requireDeck ? "place.armed" : "place.armed_admin"));
    }

    public void loadAll() {
        File folder = tablesFolder();
        File[] files = folder.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) {
            return;
        }
        for (File file : files) {
            try (FileReader reader = new FileReader(file, StandardCharsets.UTF_8)) {
                TableData data = GSON.fromJson(reader, TableData.class);
                if (data == null || data.id == null) {
                    continue;
                }
                List<Stake> unowned = new ArrayList<>();
                Table table = fromData(data, unowned);
                if (table == null) {
                    continue;
                }
                tables.put(table.getId(), table);
                migrateLegacyPiles(table, data, unowned);
                int unownedDenars = unowned.stream().mapToInt(Stake::value).sum();
                LedgerAudit.checkLoaded(table, storedDenars(data), unownedDenars);
                try {
                    boolean stale = (data.actives != null && !data.actives.isEmpty())
                            || !table.ledger().isEmpty() || !unowned.isEmpty();
                    if (stale) {
                        resetTableToIdle(table, table.getOrigin());
                    }
                    if (!unowned.isEmpty()) {
                        Accounts.ground(table, table.getOrigin()).accept(unowned);
                        MoneyLog.note(table, unownedDenars, "items with unknown owners returned to ground");
                    }
                    spawnWorld(table);
                } catch (RuntimeException ex) {
                    despawnWorld(table);
                    tables.remove(table.getId());
                    Games.plugin.getLogger().warning("[Games] Failed to spawn loaded table "
                            + file.getName() + ": " + ex.getMessage());
                }
            } catch (IOException | JsonParseException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to load table " + file.getName() + ": " + ex.getMessage());
            }
        }
        if (Cache.debug) {
            Games.plugin.getLogger().info("[Games] Debug: loaded tables=" + tables.size());
        }
    }

    public void despawnWorldAll() {
        for (Table table : tables.values()) {
            resetTableToIdle(table, table.getOrigin());
            despawnWorld(table);
        }
    }

    /**
     * End the session, settle money, muck cards, full idle deck. Table file stays.
     */
    private void resetTableToIdle(Table table, Location dropAt) {
        cancelVote(table, null);
        cancelLootArmsForTable(table.getId());
        table.bumpRecycleGen();
        table.bumpPayoutGen();
        table.clearSession();
        Game removed = gameOf(table);
        if (removed != null) {
            removed.onTableRemoved(table);
        }
        for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
            discardPlayerCards(table, playerId);
        }
        despawnTablePiles(table);
        settleAutoTray(table, dropAt);
        clearFeltNow(table, null);
        table.actives().clear();
        table.setDealerId(null);
        table.setStreet(1);
        table.getDeck().reshuffleAll();
        save(table);
    }

    /** Draw every table's chips again, for a config reload. */
    public void redrawAllChips() {
        for (Table table : tables.values()) {
            try {
                syncAllChips(table);
                notifyFeltPiles(table);
            } catch (RuntimeException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to redraw chips for table "
                        + table.getId() + ": " + ex.getMessage());
            }
        }
    }

    public void rebuildAllStacks() {
        for (Table table : tables.values()) {
            try {
                rebuildCardStacks(table);
            } catch (RuntimeException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to rebuild table stack: " + ex.getMessage());
            }
        }
    }

    /** Despawn in-hand displays and put those cards on the discard pile. Hands are not persisted. */
    public void wipeHands() {
        for (Table table : tables.values()) {
            for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
                stopDeal(playerId, table);
            }
            DisplayManager displays = DisplayManager.get();
            for (List<HandCard> hand : new ArrayList<>(table.getHands().values())) {
                for (HandCard held : hand) {
                    table.getDeck().discard(held.card());
                    displays.despawn(held.tokenId());
                }
            }
            table.getHands().clear();
            despawnTablePiles(table);
            save(table);
            try {
                rebuildCardStacks(table);
                recycleIfNeeded(table, null);
            } catch (RuntimeException ex) {
                Games.plugin.getLogger().warning("[Games] Failed to rebuild table stack: " + ex.getMessage());
            }
        }
        revealedHands.clear();
        revealedCardTokens.clear();
        revealBusy.clear();
        dealGen.clear();
    }

    public boolean tryPlace(Player player, Location at) {
        PlaceArm arm = arms.get(player.getUniqueId());
        if (arm == null) {
            return false;
        }
        if (at == null || at.getWorld() == null) {
            return false;
        }
        if (System.currentTimeMillis() > arm.expiresAt) {
            arms.remove(player.getUniqueId());
            player.sendMessage(Messages.get("place.expired"));
            return true;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (arm.requireDeck) {
            if (!TLibs.getItemAPI().getChecker().checkItemWithPath(held, CardLoader.getDeckItem())) {
                player.sendMessage(Messages.get("place.need_deck"));
                return true;
            }
        }
        Location origin = at.clone();
        origin.add(0, Cache.tableYOffset, 0);
        origin.setYaw(player.getLocation().getYaw());
        origin.setPitch(0f);
        if (tooClose(origin)) {
            player.sendMessage(Messages.get("place.too_close"));
            return true;
        }
        Optional<Deck> created = Deck.create(Cache.cardSetOf(arm.gameId));
        if (created.isEmpty()) {
            player.sendMessage(Messages.get("place.no_deck"));
            return true;
        }
        Deck deck = created.get();
        deck.shuffle();
        Table table = new Table(UUID.randomUUID(), arm.gameId, origin, player.getLocation().getYaw(), deck);
        TableHouse house = arm.house;
        if (house == null && "blackjack".equalsIgnoreCase(arm.gameId)) {
            house = TableHouse.forPlace(player, Cache.layoutOf("blackjack"));
            GuildTables.stampGuild(player, house);
        }
        if (house != null) {
            if ("blackjack".equalsIgnoreCase(arm.gameId)) {
                String refuse = GuildTables.refuseKey(player, house, null);
                if (refuse != null) {
                    arms.remove(player.getUniqueId());
                    GuiSounds.deny(player);
                    GuildTables.tellRefuse(player, refuse, house);
                    return true;
                }
            }
            house.apply(table);
        } else {
            table.setOwnerPlayer(player.getUniqueId());
            TableLayout layout = Cache.layoutOf(arm.gameId);
            if (layout != null) {
                table.setSmallBlind(layout.smallBlind());
                table.setBigBlind(layout.bigBlind());
            }
        }
        try {
            spawnWorld(table);
        } catch (RuntimeException ex) {
            despawnWorld(table);
            Games.plugin.getLogger().warning("[Games] Failed to spawn table: " + ex.getMessage());
            player.sendMessage(Messages.get("place.spawn_failed"));
            return true;
        }
        tables.put(table.getId(), table);
        save(table);
        if (arm.requireDeck) {
            consumeOne(held, player);
        }
        arms.remove(player.getUniqueId());
        player.sendMessage(Messages.get("place.done", "game", table.getGameId()));
        return true;
    }

    @EventHandler
    public void onInteractBlock(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Player player = event.getPlayer();
        Action action = event.getAction();
        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (tryInspectCard(player)) {
                event.setCancelled(true);
            }
            return;
        }
        if (action != Action.RIGHT_CLICK_BLOCK && action != Action.RIGHT_CLICK_AIR) {
            return;
        }
        Location click = clickHit(event);
        if (arms.containsKey(player.getUniqueId())) {
            if (action != Action.RIGHT_CLICK_BLOCK) {
                return;
            }
            Location origin = tablePlaceOrigin(event);
            if (origin == null) {
                player.sendMessage(Messages.get("place.need_surface"));
                return;
            }
            event.setCancelled(true);
            tryPlace(player, origin);
            return;
        }
        if (trySelectCard(player)) {
            event.setCancelled(true);
            return;
        }
        if (tryLootPlace(player, click)) {
            event.setCancelled(true);
            return;
        }
        if (tryPlaceChip(player, click)) {
            event.setCancelled(true);
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!TLibs.getItemAPI().getChecker().checkItemWithPath(held, CardLoader.getDeckItem())) {
            return;
        }
        if (tableHolding(player.getUniqueId()) != null) {
            return;
        }
        Location origin = tablePlaceOrigin(event);
        if (origin == null) {
            return;
        }
        event.setCancelled(true);
        GameSelectGui.open(player, true, origin);
    }

    @EventHandler
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        if (arms.remove(event.getPlayer().getUniqueId()) != null) {
            event.getPlayer().sendMessage(Messages.get("place.cancelled"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        arms.remove(event.getPlayer().getUniqueId());
        clearLootArm(event.getPlayer().getUniqueId());
        onWagerQuit(event.getPlayer());
        leaveIfAtTable(event.getPlayer(), true);
        clearDealer(event.getPlayer());
        selectCooldown.remove(event.getPlayer().getUniqueId());
        handLocks.remove(event.getPlayer().getUniqueId());
        layoutHoldUntil.remove(event.getPlayer().getUniqueId());
        selectAnimGen.remove(event.getPlayer().getUniqueId());
        clearRevealed(event.getPlayer().getUniqueId(), null);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player player = event.getPlayer();
        Table table = tableHolding(player.getUniqueId());
        if (table == null) {
            return;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (!allowRevealToggle(table, player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (revealBusy.contains(id)) {
            return;
        }
        playCardSound(player);
        List<HandCard> band = revealBand(hand);
        boolean show = !anyPublic(band);
        startRevealSequence(table, player, hand, band, show);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Table table = tableFrom(event.getRightClicked());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        Player player = event.getPlayer();
        if (arms.containsKey(player.getUniqueId())) {
            return;
        }
        markSelectCooldown(player);
        if (player.isSneaking()) {
            if (tryOpenHouseOptions(table, player)) {
                return;
            }
            tryManualFlush(table, player);
            return;
        }
        if (table.live()) {
            Game game = gameOf(table);
            if (game == null) {
                return;
            }
            if (game instanceof LiveCardReturns returns && game.allowReturnSelected(table, player)) {
                int n = countSelected(table, player);
                if (n > 0 && tryReturnSelected(table, player)) {
                    returns.onReturnedSelected(table, player, n);
                    return;
                }
            }
            game.onShoeClick(table, player);
            return;
        }
        Game idleGame = gameOf(table);
        if (idleGame != null && idleGame.tryClaimDealer(table, player)) {
            return;
        }
        if (allowReturnSelected(table, player) && tryReturnSelected(table, player)) {
            return;
        }
        if (allowFreeDraw(table, player)) {
            tryDraw(player, table);
        }
    }

    // Retain Bukkit chat-event ordering and String message semantics for existing integrations.
    @SuppressWarnings("deprecation")
    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlayChat(AsyncPlayerChatEvent event) {
        String action = playWord(event.getMessage());
        if (action == null) {
            return;
        }
        Player player = event.getPlayer();
        if (!isPlayActor(player)) {
            return;
        }
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(Games.plugin, () -> applyPlayCall(player, action));
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        Table table = tableFrom(event.getEntity());
        if (table == null) {
            return;
        }
        event.setCancelled(true);
        pickup(player, table);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (Table table : tables.values()) {
            Location origin = table.getOrigin();
            if (!origin.getWorld().equals(world)) {
                continue;
            }
            if ((origin.getBlockX() >> 4) != cx || (origin.getBlockZ() >> 4) != cz) {
                continue;
            }
            ensureAnchors(table);
            rebuildCardStacks(table);
            rebuildTablePiles(table);
            syncAllChips(table);
        }
    }

    private void pickup(Player player, Table table) {
        cancelVote(table, "wager.cancelled");
        table.bumpRecycleGen();
        cancelLootArmsForTable(table.getId());
        Location dropAt = table.getOrigin().clone();
        settleAutoTray(table, dropAt);
        clearFeltNow(table, player);
        returnAllHands(table, false, false);
        table.actives().clear();
        table.clearSession();
        table.setDealerId(null);
        Game removed = gameOf(table);
        if (removed != null) {
            removed.onTableRemoved(table);
        }
        String guildId = table.ownerGuildId();
        despawnWorld(table);
        tables.remove(table.getId());
        deleteFile(table.getId());
        ItemStack deckItem = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getDeckItem());
        if (deckItem != null) {
            dropAt.getWorld().dropItemNaturally(dropAt, deckItem);
        }
        player.sendMessage(Messages.get("place.picked_up"));
        refreshGuildAutoIdle(guildId);
    }

    private void spawnWorld(Table table) {
        rebuildCardStacks(table);
        rebuildTablePiles(table);
        syncAllChips(table);
        ensureAnchors(table);
        Game ready = gameOf(table);
        if (ready != null) {
            ready.onTableReady(table);
        }
    }

    private void ensureAnchors(Table table) {
        Location origin = table.getOrigin();
        Entity interaction = table.getInteractionId() != null ? Bukkit.getEntity(table.getInteractionId()) : null;
        if (interaction == null || interaction.isDead()) {
            var spawned = WorldAnchors.spawnInteraction(
                    origin.clone(), 0.55f, 0.2f, table.getId().toString(), table.getId().toString());
            table.setInteractionId(spawned != null ? spawned.getUniqueId() : null);
        }
        Entity label = table.getLabelId() != null ? Bukkit.getEntity(table.getLabelId()) : null;
        if (label == null || label.isDead()) {
            var spawned = WorldAnchors.spawnLabel(origin.clone().add(0, 0.25, 0), tableLabel(table));
            table.setLabelId(spawned != null ? spawned.getUniqueId() : null);
        } else {
            WorldAnchors.setText(table.getLabelId(), tableLabel(table));
        }
    }

    public void refreshLabel(Table table) {
        if (table.getLabelId() == null) {
            ensureAnchors(table);
            return;
        }
        WorldAnchors.setText(table.getLabelId(), tableLabel(table));
    }

    private static String tableLabel(Table table) {
        String base = Cache.labelOf(table.getGameId());
        StringBuilder text = new StringBuilder(Messages.get("label.title", "name", base));
        boolean auto = table.autoDealer();
        UUID dealer = table.dealerId();
        if ("blackjack".equalsIgnoreCase(table.getGameId())) {
            text.append("\n").append(Messages.get(table.shufflePolicy() == ShufflePolicy.ROUND
                    ? "label.shuffle_round" : "label.shuffle_shoe"));
        }
        String guildName = GuildTables.displayName(table.ownerGuildId());
        if (guildName != null) {
            text.append("\n").append(Messages.get("label.owner", "name", guildName));
        }
        Game game = gameOf(table);
        boolean stockDealer = game == null || game.showStockDealer();
        if (stockDealer) {
            if (auto && dealer == null) {
                text.append("\n").append(Messages.get("label.dealer", "name", "Auto"));
            } else if (dealer != null) {
                text.append("\n").append(Messages.get("label.dealer", "name", RpNames.of(dealer)));
            }
        }
        if (table.minBet() > 0 || table.maxBet() > 0) {
            String min = table.minBet() > 0 ? String.valueOf(table.minBet()) : "-";
            // Table keeps the maximum at least 1 and never below the minimum, so it is always set here.
            String max = String.valueOf(table.maxBet());
            text.append("\n").append(Messages.get("label.limits", "min", min, "max", max));
        }
        if (table.betOpen()) {
            text.append("\n").append(Messages.get("label.open"));
        }
        if (table.autoCountdown() > 0) {
            String seconds = String.valueOf(table.autoCountdown());
            String key = "label.countdown";
            if (table.live()) {
                key = "label.countdown_round";
            } else if (table.betOpen()) {
                key = "label.countdown_bets";
            }
            text.append("\n").append(Messages.get(key, "seconds", seconds));
        }
        if (game != null) {
            String extra = game.extraLabel(table);
            if (extra != null && !extra.isBlank()) {
                text.append("\n").append(extra);
            }
        }
        return text.toString();
    }

    private void clearDealer(Player player) {
        UUID id = player.getUniqueId();
        for (Table table : tables.values()) {
            if (id.equals(table.dealerId())) {
                table.setDealerId(null);
                Game game = gameOf(table);
                if (game != null) {
                    game.onDealerGone(table);
                } else {
                    refreshLabel(table);
                }
            }
        }
    }

    void rebuildCardStacks(Table table) {
        rebuildShoeStack(table);
        rebuildDiscardStack(table);
    }

    private void rebuildShoeStack(Table table) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : table.getStackTokens()) {
            displays.despawn(token);
        }
        table.getStackTokens().clear();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null) {
            return;
        }
        int layers = StackLayout.visibleLayers(table.getDeck().remaining(), table.getDeck().size());
        Location origin = table.getOrigin();
        DisplayPose pose = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        for (int i = 0; i < layers; i++) {
            UUID tokenId = stackTokenId(table.getId(), i);
            Location layerOrigin = origin.clone().add(0, i * Cache.stackLayerGap, 0);
            if (!displays.spawn(tokenId, layerOrigin, back, pose)) {
                throw new IllegalStateException("Failed to spawn stack layer " + i);
            }
            table.getStackTokens().add(tokenId);
        }
    }

    private void rebuildDiscardStack(Table table) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : table.getDiscardTokens()) {
            displays.despawn(token);
        }
        table.getDiscardTokens().clear();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null || table.getDeck().discarded() <= 0) {
            return;
        }
        int layers = StackLayout.visibleLayers(table.getDeck().discarded(), table.getDeck().size());
        Location origin = discardOrigin(table);
        DisplayPose pose = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        for (int i = 0; i < layers; i++) {
            UUID tokenId = discardTokenId(table.getId(), i);
            Location layerOrigin = origin.clone().add(0, i * Cache.stackLayerGap, 0);
            if (!displays.spawn(tokenId, layerOrigin, back, pose)) {
                throw new IllegalStateException("Failed to spawn discard layer " + i);
            }
            table.getDiscardTokens().add(tokenId);
        }
    }

    private void rebuildTablePiles(Table table) {
        DisplayManager displays = DisplayManager.get();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        Location origin = table.getOrigin();
        for (Map.Entry<String, List<HandCard>> entry : table.tablePiles().entrySet()) {
            List<HandCard> pile = entry.getValue();
            int n = pile.size();
            for (int i = 0; i < n; i++) {
                HandCard held = pile.get(i);
                DisplayPose pose = pileSlot(table, entry.getKey(), i, n, held.faceUp());
                ItemStack item = back;
                if (held.faceUp()) {
                    ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
                    if (face != null) {
                        item = face;
                    }
                }
                if (displays.worldLocation(held.tokenId()) == null) {
                    if (item == null || !displays.spawn(held.tokenId(), origin, item, pose)) {
                        continue;
                    }
                } else {
                    displays.setTransform(held.tokenId(), pose, 0);
                    if (item != null) {
                        displays.setItem(held.tokenId(), item);
                    }
                }
            }
        }
        notifyTablePiles(table);
    }

    private void layoutTablePile(Table table, String pile, int durationTicks) {
        List<HandCard> cards = table.tablePile(pile);
        int n = cards.size();
        DisplayManager displays = DisplayManager.get();
        for (int i = 0; i < n; i++) {
            HandCard held = cards.get(i);
            displays.setTransform(held.tokenId(), pileSlot(table, pile, i, n, held.faceUp()),
                    durationTicks);
        }
        notifyTablePiles(table);
    }

    /** Clear every public pile and put its cards on the discard. */
    private void despawnTablePiles(Table table) {
        table.bumpTableDealGen();
        DisplayManager displays = DisplayManager.get();
        for (List<HandCard> pile : table.tablePiles().values()) {
            for (HandCard held : pile) {
                displays.despawn(held.tokenId());
                table.getDeck().discard(held.card());
            }
        }
        table.tablePiles().clear();
        tableDealing.remove(table.getId());
        notifyTablePiles(table);
    }

    private boolean drawOneToTable(Table table, String pile, boolean faceUp, Runnable after, Runnable onFailure) {
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null) {
            return false;
        }
        if (table.getDeck().remaining() == 0) {
            if (table.getDeck().discarded() == 0) {
                return false;
            }
            if (table.isRecycling()) {
                return false;
            }
            int gen = table.tableDealGen();
            // recycleIfNeeded runs this straight away or from finishRecycle, on this same table.
            recycleIfNeeded(table, () -> {
                if (table.tableDealGen() != gen) {
                    tableDealing.remove(table.getId());
                    return;
                }
                // A round-shuffled blackjack shoe is not refilled mid-round, so trying again would loop.
                if (table.getDeck().remaining() == 0 || !drawOneToTable(table, pile, faceUp, after, onFailure)) {
                    onFailure.run();
                }
            });
            return true;
        }
        int dealGen = table.tableDealGen();
        Card card = table.getDeck().draw().orElseThrow();
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(card.getItem());
        List<HandCard> cards = table.tablePile(pile);
        int destIndex = cards.size();
        int destCount = destIndex + 1;
        rebuildCardStacks(table);
        save(table);
        DisplayPose end = pileSlot(table, pile, destIndex, destCount, faceUp);
        ItemStack show = faceUp && face != null ? face : back;
        if (Cache.handDealTicks <= 0) {
            UUID tokenId = UUID.randomUUID();
            if (!DisplayManager.get().spawn(tokenId, table.getOrigin(), show, end)) {
                table.getDeck().discard(card);
                rebuildCardStacks(table);
                save(table);
                return false;
            }
            HandCard held = new HandCard(card, tokenId, faceUp);
            cards.add(held);
            layoutTablePile(table, pile, 0);
            save(table);
            playCardSound(table.getOrigin());
            Bukkit.getScheduler().runTaskLater(Games.plugin, after, 1L);
            return true;
        }
        float stackTopY = stackTopOffset(StackLayout.visibleLayers(table.getDeck().remaining(),
                table.getDeck().size()));
        Location courierOrigin = table.getOrigin().clone().add(0, stackTopY, 0);
        DisplayPose start = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        UUID courierId = UUID.randomUUID();
        if (!DisplayManager.get().spawn(courierId, courierOrigin, show, start)) {
            table.getDeck().discard(card);
            rebuildCardStacks(table);
            save(table);
            return false;
        }
        playCardSound(table.getOrigin());
        DisplayPose endFromShoe = poseOnStackOrigin(end, stackTopY);
        UUID tableId = table.getId();
        // flyTableCourier only arrives while the table is still placed.
        flyTableCourier(tableId, courierId, start, endFromShoe, () -> {
            Table still = tables.get(tableId);
            DisplayManager.get().despawn(courierId);
            if (still.tableDealGen() != dealGen) {
                still.getDeck().discard(card);
                tableDealing.remove(tableId);
                rebuildCardStacks(still);
                save(still);
                return;
            }
            UUID tokenId = UUID.randomUUID();
            if (!DisplayManager.get().spawn(tokenId, still.getOrigin(), show, end)) {
                still.getDeck().discard(card);
                rebuildCardStacks(still);
                save(still);
                onFailure.run();
                return;
            }
            still.tablePile(pile).add(new HandCard(card, tokenId, faceUp));
            layoutTablePile(still, pile, 0);
            save(still);
            after.run();
        });
        return true;
    }

    private void flyTableCourier(UUID tableId, UUID courierId, DisplayPose start, DisplayPose end, Runnable onArrive) {
        DisplayManager displays = DisplayManager.get();
        displays.setTransform(courierId, start, 0);
        int ticks = Math.max(1, Cache.handDealTicks);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (tables.get(tableId) == null) {
                displays.despawn(courierId);
                return;
            }
            for (int step = 1; step <= ticks; step++) {
                final int s = step;
                Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                    if (tables.get(tableId) == null) {
                        displays.despawn(courierId);
                        return;
                    }
                    float t = s / (float) ticks;
                    float ease = 1f - (1f - t) * (1f - t);
                    displays.setTransform(courierId, lerpPose(start, end, ease), 1);
                    if (s == ticks) {
                        onArrive.run();
                    }
                }, s);
            }
        }, 1L);
    }

    static Location discardOrigin(Table table) {
        Location origin = table.getOrigin().clone();
        double yawRad = Math.toRadians(table.getYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        origin.add(rx * Cache.tableDiscardOffset, 0, rz * Cache.tableDiscardOffset);
        return origin;
    }

    public void reshuffleFull(Table table) {
        table.getDeck().reshuffleAll();
        rebuildCardStacks(table);
        save(table);
    }

    private void recycleIfNeeded(Table table, Runnable after) {
        boolean blackjack = "blackjack".equalsIgnoreCase(table.getGameId());
        if (blackjack && table.shufflePolicy() == ShufflePolicy.ROUND) {
            runPending(after);
            return;
        }
        boolean emptyShoe = table.getDeck().remaining() == 0;
        boolean idle = table.getHands().isEmpty() && table.tablePilesEmpty();
        boolean should = table.getDeck().discarded() > 0 && emptyShoe;
        // ROUND returned above, and a policy is always SHOE or ROUND, so this is the SHOE case.
        if (blackjack) {
            if (!should) {
                runPending(after);
                return;
            }
            startRecycle(table, after);
            return;
        }
        if (table.getDeck().discarded() == 0 || (!emptyShoe && !idle)) {
            runPending(after);
            return;
        }
        startRecycle(table, after);
    }

    private void startRecycle(Table table, Runnable after) {
        if (table.isRecycling()) {
            return;
        }
        table.setRecycling(true);
        int gen = table.bumpRecycleGen();
        UUID tableId = table.getId();
        int ticks = Cache.tableRecycleTicks;
        List<UUID> tokens = new ArrayList<>(table.getDiscardTokens());
        if (ticks <= 0 || tokens.isEmpty()) {
            finishRecycle(table, after);
            return;
        }
        Location shoe = table.getOrigin();
        Location discard = discardOrigin(table);
        float dx = (float) (shoe.getX() - discard.getX());
        float dz = (float) (shoe.getZ() - discard.getZ());
        DisplayPose start = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        DisplayPose end = start.withTranslation(dx, 0f, dz);
        DisplayManager displays = DisplayManager.get();
        UUID last = tokens.get(tokens.size() - 1);
        for (UUID token : tokens) {
            displays.setTransform(token, start, 0);
        }
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            Table still = tables.get(tableId);
            if (still == null || still.recycleGen() != gen) {
                return;
            }
            for (int step = 1; step <= ticks; step++) {
                final int s = step;
                Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                    Table live = tables.get(tableId);
                    if (live == null || live.recycleGen() != gen) {
                        return;
                    }
                    float t = s / (float) ticks;
                    float ease = 1f - (1f - t) * (1f - t);
                    DisplayPose pose = lerpPose(start, end, ease);
                    for (UUID token : tokens) {
                        displays.setTransform(token, pose, 1);
                    }
                    if (s == ticks) {
                        displays.setTransform(last, pose, 1);
                        finishRecycle(live, after);
                    }
                }, s);
            }
        }, 1L);
    }

    private void finishRecycle(Table table, Runnable after) {
        table.setRecycling(false);
        table.getDeck().recycle();
        rebuildCardStacks(table);
        save(table);
        runPending(after);
    }

    private void despawnWorld(Table table) {
        table.bumpRecycleGen();
        table.setRecycling(false);
        despawnHands(table);
        despawnTablePiles(table);
        despawnPiles(table);
        for (UUID token : table.getStackTokens()) {
            DisplayManager.get().despawn(token);
        }
        table.getStackTokens().clear();
        for (UUID token : table.getDiscardTokens()) {
            DisplayManager.get().despawn(token);
        }
        table.getDiscardTokens().clear();
        WorldAnchors.remove(table.getInteractionId());
        WorldAnchors.remove(table.getLabelId());
        table.setInteractionId(null);
        table.setLabelId(null);
        tableDealing.remove(table.getId());
    }

    public void proposeLoot(Player player, int denars) {
        Table table = tableWhereActive(player.getUniqueId());
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return;
        }
        if (table.getVote() != null || lootArms.containsKey(player.getUniqueId())) {
            player.sendMessage(Messages.get("wager.busy"));
            return;
        }
        if (table.isPaying()) {
            player.sendMessage(Messages.get("wager.paying"));
            return;
        }
        if ("blackjack".equalsIgnoreCase(table.getGameId())) {
            player.sendMessage(Messages.get("wager.coins_only"));
            return;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held.isEmpty()) {
            player.sendMessage(Messages.get("wager.need_item"));
            return;
        }
        if (ChipItems.isChip(held) || (ChipItems.isChipKind(held) && !ChipItems.needsDeclaredValue(held))) {
            player.sendMessage(Messages.get("wager.use_click"));
            return;
        }
        if (Cache.wagerMinPlayers >= 2 && table.actives().size() < Cache.wagerMinPlayers) {
            player.sendMessage(Messages.get("wager.need_players"));
            return;
        }
        ItemStack snapshot = held.clone();
        Set<UUID> eligible = eligibleVoters(table, player.getUniqueId());
        if (eligible.isEmpty()) {
            messageActives(table, Messages.get("wager.accepted", "player", player.getName()));
            armLootPlace(player, table, snapshot, denars);
            return;
        }
        WagerVote vote = new WagerVote(player.getUniqueId(), snapshot, denars);
        vote.eligible().addAll(eligible);
        table.setVote(vote);
        UUID tableId = table.getId();
        vote.setExpireTask(Bukkit.getScheduler().runTaskLater(Games.plugin, () -> expireVote(tableId),
                Cache.wagerVoteSeconds * 20L));
        broadcastProposed(table, player.getName(), snapshot, denars);
    }

    public void commitStreet(Player player) {
        Table table = tableWhereActive(player.getUniqueId());
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_table"));
            return;
        }
        if (table.getVote() != null) {
            player.sendMessage(Messages.get("wager.busy"));
            return;
        }
        if (table.isPaying()) {
            player.sendMessage(Messages.get("wager.paying"));
            return;
        }
        UUID owner = player.getUniqueId();
        int street = table.street();
        int value = table.ledger().total(owner, street);
        Game game = GamesRegistry.of(table.getGameId());
        int need = game != null ? Math.max(0, game.denarsToMatch(table, player)) : 0;
        if (value < need) {
            // need is only above zero when a game asked for it, so game is set here.
            refundStreet(table, player, street);
            save(table);
            game.onStreetCommit(table, player, value, true);
            player.sendMessage(Messages.get("wager.folded"));
            return;
        }
        if (game != null) {
            game.onStreetCommit(table, player, value, false);
        }
        player.sendMessage(Messages.get("wager.committed",
                "value", String.valueOf(value),
                "need", String.valueOf(need)));
    }

    public Table tableNear(Player player) {
        FeltHit felt = findFelt(player, null);
        return felt != null ? felt.table() : null;
    }

    /** Felt look, else nearest shoe origin within that game's leave-distance. */
    public Table tableNearby(Player player) {
        Table felt = tableNear(player);
        if (felt != null) {
            return felt;
        }
        Location loc = player.getLocation();
        Table best = null;
        double bestDist = Double.MAX_VALUE;
        for (Table table : tables.values()) {
            if (!atTable(player, table)) {
                continue;
            }
            double dist = table.getOrigin().distance(loc);
            if (dist < bestDist) {
                best = table;
                bestDist = dist;
            }
        }
        return best;
    }

    public Table table(UUID id) {
        return id == null ? null : tables.get(id);
    }

    public Collection<Table> tables() {
        return Collections.unmodifiableCollection(tables.values());
    }

    public boolean hasNonDealerOwnedPile(Table table) {
        return !boxOwners(table).isEmpty();
    }

    /** Everyone with money on the felt: not the tray, not the dealer's own chips. */
    public List<UUID> boxOwners(Table table) {
        List<UUID> out = new ArrayList<>();
        UUID dealer = table.dealerId();
        UUID house = table.getId();
        for (UUID owner : table.ledger().owners()) {
            if (owner.equals(house) || owner.equals(dealer)) {
                continue;
            }
            out.add(owner);
        }
        return out;
    }

    /** True if a player box (non-tray) has at least minBet on the felt. */
    public boolean hasLegalBlackjackBox(Table table) {
        int min = table.minBet();
        for (UUID owner : boxOwners(table)) {
            if (table.ledger().total(owner) >= min) {
                return true;
            }
        }
        return false;
    }

    public int ownedDenars(Table table, UUID owner) {
        return table.ledger().total(owner);
    }

    /**
     * Where this owner's chips are drawn. The ledger remembers the spot they first bet on,
     * so it survives a payout that leaves them with nothing on the felt.
     */
    public Location boxLocation(Table table, UUID owner) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location fallback = layout != null ? layout.feltCenter(table) : table.getOrigin().clone();
        if (owner == null) {
            return fallback;
        }
        if (owner.equals(table.getId())) {
            Location tray = layout != null ? layout.trayLocation(table) : null;
            return tray != null ? tray : fallback;
        }
        if (table.ledger().hasAnchor(owner)) {
            Location at = table.getOrigin().clone();
            at.setX(table.ledger().anchorX(owner));
            at.setZ(table.ledger().anchorZ(owner));
            return at;
        }
        Player online = Bukkit.getPlayer(owner);
        if (online != null) {
            Location pad = betPadCenter(table, online);
            if (pad != null) {
                return pad;
            }
        }
        return fallback;
    }

    /**
     * Put money into a bucket, in whole units of the template item, and redraw its chips.
     * Returns the denars actually added, which is always a whole number of coins.
     */
    /**
     * Draw a bucket's chips from the ledger. Chips carry no value, so this can run whenever:
     * it clears what is there and lays the stakes out again.
     */
    public void syncBucketChips(Table table, UUID owner) {
        clearBucketChips(table, owner);
        if (!Cache.wagerShowChips) {
            return;
        }
        Location anchor = boxLocation(table, owner);
        boolean tray = owner.equals(table.getId());
        int slot = 0;
        for (Stake stake : table.ledger().stakes(owner)) {
            ItemStack one = stake.item().clone();
            one.setAmount(1);
            WagerPileStyle style = ChipItems.pileStyle(one);
            int per = Math.max(1, chipPieces(one));
            int room = Math.max(1, PotLayout.room(0, style));
            int left = stake.count();
            Location spot = stakeSpot(table, stake);
            int overflow = 0;
            while (left > 0) {
                int add = Math.max(1, Math.min(left, room / per));
                Location at;
                if (spot != null) {
                    // A heap somebody put here. Anything taller than one stack piles up beside it.
                    at = spreadSlot(table, spot, overflow++);
                } else {
                    at = tray ? nextTraySlot(table) : spreadSlot(table, anchor, slot++);
                }
                PotPile pile = new PotPile(owner, one.clone(), stake.typeKey(), stake.unit(),
                        at.getX(), at.getZ());
                pile.setPieces(add * per);
                pile.setStreetId(stake.streetId());
                if (!rebuildPile(table, pile)) {
                    despawnPile(pile);
                    return;
                }
                table.getPiles().add(pile);
                left -= add;
            }
        }
    }

    /** Redraw every bucket, for load, chunk load, and the show-chips toggle. */
    public void syncAllChips(Table table) {
        for (PotPile pile : new ArrayList<>(table.getPiles())) {
            despawnPile(pile);
        }
        table.getPiles().clear();
        for (UUID owner : table.ledger().owners()) {
            syncBucketChips(table, owner);
        }
    }

    private void clearBucketChips(Table table, UUID owner) {
        for (PotPile pile : new ArrayList<>(table.getPiles())) {
            if (owner.equals(pile.ownerId())) {
                despawnPile(pile);
                table.getPiles().remove(pile);
            }
        }
    }

    /**
     * The spot a stake was put on, or null when nobody chose one and a layout decides instead.
     * Bounds were checked when it was placed, so this is taken at face value.
     */
    private static Location stakeSpot(Table table, Stake stake) {
        if (!stake.placed()) {
            return null;
        }
        Location at = table.getOrigin().clone();
        at.setX(stake.x());
        at.setZ(stake.z());
        return at;
    }

    /** Chips past the first spill outwards from the bucket anchor. */
    private Location spreadSlot(Table table, Location anchor, int index) {
        if (index <= 0) {
            return anchor.clone();
        }
        double step = Math.max(0.12, Cache.wagerMergeRange);
        int[] walk = spiralStep(index);
        double forward = TableLayout.localForward(table, anchor) + walk[1] * step;
        double right = TableLayout.localRight(table, anchor) + walk[0] * step;
        return TableLayout.fromLocal(table, forward, right);
    }

    private static int[] spiralStep(int index) {
        int x = 0;
        int z = 0;
        int dx = 0;
        int dz = -1;
        for (int n = 0; n < index; n++) {
            if (x == z || (x < 0 && x == -z) || (x > 0 && x == 1 - z)) {
                int t = dx;
                dx = -dz;
                dz = t;
            }
            x += dx;
            z += dz;
        }
        return new int[] {x, z};
    }

    private Location nextTraySlot(Table table) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location tray = layout != null ? layout.trayLocation(table) : null;
        if (tray == null) {
            return table.getOrigin().clone();
        }
        for (int i = 0; i < 80; i++) {
            Location slot = trayGridAt(table, layout, tray, i);
            if (!inTrayZone(table, slot)) {
                continue;
            }
            if (!traySlotTaken(table, slot)) {
                return slot;
            }
        }
        return tray.clone();
    }

    private static Location trayGridAt(Table table, TableLayout layout, Location tray, int index) {
        if (index <= 0) {
            return tray.clone();
        }
        TableLayout.PileSlot origin = layout.pile("tray");
        double step = Math.max(0.12, Cache.wagerMergeRange);
        int[] walk = spiralStep(index);
        return TableLayout.fromLocal(table, origin.forward() + walk[1] * step, origin.right() + walk[0] * step);
    }

    private boolean traySlotTaken(Table table, Location slot) {
        double max = Cache.wagerMergeRange;
        double maxSq = max * max;
        for (PotPile pile : table.getPiles()) {
            if (!isTrayPile(table, pile)) {
                continue;
            }
            double dx = pile.x() - slot.getX();
            double dz = pile.z() - slot.getZ();
            if (dx * dx + dz * dz <= maxSq) {
                return true;
            }
        }
        return false;
    }

    public ItemStack feltItem(Table table, UUID owner) {
        ItemStack template = table.ledger().template(owner);
        if (template == null) {
            return null;
        }
        ItemStack one = template.clone();
        one.setAmount(1);
        return one;
    }

    private static int chipPieces(ItemStack stack) {
        ChipItems.DecoChips deco = ChipItems.decoChips(stack);
        if (deco != null) {
            return Math.max(1, deco.pieces());
        }
        return 1;
    }

    /** Denars of one of these items. 0 when the item cannot hold a whole denar. */
    public int chipUnitDenars(ItemStack stack) {
        return ChipItems.unitDenars(stack);
    }

    /** The house tray is just another bucket, keyed by the table itself. */
    public UUID trayOwner(Table table) {
        return table.getId();
    }

    /** Money sitting in the tray. */
    public int trayDenars(Table table) {
        return table.ledger().total(table.getId());
    }

    /** Bank the auto tray without picking the table up. Used when a human takes the shoe. */
    public void bankAutoTray(Table table) {
        settleAutoTray(table, table.getOrigin());
    }

    public void beginSession(Table table) {
        if (table.live()) {
            return;
        }
        if (!GuildTables.canStartGuildAutoRound(table)) {
            return;
        }
        table.startSession();
        Game game = gameOf(table);
        if (game != null) {
            game.onSessionStart(table);
        }
    }

    public void tryBeginSession(Table table) {
        if (table.live()) {
            return;
        }
        Game game = gameOf(table);
        int min = game != null ? game.minActives() : 0;
        if (min < 1 || table.actives().size() < min) {
            return;
        }
        beginSession(table);
    }

    private static void notifyChipIn(Table table, Player player, int denars, ItemStack item) {
        Game game = gameOf(table);
        if (game != null) {
            game.onChipIn(table, player, denars, item);
        }
    }

    public void dealToPlayer(Table table, Player player, int n) {
        dealToPlayer(table, player, n, 0, null);
    }

    public void dealToPlayer(Table table, Player player, int n, Runnable after) {
        dealToPlayer(table, player, n, 0, after);
    }

    public void dealToPlayer(Table table, Player player, int n, int slot, Runnable after) {
        Runnable done = orNothing(after);
        dealRemaining(table, player, n, Math.max(0, slot), done, done);
    }

    /** Commands deal without a follow-up, so the deal chains always get something to run. */
    private static Runnable orNothing(Runnable after) {
        return after != null ? after : () -> { };
    }

    public void dealToTable(Table table, String name, int n, boolean faceUp) {
        dealToTable(table, name, n, faceUp, null);
    }

    public void dealToTable(Table table, String name, int n, boolean faceUp, Runnable after) {
        String pile = name.toLowerCase(java.util.Locale.ROOT);
        Runnable done = orNothing(after);
        UUID tableId = table.getId();
        int gen = table.tableDealGen();
        if (tableDealing.contains(tableId)) {
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                Table still = tables.get(tableId);
                if (still != null && still.tableDealGen() == gen) {
                    dealToTable(still, pile, n, faceUp, done);
                } else {
                    done.run();
                }
            }, 2L);
            return;
        }
        dealTableRemaining(table, pile, n, faceUp, gen, done);
    }

    public void revealTablePile(Table table, String name) {
        String pile = name.toLowerCase(java.util.Locale.ROOT);
        List<HandCard> cards = table.tablePiles().get(pile);
        if (cards == null) {
            return;
        }
        for (HandCard held : cards) {
            held.setFaceUp(true);
        }
        rebuildTablePiles(table);
        notifyTablePiles(table);
    }

    /** Callers hand over a live table whose deal generation is still {@code gen}. */
    private void dealTableRemaining(Table table, String pile, int left, boolean faceUp, int gen, Runnable after) {
        UUID tableId = table.getId();
        if (left < 1) {
            tableDealing.remove(tableId);
            after.run();
            return;
        }
        tableDealing.add(tableId);
        Runnable onFailure = () -> {
            tableDealing.remove(tableId);
            after.run();
        };
        boolean started = drawOneToTable(table, pile, faceUp, () -> {
            Table still = tables.get(tableId);
            if (still != null && still.tableDealGen() == gen) {
                dealTableRemaining(still, pile, left - 1, faceUp, gen, after);
            } else {
                onFailure.run();
            }
        }, onFailure);
        if (!started) {
            onFailure.run();
        }
    }

    public void relayoutHand(Table table, Player player) {
        layoutHand(table, player, Cache.interpolationTicks, true, null);
    }

    /**
     * Turns a hand face up for everyone. A player whose hand was returned while their card was
     * still landing has nothing here to show, and must not be given an empty hand to hold.
     */
    public void publishHand(Table table, Player player) {
        if (!table.getHands().containsKey(player.getUniqueId())) {
            return;
        }
        DisplayManager displays = DisplayManager.get();
        for (HandCard held : table.handOf(player.getUniqueId())) {
            ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
            revealedCardTokens.add(held.tokenId());
            held.setFaceUp(true);
            if (face != null) {
                displays.setItem(held.tokenId(), face);
            }
            displays.clearItemFor(held.tokenId(), player);
        }
        layoutHand(table, player, Cache.handFollowTicks, false, null);
        syncRevealedHands(player.getUniqueId(), table.handOf(player.getUniqueId()));
    }

    public void muckPlayer(Table table, Player player) {
        muckPlayer(table, player.getUniqueId());
    }

    public void muckPlayer(Table table, UUID playerId) {
        discardPlayerCards(table, playerId);
        rebuildCardStacks(table);
        save(table);
        recycleIfNeeded(table, null);
    }

    public void muckTable(Table table, String name) {
        String pile = name.toLowerCase(java.util.Locale.ROOT);
        List<HandCard> cards = table.tablePiles().get(pile);
        table.bumpTableDealGen();
        tableDealing.remove(table.getId());
        if (cards != null) {
            DisplayManager displays = DisplayManager.get();
            for (HandCard held : cards) {
                displays.despawn(held.tokenId());
                table.getDeck().discard(held.card());
            }
            table.tablePiles().remove(pile);
        }
        rebuildCardStacks(table);
        save(table);
        notifyTablePiles(table);
        recycleIfNeeded(table, null);
    }

    public void endSession(Table table) {
        table.clearSession();
        checkFeltEmpty(table, "session end");
        despawnTablePiles(table);
        rebuildCardStacks(table);
        save(table);
        recycleIfNeeded(table, null);
        Game game = gameOf(table);
        if (game != null) {
            game.onSessionEnd(table);
        }
    }

    /**
     * Deals {@code left} more cards, then runs {@code after}. A card stopped in the air (the hand
     * was mucked, returned or wiped) ends the deal there and runs {@code stopped}, the whole deal's
     * follow-up, so a game waiting on the deal carries on instead of waiting forever.
     */
    private void dealRemaining(Table table, Player player, int left, int slot, Runnable after, Runnable stopped) {
        // Games queue Player objects before dealing, so someone may have logged off by their turn.
        if (left < 1 || !player.isOnline()) {
            after.run();
            return;
        }
        UUID tableId = table.getId();
        UUID playerId = player.getUniqueId();
        if (revealBusy.contains(playerId)) {
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                Table still = tables.get(tableId);
                Player online = Bukkit.getPlayer(playerId);
                if (still != null && online != null) {
                    dealRemaining(still, online, left, slot, after, stopped);
                } else {
                    after.run();
                }
            }, 1L);
            return;
        }
        boolean started = drawOneToPlayer(table, player, slot, () -> {
            Table still = tables.get(tableId);
            Player online = Bukkit.getPlayer(playerId);
            if (still != null && online != null) {
                dealRemaining(still, online, left - 1, slot, after, stopped);
            } else {
                after.run();
            }
        }, stopped);
        if (!started) {
            after.run();
        }
    }

    public boolean canEditHouse(Player player, Table table) {
        if (player.hasPermission(TableHouse.STAFF_PERM) || player.hasPermission("games.admin")) {
            return true;
        }
        UUID owner = table.ownerPlayer();
        return owner != null && owner.equals(player.getUniqueId());
    }

    private void refreshGuildAutoIdle(String guildId) {
        if (guildId == null || guildId.isBlank()) {
            return;
        }
        for (Table table : tables.values()) {
            if (table.live() || !guildId.equals(table.ownerGuildId())) {
                continue;
            }
            Game game = gameOf(table);
            if (game != null) {
                game.onTableReady(table);
            } else {
                refreshLabel(table);
            }
        }
    }

    public void persistHouseChange(Table table) {
        save(table);
        refreshLabel(table);
        Game game = gameOf(table);
        if (game != null) {
            game.onTableReady(table);
        }
        refreshGuildAutoIdle(table.ownerGuildId());
    }

    public void applyHouse(Table table, TableHouse house) {
        String wasGuild = table.ownerGuildId();
        house.apply(table);
        if (!Objects.equals(wasGuild, table.ownerGuildId())) {
            // A float belongs to the guild that put it up, so it cannot follow the table to a new
            // owner and shelter their winnings from tax.
            table.setHouseFloat(0);
        }
        persistHouseChange(table);
    }

    private boolean tryOpenHouseOptions(Table table, Player player) {
        String gameId = table.getGameId();
        boolean blackjack = "blackjack".equalsIgnoreCase(gameId);
        boolean poker = "poker".equalsIgnoreCase(gameId);
        if (!blackjack && !poker) {
            return false;
        }
        if (table.live()) {
            player.sendMessage(Messages.get("place.options_live"));
            return true;
        }
        if (!canEditHouse(player, table)) {
            if (poker) {
                return false;
            }
            player.sendMessage(Messages.get("place.options_denied"));
            return true;
        }
        net.tfminecraft.games.gui.TableOptionsGui.openEdit(player, table);
        return true;
    }

    private static void applyHouseData(Table table, TableData data) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (data.ownerPlayer != null) {
            try {
                table.setOwnerPlayer(UUID.fromString(data.ownerPlayer));
            } catch (IllegalArgumentException ignored) {
                // keep null
            }
        }
        table.setOwnerGuildId(data.ownerGuildId);
        table.setHouseFloat(data.houseFloat != null ? data.houseFloat : 0);
        if (data.autoDealer == null) {
            boolean yaml = layout != null && layout.autoDealer();
            table.setAutoDealer(yaml);
            table.setStaffMint(yaml);
            if (layout != null) {
                table.setMinBet(layout.minBet());
                table.setMaxBet(layout.maxBet());
                table.setMaxBoxes(layout.maxBoxes());
            }
        } else {
            table.setAutoDealer(data.autoDealer);
            table.setStaffMint(Boolean.TRUE.equals(data.staffMint));
            table.setMinBet(data.minBet);
            table.setMaxBet(data.maxBet);
            table.setMaxBoxes(data.maxBoxes);
        }
        table.setShufflePolicy(ShufflePolicy.parse(data.shufflePolicy));
        if (data.smallBlind == null && data.bigBlind == null) {
            if (layout != null) {
                table.setSmallBlind(layout.smallBlind());
                table.setBigBlind(layout.bigBlind());
            }
        } else {
            table.setSmallBlind(data.smallBlind != null ? data.smallBlind : 0);
            table.setBigBlind(data.bigBlind != null ? data.bigBlind : 0);
        }
    }

    private void tryManualFlush(Table table, Player player) {
        if (!allowManualPotFlush(table, player)) {
            player.sendMessage(Messages.get("wager.no_flush"));
            return;
        }
        payout(table, player);
    }

    private DisplayPose pileSlot(Table table, String pile, int index, int count, boolean faceUp) {
        Game game = gameOf(table);
        if (game != null) {
            return game.tablePileSlot(table, pile, index, count, faceUp);
        }
        return TablePileLayout.slot(table, pile, index, count, faceUp);
    }

    private void notifyTablePiles(Table table) {
        Game game = gameOf(table);
        if (game != null) {
            game.onTablePilesChanged(table);
        }
    }

    private void notifyFeltPiles(Table table) {
        syncBucketLabels(table);
        Game game = gameOf(table);
        if (game != null) {
            game.onFeltPilesChanged(table);
        }
    }

    /**
     * With chips hidden the felt would show nothing at all, so each bucket gets a floating
     * amount at its anchor instead. With chips on, the stacks speak for themselves.
     */
    private void syncBucketLabels(Table table) {
        Map<UUID, UUID> labels = bucketLabels.computeIfAbsent(table.getId(), id -> new LinkedHashMap<>());
        if (Cache.wagerShowChips) {
            for (UUID label : labels.values()) {
                WorldAnchors.remove(label);
            }
            labels.clear();
            bucketLabels.remove(table.getId());
            return;
        }
        Set<UUID> keep = new HashSet<>();
        // Owners are only listed while their bucket holds money, and every box sits on the table.
        for (UUID owner : table.ledger().owners()) {
            int value = table.ledger().total(owner);
            Location at = boxLocation(table, owner);
            keep.add(owner);
            String text = Messages.get("label.stake", "n", String.valueOf(value));
            Location above = at.clone().add(0, 0.3, 0);
            UUID id = labels.get(owner);
            Entity entity = id != null ? Bukkit.getEntity(id) : null;
            if (!(entity instanceof TextDisplay) || entity.isDead()) {
                WorldAnchors.remove(id);
                TextDisplay spawned = WorldAnchors.spawnLabel(above, text);
                if (spawned != null) {
                    labels.put(owner, spawned.getUniqueId());
                } else {
                    labels.remove(owner);
                }
                continue;
            }
            WorldAnchors.setText(id, text);
            WorldAnchors.move(id, above);
        }
        for (UUID owner : new ArrayList<>(labels.keySet())) {
            if (!keep.contains(owner)) {
                WorldAnchors.remove(labels.remove(owner));
            }
        }
        if (labels.isEmpty()) {
            bucketLabels.remove(table.getId());
        }
    }

    private static Game gameOf(Table table) {
        return GamesRegistry.of(table.getGameId());
    }

    private static String playWord(String message) {
        String raw = message.strip();
        if (raw.endsWith(".") || raw.endsWith("!")) {
            raw = raw.substring(0, raw.length() - 1).strip();
        }
        if (raw.isEmpty()) {
            return null;
        }
        String key = raw.toLowerCase(Locale.ROOT);
        if (key.equals("hit") || key.equals("stand") || key.equals("double") || key.equals("split")
                || key.equals("check") || key.equals("call") || key.equals("fold") || key.equals("raise")
                || key.equals("draw")) {
            return key;
        }
        return null;
    }

    private boolean isPlayActor(Player player) {
        Table table = tableNearby(player);
        if (table == null || !table.live() || !player.getUniqueId().equals(table.actor())) {
            return false;
        }
        // Only a registered game ever names an actor, so an acting table always has one.
        return gameOf(table).allowPlayChat(table, player);
    }

    public void applyPlayCall(Player player, String action) {
        if (!isPlayActor(player)) {
            return;
        }
        Table table = tableNearby(player);
        Game game = gameOf(table);
        switch (action) {
            case "hit" -> game.onBetHit(table, player);
            case "stand" -> game.onBetStand(table, player);
            case "double" -> game.onBetDouble(table, player);
            case "split" -> game.onBetSplit(table, player);
            default -> game.onPlayWord(table, player, action);
        }
    }

    private static boolean allowManualPotFlush(Table table, Player player) {
        Game game = gameOf(table);
        return game != null ? game.allowManualPotFlush(table, player) : !table.live();
    }

    /** Only asked of idle tables, where a table without a game is plain free play. */
    private static boolean allowFreeDraw(Table table, Player player) {
        Game game = gameOf(table);
        return game == null || game.allowFreeDraw(table, player);
    }

    /** Only asked of idle tables, or of live ones that have a game. */
    private static boolean allowReturnSelected(Table table, Player player) {
        Game game = gameOf(table);
        return game == null || game.allowReturnSelected(table, player);
    }

    private static boolean allowRevealToggle(Table table, Player player) {
        Game game = gameOf(table);
        return game == null || game.allowRevealToggle(table, player);
    }

    private static boolean showRevealDust(Table table) {
        Game game = gameOf(table);
        return game == null || game.showRevealDust(table);
    }

    public void payout(Table table, Player winner) {
        cancelVote(table, "wager.cancelled");
        cancelLootArmsForTable(table.getId());
        if (table.isPaying()) {
            winner.sendMessage(Messages.get("wager.paying"));
            return;
        }
        if (table.ledger().isEmpty()) {
            messageActives(table, Messages.get("wager.empty"));
            return;
        }
        table.actives().clear();
        payoutAll(table, winner);
    }

    /** Every bucket goes to one winner. Manual flush and the admin pay command. */
    public void payoutAll(Table table, Player winner) {
        List<PayoutFlight> flights = new ArrayList<>();
        MoneyTx tx = wager().begin(table, "pot paid out").animate(flights);
        for (UUID owner : wager().owners(table)) {
            tx.moveAll(Accounts.bucket(table, owner), Accounts.payee(table, winner, owner));
        }
        tx.commit();
        flushPiles(table, flights, null);
        if (flights.isEmpty()) {
            // No chips to fly, so no wave lands to announce the winner. Say it now instead.
            winner.sendMessage(Messages.get("wager.paid", "player", winner.getName()));
        }
    }

    public void refundOwnedPiles(Table table, Player player) {
        List<PayoutFlight> flights = new ArrayList<>();
        UUID owner = player.getUniqueId();
        wager().begin(table, "refund").animate(flights)
                .moveAll(Accounts.bucket(table, owner), Accounts.payee(table, player, owner))
                .commit();
        wager().forget(table, owner);
        flushPiles(table, flights, null);
    }

    /**
     * Nothing should be left on the felt once a hand is over. Logs loudly if it is, which is
     * the alarm for money that failed to move.
     */
    public void checkFeltEmpty(Table table, String stage) {
        if (!Cache.wagerAuditLog) {
            return;
        }
        int left = table.ledger().totalExcept(table.getId());
        if (left == 0) {
            return;
        }
        StringBuilder detail = new StringBuilder();
        for (Map.Entry<UUID, Integer> entry : table.ledger().totalsExcept(table.getId()).entrySet()) {
            detail.append(' ').append(entry.getKey()).append('=').append(entry.getValue());
        }
        MoneyLog.mismatch(table, stage + " left " + left + " denars on the felt, tray="
                + trayDenars(table) + ", buckets:" + detail);
    }

    /**
     * Chips drawn only to be animated. They are not part of any bucket and hold no value,
     * so the flight can throw them away when it lands. Every stake handed over here holds at
     * least one real coin, since withdrawals never yield empty parts.
     */
    private List<PayoutFlight> makeFlights(Table table, List<Stake> stakes, Location from, UUID destId,
            boolean toTray) {
        List<PayoutFlight> flights = new ArrayList<>();
        if (!Cache.wagerShowChips) {
            return flights;
        }
        int slot = 0;
        for (Stake stake : stakes) {
            ItemStack one = stake.item().clone();
            one.setAmount(1);
            WagerPileStyle style = ChipItems.pileStyle(one);
            int per = Math.max(1, chipPieces(one));
            int room = Math.max(1, PotLayout.room(0, style));
            int left = stake.count();
            // Chips fly off the heap that actually paid, falling back to the bucket spot for
            // money the house put down and never placed anywhere in particular.
            Location spot = stakeSpot(table, stake);
            int overflow = 0;
            while (left > 0) {
                int add = Math.max(1, Math.min(left, room / per));
                Location at = spot != null ? spreadSlot(table, spot, overflow++)
                        : spreadSlot(table, from, slot++);
                PotPile pile = new PotPile(destId, one.clone(), stake.typeKey(), stake.unit(),
                        at.getX(), at.getZ());
                pile.setPieces(add * per);
                pile.setStreetId(stake.streetId());
                if (!rebuildPile(table, pile)) {
                    despawnPile(pile);
                    return flights;
                }
                flights.add(toTray ? PayoutFlight.toTray(pile) : new PayoutFlight(pile, destId));
                left -= add;
            }
        }
        return flights;
    }

    /**
     * Animate a wave of chips that {@link #makeFlights} drew for money that already moved. Those
     * stacks are throwaway copies, never part of the table's own piles.
     */
    public void flushPiles(Table table, List<PayoutFlight> assignments, Runnable onDone) {
        cancelVote(table, "wager.cancelled");
        cancelLootArmsForTable(table.getId());
        if (assignments.isEmpty()) {
            if (onDone != null) {
                onDone.run();
            }
            return;
        }
        // A wave already in the air must land before this one starts, or its chips would be
        // dropped from the flying list and left hanging over the table.
        Runnable pending = finishPayoutNow(table);
        save(table);
        startPayoutFlight(table, new ArrayList<>(assignments), onDone);
        // Last, so a callback that flushes again sees this wave and lands it properly.
        runPending(pending);
    }

    private static void runPending(Runnable pending) {
        if (pending != null) {
            pending.run();
        }
    }

    private void startPayoutFlight(Table table, List<PayoutFlight> snapshot, Runnable onDone) {
        int gen = table.beginPayout(snapshot, onDone);
        int ticks = Cache.wagerPayoutTicks;
        UUID tableId = table.getId();
        int delay = 0;
        for (PayoutFlight flight : new ArrayList<>(snapshot)) {
            // Tray flights have no player to reach. Anyone else must still be online to watch.
            UUID destId = flight.destId();
            if (destId != null && Bukkit.getPlayer(destId) == null) {
                finishPayoutPile(table, flight);
                continue;
            }
            if (ticks <= 0) {
                finishPayoutPile(table, flight);
                continue;
            }
            final int d = delay++;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> flyPayoutPile(tableId, gen, flight, ticks), d);
        }
    }

    private void flyPayoutPile(UUID tableId, int gen, PayoutFlight flight, int ticks) {
        Table table = tables.get(tableId);
        if (!payoutActive(table, gen)) {
            return;
        }
        PotPile pile = flight.pile();
        Location dest = destLocation(table, flight);
        Location origin = table.getOrigin();
        WagerPileStyle style = ChipItems.pileStyle(pile.item());
        List<UUID> tokens = new ArrayList<>(pile.tokens());
        DisplayManager displays = DisplayManager.get();
        for (int layer = 0; layer < tokens.size(); layer++) {
            UUID token = tokens.get(layer);
            // rebuildPile gave every drawn layer a yaw.
            DisplayPose start = chipPose(style, pile.layerYaws().get(layer));
            double layerY = origin.getY() + layer * style.layerGap();
            float dx = (float) (dest.getX() - pile.x());
            float dy = (float) (dest.getY() + 1.0 - layerY);
            float dz = (float) (dest.getZ() - pile.z());
            DisplayPose end = start.withTranslation(dx, dy, dz);
            displays.setTransform(token, start, 0);
            final int last = tokens.size() - 1;
            final int layerIndex = layer;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!payoutActive(tables.get(tableId), gen)) {
                    return;
                }
                for (int step = 1; step <= ticks; step++) {
                    final int s = step;
                    Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                        Table still = tables.get(tableId);
                        if (!payoutActive(still, gen)) {
                            return;
                        }
                        float t = s / (float) ticks;
                        float ease = 1f - (1f - t) * (1f - t);
                        displays.setTransform(token, lerpPose(start, end, ease), 1);
                        if (s == ticks && layerIndex == last) {
                            finishPayoutPile(still, flight);
                        }
                    }, s);
                }
            }, 1L);
        }
    }

    private static Location destLocation(Table table, PayoutFlight flight) {
        if (flight.stayOnTray()) {
            TableLayout layout = Cache.layoutOf(table.getGameId());
            Location tray = layout != null ? layout.trayLocation(table) : null;
            if (tray != null) {
                return tray.clone();
            }
        }
        if (flight.destId() != null) {
            Player dest = Bukkit.getPlayer(flight.destId());
            if (dest != null) {
                return dest.getLocation();
            }
        }
        return table.getOrigin();
    }

    /**
     * Only called while the flight's wave is still in the air: straight from
     * {@link #startPayoutFlight}, or from the last animation step after checking the wave.
     */
    private void finishPayoutPile(Table table, PayoutFlight flight) {
        table.payoutFlying().remove(flight);
        settleFlight(table, flight);
        if (table.payoutFlying().isEmpty()) {
            finishPayoutWave(table);
        }
    }

    /**
     * A landed flight is only chips arriving. The money moved before the animation started,
     * so there is nothing to hand over here.
     */
    private void settleFlight(Table table, PayoutFlight flight) {
        PotPile pile = flight.pile();
        despawnPile(pile);
        playChipSound(table, destLocation(table, flight));
    }

    private void finishPayoutWave(Table table) {
        Runnable onDone = table.takePayoutOnDone();
        LinkedHashSet<UUID> dests = new LinkedHashSet<>(table.payoutDests());
        table.endPayout();
        if (onDone != null) {
            onDone.run();
        } else {
            for (UUID destId : dests) {
                Player dest = Bukkit.getPlayer(destId);
                if (dest != null) {
                    dest.sendMessage(Messages.get("wager.paid", "player", dest.getName()));
                }
            }
        }
        notifyFeltPiles(table);
    }

    private static boolean payoutActive(Table table, int gen) {
        return table != null && table.isPaying() && table.payoutGen() == gen;
    }

    /**
     * Land the wave that is in the air right now, skipping the rest of the animation.
     * Returns the wave callback so the caller can run it once it is safe.
     */
    private Runnable finishPayoutNow(Table table) {
        if (!table.isPaying()) {
            return null;
        }
        table.bumpPayoutGen();
        Runnable onDone = table.takePayoutOnDone();
        for (PayoutFlight flight : new ArrayList<>(table.payoutFlying())) {
            settleFlight(table, flight);
        }
        table.endPayout();
        notifyFeltPiles(table);
        return onDone;
    }

    public void voteWager(Player player, boolean accept) {
        // Every payout cancels the vote first, so a table with a vote is never mid-payout.
        Table table = tableForVote(player);
        if (table == null) {
            player.sendMessage(Messages.get("wager.no_vote"));
            return;
        }
        WagerVote vote = table.getVote();
        UUID id = player.getUniqueId();
        if (id.equals(vote.proposerId())) {
            if (!accept) {
                cancelVote(table, "wager.cancelled");
            } else {
                player.sendMessage(Messages.get("wager.not_eligible"));
            }
            return;
        }
        if (vote.yes().contains(id) || vote.no().contains(id)) {
            player.sendMessage(Messages.get("wager.already_voted"));
            return;
        }
        if (accept) {
            vote.yes().add(id);
        } else {
            vote.no().add(id);
        }
        tryResolveVote(table);
    }

    private void onWagerQuit(Player player) {
        UUID id = player.getUniqueId();
        for (Table table : tables.values()) {
            WagerVote vote = table.getVote();
            if (vote == null) {
                continue;
            }
            if (id.equals(vote.proposerId())) {
                cancelVote(table, "wager.cancelled");
                continue;
            }
            // A no already cast is simply cast again, which cannot resolve a vote it left standing.
            if (vote.eligible().contains(id) && !vote.yes().contains(id)) {
                vote.no().add(id);
                tryResolveVote(table);
            }
        }
    }

    /**
     * Whatever ends a vote or removes its table cancels this timer first, so when it fires the
     * table and its vote are both still there.
     */
    private void expireVote(UUID tableId) {
        failVote(tables.get(tableId), "wager.expired");
    }

    /** Callers have already found a vote on this table. */
    private void tryResolveVote(Table table) {
        WagerVote vote = table.getVote();
        if (vote.majorityYes()) {
            passVote(table);
            return;
        }
        if (vote.allVoted()) {
            failVote(table, "wager.declined");
        }
    }

    /*
     * A proposer who logs off cancels their vote from onQuit, so while a vote stands its proposer
     * can always be looked up by name. The outcome is announced before the vote is detached, so
     * the proposer and voters hear it even if a game has already taken them off the seats.
     */

    private void passVote(Table table) {
        WagerVote vote = table.getVote();
        vote.cancelExpire();
        Player proposer = Bukkit.getPlayer(vote.proposerId());
        messageActives(table, Messages.get("wager.accepted", "player", proposer.getName()));
        table.setVote(null);
        armLootPlace(proposer, table, vote.item(), vote.denars());
    }

    private void failVote(Table table, String messageKey) {
        WagerVote vote = table.getVote();
        vote.cancelExpire();
        String name = Bukkit.getPlayer(vote.proposerId()).getName();
        messageActives(table, Messages.get(messageKey, "player", name));
        table.setVote(null);
    }

    private void cancelVote(Table table, String messageKey) {
        WagerVote vote = table.getVote();
        if (vote == null) {
            return;
        }
        vote.cancelExpire();
        if (messageKey != null) {
            String name = Bukkit.getPlayer(vote.proposerId()).getName();
            messageActives(table, Messages.get(messageKey, "player", name));
        }
        table.setVote(null);
    }

    private void armLootPlace(Player player, Table table, ItemStack snapshot, int denars) {
        clearLootArm(player.getUniqueId());
        ItemStack copy = snapshot.clone();
        UUID playerId = player.getUniqueId();
        UUID tableId = table.getId();
        int seconds = Math.max(1, Cache.wagerPlaceSeconds);
        // Clearing or replacing the arm, and logging off, all cancel this timer.
        BukkitTask expire = Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            clearLootArm(playerId);
            player.sendMessage(Messages.get("wager.place_timeout"));
        }, seconds * 20L);
        lootArms.put(playerId, new LootArm(tableId, copy, denars, expire));
        player.sendMessage(Messages.get("wager.place_click", "seconds", String.valueOf(seconds)));
    }

    /**
     * Removing a table cancels its arms, and blackjack never arms loot, so an armed table is
     * still there and takes items.
     */
    private boolean tryLootPlace(Player player, Location click) {
        LootArm arm = lootArms.get(player.getUniqueId());
        if (arm == null) {
            return false;
        }
        Table table = tables.get(arm.tableId);
        FeltHit felt = findFelt(player, click);
        if (felt == null || felt.table() != table) {
            return false;
        }
        ItemStack held = player.getInventory().getItemInMainHand();
        if (!held.isSimilar(arm.item) || held.getAmount() < arm.item.getAmount()) {
            player.sendMessage(Messages.get("wager.wrong_item"));
            return true;
        }
        if (!dumpLoot(player, table, arm.item, arm.denars, felt.hit())) {
            return true;
        }
        clearLootArm(player.getUniqueId());
        return true;
    }

    private void cancelLootArmsForTable(UUID tableId) {
        for (UUID playerId : new ArrayList<>(lootArms.keySet())) {
            if (lootArms.get(playerId).tableId.equals(tableId)) {
                clearLootArm(playerId);
            }
        }
    }

    private void clearLootArm(UUID playerId) {
        LootArm arm = lootArms.remove(playerId);
        if (arm != null) {
            arm.expireTask.cancel();
        }
    }

    /** tryLootPlace has just checked the player still holds the whole offered stack. */
    private boolean dumpLoot(Player player, Table table, ItemStack snapshot, int denars, Location hit) {
        int need = snapshot.getAmount();
        Location at = hit;
        if (!onPlayArea(table, player, at) && !isDealerTrayPlace(table, player.getUniqueId(), at)) {
            FeltHit felt = findFelt(player, null);
            at = felt != null && felt.table() == table ? felt.hit() : fallbackFelt(table, player);
        }
        if (inShoeZone(table, at)
                || (inTrayZone(table, at) && !isDealerTrayPlace(table, player.getUniqueId(), at))) {
            player.sendMessage(Messages.get("wager.no_bet_zone"));
            return false;
        }
        boolean dealerTray = isDealerTrayPlace(table, player.getUniqueId(), at);
        if (dealerTray && refuseTrayStock(table, player)) {
            return false;
        }
        int placeDenars = denars * need;
        ItemStack one = snapshot.clone();
        one.setAmount(1);
        UUID bucket = dealerTray ? table.getId() : player.getUniqueId();
        // The items leave the player's hands as part of the same movement that stakes them, so
        // a wager that cannot be placed never eats the loot.
        TxResult staked = wager().begin(table, dealerTray ? "dealer tray loot" : "loot wager")
                .move(Accounts.declared(table, player, one, denars),
                        Accounts.bucket(table, bucket).placedAt(at), placeDenars)
                .commit();
        // A refused transaction moves nothing, so the amount alone says whether it happened.
        if (staked.moved() < 1) {
            player.sendMessage(Messages.get("wager.gone"));
            return false;
        }
        playChipSound(table, at);
        if (!dealerTray) {
            table.actives().add(player.getUniqueId());
            save(table);
            tryBeginSession(table);
            notifyChipIn(table, player, need, one);
        }
        return true;
    }

    /** Quitting takes a player off every table's seats, so every seat is online. */
    private Set<UUID> eligibleVoters(Table table, UUID proposerId) {
        Set<UUID> out = new HashSet<>(table.actives());
        out.remove(proposerId);
        return out;
    }

    /** The proposer and every eligible voter are seated here, so the seats are the audience. */
    private void broadcastProposed(Table table, String playerName, ItemStack item, int denars) {
        for (UUID id : table.actives()) {
            WagerChat.sendProposed(Bukkit.getPlayer(id), playerName, item, denars);
        }
    }

    /**
     * Every seat is online, and any seat leaving flushes the felt, which cancels the vote while
     * the leaver can still hear it, so the vote's proposer and voters are online too.
     */
    private void messageActives(Table table, String message) {
        Set<UUID> ids = new HashSet<>(table.actives());
        if (table.getVote() != null) {
            ids.add(table.getVote().proposerId());
            ids.addAll(table.getVote().eligible());
        }
        for (UUID id : ids) {
            Bukkit.getPlayer(id).sendMessage(message);
        }
    }

    private Table tableWhereActive(UUID playerId) {
        Table best = null;
        for (Table table : tables.values()) {
            if (table.actives().contains(playerId)) {
                best = table;
            }
        }
        return best;
    }

    private Table tableForVote(Player player) {
        UUID id = player.getUniqueId();
        for (Table table : tables.values()) {
            WagerVote vote = table.getVote();
            if (vote == null) {
                continue;
            }
            if (id.equals(vote.proposerId()) || vote.eligible().contains(id)) {
                return table;
            }
        }
        return null;
    }

    private boolean tryPlaceChip(Player player, Location click) {
        FeltHit felt = findFelt(player, click);
        if (felt == null) {
            return false;
        }
        Table table = felt.table();
        Location hit = felt.hit();
        ItemStack held = player.getInventory().getItemInMainHand();
        boolean empty = held.isEmpty();
        if (!empty && "blackjack".equalsIgnoreCase(table.getGameId()) && !ChipItems.isMoneyCoin(held)
                && !wagerLike(held)) {
            return false;
        }
        if (inShoeZone(table, hit)) {
            player.sendMessage(Messages.get("wager.no_bet_zone"));
            return true;
        }
        boolean dealerTray = isDealerTrayPlace(table, player.getUniqueId(), hit);
        if (inTrayZone(table, hit) && !dealerTray) {
            player.sendMessage(Messages.get("wager.no_bet_zone"));
            return true;
        }
        if (dealerTray && refuseTrayStock(table, player)) {
            return true;
        }
        if (!dealerTray && "blackjack".equalsIgnoreCase(table.getGameId()) && (!table.betOpen() || table.live())) {
            player.sendMessage(Messages.get("bet.closed"));
            return true;
        }
        if (table.isPaying()) {
            return true;
        }
        if (empty) {
            return true;
        }
        if ("blackjack".equalsIgnoreCase(table.getGameId()) && !ChipItems.isMoneyCoin(held)) {
            player.sendMessage(Messages.get("wager.coins_only"));
            return true;
        }
        if (ChipItems.needsDeclaredValue(held)) {
            return true;
        }
        ItemStack one = held.clone();
        one.setAmount(1);
        ChipItems.DecoChips deco = ChipItems.decoChips(held);
        OptionalInt whole = deco != null ? OptionalInt.of(deco.denars())
                : ChipItems.integerDenars(held);
        if (whole.isEmpty()) {
            if (ChipItems.isChipKind(held)) {
                player.sendMessage(Messages.get("wager.not_whole"));
                return true;
            }
            return false;
        }
        int denars = whole.getAsInt();
        if (denars < 1) {
            // Sub-denar coins (silver) cannot be staked, so never eat the item for nothing.
            player.sendMessage(Messages.get("wager.not_whole"));
            return true;
        }
        if (!dealerTray && refuseBlackjackPlace(player, table, player.getUniqueId(), denars)) {
            return true;
        }
        UUID bucket = dealerTray ? table.getId() : player.getUniqueId();
        // One coin of exactly this kind, picked out and staked as a single movement. The coin is in
        // the player's hand and worth exactly this much, so the movement always goes through.
        wager().begin(table, dealerTray ? "dealer tray" : "bet")
                .move(Accounts.pockets(table, player, one::isSimilar),
                        Accounts.bucket(table, bucket).placedAt(hit), denars)
                .commit();
        playChipSound(table, hit);
        if (!dealerTray) {
            table.actives().add(player.getUniqueId());
            save(table);
            tryBeginSession(table);
            notifyChipIn(table, player, denars, one);
        }
        lockHand(table, player, false);
        markSelectCooldown(player);
        return true;
    }

    /**
     * True when this dealer must not stock the tray by hand. On a backed table the house money is
     * the bank's, so personal coins going in would be money the guild then banks as its own.
     */
    private static boolean refuseTrayStock(Table table, Player dealer) {
        if (!GuildTables.houseBacked(table)) {
            return false;
        }
        dealer.sendMessage(Messages.get("wager.tray_is_funded"));
        return true;
    }

    /**
     * Only asked about items that are not money coins. Without a coin, decoChips is always null
     * and integerDenars needs a wager.items entry, which isChipKind already covers.
     */
    private static boolean wagerLike(ItemStack held) {
        return ChipItems.isChipKind(held);
    }

    private FeltHit findFelt(Player player, Location click) {
        Location eye = player.getEyeLocation();
        List<Table> nearby = new ArrayList<>();
        Table closest = null;
        double closestPlayer = Double.MAX_VALUE;
        Location feet = player.getLocation();
        for (Table table : tables.values()) {
            if (!atTable(player, table)) {
                continue;
            }
            nearby.add(table);
            double d = table.getOrigin().distance(feet);
            if (d < closestPlayer) {
                closestPlayer = d;
                closest = table;
            }
        }
        FeltHit best = null;
        double bestDist = Double.MAX_VALUE;
        for (Table table : nearby) {
            Location origin = table.getOrigin();
            Location hit;
            // A block click is always in the player's world, which atTable has already matched.
            if (click != null) {
                hit = origin.clone();
                hit.setX(click.getX());
                hit.setZ(click.getZ());
            } else {
                hit = rayFelt(eye, origin);
            }
            if (hit == null) {
                continue;
            }
            boolean onPlay = onPlayArea(table, player, hit, table == closest);
            boolean onTray = inTrayZone(table, hit);
            if (!onPlay && !onTray) {
                continue;
            }
            double dist = horizontalDistance(origin, hit);
            if (dist < bestDist) {
                best = new FeltHit(table, hit);
                bestDist = dist;
            }
        }
        return best;
    }

    private static Location tablePlaceOrigin(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return null;
        }
        if (event.getBlockFace() != BlockFace.UP) {
            return null;
        }
        Block block = event.getClickedBlock();
        if (block == null) {
            return null;
        }
        BoundingBox box = block.getBoundingBox();
        if (box.getMaxX() - box.getMinX() < PLACE_TOP_MIN
                || box.getMaxZ() - box.getMinZ() < PLACE_TOP_MIN) {
            return null;
        }
        Location origin = block.getLocation();
        origin.setX(block.getX() + 0.5);
        origin.setZ(block.getZ() + 0.5);
        origin.setY(box.getMaxY());
        return origin;
    }

    private static Location clickHit(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        var ray = player.rayTraceBlocks(FELT_REACH);
        if (ray == null) {
            return null;
        }
        return ray.getHitPosition().toLocation(player.getWorld());
    }

    private static Location rayFelt(Location eye, Location origin) {
        Vector dir = eye.getDirection();
        if (Math.abs(dir.getY()) < 1e-4) {
            return null;
        }
        double t = (origin.getY() - eye.getY()) / dir.getY();
        if (t < 0.05 || t > FELT_REACH) {
            return null;
        }
        Location hit = eye.clone().add(dir.clone().multiply(t));
        hit.setY(origin.getY());
        return hit;
    }

    private static boolean inShoeZone(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        return layout != null && layout.inShoeZone(table, hit);
    }

    private static boolean inTrayZone(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        return layout != null && layout.inTrayZone(table, hit);
    }

    /** Tray chips are the ones drawn for the table's own bucket, wherever they sit. */
    public boolean isTrayPile(Table table, PotPile pile) {
        return table.getId().equals(pile.ownerId());
    }

    private static boolean isDealerTrayPlace(Table table, UUID ownerId, Location hit) {
        return ownerId.equals(table.dealerId()) && inTrayZone(table, hit);
    }

    private static boolean onFelt(Table table, Location hit) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout != null) {
            return layout.onFelt(table, hit);
        }
        Location origin = table.getOrigin();
        double dist = Math.hypot(hit.getX() - origin.getX(), hit.getZ() - origin.getZ());
        return dist >= Cache.wagerMinRange && dist <= Cache.wagerMaxRange;
    }

    private boolean onPlayArea(Table table, Player player, Location hit) {
        return onPlayArea(table, player, hit, true);
    }

    private boolean onPlayArea(Table table, Player player, Location hit, boolean allowBetPad) {
        if (onFelt(table, hit)) {
            return true;
        }
        if (!allowBetPad) {
            return false;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout == null || layout.betZone() == null || !layout.betZone().present()) {
            return false;
        }
        HandLock lock = peekHandLock(table, player, false);
        return layout.inBetZone(hit, lock.x(), lock.z(), lock.placeYaw());
    }

    private Location betPadCenter(Table table, Player player) {
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout == null || layout.betZone() == null || !layout.betZone().present()) {
            return null;
        }
        HandLock lock = peekHandLock(table, player, false);
        return layout.betPadCenter(table, lock.x(), lock.z(), lock.placeYaw());
    }

    /**
     * Where loot goes when the click that placed it was on somebody else's tray. Only a table
     * with a tray can get here, so there is always a layout.
     */
    private Location fallbackFelt(Table table, Player player) {
        Location pad = betPadCenter(table, player);
        if (pad != null) {
            return pad;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        Location center = layout.feltCenter(table);
        if (layout.onFelt(table, center)) {
            return center;
        }
        // A box felt configured without a size falls back to the ring, which its centre is not on.
        Location origin = table.getOrigin();
        Location loc = player.getLocation();
        double dx = loc.getX() - origin.getX();
        double dz = loc.getZ() - origin.getZ();
        double dist = Math.hypot(dx, dz);
        double ring = (Cache.wagerMinRange + Cache.wagerMaxRange) * 0.5;
        Location hit = origin.clone();
        if (dist < 1e-6) {
            hit.setX(origin.getX() + ring);
            hit.setZ(origin.getZ());
        } else {
            double s = ring / dist;
            hit.setX(origin.getX() + dx * s);
            hit.setZ(origin.getZ() + dz * s);
        }
        hit.setY(origin.getY());
        return hit;
    }

    private record FeltHit(Table table, Location hit) {}

    private static double horizontalDistance(Location a, Location b) {
        return Math.hypot(a.getX() - b.getX(), a.getZ() - b.getZ());
    }

    private boolean refuseBlackjackPlace(Player player, Table table, UUID ownerId, int placeDenars) {
        if (!"blackjack".equalsIgnoreCase(table.getGameId())) {
            return false;
        }
        // tryPlaceChip refuses closed or live blackjack tables first, and loot never reaches one.
        int max = table.maxBet();
        int have = ownedDenars(table, ownerId);
        if (have + placeDenars > max) {
            player.sendMessage(Messages.get("bet.over_max", "max", String.valueOf(max)));
            return true;
        }
        TableLayout layout = Cache.layoutOf(table.getGameId());
        int cap = table.maxBoxes();
        if (cap < 1) {
            cap = layout != null ? layout.maxBoxes() : 0;
        }
        if (cap > 0 && have < 1 && countBlackjackBoxes(table) >= cap) {
            player.sendMessage(Messages.get("bet.table_full", "max", String.valueOf(cap)));
            return true;
        }
        return false;
    }

    private int countBlackjackBoxes(Table table) {
        return boxOwners(table).size();
    }

    /** Draw a freshly laid out pile. Both callers build a new pile, so there is nothing to clear. */
    private boolean rebuildPile(Table table, PotPile pile) {
        DisplayManager displays = DisplayManager.get();
        ItemStack visual = pileDisplayItem(pile);
        WagerPileStyle style = ChipItems.pileStyle(pile.item());
        int layers = PotLayout.visibleLayers(pile.pieces(), style);
        Location origin = table.getOrigin();
        World world = origin.getWorld();
        while (pile.layerYaws().size() < layers) {
            float yaw = style.randomYaw()
                    ? table.getYaw() + ThreadLocalRandom.current().nextFloat() * 360f
                    : table.getYaw();
            pile.layerYaws().add(yaw);
        }
        for (int i = 0; i < layers; i++) {
            UUID token = UUID.randomUUID();
            float yaw = pile.layerYaws().get(i);
            DisplayPose layerPose = chipPose(style, yaw);
            Location at = new Location(world, pile.x(), origin.getY() + i * style.layerGap(), pile.z(), 0f, 0f);
            if (!displays.spawn(token, at, visual, layerPose)) {
                for (UUID spawned : pile.tokens()) {
                    displays.despawn(spawned);
                }
                pile.tokens().clear();
                return false;
            }
            pile.tokens().add(token);
        }
        return true;
    }

    /**
     * Same {@link DisplayPose#flatOnTable} recipe as cards. Pitch from wager config.
     * Y offset is pose translation (world Y, entity pitch/yaw stay 0), not spawn Location,
     * so values like -0.05 vs -0.2 are not lost to block-snapped spawn Y.
     */
    private static DisplayPose chipPose(WagerPileStyle style, float yaw) {
        return DisplayPose.flatOnTable(style.scale(), (float) style.yOffset(), yaw,
                style.pitch() - Cache.tableCardPitch);
    }

    private static ItemStack pileDisplayItem(PotPile pile) {
        WagerPileStyle style = ChipItems.pileStyle(pile.item());
        // Style resolution never leaves a blank model: it falls back to the configured item name.
        if (style.model() != null) {
            ItemStack fromPath = TLibs.getItemAPI().getCreator().getItemFromPath(style.model());
            if (fromPath != null) {
                fromPath.setAmount(1);
                return fromPath;
            }
        }
        return pile.item().clone();
    }

    private void despawnPile(PotPile pile) {
        DisplayManager displays = DisplayManager.get();
        for (UUID token : pile.tokens()) {
            displays.despawn(token);
        }
        pile.tokens().clear();
    }

    /**
     * Every despawnWorld caller has already emptied the felt: pickup and despawnWorldAll clear it
     * with the rest of the money, and a table that fails to spawn has no stakes (a new table, or a
     * loaded one whose stakes were refunded first). So there are no pile displays left to remove.
     */
    private void despawnPiles(Table table) {
        table.getPiles().clear();
    }

    private void refundStreet(Table table, Player player, int street) {
        List<PayoutFlight> flights = new ArrayList<>();
        wager().refundStreet(table, player.getUniqueId(), street, flights, "street refund");
        flushPiles(table, flights, null);
    }

    /**
     * Hand every bucket back: players get their own stakes, the tray goes to the guild bank
     * when the house funded it and to the dealer when a human did.
     */
    private void clearFeltNow(Table table, Player fallback) {
        table.bumpPayoutGen();
        table.clearPayoutOnDone();
        List<UUID> owners = wager().potOwners(table);
        MoneyTx tx = wager().begin(table, "felt cleared");
        for (UUID owner : owners) {
            Player dest = Bukkit.getPlayer(owner);
            if (dest == null) {
                // Only a pickup passes a fallback, and the player picking the table up is online.
                dest = fallback;
            }
            tx.moveAll(Accounts.bucket(table, owner), Accounts.payee(table, dest, owner));
        }
        tx.commit();
        for (UUID owner : owners) {
            wager().forget(table, owner);
        }
        // The commit above redrew every pot bucket, and both callers settle the tray first.
        table.getPiles().clear();
        for (PayoutFlight flight : new ArrayList<>(table.payoutFlying())) {
            despawnPile(flight.pile());
        }
        table.endPayout();
        notifyFeltPiles(table);
    }

    /**
     * Empty the tray. Bank money the house put there, give a human dealer their own float back,
     * and delete a staff mint float.
     */
    private void settleAutoTray(Table table, Location dropAt) {
        UUID house = table.getId();
        if (table.ledger().total(house) < 1) {
            return;
        }
        MoneyAccount tray = Accounts.tray(table);
        MoneyAccount to;
        String reason;
        if (!GuildTables.houseBacked(table)) {
            // Nothing behind this table but its dealer, so the float they stocked goes back to them.
            Player dealer = table.dealerId() != null ? Bukkit.getPlayer(table.dealerId()) : null;
            to = Accounts.payee(table, dealer, house);
            reason = "dealer float returned";
        } else {
            to = Accounts.house(table);
            reason = "tray settled";
        }
        TxResult result = wager().begin(table, reason).moveAll(tray, to).commit();
        if (!result.ok() || result.moved() < 1) {
            // Nowhere to bank it, so it goes back out as coins rather than being lost.
            wager().begin(table, "tray dropped, no guild bank")
                    .moveAll(tray, Accounts.ground(table, dropAt))
                    .commit();
        }
        wager().forget(table, house);
    }

    private boolean trySelectCard(Player player) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        HandHit hit = hitOwnCard(player);
        if (hit == null) {
            return false;
        }
        List<HandCard> hand = hit.table.getHands().get(player.getUniqueId());
        if (anyPublic(hand)) {
            return true;
        }
        markSelectCooldown(player);
        hit.card.setSelected(!hit.card.isSelected());
        player.swingMainHand();
        pushSelectedCard(hit.table, player, hit.card);
        playCardSound(player);
        return true;
    }

    private boolean tryInspectCard(Player player) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        HandHit hit = hitOwnCard(player);
        if (hit == null) {
            return false;
        }
        markSelectCooldown(player);
        player.swingMainHand();
        playCardSound(player);
        Card card = hit.card.card();
        player.sendMessage(Messages.get("card.info",
                "suit", CardNames.suitLabel(card.getSuit()),
                "rank", CardNames.rankLabel(card)));
        UUID tokenId = hit.card.tokenId();
        layoutHand(hit.table, player, 4, false, tokenId);
        holdLayout(player.getUniqueId(), (int) INSPECT_TICKS + 4);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            // Laying out a hand that has gone (mucked, or the player quit) would recreate it.
            if (tableHolding(player.getUniqueId()) != hit.table) {
                return;
            }
            layoutHand(hit.table, player, 4, false, null);
        }, INSPECT_TICKS);
        return true;
    }

    private HandHit hitOwnCard(Player player) {
        if (onSelectCooldown(player)) {
            return null;
        }
        var ray = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                Cache.handSelectRange,
                entity -> tableFrom(entity) != null);
        // An entity ray trace only reports a hit when it hit an entity.
        if (ray != null) {
            return null;
        }
        Table table = tableHolding(player.getUniqueId());
        if (table == null) {
            return null;
        }
        // tableHolding only answers for a player with a hand entry, so the list is never null.
        HandCard card = CardSelector.closestOnRay(player, table.getHands().get(player.getUniqueId()));
        if (card == null) {
            return null;
        }
        return new HandHit(table, card);
    }

    private record HandHit(Table table, HandCard card) {}

    private static int countSelected(Table table, Player player) {
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null) {
            return 0;
        }
        int n = 0;
        for (HandCard held : hand) {
            if (held.isSelected()) {
                n++;
            }
        }
        return n;
    }

    private boolean tryReturnSelected(Table table, Player player) {
        if (revealBusy.contains(player.getUniqueId())) {
            return false;
        }
        List<HandCard> hand = table.getHands().get(player.getUniqueId());
        if (hand == null) {
            return false;
        }
        List<HandCard> selected = new ArrayList<>();
        for (HandCard held : hand) {
            if (held.isSelected()) {
                selected.add(held);
            }
        }
        if (selected.isEmpty()) {
            return false;
        }
        UUID playerId = player.getUniqueId();
        DisplayManager displays = DisplayManager.get();
        List<Card> flying = new ArrayList<>();
        List<DisplayPose> fromOwner = new ArrayList<>();
        List<DisplayPose> fromOther = new ArrayList<>();
        for (HandCard held : selected) {
            DisplayPose ownerPose = displays.poseOf(held.tokenId());
            if (ownerPose == null) {
                continue;
            }
            flying.add(held.card());
            fromOwner.add(ownerPose);
            // A tracked token always has an other-viewer pose, which falls back to its own pose.
            fromOther.add(displays.otherPoseOf(held.tokenId()));
            revealedCardTokens.remove(held.tokenId());
            displays.despawn(held.tokenId());
            hand.remove(held);
        }
        if (flying.isEmpty()) {
            return false;
        }
        revealBusy.add(playerId);
        int gen = dealGen.merge(playerId, 1, Integer::sum);
        selectAnimGen.merge(playerId, 1, Integer::sum);
        dealPendingCards.computeIfAbsent(playerId, key -> new ArrayList<>()).addAll(flying);
        if (!hand.isEmpty()) {
            layoutHand(table, player, Cache.interpolationTicks, true, null);
        }
        int stagger = Math.max(0, Cache.handRevealStagger);
        int deal = Math.max(0, Cache.handDealTicks);
        holdLayout(playerId, stagger * Math.max(0, flying.size() - 1) + deal + 3);
        playCardSound(player);
        player.sendMessage(Messages.get("hand.returned_selected"));
        for (int i = 0; i < flying.size(); i++) {
            final Card card = flying.get(i);
            final DisplayPose ownerFrom = fromOwner.get(i);
            final DisplayPose otherFrom = fromOther.get(i);
            final int step = i;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!dealActive(playerId, gen)) {
                    return;
                }
                startReturnCourier(table, player, card, ownerFrom, otherFrom, gen);
            }, (long) step * stagger);
        }
        return true;
    }

    private boolean onSelectCooldown(Player player) {
        Long until = selectCooldown.get(player.getUniqueId());
        return until != null && System.currentTimeMillis() < until;
    }

    private void markSelectCooldown(Player player) {
        selectCooldown.put(player.getUniqueId(), System.currentTimeMillis() + SELECT_COOLDOWN_MS);
    }

    private static List<HandCard> revealBand(List<HandCard> hand) {
        List<HandCard> selected = new ArrayList<>();
        for (HandCard held : hand) {
            if (held.isSelected()) {
                selected.add(held);
            }
        }
        return selected.isEmpty() ? hand : selected;
    }

    private boolean anyPublic(List<HandCard> cards) {
        if (cards == null) {
            return false;
        }
        for (HandCard held : cards) {
            if (revealedCardTokens.contains(held.tokenId())) {
                return true;
            }
        }
        return false;
    }

    private boolean othersAllPublic(List<HandCard> hand, UUID exceptToken) {
        boolean any = false;
        for (HandCard held : hand) {
            if (exceptToken != null && exceptToken.equals(held.tokenId())) {
                continue;
            }
            any = true;
            if (!revealedCardTokens.contains(held.tokenId())) {
                return false;
            }
        }
        return any;
    }

    private void syncRevealedHands(UUID playerId, List<HandCard> hand) {
        if (anyPublic(hand)) {
            revealedHands.add(playerId);
        } else {
            revealedHands.remove(playerId);
        }
    }

    private List<DisplayPose> fanSlots(Table table, Player player, List<HandCard> order, float extraPitch) {
        HandLock lock = lockHand(table, player, false);
        Location origin = table.getOrigin();
        Location anchor = lockLocation(player, lock);
        int n = order.size();
        int groups = slotSpan(order);
        List<DisplayPose> slots = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            HandCard held = order.get(i);
            int groupSize = countInSlot(order, held.slot());
            int groupIndex = indexInSlot(order, held);
            slots.add(playerFanPose(origin, anchor, lock, held.slot(), groups, groupIndex, groupSize,
                    held.isSelected(), 0f, extraPitch));
        }
        return slots;
    }

    private void startRevealSequence(Table table, Player player, List<HandCard> hand, List<HandCard> band,
            boolean show) {
        List<HandCard> order = handOrder(table, hand);
        List<Integer> indices = new ArrayList<>();
        List<HandCard> changing = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            HandCard held = order.get(i);
            if (!band.contains(held)) {
                continue;
            }
            boolean publicCard = revealedCardTokens.contains(held.tokenId());
            if (show == publicCard) {
                continue;
            }
            indices.add(i);
            changing.add(held);
        }
        // The band is never empty and show was chosen from it, so at least one card turns over.
        UUID id = player.getUniqueId();
        cancelReveal(id);
        revealBusy.add(id);
        int gen = revealGen.merge(id, 1, Integer::sum);
        selectAnimGen.merge(id, 1, Integer::sum);
        HandLock lock = lockHand(table, player, false);
        List<DisplayPose> up = fanSlots(table, player, order, HandLayout.FACE_UP_PITCH);
        List<DisplayPose> down = new ArrayList<>(up.size());
        for (DisplayPose slot : up) {
            down.add(RevealLayout.withPitch(slot, lock.placeYaw(), HandLayout.FACE_DOWN_PITCH));
        }
        hideAllBacks(player, changing);
        DisplayManager displays = DisplayManager.get();
        for (int index : indices) {
            displays.setTransform(order.get(index).tokenId(), show ? down.get(index) : up.get(index), 0);
        }
        int steps = indices.size();
        int stagger = Math.max(0, Cache.handRevealStagger);
        int flip = Math.max(1, Cache.handRevealFlip);
        holdLayout(id, stagger * Math.max(0, steps - 1) + flip + 3);
        for (int i = 0; i < steps; i++) {
            final int step = i;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                // Leaving cancels the reveal. Only wipeHands skips that, and it has already
                // despawned every token the flip would touch.
                if (!Integer.valueOf(gen).equals(revealGen.get(id))) {
                    return;
                }
                int index = indices.get(show ? step : (steps - 1 - step));
                flipSandwich(table, player, order, index, down, up, show, gen);
            }, (long) step * stagger);
        }
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!Integer.valueOf(gen).equals(revealGen.get(id))) {
                return;
            }
            List<HandCard> live = table.getHands().get(id);
            if (!show) {
                restorePrivateFaces(player, changing);
            }
            syncRevealedHands(id, live);
            // After wipeHands the hand may be gone or at another table. Laying it out here would
            // recreate an empty one.
            Table still = tableHolding(id);
            if (still != null && still.getId().equals(table.getId())) {
                layoutHand(table, player, Cache.handFollowTicks, false, null);
            }
            revealBusy.remove(id);
        }, (long) stagger * Math.max(0, steps - 1) + flip + 2);
    }

    private void hideAllBacks(Player owner, List<HandCard> hand) {
        DisplayManager displays = DisplayManager.get();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        for (HandCard held : hand) {
            if (back != null) {
                displays.setItem(held.tokenId(), back);
            }
            displays.clearItemFor(held.tokenId(), owner);
        }
    }

    private void restorePrivateFaces(Player owner, List<HandCard> hand) {
        DisplayManager displays = DisplayManager.get();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        for (HandCard held : hand) {
            if (back != null) {
                displays.setItem(held.tokenId(), back);
            }
            ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
            if (face != null) {
                displays.setItemFor(held.tokenId(), owner, face);
            }
        }
    }

    private void flipSandwich(Table table, Player player, List<HandCard> hand, int index,
            List<DisplayPose> down, List<DisplayPose> up, boolean toFace, int gen) {
        HandCard held = hand.get(index);
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(held.card().getItem());
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        DisplayPose from = toFace ? down.get(index) : up.get(index);
        DisplayPose to = toFace ? up.get(index) : down.get(index);
        DisplayManager displays = DisplayManager.get();
        UUID token = held.tokenId();
        int flip = Math.max(1, Cache.handRevealFlip);
        if (!toFace) {
            revealedCardTokens.remove(token);
        }
        if (back != null) {
            displays.setItem(token, back);
        }
        displays.clearItemFor(token, player);
        displays.setTransform(token, from, 0);
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!Integer.valueOf(gen).equals(revealGen.get(playerId))) {
                return;
            }
            displays.setTransform(token, to, flip);
        }, 1L);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (!Integer.valueOf(gen).equals(revealGen.get(playerId))) {
                return;
            }
            if (toFace) {
                if (face != null) {
                    displays.setItem(token, face);
                    displays.setItemFor(token, player, face);
                }
        displays.setTransform(token, to, 0);
                revealedCardTokens.add(token);
                return;
            }
            revealedCardTokens.remove(token);
            respawnPrivateCard(table, player, held, up.get(index), back, face);
        }, 1L + flip);
    }

    private void respawnPrivateCard(Table table, Player player, HandCard old, DisplayPose pose,
            ItemStack back, ItemStack face) {
        // A reload wipes hands without cancelling the flip, so never recreate a hand that has gone.
        List<HandCard> live = table.getHands().get(player.getUniqueId());
        int liveIndex = live != null ? live.indexOf(old) : -1;
        if (liveIndex < 0) {
            return;
        }
        DisplayManager displays = DisplayManager.get();
        revealedCardTokens.remove(old.tokenId());
        displays.despawn(old.tokenId());
        UUID neu = UUID.randomUUID();
        ItemStack spawnItem = back != null ? back : face;
        if (spawnItem == null || !displays.spawn(neu, table.getOrigin(), spawnItem, pose)) {
            return;
        }
        displays.setLayoutOwner(neu, player.getUniqueId());
        if (back != null) {
            displays.setItem(neu, back);
        }
        if (face != null) {
            displays.setItemFor(neu, player, face);
        }
        HandCard next = new HandCard(old.card(), neu);
        next.setSelected(old.isSelected());
        next.setSlot(old.slot());
        next.setFaceUp(old.faceUp());
        live.set(liveIndex, next);
    }

    /** Turn over a card that has just joined a hand whose other cards are all showing. */
    private void startSingleRevealFlip(Table table, Player player, HandCard added) {
        List<HandCard> order = handOrder(table, table.handOf(player.getUniqueId()));
        int index = order.indexOf(added);
        UUID tokenId = added.tokenId();
        UUID id = player.getUniqueId();
        revealBusy.add(id);
        int gen = revealGen.merge(id, 1, Integer::sum);
        HandLock lock = lockHand(table, player, false);
        List<DisplayPose> up = fanSlots(table, player, order, HandLayout.FACE_UP_PITCH);
        List<DisplayPose> down = new ArrayList<>(up.size());
        for (DisplayPose slot : up) {
            down.add(RevealLayout.withPitch(slot, lock.placeYaw(), HandLayout.FACE_DOWN_PITCH));
        }
        int flip = Math.max(1, Cache.handRevealFlip);
        holdLayout(id, flip + 3);
        DisplayManager.get().clearItemFor(tokenId, player);
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back != null) {
            DisplayManager.get().setItem(tokenId, back);
        }
        DisplayManager.get().setTransform(tokenId, down.get(index), 0);
        flipSandwich(table, player, order, index, down, up, true, gen);
        Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
            if (Integer.valueOf(gen).equals(revealGen.get(id))) {
                syncRevealedHands(id, table.getHands().get(id));
                revealBusy.remove(id);
            }
        }, flip + 2);
    }

    private void cancelReveal(UUID playerId) {
        revealBusy.remove(playerId);
        revealGen.merge(playerId, 1, Integer::sum);
    }

    /** Every caller is acting for an online player, usually the one who clicked. */
    private void playCardSound(Player player) {
        playCardSound(player.getLocation());
    }

    private void playCardSound(Location at) {
        if (Cache.cardSound == null || Cache.cardSoundVolume <= 0f) {
            return;
        }
        at.getWorld().playSound(at, Cache.cardSound, Cache.cardSoundVolume, Cache.cardSoundPitch);
    }

    /** Every caller passes a spot on the table's own felt, so it has the table's world. */
    public void playChipSound(Table table, Location at) {
        Sound sound = Cache.chipSound;
        float volume = Cache.chipSoundVolume;
        float pitch = Cache.chipSoundPitch;
        TableLayout layout = Cache.layoutOf(table.getGameId());
        if (layout != null && layout.chipFx() != null) {
            sound = layout.chipFx().sound();
            volume = layout.chipFx().volume();
            pitch = layout.chipFx().pitch();
        }
        if (sound == null || volume <= 0f) {
            return;
        }
        at.getWorld().playSound(at, sound, volume, pitch);
    }

    private boolean layoutHeld(UUID playerId) {
        Long until = layoutHoldUntil.get(playerId);
        return until != null && System.currentTimeMillis() < until;
    }

    private void holdLayout(UUID playerId, int ticks) {
        if (ticks <= 0) {
            return;
        }
        layoutHoldUntil.put(playerId, System.currentTimeMillis() + ticks * 50L + 50L);
    }

    /**
     * Slide a card the player has just picked out of, or back into, their fan. The card comes
     * from hitOwnCard, so it is in the hand, tracked by the display manager and never public
     * (trySelectCard refuses once any card is showing).
     */
    private void pushSelectedCard(Table table, Player player, HandCard card) {
        List<HandCard> hand = table.handOf(player.getUniqueId());
        int n = countInSlot(hand, card.slot());
        int sortIndex = indexInSlot(handOrder(table, hand), card);
        HandLock lock = lockHand(table, player, false);
        Location origin = table.getOrigin();
        Location anchor = lockLocation(player, lock);
        int groups = slotSpan(hand);
        DisplayPose ownerEnd = playerFanPose(origin, anchor, lock, card.slot(), groups, sortIndex, n,
                card.isSelected(), 0f, HandLayout.FACE_UP_PITCH);
        DisplayPose otherEnd = playerFanPose(origin, anchor, lock, card.slot(), groups, indexInSlot(hand, card), n,
                card.isSelected(), 0f, HandLayout.FACE_UP_PITCH);
        int ticks = Cache.handSelectTicks;
        DisplayManager.get().setLayoutOwner(card.tokenId(), player.getUniqueId());
        if (ticks <= 0) {
            DisplayManager.get().setTransformSplit(card.tokenId(), ownerEnd, otherEnd, 0);
            return;
        }
        holdLayout(player.getUniqueId(), ticks);
        int gen = selectAnimGen.merge(player.getUniqueId(), 1, Integer::sum);
        UUID playerId = player.getUniqueId();
        UUID tokenId = card.tokenId();
        DisplayPose fromOwner = DisplayManager.get().poseOf(tokenId);
        DisplayPose fromOther = DisplayManager.get().otherPoseOf(tokenId);
        for (int step = 1; step <= ticks; step++) {
            final int s = step;
            Bukkit.getScheduler().runTaskLater(Games.plugin, () -> {
                if (!Integer.valueOf(gen).equals(selectAnimGen.get(playerId))) {
                    return;
                }
                // Quitting clears the generation. wipeHands does not, but it despawns the token.
                float t = s / (float) ticks;
                float ease = 1f - (1f - t) * (1f - t);
                DisplayManager.get().setTransformSplit(tokenId,
                        lerpPose(fromOwner, ownerEnd, ease),
                        lerpPose(fromOther, otherEnd, ease),
                        1);
            }, s);
        }
    }

    private void tryDraw(Player player, Table table) {
        if (revealBusy.contains(player.getUniqueId())) {
            return;
        }
        // The shoe click only gets here once allowFreeDraw has passed.
        if (!player.getInventory().getItemInMainHand().isEmpty()) {
            player.sendMessage(Messages.get("hand.need_empty"));
            return;
        }
        drawOneToPlayer(table, player, 0, null, null);
    }

    /**
     * Both callers, tryDraw and dealRemaining, have just checked the player is not mid-reveal.
     * {@code after} runs once the card has landed, or {@code stopped} if it is stopped in the air.
     */
    private boolean drawOneToPlayer(Table table, Player player, int slot, Runnable after, Runnable stopped) {
        Table other = tableHolding(player.getUniqueId());
        if (other != null && !other.getId().equals(table.getId())) {
            player.sendMessage(Messages.get("hand.busy"));
            return false;
        }
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null) {
            player.sendMessage(Messages.get("place.spawn_failed"));
            return false;
        }
        if (table.getDeck().remaining() == 0) {
            if (table.getDeck().discarded() == 0) {
                player.sendMessage(Messages.get("hand.empty"));
                return false;
            }
            UUID playerId = player.getUniqueId();
            UUID tableId = table.getId();
            // Returning true promises that after, or stopped, runs. Go back through dealRemaining once
            // the shoe is full, so a player who started a reveal meanwhile is handled like any deal.
            Runnable done = orNothing(after);
            Runnable retry = () -> {
                Table still = tables.get(tableId);
                Player online = Bukkit.getPlayer(playerId);
                if (still != null && online != null) {
                    dealRemaining(still, online, 1, slot, done, orNothing(stopped));
                } else {
                    done.run();
                }
            };
            if (table.isRecycling()) {
                // Another shuffle is already bringing the discards back, so wait for it.
                Bukkit.getScheduler().runTaskLater(Games.plugin, retry, 1L);
                return true;
            }
            // The recycle only calls back while this table is still placed.
            recycleIfNeeded(table, () -> {
                if (table.getDeck().remaining() == 0) {
                    // The table's shuffle policy kept the discards out (blackjack shuffles per
                    // round), so there is nothing to deal until it does. Retrying would loop.
                    player.sendMessage(Messages.get("hand.empty"));
                    done.run();
                    return;
                }
                retry.run();
            });
            return true;
        }
        int layersBefore = StackLayout.visibleLayers(table.getDeck().remaining(), table.getDeck().size());
        float stackTopY = stackTopOffset(layersBefore);
        // The shoe was checked above, so there is a card to draw.
        Card card = table.getDeck().draw().orElseThrow();
        ItemStack face = TLibs.getItemAPI().getCreator().getItemFromPath(card.getItem());
        UUID playerId = player.getUniqueId();
        List<HandCard> live = table.handOf(playerId);
        int destSlot = Math.max(0, slot);
        int destIndex;
        int destCount;
        if (sortHeld(table)) {
            List<Card> preview = new ArrayList<>();
            for (HandCard held : live) {
                preview.add(held.card());
            }
            preview.add(card);
            preview.sort(HandLayout.orderFor(table.getGameId()));
            destIndex = preview.indexOf(card);
            destCount = preview.size();
        } else {
            destIndex = 0;
            for (HandCard held : live) {
                if (held.slot() == destSlot) {
                    destIndex++;
                }
            }
            destCount = destIndex + 1;
        }
        rebuildCardStacks(table);
        save(table);
        HandLock lock = lockHand(table, player, true);
        Location anchor = lockLocation(player, lock);
        // The incoming card may be the first in a freshly split group, so it can widen the span.
        int destGroups = Math.max(slotSpan(live), destSlot + 1);
        DisplayPose fan = playerFanPose(table.getOrigin(), anchor, lock, destSlot, destGroups, destIndex, destCount,
                false, 0f, HandLayout.FACE_UP_PITCH);
        DisplayPose otherFan = sortHeld(table)
                ? HandLayout.fanSlot(live.size(), destCount, table.getOrigin(), anchor, lock.placeYaw(),
                        lock.sitting(), false, 0f)
                : fan;
        boolean autoReveal = othersAllPublic(live, null);
        if (autoReveal) {
            otherFan = fan;
        }
        if (Cache.handDealTicks <= 0) {
            HandCard added = spawnPrivateHandCard(table, player, card, face, back, fan, destSlot);
            layoutHand(table, player, Cache.interpolationTicks, true, null);
            holdLayout(playerId, Cache.interpolationTicks);
            playCardSound(player);
            if (added != null && autoReveal) {
                startSingleRevealFlip(table, player, added);
            }
            runAfterDraw(player, after);
            return true;
        }
        Location courierOrigin = table.getOrigin().clone().add(0, stackTopY, 0);
        DisplayPose start = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        DisplayPose endOwner = poseOnStackOrigin(fan, stackTopY);
        DisplayPose endOther = poseOnStackOrigin(otherFan, stackTopY);
        UUID courierId = UUID.randomUUID();
        if (!DisplayManager.get().spawn(courierId, courierOrigin, back, start)) {
            table.getDeck().discard(card);
            rebuildCardStacks(table);
            save(table);
            player.sendMessage(Messages.get("place.spawn_failed"));
            return false;
        }
        DisplayManager.get().setLayoutOwner(courierId, playerId);
        revealBusy.add(playerId);
        int gen = dealGen.merge(playerId, 1, Integer::sum);
        selectAnimGen.merge(playerId, 1, Integer::sum);
        trackDealCourier(playerId, courierId);
        dealPendingCards.computeIfAbsent(playerId, key -> new ArrayList<>()).add(card);
        layoutHand(table, player, Cache.interpolationTicks, true, null, destIndex, 1, destSlot);
        holdLayout(playerId, Cache.handDealTicks + 3);
        playCardSound(player);
        flyCourier(table, player, courierId, start, start, endOwner, endOther, gen, () -> {
            finishDrawCourier(table, player, card, face, back, fan, courierId, gen, destSlot);
            runAfterDraw(player, after);
        }, () -> runAfterDraw(player, stopped));
        return true;
    }

    private void runAfterDraw(Player player, Runnable after) {
        if (after == null) {
            return;
        }
        UUID id = player.getUniqueId();
        long delay = revealBusy.contains(id) ? Math.max(1, Cache.handRevealFlip) + 3L : 1L;
        Bukkit.getScheduler().runTaskLater(Games.plugin, after, delay);
    }

    private HandCard spawnPrivateHandCard(Table table, Player player, Card card, ItemStack face, ItemStack back,
            DisplayPose fan, int slot) {
        UUID tokenId = UUID.randomUUID();
        if (!DisplayManager.get().spawn(tokenId, table.getOrigin(), back, fan)) {
            table.getDeck().discard(card);
            rebuildCardStacks(table);
            save(table);
            return null;
        }
        DisplayManager.get().setLayoutOwner(tokenId, player.getUniqueId());
        DisplayManager.get().setItem(tokenId, back);
        if (face != null) {
            DisplayManager.get().setItemFor(tokenId, player, face);
        }
        HandCard held = new HandCard(card, tokenId);
        held.setSlot(slot);
        table.handOf(player.getUniqueId()).add(held);
        save(table);
        return held;
    }

    private void finishDrawCourier(Table table, Player player, Card card, ItemStack face, ItemStack back,
            DisplayPose fan, UUID courierId, int gen, int slot) {
        // Only flyCourier calls this, straight after checking the deal is still active.
        UUID playerId = player.getUniqueId();
        untrackDealCourier(playerId, courierId);
        DisplayManager.get().despawn(courierId);
        removePendingCard(playerId, card);
        HandCard added = spawnPrivateHandCard(table, player, card, face, back, fan, slot);
        layoutHand(table, player, 0, false, null);
        if (added != null && othersAllPublic(table.handOf(playerId), added.tokenId())) {
            startSingleRevealFlip(table, player, added);
            return;
        }
        revealBusy.remove(playerId);
    }

    private void startReturnCourier(Table table, Player player, Card card, DisplayPose fromOwner, DisplayPose fromOther,
            int gen) {
        UUID playerId = player.getUniqueId();
        Location discard = discardOrigin(table);
        float stackTopY = stackTopOffset(StackLayout.visibleLayers(table.getDeck().discarded(), table.getDeck().size()));
        Location courierOrigin = discard.clone().add(0, stackTopY, 0);
        DisplayPose startOwner = poseRelativeTo(fromOwner, table.getOrigin(), courierOrigin);
        DisplayPose startOther = poseRelativeTo(fromOther, table.getOrigin(), courierOrigin);
        DisplayPose end = DisplayPose.flatOnTable(Cache.cardScale, 0f, table.getYaw());
        UUID courierId = UUID.randomUUID();
        ItemStack back = TLibs.getItemAPI().getCreator().getItemFromPath(CardLoader.getBackItem());
        if (back == null || Cache.handDealTicks <= 0
                || !DisplayManager.get().spawn(courierId, courierOrigin, back, startOwner)) {
            finishReturnCard(table, player, card, null);
            return;
        }
        DisplayManager.get().setLayoutOwner(courierId, playerId);
        DisplayManager.get().setTransformSplit(courierId, startOwner, startOther, 0);
        trackDealCourier(playerId, courierId);
        // stopDeal has already put a stopped return on the discard pile, so nothing waits on it.
        flyCourier(table, player, courierId, startOwner, startOther, end, end, gen,
                () -> finishReturnCard(table, player, card, courierId), () -> { });
    }

    /** Both callers have just checked that this return is still active. */
    private void finishReturnCard(Table table, Player player, Card card, UUID courierId) {
        UUID playerId = player.getUniqueId();
        if (courierId != null) {
            untrackDealCourier(playerId, courierId);
            DisplayManager.get().despawn(courierId);
        }
        removePendingCard(playerId, card);
        table.getDeck().discard(card);
        rebuildCardStacks(table);
        save(table);
        if (dealStillFlying(playerId)) {
            return;
        }
        revealBusy.remove(playerId);
        List<HandCard> hand = table.getHands().get(playerId);
        if (hand.isEmpty()) {
            table.getHands().remove(playerId);
            handLocks.remove(playerId);
            clearRevealed(playerId, hand);
            if (table.getHands().isEmpty()) {
                recycleIfNeeded(table, null);
            }
            return;
        }
        layoutHand(table, player, Cache.handFollowTicks, false, null);
    }

    /**
     * Flies a courier one step a tick, each step scheduling the next. The first step to find its
     * deal stopped runs {@code onStopped} and ends the flight, so exactly one of the two runs.
     */
    private void flyCourier(Table table, Player player, UUID courierId, DisplayPose startOwner, DisplayPose startOther,
            DisplayPose endOwner, DisplayPose endOther, int gen, Runnable onArrive, Runnable onStopped) {
        DisplayManager displays = DisplayManager.get();
        displays.setLayoutOwner(courierId, player.getUniqueId());
        displays.setTransformSplit(courierId, startOwner, startOther, 0);
        int ticks = Math.max(1, Cache.handDealTicks);
        IntConsumer moveTo = step -> {
            float t = step / (float) ticks;
            float ease = 1f - (1f - t) * (1f - t);
            displays.setTransformSplit(courierId,
                    lerpPose(startOwner, endOwner, ease),
                    lerpPose(startOther, endOther, ease),
                    1);
        };
        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskLater(Games.plugin,
                () -> courierStep(playerId, gen, 1, ticks, moveTo, onArrive, onStopped), 2L);
    }

    private void courierStep(UUID playerId, int gen, int step, int ticks, IntConsumer moveTo, Runnable onArrive,
            Runnable onStopped) {
        if (!dealActive(playerId, gen)) {
            onStopped.run();
            return;
        }
        moveTo.accept(step);
        if (step == ticks) {
            onArrive.run();
            return;
        }
        Bukkit.getScheduler().runTaskLater(Games.plugin,
                () -> courierStep(playerId, gen, step + 1, ticks, moveTo, onArrive, onStopped), 1L);
    }

    private static DisplayPose lerpPose(DisplayPose start, DisplayPose end, float ease) {
        Vector3f a = start.translation();
        Vector3f b = end.translation();
        return end.withTranslation(
                a.x + (b.x - a.x) * ease,
                a.y + (b.y - a.y) * ease,
                a.z + (b.z - a.z) * ease);
    }

    private static List<HandCard> sortedCopy(List<HandCard> hand, String gameId) {
        List<HandCard> copy = new ArrayList<>(hand);
        copy.sort(java.util.Comparator.comparing(HandCard::card, HandLayout.orderFor(gameId)));
        return copy;
    }

    private static boolean sortHeld(Table table) {
        Game game = gameOf(table);
        return game == null || game.sortHeldCards();
    }

    private static List<HandCard> handOrder(Table table, List<HandCard> hand) {
        if (sortHeld(table)) {
            return sortedCopy(hand, table.getGameId());
        }
        return new ArrayList<>(hand);
    }

    private static int countInSlot(List<HandCard> hand, int slot) {
        int n = 0;
        for (HandCard held : hand) {
            if (held.slot() == slot) {
                n++;
            }
        }
        return n;
    }

    /** Position of a card within its own hand group. Every caller passes a card from this list. */
    private static int indexInSlot(List<HandCard> hand, HandCard card) {
        int index = 0;
        for (HandCard held : hand.subList(0, hand.indexOf(card))) {
            if (held.slot() == card.slot()) {
                index++;
            }
        }
        return index;
    }

    /**
     * One card of one hand group. A box holding several hands spreads its groups sideways around
     * the player's anchor, so four blackjack hands read as four fans instead of one heap.
     */
    private static DisplayPose playerFanPose(Location origin, Location anchor, HandLock lock, int slot, int slots,
            int index, int count, boolean selected, float extraBump, float extraPitch) {
        int groups = Math.max(1, slots);
        int group = Math.min(Math.max(0, slot), groups - 1);
        if (groups < 2) {
            return HandLayout.fanSlot(index, Math.max(1, count), origin, anchor, lock.placeYaw(),
                    lock.sitting(), selected, extraBump, extraPitch);
        }
        // Later groups sit a touch nearer the shoe and a layer higher, as the second hand always has.
        float extra = extraBump + group * 0.1f;
        DisplayPose pose = HandLayout.fanSlot(index, Math.max(1, count), origin, anchor, lock.placeYaw(),
                lock.sitting(), selected, extra, extraPitch);
        double side = Cache.handSplitGroupGap * (group - (groups - 1) / 2.0);
        double yawRad = Math.toRadians(lock.placeYaw());
        double fx = -Math.sin(yawRad);
        double fz = Math.cos(yawRad);
        double rx = fz;
        double rz = -fx;
        var t = pose.translation();
        return pose.withTranslation(
                t.x + (float) (rx * side),
                t.y + group * Cache.stackLayerGap,
                t.z + (float) (rz * side));
    }

    /** How many hand groups this list spans, so the groups can be centred on the player. */
    private static int slotSpan(List<HandCard> hand) {
        int max = 0;
        for (HandCard held : hand) {
            max = Math.max(max, held.slot());
        }
        return max + 1;
    }

    private static float stackTopOffset(int layers) {
        if (layers <= 0) {
            return 0f;
        }
        return (layers - 1) * Cache.stackLayerGap;
    }

    private static DisplayPose poseOnStackOrigin(DisplayPose tablePose, float stackTopY) {
        Vector3f t = tablePose.translation();
        return tablePose.withTranslation(t.x, t.y - stackTopY, t.z);
    }

    private static DisplayPose poseRelativeTo(DisplayPose tableRelative, Location tableOrigin, Location spawn) {
        Vector3f t = tableRelative.translation();
        float dx = (float) (tableOrigin.getX() - spawn.getX());
        float dy = (float) (tableOrigin.getY() - spawn.getY());
        float dz = (float) (tableOrigin.getZ() - spawn.getZ());
        return tableRelative.withTranslation(t.x + dx, t.y + dy, t.z + dz);
    }

    /**
     * Whether a card animation may still land. Everything that takes a hand away or sees the
     * player leave goes through stopDeal, which moves the generation on (wipeHands clears it),
     * so a matching generation means the player is online and still holding this table.
     */
    private boolean dealActive(UUID playerId, int gen) {
        return Integer.valueOf(gen).equals(dealGen.get(playerId));
    }

    /** Both maps drop a player's entry as soon as their list empties. */
    private boolean dealStillFlying(UUID playerId) {
        return dealCouriers.containsKey(playerId) || dealPendingCards.containsKey(playerId);
    }

    private void trackDealCourier(UUID playerId, UUID courierId) {
        dealCouriers.computeIfAbsent(playerId, key -> new ArrayList<>()).add(courierId);
    }

    /** Only called for a courier this player still has in flight. */
    private void untrackDealCourier(UUID playerId, UUID courierId) {
        List<UUID> couriers = dealCouriers.get(playerId);
        couriers.remove(courierId);
        if (couriers.isEmpty()) {
            dealCouriers.remove(playerId);
        }
    }

    /** Only called for a card this player still has in flight. */
    private void removePendingCard(UUID playerId, Card card) {
        List<Card> pending = dealPendingCards.get(playerId);
        pending.remove(card);
        if (pending.isEmpty()) {
            dealPendingCards.remove(playerId);
        }
    }

    private void stopDeal(UUID playerId, Table table) {
        dealGen.merge(playerId, 1, Integer::sum);
        List<UUID> couriers = dealCouriers.remove(playerId);
        if (couriers != null) {
            for (UUID courierId : couriers) {
                DisplayManager.get().despawn(courierId);
            }
        }
        List<Card> pending = dealPendingCards.remove(playerId);
        if (pending != null) {
            for (Card card : pending) {
                table.getDeck().discard(card);
            }
        }
        revealBusy.remove(playerId);
    }

    private void layoutHand(Table table, Player player, int durationTicks, boolean forceHeading,
            UUID pulseToken) {
        layoutHand(table, player, durationTicks, forceHeading, pulseToken, -1, 0, 0);
    }

    private void layoutHand(Table table, Player player, int durationTicks, boolean forceHeading,
            UUID pulseToken, int gapIndex, int extraSlots, int extraOnSlot) {
        List<HandCard> hand = table.handOf(player.getUniqueId());
        Location origin = table.getOrigin();
        HandLock lock = lockHand(table, player, forceHeading);
        Location anchor = lockLocation(player, lock);
        List<HandCard> order = handOrder(table, hand);
        int groups = Math.max(slotSpan(order), extraSlots > 0 ? extraOnSlot + 1 : 0);
        Map<UUID, DisplayPose> ownerPoses = new HashMap<>();
        for (HandCard held : order) {
            int groupSlot = held.slot();
            int n = countInSlot(order, groupSlot);
            int index = indexInSlot(order, held);
            if (extraSlots > 0 && groupSlot == extraOnSlot) {
                n += extraSlots;
                // Only a draw asks for extra room, and it always names where the gap goes.
                if (index >= gapIndex) {
                    index++;
                }
            }
            float extra = pulseToken != null && pulseToken.equals(held.tokenId()) ? INSPECT_BUMP : 0f;
            ownerPoses.put(held.tokenId(), playerFanPose(origin, anchor, lock, groupSlot, groups, index, n,
                    held.isSelected(), extra, HandLayout.FACE_UP_PITCH));
        }
        Map<UUID, DisplayPose> otherPoses = new HashMap<>();
        int otherIndex = 0;
        int otherCount = hand.size() + extraSlots;
        for (HandCard held : hand) {
            float extra = pulseToken != null && pulseToken.equals(held.tokenId()) ? INSPECT_BUMP : 0f;
            // The anonymous flat fan only makes sense for a single group, so split boxes show true spots.
            if (groups > 1 || revealedCardTokens.contains(held.tokenId())) {
                otherPoses.put(held.tokenId(), ownerPoses.get(held.tokenId()));
            } else {
                otherPoses.put(held.tokenId(), HandLayout.fanSlot(
                        otherIndex, otherCount, origin, anchor, lock.placeYaw(), lock.sitting(),
                        held.isSelected(), extra));
            }
            otherIndex++;
        }
        DisplayManager displays = DisplayManager.get();
        UUID ownerId = player.getUniqueId();
        for (HandCard held : hand) {
            DisplayPose ownerPose = ownerPoses.get(held.tokenId());
            DisplayPose otherPose = revealedCardTokens.contains(held.tokenId()) || groups > 1
                    ? ownerPose
                    : otherPoses.get(held.tokenId());
            displays.setLayoutOwner(held.tokenId(), ownerId);
            displays.setTransformSplit(held.tokenId(), ownerPose, otherPose, durationTicks);
        }
    }

    private HandLock lockHand(Table table, Player player, boolean force) {
        HandLock next = peekHandLock(table, player, force);
        handLocks.put(player.getUniqueId(), next);
        return next;
    }

    private HandLock peekHandLock(Table table, Player player, boolean force) {
        Location origin = table.getOrigin();
        HandAnchor.Raw raw = HandAnchor.resolve(player, origin);
        UUID id = player.getUniqueId();
        HandLock last = handLocks.get(id);
        boolean sitting = raw.sitting();
        double x = raw.location().getX();
        double z = raw.location().getZ();
        double y = raw.location().getY();
        boolean holdPos = !force && last != null && last.sitting() == sitting && Cache.handPosStick > 0
                && Math.hypot(x - last.x(), z - last.z()) < Cache.handPosStick;
        if (holdPos) {
            x = last.x();
            z = last.z();
            if (sitting) {
                y = last.y();
            }
        }
        if (!sitting) {
            y = player.getLocation().getY() + Cache.handLift;
        }
        float yaw;
        if (sitting) {
            yaw = holdPos ? last.placeYaw() : raw.placeYaw();
        } else {
            float candidateYaw = HandLayout.candidatePlaceYaw(
                    origin, player.getLocation(), table.getYaw(), BodyYaw.of(player));
            yaw = candidateYaw;
            if (!force && last != null && !last.sitting() && Cache.handYawStick > 0f
                    && Math.abs(HandLayout.wrapDegrees(candidateYaw - last.placeYaw())) < Cache.handYawStick) {
                yaw = last.placeYaw();
            }
        }
        return new HandLock(yaw, x, z, y, sitting);
    }

    /** Within the game's leave distance, which is always positive, and roughly level with the felt. */
    private static boolean atTable(Player player, Table table) {
        Location origin = table.getOrigin();
        Location loc = player.getLocation();
        if (!origin.getWorld().equals(loc.getWorld())) {
            return false;
        }
        if (Math.abs(loc.getY() - origin.getY()) > TABLE_Y_SLOP) {
            return false;
        }
        return origin.distance(loc) <= Cache.leaveDistanceOf(table.getGameId());
    }

    private static Location lockLocation(Player player, HandLock lock) {
        return new Location(player.getWorld(), lock.x(), lock.y(), lock.z());
    }

    private record HandLock(float placeYaw, double x, double z, double y, boolean sitting) {}

    private Table tableHolding(UUID playerId) {
        for (Table table : tables.values()) {
            List<HandCard> hand = table.getHands().get(playerId);
            if (hand != null) {
                return table;
            }
        }
        return null;
    }

    private void tickAway(Table table) {
        Set<UUID> watch = new LinkedHashSet<>(table.actives());
        watch.addAll(table.getHands().keySet());
        if (table.dealerId() != null) {
            watch.add(table.dealerId());
        }
        for (UUID playerId : watch) {
            // Everyone watched is online: quitting takes a player's seats, hand and shoe, and games
            // read hands through Table.heldBy, which never makes one for somebody who has left.
            leaveIfAtTable(Bukkit.getPlayer(playerId), false);
        }
    }

    private boolean stillNear(Table table, Player player) {
        Location origin = table.getOrigin();
        return player.getWorld().equals(origin.getWorld())
                && origin.distance(player.getLocation()) <= Cache.leaveDistanceOf(table.getGameId());
    }

    private Table tableWhereDealer(UUID playerId) {
        for (Table table : tables.values()) {
            if (playerId.equals(table.dealerId())) {
                return table;
            }
        }
        return null;
    }

    private void leaveIfAtTable(Player player, boolean force) {
        UUID id = player.getUniqueId();
        Table table = tableHolding(id);
        boolean involved = table != null;
        if (table != null && (force || !stillNear(table, player))) {
            returnHand(table, player, !force, true);
        }
        // Chips can be down at several tables at once, so every one of them is checked.
        for (Table felt : new ArrayList<>(tables.values())) {
            if (felt == table || !felt.actives().contains(id)) {
                continue;
            }
            involved = true;
            if (!force && stillNear(felt, player)) {
                continue;
            }
            Game game = GamesRegistry.of(felt.getGameId());
            if (game != null) {
                game.onLeave(felt, player);
                game.onChipIn(felt, player);
            } else {
                refundOwnedPiles(felt, player);
                felt.actives().remove(id);
            }
            if (felt.getHands().isEmpty()) {
                recycleIfNeeded(felt, null);
            }
            save(felt);
        }
        if (involved) {
            return;
        }
        Table dealing = tableWhereDealer(player.getUniqueId());
        if (dealing == null) {
            return;
        }
        if (!force && stillNear(dealing, player)) {
            return;
        }
        clearDealer(player);
    }

    private void returnHand(Table table, Player player, boolean notify, boolean refundChips) {
        muckPlayer(table, player);
        if (refundChips) {
            Game game = GamesRegistry.of(table.getGameId());
            if (game != null) {
                game.onLeave(table, player);
                game.onChipIn(table, player);
            } else {
                refundOwnedPiles(table, player);
                table.actives().remove(player.getUniqueId());
            }
        }
        // Only the clock asks for a notice, and it skips players who are offline.
        if (notify) {
            player.sendMessage(Messages.get("hand.returned"));
        }
    }

    private void discardPlayerCards(Table table, UUID playerId) {
        List<HandCard> hand = table.getHands().remove(playerId);
        handLocks.remove(playerId);
        layoutHoldUntil.remove(playerId);
        selectAnimGen.merge(playerId, 1, Integer::sum);
        stopDeal(playerId, table);
        cancelReveal(playerId);
        clearRevealed(playerId, hand);
        if (hand != null) {
            DisplayManager displays = DisplayManager.get();
            for (HandCard held : hand) {
                table.getDeck().discard(held.card());
                displays.despawn(held.tokenId());
            }
        }
    }

    private void returnAllHands(Table table, boolean notify, boolean refundChips) {
        // Quitting returns a hand straight away, so everybody still holding one is online.
        for (UUID playerId : new ArrayList<>(table.getHands().keySet())) {
            returnHand(table, Bukkit.getPlayer(playerId), notify, refundChips);
        }
        rebuildCardStacks(table);
    }

    /**
     * Hands are never saved, and pickup and despawnWorldAll return or discard every hand before
     * calling despawnWorld, so there is nothing in flight or on show to remove here.
     */
    private void despawnHands(Table table) {
        table.getHands().clear();
    }

    private boolean tooClose(Location origin) {
        double minSq = MIN_DISTANCE * MIN_DISTANCE;
        for (Table table : tables.values()) {
            Location other = table.getOrigin();
            if (other.getWorld().equals(origin.getWorld()) && other.distanceSquared(origin) < minSq) {
                return true;
            }
        }
        return false;
    }

    private Table tableFrom(Entity entity) {
        String raw = WorldAnchors.tableId(entity);
        if (raw == null) {
            return null;
        }
        try {
            return tables.get(UUID.fromString(raw));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private void save(Table table) {
        File file = tableFile(table.getId());
        TableData data = toData(table);
        try (FileWriter writer = new FileWriter(file, StandardCharsets.UTF_8)) {
            GSON.toJson(data, writer);
        } catch (IOException ex) {
            Games.plugin.getLogger().warning("[Games] Failed to save table " + table.getId() + ": " + ex.getMessage());
        }
    }

    private void deleteFile(UUID id) {
        File file = tableFile(id);
        if (file.exists() && !file.delete()) {
            Games.plugin.getLogger().warning("[Games] Could not delete table file " + file.getName());
        }
    }

    private static File tablesFolder() {
        File folder = new File(Games.plugin.getDataFolder(), "Data/tables");
        if (!folder.exists()) {
            folder.mkdirs();
        }
        return folder;
    }

    private static File tableFile(UUID id) {
        return new File(tablesFolder(), id + ".json");
    }

    private static TableData toData(Table table) {
        TableData data = new TableData();
        data.id = table.getId().toString();
        data.gameId = table.getGameId();
        Location origin = table.getOrigin();
        data.world = origin.getWorld().getName();
        data.x = origin.getX();
        data.y = origin.getY();
        data.z = origin.getZ();
        data.yaw = table.getYaw();
        data.setName = table.getDeck().getSetName();
        data.remaining = table.getDeck().remainingIds();
        data.discarded = table.getDeck().discardedIds();
        data.street = table.street();
        data.actives = new ArrayList<>();
        for (UUID id : table.actives()) {
            data.actives.add(id.toString());
        }
        data.ledger = new ArrayList<>();
        for (UUID owner : table.ledger().owners()) {
            for (Stake stake : table.ledger().stakes(owner)) {
                StakeData raw = toStakeData(table, owner, stake);
                if (raw != null) {
                    data.ledger.add(raw);
                }
            }
        }
        data.ownerPlayer = table.ownerPlayer() != null ? table.ownerPlayer().toString() : null;
        data.ownerGuildId = table.ownerGuildId();
        data.autoDealer = table.autoDealer();
        data.staffMint = table.staffMint();
        data.houseFloat = table.houseFloat();
        data.minBet = table.minBet();
        data.maxBet = table.maxBet();
        data.maxBoxes = table.maxBoxes();
        data.shufflePolicy = table.shufflePolicy().name();
        data.smallBlind = table.smallBlind();
        data.bigBlind = table.bigBlind();
        return data;
    }

    private static Table fromData(TableData data, List<Stake> unowned) {
        UUID tableId;
        try {
            tableId = UUID.fromString(data.id);
        } catch (IllegalArgumentException ex) {
            throw new JsonParseException("Invalid table id: " + data.id, ex);
        }
        World world = Bukkit.getWorld(data.world);
        if (world == null) {
            Games.plugin.getLogger().warning("[Games] Table world missing: " + data.world);
            return null;
        }
        Optional<Deck> deck = Deck.create(data.setName != null ? data.setName : Cache.cardSetOf(data.gameId), data.remaining,
                data.discarded);
        if (deck.isEmpty()) {
            return null;
        }
        Location origin = new Location(world, data.x, data.y, data.z, data.yaw, 0f);
        Table table = new Table(tableId, data.gameId, origin, data.yaw, deck.get());
        table.setStreet(data.street > 0 ? data.street : 1);
        if (data.actives != null) {
            for (String raw : data.actives) {
                if (raw == null) {
                    continue;
                }
                try {
                    table.actives().add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {
                    // skip
                }
            }
        }
        if (data.ledger != null) {
            for (StakeData raw : data.ledger) {
                readStakeData(table, raw, unowned);
            }
        }
        applyHouseData(table, data);
        return table;
    }

    /**
     * Files written before the ledger stored the money in the piles themselves, with the tray
     * decided by position. Read that once so no table loses value on the upgrade.
     */
    private void migrateLegacyPiles(Table table, TableData data, List<Stake> unowned) {
        if (data.piles == null || data.piles.isEmpty()
                || (data.ledger != null && !data.ledger.isEmpty())) {
            return;
        }
        int moved = 0;
        for (PileData raw : data.piles) {
            if (raw == null || raw.item == null || raw.count < 1 || raw.denars < 1) {
                continue;
            }
            ItemStack item = decodeItem(raw.item);
            if (item == null) {
                continue;
            }
            item.setAmount(1);
            UUID owner = null;
            if (raw.owner != null) {
                try {
                    owner = UUID.fromString(raw.owner);
                } catch (IllegalArgumentException ignored) {
                    owner = null;
                }
            }
            if (owner == null) {
                unowned.add(new Stake(item, raw.typeKey, raw.denars, raw.count, raw.streetId));
                continue;
            }
            Location at = table.getOrigin().clone();
            at.setX(raw.x);
            at.setZ(raw.z);
            // The tray used to be decided by where the pile sat.
            UUID bucket = inTrayZone(table, at) ? table.getId() : owner;
            // Old piles each carried their own spot, so they keep it rather than being laid out again.
            wager().restore(table, bucket, item, raw.typeKey, raw.denars, raw.count, raw.streetId,
                    raw.x, raw.z, raw.x, raw.z);
            moved += raw.denars * raw.count;
        }
        if (moved > 0) {
            MoneyLog.note(table, moved, "read from old pile data into the ledger");
        }
    }

    /** What the file on disk claims this table was holding, ledger entries and old piles alike. */
    private static int storedDenars(TableData data) {
        int sum = 0;
        if (data.ledger != null && !data.ledger.isEmpty()) {
            for (StakeData stake : data.ledger) {
                if (stake != null && stake.unit > 0 && stake.count > 0) {
                    sum += stake.unit * stake.count;
                }
            }
            return sum;
        }
        if (data.piles != null) {
            for (PileData pile : data.piles) {
                if (pile != null && pile.denars > 0 && pile.count > 0) {
                    sum += pile.denars * pile.count;
                }
            }
        }
        return sum;
    }

    private static StakeData toStakeData(Table table, UUID owner, Stake stake) {
        // The ledger never holds an empty stake or one without an item, so only encoding can fail.
        String item = encodeItem(stake.item());
        if (item == null) {
            return null;
        }
        StakeData data = new StakeData();
        data.owner = owner.toString();
        data.item = item;
        data.typeKey = stake.typeKey();
        data.unit = stake.unit();
        data.count = stake.count();
        data.streetId = stake.streetId();
        if (table.ledger().hasAnchor(owner)) {
            data.anchorX = table.ledger().anchorX(owner);
            data.anchorZ = table.ledger().anchorZ(owner);
        }
        if (stake.placed()) {
            data.x = stake.x();
            data.z = stake.z();
        }
        return data;
    }

    private static void readStakeData(Table table, StakeData data, List<Stake> unowned) {
        if (data == null || data.item == null || data.unit < 1 || data.count < 1) {
            return;
        }
        UUID owner = null;
        if (data.owner != null) {
            try {
                owner = UUID.fromString(data.owner);
            } catch (IllegalArgumentException ignored) {
                // Decoded items remain recoverable even when their owner cannot be identified.
            }
        }
        ItemStack item = decodeItem(data.item);
        if (item == null) {
            return;
        }
        if (owner == null) {
            item.setAmount(1);
            unowned.add(new Stake(item, data.typeKey, data.unit, data.count, data.streetId));
            return;
        }
        WagerEngine.get().restore(table, owner, item, data.typeKey, data.unit, data.count,
                data.streetId, data.anchorX, data.anchorZ, data.x, data.z);
    }

    // Preserve the existing serialized item format so previously saved graves remain readable.
    @SuppressWarnings("deprecation")
    private static String encodeItem(ItemStack item) {
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeObject(item);
            out.flush();
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } catch (IOException ex) {
            Games.plugin.getLogger().warning("[Games] Failed to encode pot item: " + ex.getMessage());
            return null;
        }
    }

    // Preserve the existing serialized item format so previously saved graves remain readable.
    @SuppressWarnings("deprecation")
    private static ItemStack decodeItem(String raw) {
        // Both callers skip entries with no item at all, but a saved file can still hold "".
        if (raw.isBlank()) {
            return null;
        }
        try (ByteArrayInputStream bytes = new ByteArrayInputStream(Base64.getDecoder().decode(raw));
                BukkitObjectInputStream in = new BukkitObjectInputStream(bytes)) {
            Object value = in.readObject();
            return value instanceof ItemStack stack ? stack : null;
        } catch (IOException | ClassNotFoundException | IllegalArgumentException ex) {
            Games.plugin.getLogger().warning("[Games] Failed to decode pot item: " + ex.getMessage());
            return null;
        }
    }

    private static UUID stackTokenId(UUID tableId, int layer) {
        return UUID.nameUUIDFromBytes(("games-stack-" + tableId + "-" + layer).getBytes(StandardCharsets.UTF_8));
    }

    private static UUID discardTokenId(UUID tableId, int layer) {
        return UUID.nameUUIDFromBytes(("games-discard-" + tableId + "-" + layer).getBytes(StandardCharsets.UTF_8));
    }

    private static void consumeOne(ItemStack held, Player player) {
        int amount = held.getAmount();
        if (amount <= 1) {
            player.getInventory().setItemInMainHand(null);
        } else {
            held.setAmount(amount - 1);
        }
    }

    private static final class PlaceArm {
        private final String gameId;
        private final long expiresAt;
        private final boolean requireDeck;
        private final TableHouse house;

        private PlaceArm(String gameId, long expiresAt, boolean requireDeck, TableHouse house) {
            this.gameId = gameId;
            this.expiresAt = expiresAt;
            this.requireDeck = requireDeck;
            this.house = house;
        }
    }

    private static final class LootArm {
        private final UUID tableId;
        private final ItemStack item;
        private final int denars;
        private final BukkitTask expireTask;

        private LootArm(UUID tableId, ItemStack item, int denars, BukkitTask expireTask) {
            this.tableId = tableId;
            this.item = item;
            this.denars = denars;
            this.expireTask = expireTask;
        }
    }

    static final class TableData {
        String id;
        String gameId;
        String world;
        double x;
        double y;
        double z;
        float yaw;
        String setName;
        List<String> remaining = new ArrayList<>();
        List<String> discarded = new ArrayList<>();
        int street = 1;
        List<String> actives = new ArrayList<>();
        List<StakeData> ledger = new ArrayList<>();
        List<PileData> piles = new ArrayList<>();
        String ownerPlayer;
        String ownerGuildId;
        Boolean autoDealer;
        Boolean staffMint;
        /** Bank money still out on the felt. Absent on files written before profits were taxed. */
        Integer houseFloat;
        int minBet;
        int maxBet;
        int maxBoxes;
        String shufflePolicy;
        Integer smallBlind;
        Integer bigBlind;
    }

    /** One kind of coin held by one bucket. The money half of the old PileData. */
    static final class StakeData {
        String owner;
        String item;
        String typeKey;
        int unit;
        int count;
        int streetId;
        Double anchorX;
        Double anchorZ;
        /** Where this heap was put, when somebody chose the spot. Absent on older files. */
        Double x;
        Double z;
    }

    /** Only read now, to bring pre-ledger files forward. */
    static final class PileData {
        String owner;
        String item;
        String typeKey;
        int denars;
        int count;
        int pieces;
        int streetId;
        double x;
        double z;
        List<Double> layerYaws = new ArrayList<>();
    }
}
