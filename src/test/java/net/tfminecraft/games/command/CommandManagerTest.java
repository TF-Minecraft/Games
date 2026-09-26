package net.tfminecraft.games.command;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.game.Game;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.gui.GameSelectGui;
import net.tfminecraft.games.gui.TableOptionsGui;
import net.tfminecraft.games.help.HelpBook;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableManager;

class CommandManagerTest {
    private final CommandManager commands = new CommandManager();
    private Command command;
    private Player player;
    private TableManager manager;
    private Table table;
    private MockedStatic<TableManager> managers;
    private MockedStatic<Messages> messages;

    @BeforeEach void setUp() {
        command = mock(Command.class);
        when(command.getName()).thenReturn("games");
        player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        manager = mock(TableManager.class);
        table = new Table(UUID.randomUUID(), "freeplay", null, 0, null);
        managers = mockStatic(TableManager.class);
        managers.when(TableManager::get).thenReturn(manager);
        messages = mockStatic(Messages.class, call -> call.getArgument(0));
    }
    @AfterEach void tearDown() { messages.close(); managers.close(); }
    private void run(String... args) { assertTrue(commands.onCommand(player, command, "games", args)); }
    private void admin() { when(player.hasPermission("games.admin")).thenReturn(true); }
    private void dealer() {
        when(player.hasPermission("games.bet")).thenReturn(true);
        when(manager.tableNearby(player)).thenReturn(table);
        table.setDealerId(player.getUniqueId());
    }

    @Test void usageHonorsPermissionPriorityAndUnauthorizedActionsDoNothing() {
        run();
        verify(player).sendMessage("admin.no_permission");
        when(player.hasPermission("games.help")).thenReturn(true);
        run();
        verify(player).sendMessage("help.hint");
        when(player.hasPermission("games.bet")).thenReturn(true);
        run();
        verify(player).sendMessage("bet.usage");
        admin();
        run();
        verify(player).sendMessage("admin.usage");
        when(player.hasPermission(anyString())).thenReturn(false);
        run("help"); run("bet"); run("reload");
        verify(player, times(4)).sendMessage("admin.no_permission");
        verifyNoInteractions(manager);
        when(command.getName()).thenReturn("another");
        assertFalse(commands.onCommand(player, command, "another", new String[0]));
    }

    @Test void reloadRequiresDedicatedPermissionAndReportsOutcome() {
        Games previous = Games.plugin;
        Games.plugin = mock(Games.class);
        try {
            admin();
            run("reload");
            verifyNoInteractions(Games.plugin);
            when(player.hasPermission("games.admin.reload")).thenReturn(true);
            when(Games.plugin.reloadAll()).thenReturn(true, false);
            run("reload"); run("reload");
            verify(player).sendMessage("reload.success");
            verify(player).sendMessage("reload.failed");
            run("unknown");
            verify(player).sendMessage("admin.usage");
        } finally { Games.plugin = previous; }
    }

    @Test void consoleCannotRunPlayerOnlyCommands() {
        CommandSender console = mock(CommandSender.class);
        when(console.hasPermission(anyString())).thenReturn(true);
        for (String action : List.of("place", "payout", "session", "deal", "bet", "help", "display")) {
            assertTrue(commands.onCommand(console, command, "games", new String[] {action}));
        }
        verify(console, times(5)).sendMessage("place.players_only");
        verify(console).sendMessage("help.player_only");
        verify(console).sendMessage("display.players_only");
        verifyNoInteractions(manager);
    }

    @Test void placementChoosesGameOrBlackjackOptions() {
        admin();
        try (MockedStatic<GameSelectGui> select = mockStatic(GameSelectGui.class);
                MockedStatic<TableOptionsGui> options = mockStatic(TableOptionsGui.class)) {
            run("place");
            select.verify(() -> GameSelectGui.open(player, false, null));
            run("place", "blackjack");
            options.verify(() -> TableOptionsGui.openPlace(player, false, null));
            for (String game : List.of("poker", "draw", "freeplay")) {
                run("place", game);
                verify(manager).armPlace(player, game, false);
            }
            run("place", "invalid");
            verify(player).sendMessage("place.usage");
        }
    }

