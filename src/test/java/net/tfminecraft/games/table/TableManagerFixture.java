package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Path;
import java.util.Collection;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.entity.Entity;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.function.Executable;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.card.Card;
import net.tfminecraft.games.display.DisplayManager;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.Game;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.loader.CardLoader;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerItemOverride;
import net.tfminecraft.tlibs.TLibs;
import net.tfminecraft.tlibs.objects.api.ItemAPI;

/** Shared external API boundaries for public TableManager workflow tests. */
abstract class TableManagerFixture {
    private final Deque<Executable> cleanup = new ArrayDeque<>();
    @TempDir Path data;
    TableManager manager;
    private Games previousPlugin;
    private Object previousEngine;
    PlayerMock player;
    WorldMock world;
    DisplayManager display;
    Game game;
    ItemAPI items;
    MockedStatic<TLibs> libs;
    MockedStatic<CardLoader> cards;
    MockedStatic<DisplayManager> displays;
    MockedStatic<WorldAnchors> anchors;
    MockedStatic<GamesRegistry> games;
    MockedStatic<Messages> messages;
    List<Card> catalog;
    private int oldDealTicks, oldRecycleTicks, oldPayoutTicks, oldVoteSeconds, oldMinPlayers;
    private boolean oldShowChips;
    private WagerItemOverride oldGold, oldSilver;
    private List<WagerItemOverride> oldWagerItems;

    @BeforeEach final void setUpTableManager() throws Exception {
        oldDealTicks = Cache.handDealTicks;
        oldRecycleTicks = Cache.tableRecycleTicks;
        oldPayoutTicks = Cache.wagerPayoutTicks;
        oldVoteSeconds = Cache.wagerVoteSeconds;
        oldMinPlayers = Cache.wagerMinPlayers;
        oldShowChips = Cache.wagerShowChips;
        oldGold = Cache.wagerGold;
        oldSilver = Cache.wagerSilver;
        oldWagerItems = new ArrayList<>(Cache.wagerItems);
        cleanup.addFirst(this::restoreConfig);
        Cache.handDealTicks = 0;
        Cache.tableRecycleTicks = 0;
        Cache.wagerPayoutTicks = 0;
        Cache.wagerVoteSeconds = 1;
        Cache.wagerMinPlayers = 1;
        Cache.wagerShowChips = true;
        Cache.wagerGold = new WagerItemOverride("GOLD_NUGGET", 1, null, null, null, null,
                null, null, false, null);
        Cache.wagerSilver = null;
        Cache.wagerItems.clear();
        manager = TableManager.get();
        assertTrue(manager.tables().isEmpty(), "Previous table test leaked state");
        List<SingletonField> singleton = snapshotManager();
        cleanup.addFirst(() -> restoreManager(singleton));
        Field engine = WagerEngine.class.getDeclaredField("instance");
        engine.setAccessible(true);
        previousEngine = engine.get(null);
        cleanup.addFirst(() -> engine.set(null, previousEngine));
        WagerEngine.init(manager);
        previousPlugin = Games.plugin;
        cleanup.addFirst(() -> Games.plugin = previousPlugin);
        Games.plugin = mock(Games.class);
        Games testPlugin = Games.plugin;
        cleanup.addFirst(() -> Bukkit.getScheduler().cancelTasks(testPlugin));
        when(Games.plugin.getDataFolder()).thenReturn(data.toFile());
        when(Games.plugin.getLogger()).thenReturn(mock(Logger.class));
        when(Games.plugin.isEnabled()).thenReturn(true);
        world = new WorldMock() {
            @Override public RayTraceResult rayTraceEntities(Location start, Vector direction,
                    double distance, java.util.function.Predicate<? super Entity> filter) {
                // Anchors and packet displays are mocked; this world has no selectable card entities.
                return null;
            }
        };
        world.setName("tables-" + UUID.randomUUID());
        MockBukkit.getMock().addWorld(world);
        player = MockBukkit.getMock().addPlayer();
        player.teleport(new Location(world, 0, 65, 0, 90, 0));
        libs = track(mockStatic(TLibs.class));
        items = mock(ItemAPI.class, RETURNS_DEEP_STUBS);
        libs.when(TLibs::getItemAPI).thenReturn(items);
        when(items.getCreator().getItemFromPath(anyString())).thenReturn(new ItemStack(Material.PAPER));
        when(items.getChecker().checkItemWithPath(any(), anyString())).thenReturn(true);
        catalog = List.of(new Card("one", "oseni", 1, false, "face"), new Card("two", "oseni", 2, false, "face"));
        cards = track(mockStatic(CardLoader.class));
        cards.when(() -> CardLoader.hasSet(Cache.pokerCardSet)).thenReturn(true);
        cards.when(() -> CardLoader.getSet(Cache.pokerCardSet)).thenReturn(catalog);
        cards.when(CardLoader::getBackItem).thenReturn("back");
        cards.when(CardLoader::getDeckItem).thenReturn("deck");
        for (Card card : catalog) cards.when(() -> CardLoader.get(card.getId())).thenReturn(card);
        display = mock(DisplayManager.class);
        when(display.spawn(any(), any(), any(), any())).thenReturn(true);
        displays = track(mockStatic(DisplayManager.class));
        displays.when(DisplayManager::get).thenReturn(display);
        anchors = track(mockStatic(WorldAnchors.class));
        game = mock(Game.class, withSettings().extraInterfaces(net.tfminecraft.games.game.LiveCardReturns.class));
        when(game.tablePileSlot(any(), anyString(), anyInt(), anyInt(), anyBoolean())).thenCallRealMethod();
        games = track(mockStatic(GamesRegistry.class));
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(game);
        messages = track(mockStatic(Messages.class, call -> call.getArgument(0)));
    }

