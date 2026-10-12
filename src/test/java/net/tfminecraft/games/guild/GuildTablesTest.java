package net.tfminecraft.games.guild;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.table.Table;
import net.tfminecraft.games.table.TableHouse;
import net.tfminecraft.games.table.TableManager;
import net.tfminecraft.simplefactions.enums.GuildModifier;
import net.tfminecraft.simplefactions.api.SimpleFactionsApi;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;

class GuildTablesTest {
    private MockedStatic<Bukkit> bukkit;
    private Games previousPlugin;
    private Logger logger;
    private MockedStatic<FactionManager> factions;
    private MockedStatic<SimpleFactionsApi> api;
    private static final UUID INCARNATION = UUID.fromString("00000000-0000-0000-0000-000000000111");
    private MockedStatic<TableManager> managers;
    private PluginManager plugins;
    private Plugin integration;
    private Guild guild;
    private Player player;
    private final List<Table> tables = new ArrayList<>();

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
        // The warning is once per server start; begin each test from a fresh start.
        var loggedFail = GuildTables.class.getDeclaredField("loggedFail");
        loggedFail.setAccessible(true);
        loggedFail.setBoolean(null, false);
        plugins = mock(PluginManager.class);
        integration = mock(Plugin.class);
        when(integration.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(integration);
        bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
        bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
        api = mockStatic(SimpleFactionsApi.class);
        api.when(SimpleFactionsApi::ready).thenReturn(true);
        factions = mockStatic(FactionManager.class);
        guild = mock(Guild.class);
        when(guild.getId()).thenReturn("guild");
        when(guild.getIncarnationId()).thenReturn(INCARNATION);
        when(guild.getName()).thenReturn("The Guild");
        when(guild.isLeader(any(Player.class))).thenReturn(true);
        when(guild.getModifier(GuildModifier.AUTO_DEALER_TABLES)).thenReturn(2.0);
        player = mock(Player.class);
        when(player.getName()).thenReturn("Alice");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        factions.when(() -> FactionManager.getGuildByMember(player)).thenReturn(guild);
        factions.when(() -> FactionManager.getGuildByString("guild")).thenReturn(guild);
        TableManager manager = mock(TableManager.class);
        when(manager.tables()).thenReturn(tables);
        managers = mockStatic(TableManager.class);
        managers.when(TableManager::get).thenReturn(manager);
    }

    @AfterEach
    void closeMocks() {
        if (managers != null) managers.close();
        if (api != null) api.close();
        if (factions != null) factions.close();
        if (bukkit != null) bukkit.close();
        Games.plugin = previousPlugin;
    }

    @Test
    void onlyGuildBankTablesCountWhileBothGuildAndStaffTablesAreHouseBacked() {
        Table personal = table(null);
        Table owned = table("guild");
        Table minted = table("guild");
        minted.setStaffMint(true);
        assertFalse(GuildTables.counts(null));
        assertFalse(GuildTables.counts(personal));
        assertFalse(GuildTables.counts(table(" ")));
        assertTrue(GuildTables.counts(owned));
        assertFalse(GuildTables.counts(minted));
        assertFalse(GuildTables.houseBacked(null));
        assertFalse(GuildTables.houseBacked(personal));
        assertFalse(GuildTables.houseBacked(table(" ")));
        assertTrue(GuildTables.houseBacked(owned));
        assertTrue(GuildTables.houseBacked(minted));
        assertFalse(GuildTables.wouldCount(null));
        assertTrue(GuildTables.wouldCount(new TableHouse()));
        assertFalse(GuildTables.wouldCount(TableHouse.from(minted)));
    }

