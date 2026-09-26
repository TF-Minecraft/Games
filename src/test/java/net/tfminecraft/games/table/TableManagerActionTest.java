package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.DisplayPose;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.game.FreePlayGame;
import net.tfminecraft.games.game.GamesRegistry;
import net.tfminecraft.games.gui.TableOptionsGui;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.simplefactions.enums.GuildModifier;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

class TableManagerActionTest extends TableManagerFixture {
    @Test void selectingGameAtChosenSurfacePlacesItAndConsumesOneDeck() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 2));
        Location chosen = new Location(world, 3, 65, 4);
        manager.selectGame(player, "freeplay", chosen, true);
        Table table = manager.tables().iterator().next();
        assertEquals("freeplay", table.getGameId());
        assertEquals(3, table.getOrigin().getX());
        assertEquals(4, table.getOrigin().getZ());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertFalse(manager.tryPlace(player, chosen.clone().add(10, 0, 0)), "selection arm is consumed");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void selectingGameWithoutSurfaceArmsTheNextPlacement(boolean requireDeck) {
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 2));
        manager.selectGame(player, "freeplay", null, requireDeck);
        assertTrue(manager.tables().isEmpty());
        assertEquals(requireDeck ? "place.armed" : "place.armed_admin", player.nextMessage());
        assertTrue(manager.tryPlace(player, player.getLocation()));
        assertEquals(1, manager.tables().size());
        assertEquals(requireDeck ? 1 : 2, player.getInventory().getItemInMainHand().getAmount());
    }

    @Test void blackjackOwnerAndStaffCanOpenOptionsButLiveGameCannotBeEdited() {
        Table table = blackjack();
        PlayerMock staff = opponent();
        staff.addAttachment(Games.plugin, "games.admin", true);
        player.setSneaking(true);
        staff.setSneaking(true);
        try (var options = mockStatic(TableOptionsGui.class)) {
            assertTrue(shoe(table, player).isCancelled());
            options.verify(() -> TableOptionsGui.openEdit(player, table));
            shoe(table, staff);
            options.verify(() -> TableOptionsGui.openEdit(staff, table));
            options.clearInvocations();
            manager.beginSession(table);
            clearMessages(player);
            shoe(table, player);
            assertEquals("place.options_live", player.nextMessage());
            options.verifyNoInteractions();
            assertTrue(table.live());
        }
    }

    @Test void unrelatedBlackjackPlayerCannotOpenOptionsOrFlushMoney() {
        Table table = blackjack();
        PlayerMock visitor = opponent();
        stakeCoin(visitor, table);
        visitor.setSneaking(true);
        clearMessages(visitor);
        try (var options = mockStatic(TableOptionsGui.class)) {
            shoe(table, visitor);
            options.verifyNoInteractions();
        }
        assertEquals("place.options_denied", visitor.nextMessage());
        assertEquals(1, table.ledger().total(visitor.getUniqueId()));
        assertEquals(0, Accounts.coins(table, visitor).available());
    }

    @Test void sneakingFreeplayShoePaysIdlePotButLiveGameRefusesManualFlush() {
        games.when(() -> GamesRegistry.of("freeplay")).thenReturn(new FreePlayGame());
        Table table = place(false);
        PlayerMock other = opponent();
        stakeCoin(player, table);
        stakeCoin(other, table);
        player.setSneaking(true);
        manager.beginSession(table);
        clearMessages(player);
        shoe(table, player);
        assertEquals("wager.no_flush", player.nextMessage());
        assertEquals(2, table.ledger().total());
        manager.endSession(table);
        shoe(table, player);
        assertTrue(table.ledger().isEmpty());
        assertEquals(2, Accounts.coins(table, player).available());
        assertEquals(0, Accounts.coins(table, other).available());
    }

    @Test void liveShoeReturnsSelectedCardAndReportsExactCountToGame() {
        Table table = place(false);
        Map<UUID, DisplayPose> poses = new HashMap<>();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            poses.put(call.getArgument(0), call.getArgument(3));
            return true;
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        // Like DisplayManager, a tracked token's pose for other viewers falls back to its own.
        when(display.otherPoseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransformSplit(any(), any(), any(), anyInt());
        manager.dealToPlayer(table, player, 2);
        tick(3);
        HandCard selected = table.handOf(player.getUniqueId()).getFirst();
        aimAndSelect(selected);
        assertTrue(selected.isSelected());
        assertFalse(table.handOf(player.getUniqueId()).get(1).isSelected());
        when(game.allowReturnSelected(table, player)).thenReturn(true);
        manager.beginSession(table);
        assertTrue(shoe(table, player).isCancelled());
        tick(5);
        verify((net.tfminecraft.games.game.LiveCardReturns) game).onReturnedSelected(table, player, 1);
        verify(game, never()).onShoeClick(table, player);
        assertEquals(1, table.handOf(player.getUniqueId()).size());
        assertFalse(table.handOf(player.getUniqueId()).contains(selected));
        assertEquals(1, table.getDeck().remaining() + table.getDeck().discarded());
    }

    @Test void aLiveGameThatTakesNoCardsBackMidHandKeepsTheSelectionAndGetsTheShoeClick() {
        // Like poker and blackjack: cards go back to the shoe only between hands.
        net.tfminecraft.games.game.Game between = mock(net.tfminecraft.games.game.Game.class);
        when(between.allowReturnSelected(any(), any())).thenAnswer(call -> !((Table) call.getArgument(0)).live());
        games.when(() -> net.tfminecraft.games.game.GamesRegistry.of("freeplay")).thenReturn(between);
        Table table = place(false);
        Map<UUID, DisplayPose> poses = new HashMap<>();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            poses.put(call.getArgument(0), call.getArgument(3));
            return true;
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        when(display.otherPoseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransformSplit(any(), any(), any(), anyInt());
        manager.dealToPlayer(table, player, 2);
        tick(3);
        HandCard selected = table.handOf(player.getUniqueId()).getFirst();
        aimAndSelect(selected);
        assertTrue(selected.isSelected());
        manager.beginSession(table);
        shoe(table, player);
        tick(5);
        verify(between).onShoeClick(table, player);
        assertEquals(2, table.handOf(player.getUniqueId()).size(), "The selected card stays in the hand");
        assertTrue(selected.isSelected());
    }

    @Test void aLiveReturnGameThatDeclinesRightNowKeepsTheSelectionAndGetsTheShoeClick() {
        // Like draw outside its draw phase, or when it is someone else's turn.
        when(game.allowReturnSelected(any(), any())).thenAnswer(call -> !((Table) call.getArgument(0)).live());
        Table table = place(false);
        Map<UUID, DisplayPose> poses = new HashMap<>();
        when(display.spawn(any(), any(), any(), any())).thenAnswer(call -> {
            poses.put(call.getArgument(0), call.getArgument(3));
            return true;
        });
        when(display.poseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        when(display.otherPoseOf(any())).thenAnswer(call -> poses.get(call.getArgument(0)));
        doAnswer(call -> { poses.put(call.getArgument(0), call.getArgument(1)); return null; })
                .when(display).setTransformSplit(any(), any(), any(), anyInt());
        manager.dealToPlayer(table, player, 2);
        tick(3);
        HandCard selected = table.handOf(player.getUniqueId()).getFirst();
        aimAndSelect(selected);
        manager.beginSession(table);
        shoe(table, player);
        tick(5);
        verify(game).onShoeClick(table, player);
        verify((net.tfminecraft.games.game.LiveCardReturns) game, never()).onReturnedSelected(any(), any(), anyInt());
        assertEquals(2, table.handOf(player.getUniqueId()).size(), "The selected card stays in the hand");
        assertTrue(selected.isSelected());
    }

    @Test void liveShoeWithNoSelectionDispatchesGameActionWithoutReturningCards() {
        Table table = place(false);
        when(game.allowReturnSelected(table, player)).thenReturn(true);
        manager.beginSession(table);
        shoe(table, player);
        verify(game).onShoeClick(table, player);
        manager.dealToPlayer(table, player, 1);
        tick(3);
        List<HandCard> held = List.copyOf(table.handOf(player.getUniqueId()));
        shoe(table, player);
        verify(game, times(2)).onShoeClick(table, player);
        verify((net.tfminecraft.games.game.LiveCardReturns) game, never()).onReturnedSelected(any(), any(), anyInt());
        assertEquals(held, table.handOf(player.getUniqueId()));
    }

    @Test void successfulDealerClaimConsumesShoeClickBeforeFreeDrawing() {
        Table table = place(false);
        when(game.tryClaimDealer(table, player)).thenReturn(true);
        when(game.allowFreeDraw(table, player)).thenReturn(true);
        shoe(table, player);
        verify(game).tryClaimDealer(table, player);
        assertTrue(table.getHands().isEmpty());
        assertEquals(2, table.getDeck().remaining());
    }

    @Test void armedPlacementClickOnExistingShoeDoesNotDrawOrLosePlacementArm() {
        Table table = place(false);
        when(game.allowFreeDraw(table, player)).thenReturn(true);
        manager.armPlace(player, "freeplay", false);
        shoe(table, player);
        verify(game, never()).tryClaimDealer(table, player);
        assertTrue(table.getHands().isEmpty());
        assertTrue(manager.tryPlace(player, table.getOrigin().clone().add(10, 0, 0)));
        assertEquals(2, manager.tables().size());
    }

    @Test void invalidLootProposalsPreserveItemsAndDoNotCreateVotes() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 7);
        assertEquals("wager.no_table", player.nextMessage());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        Table table = place(false);
        stakeCoin(player, table);
        clearMessages(player);
        manager.proposeLoot(player, 7);
        assertEquals("wager.need_item", player.nextMessage());
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET, 2));
        manager.proposeLoot(player, 7);
        assertEquals("wager.use_click", player.nextMessage());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        Cache.wagerMinPlayers = 2;
        manager.proposeLoot(player, 7);
        assertEquals("wager.need_players", player.nextMessage());
        assertNull(table.getVote());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, table.ledger().total());
    }

    @Test void proposerCannotApproveAndDuplicateVotesCannotManufactureAMajority() {
        Table table = place(false);
        PlayerMock first = opponent();
        PlayerMock second = opponent();
        PlayerMock third = opponent();
        PlayerMock outsider = opponent();
        for (PlayerMock seated : List.of(player, first, second, third)) stakeCoin(seated, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 7);
        var vote = table.getVote();
        assertNotNull(vote);
        clearMessages(player);
        manager.voteWager(player, true);
        assertEquals("wager.not_eligible", player.nextMessage());
        manager.voteWager(outsider, true);
        assertEquals("wager.no_vote", outsider.nextMessage());
        manager.voteWager(first, true);
        clearMessages(first);
        manager.voteWager(first, true);
        assertEquals("wager.already_voted", first.nextMessage());
        assertEquals(1, vote.yes().size());
        assertSame(vote, table.getVote());
        clearMessages(player);
        manager.proposeLoot(player, 99);
        assertEquals("wager.busy", player.nextMessage());
        assertEquals(7, vote.denars());
        manager.voteWager(player, false);
        assertNull(table.getVote());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(4, table.ledger().total());
        tick(25);
        assertEquals(4, table.ledger().total());
    }

    @Test void soloLootApprovalWaitsForPlacementAndBlocksReplacingTheArmedProposal() {
        Table table = place(false);
        stakeCoin(player, table);
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
        manager.proposeLoot(player, 7);
        assertNull(table.getVote());
        assertEquals(1, table.ledger().total());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        clearMessages(player);
        manager.proposeLoot(player, 99);
        assertEquals("wager.busy", player.nextMessage());
        clickFelt(player, table);
        assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        assertEquals(15, table.ledger().total());
    }

    @Test void expiredLootPlacementKeepsItemsAndAllowsANewProposal() {
        int previousSeconds = Cache.wagerPlaceSeconds;
        Cache.wagerPlaceSeconds = 1;
        try {
            Table table = place(false);
            stakeCoin(player, table);
            player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
            manager.proposeLoot(player, 7);
            clearMessages(player);
            tick(21);
            assertEquals("wager.place_timeout", player.nextMessage());
            assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
            assertEquals(1, table.ledger().total());
            manager.proposeLoot(player, 9);
            clickFelt(player, table);
            assertEquals(19, table.ledger().total());
            assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
            clearMessages(player);
            tick(21);
            assertNull(player.nextMessage(), "Successful placement cancels the new expiry task");
            assertEquals(19, table.ledger().total());
        } finally {
            Cache.wagerPlaceSeconds = previousSeconds;
        }
    }

    @Test void wrongOrReducedLootStackCannotCommitButRestoredOriginalCanRetry() {
        Table table = place(false);
        stakeCoin(player, table);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
        player.getInventory().setItem(1, new ItemStack(Material.EMERALD, 2));
        player.getInventory().setHeldItemSlot(0);
        manager.proposeLoot(player, 7);
        clearMessages(player);
        player.getInventory().setHeldItemSlot(1);
        clickFelt(player, table);
        assertEquals("wager.wrong_item", player.nextMessage());
        assertEquals(2, player.getInventory().getItem(0).getAmount());
        assertEquals(2, player.getInventory().getItem(1).getAmount());
        assertEquals(1, table.ledger().total());
        player.getInventory().setHeldItemSlot(0);
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND));
        player.getInventory().setItem(2, new ItemStack(Material.DIAMOND));
        clickFelt(player, table);
        assertEquals("wager.wrong_item", player.nextMessage());
        assertEquals(1, table.ledger().total());
        assertEquals(2, player.getInventory().all(Material.DIAMOND).values().stream()
                .mapToInt(ItemStack::getAmount).sum());
        player.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 2));
        player.getInventory().clear(2);
        clickFelt(player, table);
        assertEquals(15, table.ledger().total());
        assertTrue(player.getInventory().all(Material.DIAMOND).isEmpty());
        assertEquals(2, player.getInventory().getItem(1).getAmount());
    }

    @Test void pickingUpTableCancelsPendingLootPlacementWithoutTakingTheOfferedItems() {
        int previousSeconds = Cache.wagerPlaceSeconds;
        Cache.wagerPlaceSeconds = 1;
        try {
            Table table = place(false);
            stakeCoin(player, table);
            player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 2));
            manager.proposeLoot(player, 7);
            Entity anchor = mock(Entity.class);
            anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
            EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
            when(hit.getEntity()).thenReturn(anchor);
            when(hit.getDamager()).thenReturn(player);
            manager.onHitEntity(hit);
            assertTrue(manager.tables().isEmpty());
            assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
            assertEquals(Material.DIAMOND, player.getInventory().getItemInMainHand().getType());
            assertEquals(1, Accounts.coins(table, player).available());
            clearMessages(player);
            tick(21);
            assertNull(player.nextMessage(), "Removed tables must cancel placement expiry messages");
            Table replacement = place(false);
            player.getInventory().setHeldItemSlot(player.getInventory().first(Material.GOLD_NUGGET));
            clickFelt(player, replacement);
            assertEquals(1, replacement.ledger().total());
            player.getInventory().setHeldItemSlot(0);
            manager.proposeLoot(player, 9);
            clickFelt(player, replacement);
            assertEquals(19, replacement.ledger().total());
        } finally {
            Cache.wagerPlaceSeconds = previousSeconds;
        }
    }

    @Test void guildlessBlackjackPlacementRefusalPreservesDeckAndConsumesOnlyThePlacementArm() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 2));
        manager.selectGame(player, "blackjack", player.getLocation(), true);
        assertEquals("place.no_guild", player.nextMessage());
        assertTrue(manager.tables().isEmpty());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertFalse(manager.tryPlace(player, player.getLocation()));
        verify(display, never()).spawn(any(), any(), any(), any());
    }

    @Test void guildTableCapRefusesExtraPlacementUntilCapIncreasesWithoutConsumingDeck() throws Exception {
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(game);
        PluginManager plugins = mock(PluginManager.class);
        Plugin integration = mock(Plugin.class);
        when(integration.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(integration);
        Guild guild = mock(Guild.class);
        when(guild.getId()).thenReturn("card-guild");
        when(guild.getLeader()).thenReturn(player.getName());
        when(guild.getModifier(GuildModifier.AUTO_DEALER_TABLES)).thenReturn(1.0);
        try (var bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                var factions = mockStatic(FactionManager.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            factions.when(() -> FactionManager.getGuildByMember(player.getName())).thenReturn(guild);
            factions.when(() -> FactionManager.getGuildByString("card-guild")).thenReturn(guild);
            player.getInventory().setItemInMainHand(new ItemStack(Material.PAPER, 2));
            manager.selectGame(player, "blackjack", player.getLocation(), true);
            assertEquals(1, manager.tables().size());
            Table first = manager.tables().iterator().next();
            assertEquals("card-guild", first.ownerGuildId());
            assertEquals(player.getUniqueId(), first.ownerPlayer());
            assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
            clearMessages(player);
            Location second = player.getLocation().clone().add(10, 0, 0);
            manager.selectGame(player, "blackjack", second, true);
            assertEquals("place.no_slots", player.nextMessage());
            assertEquals(1, manager.tables().size());
            assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
            assertFalse(manager.tryPlace(player, second));
            try (var files = java.nio.file.Files.list(data.resolve("Data/tables"))) {
                assertEquals(1, files.count(), "refused placement must not leave a table file");
            }
            when(guild.getModifier(GuildModifier.AUTO_DEALER_TABLES)).thenReturn(2.0);
            manager.selectGame(player, "blackjack", second, true);
            assertEquals(2, manager.tables().size());
            assertTrue(manager.tables().stream().allMatch(table -> "card-guild".equals(table.ownerGuildId())));
            assertTrue(player.getInventory().getItemInMainHand().getType().isAir());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void endingAnUnsettledSessionAuditsRemainingOwnerBalanceWithoutDestroyingMoney(boolean auditEnabled) {
        boolean previousAudit = Cache.wagerAuditLog;
        try {
            Cache.wagerAuditLog = auditEnabled;
            Table table = place(false);
            stakeCoin(player, table);
            manager.beginSession(table);
            var logger = Games.plugin.getLogger();
            clearInvocations(logger);
            manager.endSession(table);
            assertFalse(table.live());
            assertEquals(1, table.ledger().total(player.getUniqueId()));
            assertEquals(0, Accounts.coins(table, player).available());
            verify(game).onSessionEnd(table);
            if (auditEnabled) {
                verify(logger).warning(argThat((String message) -> message.contains(table.getId().toString())
                        && message.contains("session end left 1 denars on the felt, tray=0")
                        && message.contains(player.getUniqueId() + "=1")));
            } else {
                verify(logger, never()).warning(anyString());
            }
            manager.refundOwnedPiles(table, player);
            assertTrue(table.ledger().isEmpty());
            assertEquals(1, Accounts.coins(table, player).available(), "an audited balance stays recoverable");
        } finally {
            Cache.wagerAuditLog = previousAudit;
        }
    }

    @Test void automaticSessionStartWaitsForTheConfiguredNumberOfFundedSeats() {
        Table table = place(false);
        when(game.minActives()).thenReturn(2);
        stakeCoin(player, table);
        assertFalse(table.live());
        verify(game, never()).onSessionStart(table);
        PlayerMock other = opponent();
        stakeCoin(other, table);
        assertTrue(table.live());
        verify(game).onSessionStart(table);
        assertEquals(2, table.ledger().total());
        assertEquals(2, table.actives().size());
        manager.tryBeginSession(table);
        verify(game, times(1)).onSessionStart(table);
    }

    private Table blackjack() {
        games.when(() -> GamesRegistry.of("blackjack")).thenReturn(game);
        var permission = player.addAttachment(Games.plugin, TableHouse.STAFF_PERM, true);
        TableHouse house = TableHouse.forPlace(player, null);
        house.setStaffMint(true);
        manager.armPlace(player, "blackjack", false, house);
        assertTrue(manager.tryPlace(player, player.getLocation()));
        Table table = manager.tables().iterator().next();
        player.removeAttachment(permission);
        table.setBetOpen(true);
        return table;
    }

    private PlayerInteractAtEntityEvent shoe(Table table, PlayerMock actor) {
        Entity shoe = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(shoe)).thenReturn(table.getId().toString());
        PlayerInteractAtEntityEvent event = new PlayerInteractAtEntityEvent(actor, shoe, new Vector(), EquipmentSlot.HAND);
        manager.onInteractAtEntity(event);
        return event;
    }

    private void aimAndSelect(HandCard held) {
        Location hit = player.getEyeLocation().add(player.getEyeLocation().getDirection().multiply(0.5));
        when(display.worldLocation(held.tokenId())).thenReturn(hit);
        Player input = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        doReturn(null).when(input).rayTraceBlocks(anyDouble());
        PlayerInteractEvent event = new PlayerInteractEvent(input, Action.RIGHT_CLICK_AIR, null, null, null, EquipmentSlot.HAND);
        manager.onInteractBlock(event);
        assertTrue(event.isCancelled());
    }

    private static void clearMessages(PlayerMock player) {
        while (player.nextMessage() != null) { }
    }
}
