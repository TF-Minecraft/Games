package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.events.PlayerWonMoneyEvent;
import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;

/**
 * The only way money moves on a table.
 *
 * <p>Games ask questions of this and start transactions through it. They do not touch the
 * ledger, a player's pockets, or a guild bank, because every bug worth fixing in this area came
 * from two of those being changed in sequence and the second one failing.
 */
public final class WagerEngine {

    private static WagerEngine instance;

    private final WagerHost host;

    private WagerEngine(WagerHost host) {
        this.host = host;
    }

    public static void init(WagerHost host) {
        instance = new WagerEngine(host);
    }

    public static WagerEngine get() {
        return instance;
    }

    /** Start a movement of money. Nothing happens until {@code commit()}. */
    public MoneyTx begin(Table table, String reason) {
        return new MoneyTx(host, table, reason);
    }

    // ---------------------------------------------------------------- reading

    /** Every denar this table is holding, tray included. */
    public int total(Table table) {
        return table.ledger().total();
    }

    /** Money in play: everything except the house tray. */
    public int felt(Table table) {
        return table.ledger().totalExcept(table.getId());
    }

    public int owned(Table table, UUID owner) {
        return table.ledger().total(owner);
    }

    public int owned(Table table, UUID owner, int street) {
        return table.ledger().total(owner, street);
    }

    public int tray(Table table) {
        return table.ledger().total(table.getId());
    }

    /** Owner to denars, skipping one bucket. Used for pot levels. */
    public Map<UUID, Integer> totalsExcept(Table table, UUID skip) {
        return table.ledger().totalsExcept(skip);
    }

    /** Everyone holding money, in the order they first staked. */
    public List<UUID> owners(Table table) {
        return new ArrayList<>(table.ledger().owners());
    }

    /** Everyone holding money except the tray. In poker this is the pot. */
    public List<UUID> potOwners(Table table) {
        List<UUID> out = new ArrayList<>();
        for (UUID owner : table.ledger().owners()) {
            if (!owner.equals(table.getId())) {
                out.add(owner);
            }
        }
        return out;
    }

    /** The pot buckets as accounts, ready to pay a winner out of. */
    public List<MoneyAccount> potAccounts(Table table) {
        List<MoneyAccount> out = new ArrayList<>();
        for (UUID owner : potOwners(table)) {
            out.add(Accounts.bucket(table, owner));
        }
        return out;
    }

    /** Coin denars in the pot, excluding loot stakes. */
    public int coinPot(Table table) {
        int sum = 0;
        for (UUID owner : potOwners(table)) {
            for (Stake stake : table.ledger().stakes(owner)) {
                sum += ChipItems.moneyValue(stake);
            }
        }
        return sum;
    }

    // ---------------------------------------------------------------- sweeping

    /**
     * The whole pot to one place. Coin profit is taxed to the sink; loot and any leftover stakes
     * are swept after the coin spreads.
     */
    public TxResult sweepPot(Table table, Player dest, UUID winner, List<PayoutFlight> flights,
            String reason) {
        int coinTotal = coinPot(table);
        int moved = 0;
        if (coinTotal > 0 && winner != null) {
            moved += payPotCoins(table, dest, winner, coinTotal, flights, reason).moved();
        }
        List<UUID> owners = potOwners(table);
        if (owners.isEmpty()) {
            return moved > 0 ? TxResult.done(moved, flights) : TxResult.nothing();
        }
        MoneyTx tx = begin(table, reason).animate(flights);
        MoneyAccount payee = Accounts.payee(table, dest, winner);
        for (UUID owner : owners) {
            tx.moveAll(Accounts.bucket(table, owner), payee);
        }
        TxResult swept = tx.commit();
        return TxResult.done(moved + swept.moved(), flights);
    }

    /** Every stake back to whoever put it there, as one movement. */
    public TxResult returnStakes(Table table, List<PayoutFlight> flights, String reason) {
        MoneyTx tx = begin(table, reason).animate(flights);
        for (UUID owner : potOwners(table)) {
            tx.moveAll(Accounts.bucket(table, owner),
                    Accounts.payee(table, Accounts.online(owner), owner));
        }
        return tx.commit();
    }