    @Test void sessionStartAndStopCheckCurrentStateAndNearbyTable() {
        admin();
        run("session"); run("session", "unknown");
        verify(player, times(2)).sendMessage("session.usage");
        run("session", "start");
        verify(player).sendMessage("wager.no_table");
        when(manager.tableNear(player)).thenReturn(table);
        run("session", "stop");
        verify(player).sendMessage("session.idle");
        run("session", "start");
        verify(manager).beginSession(table);
        table.startSession();
        run("session", "start");
        verify(player).sendMessage("session.already");
        run("session", "stop");
        verify(manager).endSession(table);
    }

    @Test void dealingValidatesCountsAndRoutesPlayerAndBoardCards() {
        admin();
        for (String count : List.of("zero", "0", "-1", "99999999999999")) run("deal", count);
        verify(player, times(4)).sendMessage("deal.usage");
        run("deal");
        verify(player).sendMessage("wager.no_table");
        when(manager.tableNear(player)).thenReturn(table);
        run("deal"); run("deal", "3");
        verify(manager).dealToPlayer(table, player, 1);
        verify(manager).dealToPlayer(table, player, 3);
        run("deal", "table"); run("deal", "table", "unknown");
        run("deal", "table", "board", "x"); run("deal", "table", "board", "0");
        verify(player, times(4)).sendMessage("deal.table_usage");
        run("deal", "table", "BOARD");
        run("deal", "table", "dealer", "back");
        run("deal", "table", "board", "3");
        run("deal", "table", "dealer", "2", "back");
        run("deal", "table", "dealer", "4", "up");
        verify(manager).dealToTable(table, "board", 1, true);
        verify(manager).dealToTable(table, "dealer", 1, false);
        verify(manager).dealToTable(table, "board", 3, true);
        verify(manager).dealToTable(table, "dealer", 2, false);
        verify(manager).dealToTable(table, "dealer", 4, true);
        when(manager.tableNear(player)).thenReturn(null);
        run("deal", "table", "board");
        verify(player, times(2)).sendMessage("wager.no_table");
    }