    @Test
    void slotsCountOnlyMatchingGuildAndFreezeOnlyIdleRoundsBeyondCap() {
        Table first = table("guild");
        Table second = table("guild");
        Table minted = table("guild");
        minted.setStaffMint(true);
        tables.addAll(List.of(first, table("other"), table(null), minted));
        assertEquals(1, GuildTables.count("guild"));
        assertTrue(GuildTables.canAddGuildAuto("guild"));
        tables.add(second);
        assertFalse(GuildTables.canAddGuildAuto("guild"));
        assertFalse(GuildTables.overCap("guild"));
        assertTrue(GuildTables.canStartGuildAutoRound(first));
        when(guild.getModifier(GuildModifier.AUTO_DEALER_TABLES)).thenReturn(1.0);
        assertTrue(GuildTables.overCap("guild"));
        assertTrue(GuildTables.frozen(first));
        assertFalse(GuildTables.frozen(minted));
        assertFalse(GuildTables.canStartGuildAutoRound(first));
        first.startSession();
        assertTrue(GuildTables.canStartGuildAutoRound(first), "a cap reduction must not interrupt an ongoing hand");
    }

    @Test
    void guildIdentityNamesAndCapsComeFromEnabledIntegration() {
        assertEquals("guild", GuildTables.guildId(player));
        assertEquals("The Guild", GuildTables.displayName("guild"));
        assertEquals(2, GuildTables.cap("guild"));
        when(guild.getModifier(GuildModifier.AUTO_DEALER_TABLES)).thenReturn(2.8);
        assertEquals(2, GuildTables.cap("guild"));
        when(guild.getModifier(GuildModifier.AUTO_DEALER_TABLES)).thenReturn(-1.0);
        assertEquals(0, GuildTables.cap("guild"));
        when(guild.getName()).thenReturn(" ");
        assertNull(GuildTables.displayName("guild"));
        when(guild.getName()).thenReturn(null);
        assertNull(GuildTables.displayName("guild"));
        when(guild.getId()).thenReturn(" ");
        assertNull(GuildTables.guildId(player));
        when(guild.getId()).thenReturn(null);
        assertNull(GuildTables.guildId(player));
    }

    @Test
    void missingDisabledOrDeletedGuildIntegrationFailsClosed() {
        assertNull(GuildTables.guildId(null));
        assertNull(GuildTables.displayName(null));
        assertNull(GuildTables.displayName(" "), "a blank guild id saved with a table names nobody");
        assertNull(GuildTables.displayName("deleted"));
        assertEquals(0, GuildTables.cap("deleted"));
        when(integration.isEnabled()).thenReturn(false);
        assertNull(GuildTables.guildId(player));
        assertNull(GuildTables.displayName("guild"));
        assertEquals(0, GuildTables.cap("guild"));
        when(plugins.getPlugin("SimpleFactions")).thenReturn(null);
        assertNull(GuildTables.guildId(player));
        assertEquals(0, GuildTables.cap("guild"));
    }

    @Test
    void placementStampsMembershipAndRequiresLeaderAndFreeSlot() {
        TableHouse house = new TableHouse();
        assertNull(GuildTables.refuseKey(player, house, null));
        assertEquals("guild", house.ownerGuildId());
        when(guild.isLeader(player)).thenReturn(false);
        assertEquals("place.not_leader", GuildTables.refuseKey(player, house, null));
        when(guild.isLeader(player)).thenReturn(true);
        tables.addAll(List.of(table("guild"), table("guild")));
        assertEquals("place.no_slots", GuildTables.refuseKey(player, house, null));
        assertNull(GuildTables.refuseKey(player, house, tables.getFirst()), "editing a counted table uses its existing slot");
        assertEquals("place.no_slots", GuildTables.refuseKey(player, house, table(null)));
    }

    @Test
    void blankSavedGuildIsReplacedByTheLeadersOwnGuild() {
        TableHouse house = new TableHouse();
        house.setOwnerGuildId(" ");
        assertNull(GuildTables.refuseKey(player, house, null));
        assertEquals("guild", house.ownerGuildId());
    }

    @Test
    void deletedGuildOrMissingLeaderCannotAuthorisePlacement() {
        TableHouse house = new TableHouse();
        house.setOwnerGuildId("deleted");
        assertEquals("place.not_leader", GuildTables.refuseKey(player, house, null));
        when(guild.isLeader(player)).thenReturn(false);
        house.setOwnerGuildId("guild");
        assertEquals("place.not_leader", GuildTables.refuseKey(player, house, null));
    }