    /**
     * One bucket back to a player. Pass {@code denars} below 1 to hand back the whole bucket,
     * which is always exact; a set amount is limited to what the coins there can make.
     */
    public TxResult refund(Table table, UUID owner, Player dest, int denars,
            List<PayoutFlight> flights, String reason) {
        MoneyTx tx = begin(table, reason).animate(flights);
        MoneyAccount from = Accounts.bucket(table, owner);
        MoneyAccount to = Accounts.payee(table, dest, owner);
        if (denars > 0) {
            tx.moveUpTo(from, to, denars);
        } else {
            tx.moveAll(from, to);
        }
        return tx.commit();
    }

    /**
     * House money onto the felt, shaped like {@code template}. The amount has to be a whole
     * number of those coins or the transaction refuses, which is what used to need a compensating
     * deposit afterwards.
     */
    public TxResult fundFromHouse(Table table, UUID owner, ItemStack template, int denars,
            Location anchor, String reason) {
        return begin(table, reason)
                .move(Accounts.house(table), Accounts.bucket(table, owner).at(anchor), denars, template)
                .commit();
    }

    /**
     * Tray money back to the house, as much of {@code denars} as the tray can make in whole
     * coins. One movement, so the tray and the bank can never disagree about it.
     */
    public TxResult peelToHouse(Table table, int denars, String reason) {
        return begin(table, reason)
                .moveUpTo(Accounts.tray(table), Accounts.house(table), denars)
                .commit();
    }

    /** A losing bet off the felt and into the house tray. */
    public TxResult toTray(Table table, UUID owner, int denars, List<PayoutFlight> flights,
            String reason) {
        MoneyTx tx = begin(table, reason).animate(flights);
        MoneyAccount from = Accounts.bucket(table, owner);
        if (denars > 0) {
            tx.moveUpTo(from, Accounts.tray(table), denars);
        } else {
            tx.moveAll(from, Accounts.tray(table));
        }
        return tx.commit();
    }

    /** Pay a share of the pot, drawing from the buckets that built it so the chips fly from there. */
    public TxResult payFromPot(Table table, Player dest, UUID owner, int denars,
            List<PayoutFlight> flights, String reason) {
        if (denars < 1) {
            return TxResult.nothing();
        }
        return payPotCoins(table, dest, owner, denars, flights, reason);
    }

    /**
     * Spread coin denars from the pot to a winner, withholding citizen tax on profit only. Loot
     * is not involved; callers sweep buckets separately when needed.
     */
    private TxResult payPotCoins(Table table, Player dest, UUID owner, int denars,
            List<PayoutFlight> flights, String reason) {
        int profit = table.roundMoney().taxableProfit(owner, denars);
        int returnedPrincipal = denars - profit;
        CitizenTax.Levy levy = CitizenTax.levy(dest, profit);
        int net = denars - levy.chips();
        MoneyAccount payee = Accounts.payee(table, dest, owner);
        List<MoneyAccount> sources = potAccounts(table);

        int moved = 0;
        if (net > 0) {
            moved += begin(table, reason).animate(flights)
                    .spread(payee, net, sources)
                    .commit()
                    .moved();
        }
        if (levy.chips() > 0) {
            int taxMoved = begin(table, "citizen tax").animate(flights)
                    .spread(Accounts.taxSink(), levy.chips(), sources)
                    .commit()
                    .moved();
            if (taxMoved > 0) {
                CitizenTax.tell(dest, levy.tax());
            }
            moved += taxMoved;
        }
        table.roundMoney().recordProfit(owner, Math.max(0, moved - returnedPrincipal));
        return moved > 0 ? TxResult.done(moved, flights) : TxResult.nothing();
    }