    @Test void displayDiagnosticSchedulesMovementAndRemovesBothAnchors() {
        admin();
        var display = mock(net.tfminecraft.games.display.DisplayManager.class);
        var itemApi = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class, RETURNS_DEEP_STUBS);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        var world = mock(org.bukkit.World.class);
        var interaction = mock(org.bukkit.entity.Interaction.class);
        var hologram = mock(org.bukkit.entity.TextDisplay.class);
        UUID interactionId = UUID.randomUUID(), labelId = UUID.randomUUID();
        when(interaction.getUniqueId()).thenReturn(interactionId);
        when(hologram.getUniqueId()).thenReturn(labelId);
        when(player.getLocation()).thenReturn(new org.bukkit.Location(world, 10, 65, 20));
        when(player.getEyeLocation()).thenReturn(new org.bukkit.Location(world, 10, 66.62, 20));
        var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        when(itemApi.getCreator().getItemFromPath("back")).thenReturn(item);
        when(display.spawn(any(), any(), eq(item), any())).thenReturn(true);
        try (var protocol = mockStatic(net.tfminecraft.games.display.ProtocolLibBridge.class);
                var displays = mockStatic(net.tfminecraft.games.display.DisplayManager.class);
                var anchors = mockStatic(net.tfminecraft.games.display.WorldAnchors.class);
                var cards = mockStatic(net.tfminecraft.games.loader.CardLoader.class);
                var libs = mockStatic(net.tfminecraft.tlibs.TLibs.class);
                var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            run("display");
            run("display", "spin");
            verify(player, times(2)).sendMessage("display.usage");
            run("display", "test");
            verify(player).sendMessage("display.not_ready");
            protocol.when(net.tfminecraft.games.display.ProtocolLibBridge::isReady).thenReturn(true);
            libs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(itemApi);
            cards.when(net.tfminecraft.games.loader.CardLoader::getBackItem).thenReturn("back");
            displays.when(net.tfminecraft.games.display.DisplayManager::get).thenReturn(display);
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            anchors.when(() -> net.tfminecraft.games.display.WorldAnchors.spawnInteraction(any(), eq(.4f), eq(.2f), anyString())).thenReturn(interaction);
            anchors.when(() -> net.tfminecraft.games.display.WorldAnchors.spawnLabel(any(), eq("Display test"))).thenReturn(hologram);
            run("display", "test");
            var token = org.mockito.ArgumentCaptor.forClass(UUID.class);
            var origin = org.mockito.ArgumentCaptor.forClass(org.bukkit.Location.class);
            verify(display).spawn(token.capture(), origin.capture(), eq(item), any());
            assertEquals(10, origin.getValue().getX());
            assertEquals(66 + Cache.tableYOffset, origin.getValue().getY());
            assertEquals(22, origin.getValue().getZ());
            verify(display).setItemFor(token.getValue(), player, item);
            var move = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            var remove = org.mockito.ArgumentCaptor.forClass(Runnable.class);
            verify(scheduler).runTaskLater(eq(Games.plugin), move.capture(), eq(5L));
            verify(scheduler).runTaskLater(eq(Games.plugin), remove.capture(), eq(160L));
            move.getValue().run();
            verify(display).setTransform(eq(token.getValue()), argThat(pose -> Math.abs(pose.translation().x - .8f) < .00001f), eq(Cache.interpolationTicks));
            remove.getValue().run();
            verify(display).despawn(token.getValue());
            anchors.verify(() -> net.tfminecraft.games.display.WorldAnchors.remove(interactionId));
            anchors.verify(() -> net.tfminecraft.games.display.WorldAnchors.remove(labelId));
        }
    }

    @Test void displayDiagnosticRefusesMissingItemsAndSpawnFailureWithoutSchedulingTasks() {
        admin();
        var display = mock(net.tfminecraft.games.display.DisplayManager.class);
        var api = mock(net.tfminecraft.tlibs.objects.api.ItemAPI.class, RETURNS_DEEP_STUBS);
        var world = mock(org.bukkit.World.class);
        var surface = mock(org.bukkit.block.Block.class);
        when(surface.getType()).thenReturn(org.bukkit.Material.STONE);
        when(surface.getLocation()).thenAnswer(call -> new org.bukkit.Location(world, 2, 64, 3));
        when(player.getTargetBlockExact(8)).thenReturn(surface);
        var item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        var scheduler = mock(org.bukkit.scheduler.BukkitScheduler.class);
        try (var protocol = mockStatic(net.tfminecraft.games.display.ProtocolLibBridge.class);
                var displays = mockStatic(net.tfminecraft.games.display.DisplayManager.class);
                var cards = mockStatic(net.tfminecraft.games.loader.CardLoader.class);
                var libs = mockStatic(net.tfminecraft.tlibs.TLibs.class);
                var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            protocol.when(net.tfminecraft.games.display.ProtocolLibBridge::isReady).thenReturn(true);
            displays.when(net.tfminecraft.games.display.DisplayManager::get).thenReturn(display);
            libs.when(net.tfminecraft.tlibs.TLibs::getItemAPI).thenReturn(api);
            bukkit.when(org.bukkit.Bukkit::getScheduler).thenReturn(scheduler);
            when(api.getCreator().getItemFromPath("missing")).thenReturn(null);
            for (String missing : java.util.Arrays.asList(null, " ", "missing")) {
                cards.when(net.tfminecraft.games.loader.CardLoader::getBackItem).thenReturn(missing);
                run("display", "test");
            }
            verify(player, times(3)).sendMessage("display.missing_item");
            verifyNoInteractions(display);
            cards.when(net.tfminecraft.games.loader.CardLoader::getBackItem).thenReturn("back");
            cards.when(() -> net.tfminecraft.games.loader.CardLoader.get("cerrith_1"))
                    .thenReturn(new net.tfminecraft.games.card.Card("cerrith_1", "cerrith", 1, false, "face"));
            when(api.getCreator().getItemFromPath("back")).thenReturn(item);
            run("display", "test");
            verify(display).spawn(any(), argThat(atLocation -> atLocation.getX() == 2.5
                    && atLocation.getY() == 65.05 + Cache.tableYOffset && atLocation.getZ() == 3.5), eq(item), any());
            verify(player).sendMessage("display.spawn_failed");
            verifyNoInteractions(scheduler);
            verify(display, never()).setItemFor(any(), any(), any());
        }
    }

    @Test void completionOffersOnlinePayoutTargetsAndConfiguredCardSetsByPrefix() {
        admin();
        Player alice = mock(Player.class), bob = mock(Player.class);
        when(alice.getName()).thenReturn("Alice"); when(bob.getName()).thenReturn("Bob");
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class);
                var cards = mockStatic(net.tfminecraft.games.loader.CardLoader.class)) {
            bukkit.when(org.bukkit.Bukkit::getOnlinePlayers).thenReturn(List.of(alice, bob));
            cards.when(net.tfminecraft.games.loader.CardLoader::setNames).thenReturn(new java.util.LinkedHashSet<>(List.of("poker", "blackjack")));
            assertEquals(List.of("Bob"), commands.onTabComplete(player, command, "games", new String[] {"payout", "b"}));
            assertEquals(List.of("poker"), commands.onTabComplete(player, command, "games", new String[] {"deck", "test", "p"}));
            assertTrue(commands.onTabComplete(player, command, "games", new String[] {"payout", "Nobody"}).isEmpty());
        }
    }

    @Test void payoutSelectsSelfOrOnlineNamedWinnerAndRefusesMissingTargets() {
        admin();
        run("payout");
        verify(player).sendMessage("wager.no_table");
        when(manager.tableNear(player)).thenReturn(table);
        run("payout");
        verify(manager).payout(table, player);
        Player winner = mock(Player.class);
        try (var bukkit = mockStatic(org.bukkit.Bukkit.class)) {
            run("payout", "Missing");
            verify(player).sendMessage("admin.unknown_player");
            bukkit.when(() -> org.bukkit.Bukkit.getPlayerExact("Bob")).thenReturn(winner);
            run("payout", "Bob");
            verify(player, times(2)).sendMessage("admin.unknown_player");
            when(winner.isOnline()).thenReturn(true);
            run("payout", "Bob");
            verify(manager).payout(table, winner);
        }
    }

    @Test void deckDiagnosticReportsDrawDiscardAndRecycleResults() {
        admin();
        run("deck"); run("deck", "invalid");
        verify(player, times(2)).sendMessage("deck.usage");
        var deck = mock(net.tfminecraft.games.deck.Deck.class);
        var normal = new net.tfminecraft.games.card.Card("ace", "oseni", 1, false, "");
        var joker = new net.tfminecraft.games.card.Card("joker", "none", 0, true, "");
        when(deck.getSetName()).thenReturn("test-set");
        when(deck.size()).thenReturn(2);
        when(deck.draw()).thenReturn(java.util.Optional.of(normal), java.util.Optional.of(joker), java.util.Optional.empty());
        try (var decks = mockStatic(net.tfminecraft.games.deck.Deck.class)) {
            decks.when(() -> net.tfminecraft.games.deck.Deck.create("missing")).thenReturn(java.util.Optional.empty());
            run("deck", "test", "missing");
            verify(player).sendMessage("deck.unknown_set");
            decks.when(() -> net.tfminecraft.games.deck.Deck.create(Cache.pokerCardSet)).thenReturn(java.util.Optional.of(deck));
            run("deck", "test");
            verify(deck).shuffle();
            verify(deck).discard(joker);
            verify(deck).recycle();
            verify(player).sendMessage("deck.shuffled");
            verify(player).sendMessage("deck.drawn");
            verify(player).sendMessage("deck.empty_ok");
            verify(player).sendMessage("deck.discarded");
            verify(player).sendMessage("deck.recycled");
            messages.verify(() -> Messages.get("deck.drawn", "drawn", "2", "jokers", "1", "remaining", "0"));
        }
    }

    @Test void deckDiagnosticReportsADeckThatKeepsDealingAfterRunningOut() {
        admin();
        var deck = mock(net.tfminecraft.games.deck.Deck.class);
        var card = new net.tfminecraft.games.card.Card("ace", "oseni", 1, false, "");
        when(deck.draw()).thenReturn(java.util.Optional.of(card), java.util.Optional.empty(),
                java.util.Optional.of(card));
        try (var decks = mockStatic(net.tfminecraft.games.deck.Deck.class)) {
            decks.when(() -> net.tfminecraft.games.deck.Deck.create("broken")).thenReturn(java.util.Optional.of(deck));
            run("deck", "test", "broken");
            verify(player).sendMessage("deck.empty_failed");
            verify(player, never()).sendMessage("deck.empty_ok");
        }
    }

    @Test void aMinimumAloneAlsoCapsTheTableButAMaximumAloneCannotOpenIt() {
        dealer();
        run("bet", "max", "3");
        assertEquals(3, table.maxBet());
        run("bet", "open");
        verify(player).sendMessage("bet.need_limits");
        assertFalse(table.betOpen());
        Table other = new Table(UUID.randomUUID(), "freeplay", null, 0, null);
        other.setDealerId(player.getUniqueId());
        when(manager.tableNearby(player)).thenReturn(other);
        run("bet", "min", "50");
        assertEquals(50, other.minBet());
        assertEquals(50, other.maxBet());
        run("bet", "open");
        assertTrue(other.betOpen());
        run("bet", "max", "60");
        assertEquals(60, other.maxBet());
    }

    @Test void closingBlackjackBetsHandsTheBoxesToTheGameInsteadOfStartingASession() {
        dealer();
        table.setBetOpen(true);
        var blackjack = mock(net.tfminecraft.games.game.BlackjackGame.class);
        try (MockedStatic<GamesRegistry> games = mockStatic(GamesRegistry.class)) {
            games.when(() -> GamesRegistry.of("freeplay")).thenReturn(blackjack);
            run("bet", "close");
            verify(blackjack).closeBets(table);
            verify(manager, never()).beginSession(table);
            verify(player).sendMessage("bet.closed_cmd");
            assertFalse(table.betOpen());
        }
    }

    @Test void bettingLimitsRequireIdleDealerAndCannotInvertRange() {
        when(player.hasPermission("games.bet")).thenReturn(true);
        run("bet");
        verify(player).sendMessage("bet.usage");
        run("bet", "min", "5");
        verify(player).sendMessage("wager.no_table");
        when(manager.tableNearby(player)).thenReturn(table);
        run("bet", "min", "5");
        verify(player).sendMessage("bet.not_dealer");
        dealer();
        run("bet", "open");
        verify(player).sendMessage("bet.need_limits");
        run("bet", "min"); run("bet", "max", "bad"); run("bet", "min", "0");
        run("bet", "max", "20"); run("bet", "min", "5");
        assertEquals(5, table.minBet()); assertEquals(20, table.maxBet());
        run("bet", "min", "21"); run("bet", "max", "4");
        assertEquals(5, table.minBet()); assertEquals(20, table.maxBet());
        run("bet", "open"); assertTrue(table.betOpen());
        run("bet", "close"); assertFalse(table.betOpen());
        verify(manager, never()).beginSession(table);
        when(manager.hasNonDealerOwnedPile(table)).thenReturn(true);
        run("bet", "close"); verify(manager).beginSession(table);
        run("bet", "unknown");
        table.startSession();
        run("bet", "min", "2");
        verify(player).sendMessage("session.already");
        assertEquals(5, table.minBet());
    }

    @Test void playingWordsRequireLiveGameAndActorPermission() {
        dealer();
        run("bet", "hit");
        verify(player).sendMessage("bet.need_live");
        table.startSession();
        Game game = mock(Game.class);
        try (MockedStatic<GamesRegistry> games = mockStatic(GamesRegistry.class)) {
            run("bet", "hit");
            verify(player, times(2)).sendMessage("bet.need_live");
            games.when(() -> GamesRegistry.of("freeplay")).thenReturn(game);
            run("bet", "hit"); verify(player).sendMessage("bet.not_actor");
            when(game.allowPlayChat(table, player)).thenReturn(true);
            for (String action : List.of("hit", "stand", "double", "split", "check", "call", "fold", "raise")) {
                run("bet", action);
                verify(manager).applyPlayCall(player, action);
            }
        }
    }

    @Test void helpOpensRequestedOrDefaultBookAndRejectsMissingContent() {
        var saved = Map.copyOf(Cache.helpBooks);
        try {
            Cache.helpBooks.clear();
            when(player.hasPermission("games.help")).thenReturn(true);
            run("help"); verify(player).sendMessage("help.unknown");
            HelpBook index = mock(HelpBook.class);
            HelpBook poker = mock(HelpBook.class);
            Cache.helpBooks.put("index", index);
            Cache.helpBooks.put("poker", poker);
            run("help"); verify(index).openFor(player);
            run("help", "POKER"); verify(poker).openFor(player);
            run("help", "rules"); verify(player, times(2)).sendMessage("help.unknown");
            assertEquals(List.of("poker"), commands.onTabComplete(player, command, "games", new String[] {"help", ""}));
        } finally { Cache.helpBooks.clear(); Cache.helpBooks.putAll(saved); }
    }

    @Test void firstWordCompletionListsEveryPermittedGroup() {
        when(player.hasPermission("games.help")).thenReturn(true);
        assertEquals(List.of("help"), commands.onTabComplete(player, command, "games", new String[] {""}));
        admin();
        assertEquals(List.of("help", "reload", "deck", "display", "place", "payout", "session", "deal"),
                commands.onTabComplete(player, command, "games", new String[] {""}));
    }

    @Test void adminsWithoutHelpOrBetPermissionGetNoSuggestionsForThem() {
        admin();
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"help", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"bet", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"deal", "3", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"deck", "run", ""}).isEmpty());
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"place", "poker", ""}).isEmpty());
    }

    @Test void tabCompletionExposesOnlyPermittedActionsAndFiltersPrefixes() {
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {""}).isEmpty());
        when(player.hasPermission("games.bet")).thenReturn(true);
        assertEquals(List.of("bet"), commands.onTabComplete(player, command, "games", new String[] {"B"}));
        assertEquals(List.of("close", "check", "call"), commands.onTabComplete(player, command, "games", new String[] {"bet", "c"}));
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"session", ""}).isEmpty());
        admin();
        assertEquals(List.of("start", "stop"), commands.onTabComplete(player, command, "games", new String[] {"session", "s"}));
        assertEquals(List.of("table"), commands.onTabComplete(player, command, "games", new String[] {"deal", ""}));
        assertEquals(List.of("board", "dealer"), commands.onTabComplete(player, command, "games", new String[] {"deal", "table", ""}));
        assertEquals(List.of("blackjack"), commands.onTabComplete(player, command, "games", new String[] {"place", "bl"}));
        assertEquals(List.of("test"), commands.onTabComplete(player, command, "games", new String[] {"deck", "t"}));
        assertEquals(List.of("test"), commands.onTabComplete(player, command, "games", new String[] {"display", "t"}));
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {"unknown", ""}).isEmpty());
        when(command.getName()).thenReturn("another");
        assertTrue(commands.onTabComplete(player, command, "games", new String[] {""}).isEmpty());
    }
}
