package net.tfminecraft.games.table;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.util.RayTraceResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.cache.Cache;
import net.tfminecraft.games.display.WorldAnchors;
import net.tfminecraft.games.layout.TableLayout;
import net.tfminecraft.games.wager.Accounts;
import net.tfminecraft.games.wager.WagerEngine;
import net.tfminecraft.simplefactions.guild.Guild;
import net.tfminecraft.simplefactions.managers.FactionManager;
import net.tfminecraft.simplefactions.objects.Bank;

/** Settling the tray and the felt when a table is picked up or banked. */
class TableManagerSettleTest extends TableManagerFixture {
    private Map<String, TableLayout> previousLayouts;

    @BeforeEach void rememberLayouts() {
        previousLayouts = new HashMap<>(Cache.tableLayouts);
    }

    @AfterEach void restoreLayouts() {
        Cache.tableLayouts.clear();
        Cache.tableLayouts.putAll(previousLayouts);
    }

    private int dropped() {
        return world.getEntitiesByClass(Item.class).stream().map(Item::getItemStack)
                .filter(item -> item.getType() == Material.GOLD_NUGGET).mapToInt(ItemStack::getAmount).sum();
    }

    private void pickUp(Table table, Player by) {
        Entity anchor = mock(Entity.class);
        anchors.when(() -> WorldAnchors.tableId(anchor)).thenReturn(table.getId().toString());
        EntityDamageByEntityEvent hit = mock(EntityDamageByEntityEvent.class);
        when(hit.getEntity()).thenReturn(anchor);
        when(hit.getDamager()).thenReturn(by);
        manager.onHitEntity(hit);
    }

    @Test void aGuildBankThatFailsMidDepositGetsTheTrayCoinsDroppedAtTheTableInstead() {
        Table table = place(false);
        table.setOwnerGuildId("guild");
        table.setHouseFloat(5);
        WagerEngine.get().restore(table, table.getId(), new ItemStack(Material.GOLD_NUGGET), "gold", 1, 5,
                table.street(), null, null);
        PluginManager plugins = mock(PluginManager.class);
        Plugin factions = mock(Plugin.class);
        when(factions.isEnabled()).thenReturn(true);
        when(plugins.getPlugin("SimpleFactions")).thenReturn(factions);
        Guild guild = mock(Guild.class);
        Bank bank = mock(Bank.class);
        when(guild.getBank()).thenReturn(bank);
        doThrow(new IllegalStateException("bank storage offline")).when(bank).deposit(anyDouble());
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class, CALLS_REAL_METHODS);
                MockedStatic<FactionManager> registry = mockStatic(FactionManager.class)) {
            bukkit.when(Bukkit::getPluginManager).thenReturn(plugins);
            registry.when(() -> FactionManager.getGuildByString("guild")).thenReturn(guild);
            manager.bankAutoTray(table);
        }
        assertEquals(5, dropped(), "the coins survive a bank that took nothing");
        assertTrue(table.ledger().isEmpty());
        assertEquals(5, table.houseFloat(), "nothing reached the bank, so the float is still owed");
    }

    @Test void pickingUpATableHandsAnAbsentPlayersStakeToThePlayerPickingItUp() {
        // Like poker, this game keeps a leaver's chips in the pot but gives up their seat.
        doAnswer(call -> ((Table) call.getArgument(0)).actives().remove(((Player) call.getArgument(1)).getUniqueId()))
                .when(game).onLeave(any(), any());
        Table table = place(false);
        PlayerMock gone = opponent();
        stakeCoin(gone, table);
        manager.onQuit(new PlayerQuitEvent(gone, "quit"));
        gone.disconnect();
        assertEquals(1, manager.ownedDenars(table, gone.getUniqueId()), "the leaver's chips stayed in the pot");
        pickUp(table, player);
        assertNull(manager.table(table.getId()));
        assertEquals(1, Accounts.coins(table, player).available(), "rather than scattered on the floor");
        assertEquals(0, dropped());
        assertTrue(table.ledger().isEmpty());
    }

    @Test void aDealerClickingTheFeltRatherThanTheTrayPlacesAnOrdinaryBet() {
        Cache.tableLayouts.put("freeplay", new TableLayout(Cache.pokerCardSet, "Cards", "icon", 6,
                Map.of("tray", new TableLayout.PileSlot(0, 0.75)), new TableLayout.FeltRing(0.3, 1.2),
                null, null, 0.15));
        Table table = place(false);
        table.setDealerId(player.getUniqueId());
        player.getInventory().setItemInMainHand(new ItemStack(Material.GOLD_NUGGET));
        Player clicker = mock(Player.class, org.mockito.AdditionalAnswers.delegatesTo(player));
        Location felt = table.getOrigin().clone().add(0.75, 0, 0);
        doReturn(new RayTraceResult(felt.toVector())).when(clicker).rayTraceBlocks(anyDouble());
        manager.onInteractBlock(new PlayerInteractEvent(clicker, Action.RIGHT_CLICK_BLOCK,
                player.getInventory().getItemInMainHand(), world.getBlockAt(0, 64, 0), BlockFace.UP, EquipmentSlot.HAND));
        assertEquals(1, manager.ownedDenars(table, player.getUniqueId()));
        assertEquals(0, manager.trayDenars(table));
        assertTrue(table.actives().contains(player.getUniqueId()));
    }
}