    @AfterEach final void tearDownTableManager() {
        // assertAll runs every registered cleanup even when setup or an earlier cleanup failed.
        List<Executable> steps = new ArrayList<>(cleanup);
        cleanup.clear();
        assertAll("TableManager fixture cleanup", steps);
    }

    private <T> MockedStatic<T> track(MockedStatic<T> scope) {
        cleanup.addFirst(scope::close);
        return scope;
    }

    private void restoreConfig() {
        Cache.handDealTicks = oldDealTicks;
        Cache.tableRecycleTicks = oldRecycleTicks;
        Cache.wagerPayoutTicks = oldPayoutTicks;
        Cache.wagerVoteSeconds = oldVoteSeconds;
        Cache.wagerMinPlayers = oldMinPlayers;
        Cache.wagerShowChips = oldShowChips;
        Cache.wagerGold = oldGold;
        Cache.wagerSilver = oldSilver;
        Cache.wagerItems.clear();
        Cache.wagerItems.addAll(oldWagerItems);
    }

    private List<SingletonField> snapshotManager() throws IllegalAccessException {
        List<SingletonField> snapshot = new ArrayList<>();
        for (Field field : TableManager.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            field.setAccessible(true);
            Object original = field.get(manager);
            Object contents = original instanceof Map<?, ?> map ? new LinkedHashMap<>(map)
                    : original instanceof Collection<?> collection ? new ArrayList<>(collection) : original;
            snapshot.add(new SingletonField(field, original, contents));
        }
        return snapshot;
    }

    private void restoreManager(List<SingletonField> snapshot) {
        List<Executable> steps = new ArrayList<>();
        steps.add(() -> {
            for (SingletonField saved : snapshot) {
                if (saved.field().getName().equals("handClock")
                        && saved.field().get(manager) != saved.original()) {
                    manager.stopClock();
                }
            }
        });
        for (SingletonField saved : snapshot) steps.add(() -> saved.restore(manager));
        assertAll("TableManager singleton restoration", steps);
    }

    /** Snapshot private state only for cleanup; scenarios still use public workflows. */
    private record SingletonField(Field field, Object original, Object contents) {
        @SuppressWarnings({"rawtypes", "unchecked"})
        void restore(TableManager manager) throws IllegalAccessException {
            if (original instanceof Map map) {
                map.clear();
                map.putAll((Map) contents);
            } else if (original instanceof Collection collection) {
                collection.clear();
                collection.addAll((Collection) contents);
            } else {
                field.set(manager, original);
            }
        }
    }

    Table place(boolean needsDeck) {
        manager.armPlace(player, "freeplay", needsDeck);
        assertTrue(manager.tryPlace(player, new Location(world, 0, 65, 0)));
        assertEquals(1, manager.tables().size());
        return manager.tables().iterator().next();
    }

    void tick(long ticks) {
        MockBukkit.getMock().getScheduler().performTicks(ticks);
    }

    PlayerMock opponent() {
        PlayerMock other = MockBukkit.getMock().addPlayer();
        other.teleport(player.getLocation());
        return other;
    }

    void clickFelt(PlayerMock actor, Table table) {
        // Supply the block-ray result at the input boundary; game state and transfers remain real.
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(actor));
        Location hit = table.getOrigin().clone().add(0.75, 0, 0);
        doReturn(new RayTraceResult(hit.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        PlayerInteractEvent click = new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                actor.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0),
                BlockFace.UP, EquipmentSlot.HAND);
        manager.onInteractBlock(click);
        assertTrue(click.isCancelled());
    }

    void stakeCoin(PlayerMock actor, Table table) {
        actor.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        clickFelt(actor, table);
        assertEquals(1, manager.ownedDenars(table, actor.getUniqueId()));
        assertEquals(0, Accounts.coins(table, actor).available());
    }

}