    @Test
    void guildlessPlacementIsRefusedButStaffMintNeedsNoGuildSlot() {
        factions.when(() -> FactionManager.getGuildByMember(player)).thenReturn(null);
        assertEquals("place.no_guild", GuildTables.refuseKey(player, new TableHouse(), null));
        TableHouse staff = new TableHouse();
        staff.setStaffMint(true);
        assertNull(GuildTables.refuseKey(player, staff, null));
        assertNull(GuildTables.refuseKey(player, null, null));
        GuildTables.stampGuild(player, staff);
        assertNull(staff.ownerGuildId());
    }

    @Test
    void stampingPreservesExplicitOwnershipAndOnlyFillsMissingGuild() {
        TableHouse house = new TableHouse();
        GuildTables.stampGuild(player, house);
        assertEquals("guild", house.ownerGuildId());
        house.setOwnerGuildId("other");
        GuildTables.stampGuild(player, house);
        assertEquals("other", house.ownerGuildId());
        house.setOwnerGuildId(" ");
        GuildTables.stampGuild(player, house);
        assertEquals("guild", house.ownerGuildId());
    }

    @Test
    void dealingRequiresMembershipPersonalOwnershipOrExplicitStaffPermission() {
        Table owned = table("guild");
        assertFalse(GuildTables.mayDeal(owned, player));
        when(guild.isMember(player)).thenReturn(true);
        assertTrue(GuildTables.mayDeal(owned, player));
        Table personal = table(null);
        assertFalse(GuildTables.mayDeal(personal, player));
        personal.setOwnerPlayer(UUID.randomUUID());
        assertFalse(GuildTables.mayDeal(personal, player));
        personal.setOwnerPlayer(player.getUniqueId());
        assertTrue(GuildTables.mayDeal(personal, player));
        when(player.hasPermission(TableHouse.STAFF_PERM)).thenReturn(true);
        assertTrue(GuildTables.mayDeal(table("other"), player));
        when(player.hasPermission(TableHouse.STAFF_PERM)).thenReturn(false);
        when(player.hasPermission("games.admin")).thenReturn(true);
        assertTrue(GuildTables.mayDeal(table("other"), player));
        assertFalse(GuildTables.mayDeal(null, player));
        assertFalse(GuildTables.mayDeal(personal, null));
    }

    @Test
    void onlyTheLeaderOfTheOwningGuildLeadsItsTable() {
        assertTrue(GuildTables.leadsOwner(table("guild"), player), "the active character leads the captured guild incarnation");
        when(guild.isLeader(player)).thenReturn(false);
        assertFalse(GuildTables.leadsOwner(table("guild"), player), "a member who does not lead it");
        when(guild.isLeader(player)).thenReturn(true);
        assertFalse(GuildTables.leadsOwner(table(null), player), "a personal table has no guild to lead");
        assertFalse(GuildTables.leadsOwner(table(" "), player));
        assertFalse(GuildTables.leadsOwner(table("deleted"), player));
    }

    @Test
    void blankSavedGuildFallsBackToPersonalOwnershipAndDeletedGuildHasNoMembers() {
        Table blank = table(" ");
        blank.setOwnerPlayer(player.getUniqueId());
        assertTrue(GuildTables.mayDeal(blank, player));
        assertFalse(GuildTables.mayDeal(table("deleted"), player));
    }

    @Test
    void incompatibleIntegrationCannotAuthorizePlacementOrDealing() {
        factions.when(() -> FactionManager.getGuildByString("guild"))
                .thenThrow(new NoClassDefFoundError("guild API changed"));
        factions.when(() -> FactionManager.getGuildByMember(player))
                .thenThrow(new IllegalStateException("integration unavailable"));
        assertNull(GuildTables.guildId(player));
        assertNull(GuildTables.displayName("guild"));
        assertEquals(0, GuildTables.cap("guild"));
        assertFalse(GuildTables.mayDeal(table("guild"), player));
        TableHouse house = new TableHouse();
        house.setOwnerGuildId("guild");
        assertEquals("place.not_leader", GuildTables.refuseKey(player, house, null));
        verify(logger, times(1)).warning(startsWith("[Games] SimpleFactions guild tables skipped: "));
    }

