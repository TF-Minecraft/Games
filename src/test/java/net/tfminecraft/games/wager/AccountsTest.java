package net.tfminecraft.games.wager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import net.tfminecraft.games.table.Table;

class AccountsTest {
    private Table table(Location origin) {
        return new Table(UUID.randomUUID(), "test", origin, 0, null);
    }

    @Test
    void pocketFactoriesUsePlayerIdentityAndTheirSpecifiedItemFilter() {
        Table table = table(null);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack chip = new ItemStack(Material.GOLD_NUGGET, 2);
        ItemStack loot = new ItemStack(Material.DIAMOND, 3);
        player.getInventory().setItem(0, chip);
        player.getInventory().setItem(1, loot);
        try (MockedStatic<ChipItems> items = mockStatic(ChipItems.class)) {
            items.when(() -> ChipItems.isChip(any())).thenReturn(true);
            items.when(() -> ChipItems.isMoneyCoin(any())).thenAnswer(call ->
                    ((ItemStack) call.getArgument(0)).getType() == Material.GOLD_NUGGET);
            items.when(() -> ChipItems.unitDenars(any())).thenReturn(2);
            PlayerAccount pockets = Accounts.pockets(table, player);
            assertEquals(player.getUniqueId(), pockets.flightTarget());
            assertEquals(10, pockets.available());
            assertEquals(4, Accounts.coins(table, player).available());
            assertEquals(6, Accounts.pockets(table, player,
                    item -> item.getType() == Material.DIAMOND).available());
            assertNull(Accounts.pockets(table, null).flightTarget());
            assertNull(Accounts.coins(table, null).flightTarget());
            assertNull(Accounts.pockets(table, null, item -> true).flightTarget());
        }
    }

    @Test
    void declaredLootUsesSampleSimilarityAndDeclaredValueWithoutMutatingSample() {
        Table table = table(null);
        PlayerMock player = MockBukkit.getMock().addPlayer();
        ItemStack sample = new ItemStack(Material.DIAMOND, 3);
        player.getInventory().setItem(0, sample.clone());
        player.getInventory().setItem(1, new ItemStack(Material.GOLD_NUGGET, 5));
        PlayerAccount declared = Accounts.declared(table, player, sample, 7);
        assertEquals(player.getUniqueId(), declared.flightTarget());
        assertEquals(21, declared.available());
        assertEquals(3, sample.getAmount());
        assertNull(Accounts.declared(table, null, sample, 7).flightTarget());
    }

    @Test
    void onlinePayeeDropsInventoryOverflowAtPlayersCurrentLocation() {
        World world = mock(World.class);
        Location playerLocation = new Location(world, 8, 9, 10);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        UUID id = UUID.randomUUID();
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.getLocation()).thenReturn(playerLocation);
        when(player.getInventory()).thenReturn(inventory);
        when(inventory.addItem(any(ItemStack.class))).thenAnswer(call -> {
            HashMap<Integer, ItemStack> rest = new HashMap<>();
            rest.put(0, call.getArgument(0));
            return rest;
        });
        PlayerAccount payee = Accounts.payee(table(new Location(world, 1, 2, 3)), player,
                UUID.randomUUID());
        assertEquals(id, payee.flightTarget());
        assertEquals(6, payee.accept(List.of(new Stake(new ItemStack(Material.GOLD_NUGGET),
                "coin", 2, 3, 0))));
        verify(world).dropItemNaturally(eq(playerLocation), argThat(item -> item.getAmount() == 3));
    }

    @Test
    void absentOrOfflinePayeesDropAtSnapshotOfTableOrigin() {
        World world = mock(World.class);
        Location origin = new Location(world, 1, 2, 3);
        Location expected = origin.clone();
        Table table = table(origin);
        UUID owner = UUID.randomUUID();
        PlayerAccount absent = Accounts.payee(table, null, owner);
        Player offline = mock(Player.class);
        UUID offlineId = UUID.randomUUID();
        when(offline.getUniqueId()).thenReturn(offlineId);
        PlayerAccount gone = Accounts.payee(table, offline, owner);
        assertEquals(owner, absent.flightTarget());
        assertEquals(offlineId, gone.flightTarget());
        origin.setX(100);
        List<Stake> stakes = List.of(new Stake(new ItemStack(Material.GOLD_NUGGET), "coin", 2, 1, 0));
        assertEquals(2, absent.accept(stakes));
        assertEquals(2, gone.accept(stakes));
        verify(world, times(2)).dropItemNaturally(eq(expected), any(ItemStack.class));
        assertEquals(owner, Accounts.payee(table(null), null, owner).flightTarget());
    }

    @Test
    void groundUsesExplicitLocationOrClonedTableOrigin() {
        World world = mock(World.class);
        Location origin = new Location(world, 1, 2, 3);
        Location explicit = new Location(world, 4, 5, 6);
        Table table = table(origin);
        PlayerAccount ground = Accounts.ground(table, explicit);
        PlayerAccount fallback = Accounts.ground(table, null);
        Location expected = origin.clone();
        origin.setX(100);
        List<Stake> stakes = List.of(new Stake(new ItemStack(Material.GOLD_NUGGET), "coin", 2, 1, 0));
        assertNull(ground.flightTarget());
        assertEquals(2, ground.accept(stakes));
        assertEquals(2, fallback.accept(stakes));
        verify(world).dropItemNaturally(eq(explicit), any(ItemStack.class));
        verify(world).dropItemNaturally(eq(expected), any(ItemStack.class));
        assertNull(Accounts.ground(table(null), null).flightTarget());
    }

    @Test
    void houseFactoryUsesGuildBankUnlessStaffMintIsEnabled() {
        Table table = table(null);
        assertInstanceOf(BankAccount.class, Accounts.bank(table));
        assertInstanceOf(MintAccount.class, Accounts.mint(table));
        assertInstanceOf(BankAccount.class, Accounts.house(table));
        table.setStaffMint(true);
        assertInstanceOf(MintAccount.class, Accounts.house(table));
    }

    @Test
    void onlineLookupReturnsOnlyConnectedOwners() {
        PlayerMock player = MockBukkit.getMock().addPlayer();
        assertNull(Accounts.online(null));
        assertNull(Accounts.online(UUID.randomUUID()));
        assertSame(player, Accounts.online(player.getUniqueId()));
    }

    @Test
    void citizenTaxSinkConsumesValueWithoutEverOfferingAWithdrawal() {
        MoneyAccount sink = Accounts.taxSink();
        assertSame(sink, Accounts.taxSink());
        assertEquals("citizen tax", sink.label());
        assertEquals(0, sink.available());
        assertNull(sink.planTake(1, null));
        assertNull(sink.planTakeAll(null));
        assertEquals(0, sink.largestTakeUpTo(100, null));
        assertTrue(sink.canAccept(100));
        assertEquals(0, sink.accept(null));
        assertEquals(6, sink.accept(List.of(new Stake(null, "coin", 2, 3, 0))));
        assertFalse(sink.onFelt());
        assertFalse(sink.isTray());
        assertNull(sink.feltOwner());
        assertNull(sink.flightTarget());
    }
}