    /**
     * Pay blackjack win profit to a player, withholding citizen tax to the sink first. Tray pays
     * before whoever backs the table; tax chips are destroyed rather than banked.
     */
    public PayWinResult payWin(Table table, Player winner, UUID owner, int profit, Player dealer,
            boolean dealerBacked, ItemStack template, List<PayoutFlight> flights) {
        if (profit < 1) {
            return PayWinResult.NONE;
        }
        CitizenTax.Levy levy = CitizenTax.levy(winner, profit);
        int net = profit - levy.chips();
        MoneyAccount payee = Accounts.payee(table, winner, owner);
        List<MoneyAccount> sources = winSources(table, dealer, dealerBacked);

        int trayBefore = tray(table);
        int paidNet = 0;
        if (net > 0) {
            paidNet = begin(table, "win payout").animate(flights)
                    .spread(payee, net, sources, template)
                    .commit()
                    .moved();
        }
        int trayMoved = trayBefore - tray(table);

        int paidTax = 0;
        if (levy.chips() > 0) {
            int trayBeforeTax = tray(table);
            paidTax = begin(table, "citizen tax").animate(flights)
                    .spread(Accounts.taxSink(), levy.chips(), sources, template)
                    .commit()
                    .moved();
            if (paidTax > 0) {
                CitizenTax.tell(winner, levy.tax());
            }
            trayMoved += trayBeforeTax - tray(table);
        }

        int moved = paidNet + paidTax;
        table.roundMoney().recordProfit(owner, moved);
        return new PayWinResult(moved, trayMoved, Math.max(0, profit - moved));
    }

    /**
     * One {@link PlayerWonMoneyEvent} per player who is up on this settled round, pre-tax. Call
     * once at the end of a settlement; offline winners are skipped.
     */
    public void announceWins(Table table, String game) {
        for (Map.Entry<UUID, Integer> entry : table.roundMoney().wonProfit().entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            Bukkit.getPluginManager().callEvent(
                    new PlayerWonMoneyEvent(player, entry.getValue(), game));
        }
    }

    private static List<MoneyAccount> winSources(Table table, Player dealer, boolean dealerBacked) {
        List<MoneyAccount> sources = new ArrayList<>();
        sources.add(Accounts.tray(table));
        if (dealerBacked && dealer != null && dealer.isOnline()) {
            sources.add(Accounts.pockets(table, dealer));
        } else if (!dealerBacked) {
            sources.add(Accounts.house(table));
        }
        return sources;
    }

    /** One player's bet on the current street back to them, leaving earlier streets in the pot. */
    public TxResult refundStreet(Table table, UUID owner, int street, List<PayoutFlight> flights,
            String reason) {
        return begin(table, reason).animate(flights)
                .moveStreet(Accounts.bucket(table, owner),
                        Accounts.payee(table, Accounts.online(owner), owner), street)
                .commit();
    }

    /**
     * Put money back on a table as it was saved. Loading is not a transfer of anything, so no
     * transaction, but it still comes through here so the ledger only has one door.
     */
    public void restore(Table table, UUID owner, ItemStack item, String typeKey, int unit, int count,
            int street, Double anchorX, Double anchorZ) {
        restore(table, owner, item, typeKey, unit, count, street, anchorX, anchorZ, null, null);
    }

    /** As above, keeping the spot a heap was put down on so chips come back where they were. */
    public void restore(Table table, UUID owner, ItemStack item, String typeKey, int unit, int count,
            int street, Double anchorX, Double anchorZ, Double spotX, Double spotZ) {
        ItemStack one = item.clone();
        one.setAmount(1);
        table.ledger().add(owner, one, typeKey != null ? typeKey : ChipItems.typeKey(one), unit, count,
                street > 0 ? street : table.street(), spotX, spotZ);
        if (anchorX != null && anchorZ != null) {
            table.ledger().setAnchor(owner, anchorX, anchorZ);
        }
    }

    /** Forget an emptied bucket, including the spot its chips were drawn on. */
    public void forget(Table table, UUID owner) {
        table.ledger().forget(owner);
    }
}