    @Test
    void refusalMessagesIncludeUsedSlotsAndCapOnlyWhenRelevant() {
        TableHouse house = new TableHouse();
        house.setOwnerGuildId("guild");
        tables.addAll(List.of(table("guild"), table("guild")));
        try (MockedStatic<Messages> messages = mockStatic(Messages.class, call -> call.getArgument(0))) {
            GuildTables.tellRefuse(player, "place.no_slots", house);
            messages.verify(() -> Messages.get("place.no_slots", "used", "2", "cap", "2"));
            verify(player).sendMessage("place.no_slots");
            GuildTables.tellRefuse(player, "place.not_leader", house);
            verify(player).sendMessage("place.not_leader");
        }
    }

    private static Table table(String guild) {
        Table table = new Table(UUID.randomUUID(), "blackjack", null, 0, null);
        table.setOwnerGuildId(guild);
        table.setOwnerGuildIncarnation(INCARNATION);
        return table;
    }

    @Test void installedFactionWaitingDisabledAndLookupFailuresFailClosed() {
        Table table = table("guild");
        api.when(SimpleFactionsApi::ready).thenReturn(false);
        assertFalse(GuildTables.ready());
        assertNull(GuildTables.guildId(player));
        assertTrue(GuildTables.frozen(table));
        api.when(SimpleFactionsApi::ready).thenReturn(true);
        assertTrue(GuildTables.ready());
        assertFalse(GuildTables.frozen(table));
        when(integration.isEnabled()).thenReturn(false);
        assertTrue(GuildTables.frozen(table));
        when(integration.isEnabled()).thenReturn(true);
        api.when(SimpleFactionsApi::ready).thenThrow(new IllegalStateException("waiting"));
        assertFalse(GuildTables.ready());
        assertTrue(GuildTables.frozen(table));
    }

    @Test void incarnationLookupSeparatesAbsentGuildsFromFailedLookups() {
        assertEquals(new GuildTables.Lookup(INCARNATION, false), GuildTables.resolveIncarnation("guild"));
        assertEquals(new GuildTables.Lookup(null, false), GuildTables.resolveIncarnation("deleted"));
        assertEquals(new GuildTables.Lookup(null, false), GuildTables.resolveIncarnation(null));
        assertEquals(new GuildTables.Lookup(null, false), GuildTables.resolveIncarnation(" "));
        assertNull(GuildTables.incarnation("deleted"));

        when(guild.getIncarnationId()).thenReturn(null);
        assertTrue(GuildTables.resolveIncarnation("guild").failed(),
                "a live guild with no incarnation yet is retried, not treated as deleted");
        when(guild.getIncarnationId()).thenReturn(INCARNATION);

        api.when(SimpleFactionsApi::ready).thenReturn(false);
        assertTrue(GuildTables.resolveIncarnation("guild").failed(),
                "factions not ready cannot prove a guild is gone");
        api.when(SimpleFactionsApi::ready).thenReturn(true);

        factions.when(() -> FactionManager.getGuildByString("guild"))
                .thenThrow(new IllegalStateException("integration unavailable"));
        assertTrue(GuildTables.resolveIncarnation("guild").failed());
        assertNull(GuildTables.incarnation("guild"), "runtime lookups still fail closed");
    }

    @Test void replacementGuildCannotUseOrCountAnOldIncarnationAndAccountNamesGiveNoRights() {
        Table table = table("guild");
        tables.add(table);
        when(guild.getIncarnationId()).thenReturn(UUID.randomUUID());
        assertEquals(0, GuildTables.count("guild"));
        assertTrue(GuildTables.frozen(table));
        assertFalse(GuildTables.leadsOwner(table, player));
        when(guild.getIncarnationId()).thenReturn(INCARNATION);
        when(guild.isLeader(player)).thenReturn(false);
        when(guild.getLeader()).thenReturn("Alice");
        assertFalse(GuildTables.leadsOwner(table, player));
        when(guild.isLeader(player)).thenReturn(true);
        assertTrue(GuildTables.leadsOwner(table, player));
    }
}
