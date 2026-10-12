package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.Base64;
import java.util.IdentityHashMap;
import java.util.Map;
import net.tfminecraft.denareconomy.api.Denars;
import net.tfminecraft.tlibs.character.CharacterRef;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.PayoutFlight;
import net.tfminecraft.games.table.Table;

/**
 * One movement of money, checked in full before any of it happens.
 *
 * <p>Every leg is planned first. If any leg cannot be met the whole thing is abandoned and not
 * a single coin has moved, so a caller that gets a failure can refuse its action without
 * putting anything back. That is the difference from the old helpers, which mutated as they
 * went and then leaned on a refund that was allowed to quietly hand back less than it took.
 *
 * <p>Everything happens in one synchronous pass. Animations are drawn from what moved and
 * carry no value of their own.
 */
public final class MoneyTx {

    private enum Mode {
        /** All of it or nothing. */
        EXACT,
        /** As much as the source can hand over exactly, no more than asked. */
        UP_TO,
        /** Whatever the source is holding. */
        ALL
    }

    private static final class Leg {
        private MoneyAccount from;
        private MoneyAccount to;
        private int denars;
        private Mode mode;
        private ItemStack template;
        private Integer street;
        /** Shared remainder when several sources are covering one amount between them. */
        private int[] pool;
    }

    private record Planned(Leg leg, Withdrawal plan) {}

    private final WagerHost host;
    private final Table table;
    private final String reason;
    private final List<Leg> legs = new ArrayList<>();
    private List<PayoutFlight> flights;
    private TxResult completed;
    private CitizenTax.Levy taxQuote;
    private String operationId;
    MoneyTx taxQuote(CitizenTax.Levy quote) { this.taxQuote = quote; return this; }
    String operationId() { return operationId; }
    private final Map<UUID, CharacterRef> characters = new java.util.HashMap<>();

    MoneyTx(WagerHost host, Table table, String reason) {
        this.host = host;
        this.table = table;
        this.reason = reason;
    }

    /** Exactly this much, or the transaction fails and nothing moves. */
    public MoneyTx move(MoneyAccount from, MoneyAccount to, int denars) {
        return move(from, to, denars, null);
    }

    /**
     * Exactly this much, shaped like {@code template} where the source holds a balance instead
     * of coins. The amount must be a whole number of those coins, which is checked while
     * planning, so a bank never pays out more than the felt can hold.
     */
    public MoneyTx move(MoneyAccount from, MoneyAccount to, int denars, ItemStack template) {
        if (from == null || to == null || denars < 1) {
            return this;
        }
        Leg leg = new Leg();
        leg.from = from;
        leg.to = to;
        leg.denars = denars;
        leg.mode = Mode.EXACT;
        leg.template = template;
        legs.add(leg);
        return this;
    }

    /** As much as the source can hand over exactly, up to this much. Never fails for being short. */
    public MoneyTx moveUpTo(MoneyAccount from, MoneyAccount to, int denars) {
        return moveUpTo(from, to, denars, null, null);
    }

    /** As above, shaped like {@code template} where the source holds a balance. */
    public MoneyTx moveUpTo(MoneyAccount from, MoneyAccount to, int denars, ItemStack template) {
        return moveUpTo(from, to, denars, template, null);
    }

    private MoneyTx moveUpTo(MoneyAccount from, MoneyAccount to, int denars, ItemStack template,
            int[] pool) {
        if (from == null || to == null || denars < 1) {
            return this;
        }
        Leg leg = new Leg();
        leg.from = from;
        leg.to = to;
        leg.denars = denars;
        leg.mode = Mode.UP_TO;
        leg.template = template;
        leg.pool = pool;
        legs.add(leg);
        return this;
    }

    /** Everything the source is holding. */
    public MoneyTx moveAll(MoneyAccount from, MoneyAccount to) {
        return moveStreet(from, to, null);
    }

    /** Everything the source staked on one betting street. */
    public MoneyTx moveStreet(MoneyAccount from, MoneyAccount to, Integer street) {
        if (from == null || to == null) {
            return this;
        }
        Leg leg = new Leg();
        leg.from = from;
        leg.to = to;
        leg.mode = Mode.ALL;
        leg.street = street;
        legs.add(leg);
        return this;
    }

    /**
     * Cover one amount from several sources in turn, each giving what it can. Used to pay a pot
     * out of the boxes that built it, and to top a payout up from the house when the tray
     * cannot make the exact figure on its own.
     */
    public MoneyTx spread(MoneyAccount to, int denars, List<MoneyAccount> sources) {
        return spread(to, denars, sources, null);
    }

    public MoneyTx spread(MoneyAccount to, int denars, List<MoneyAccount> sources, ItemStack template) {
        if (to == null || sources == null || denars < 1) {
            return this;
        }
        int[] pool = {denars};
        for (MoneyAccount source : sources) {
            moveUpTo(source, to, denars, template, pool);
        }
        return this;
    }

    /** Collect chip animations for {@code flushPiles} instead of moving the money silently. */
    public MoneyTx animate(List<PayoutFlight> into) {
        this.flights = into;
        return this;
    }

    /** Plan every leg, then carry them all out. Nothing moves unless all of it can. */
    public TxResult commit() {
        if (completed != null) return completed;
        characters.clear();
        for (Leg leg : legs) if (leg.pool != null) leg.pool[0] = leg.denars;
        if (table == null || host == null) {
            return TxResult.failed(TxResult.Reason.NO_TABLE, 0, 0);
        }
        if (table.moneyBlocked() || (WagerJournal.blocked(table) && !WagerJournal.retryable(table)))
            return TxResult.failed(TxResult.Reason.BANK_SHORT, 0, 0);
        if (legs.isEmpty()) {
            return TxResult.nothing();
        }
        List<Planned> planned = new ArrayList<>();
        Map<MoneyAccount, MoneyAccount> copies = new IdentityHashMap<>();
        Map<String, MoneyAccount> sources = new java.util.HashMap<>();
        for (Leg leg : legs) {
            String sourceKey = leg.from instanceof BucketAccount ? "bucket:" + leg.from.feltOwner()
                    : leg.from instanceof PlayerAccount ? "player:" + leg.from.flightTarget()
                    : leg.from instanceof BankAccount ? "bank:" + leg.from.ledgerAccount() : null;
            if (sourceKey != null) {
                MoneyAccount prior = sources.putIfAbsent(sourceKey, leg.from);
                if (prior != null && prior != leg.from) return TxResult.failed(TxResult.Reason.NO_CHANGE, leg.denars, 0);
            }
            if (leg.to instanceof PlayerAccount recipient && recipient.flightTarget() != null) {
                CharacterRef expected = table.wagerCharacters().get(recipient.flightTarget());
                if (expected == null || (recipient.online() && !expected.equals(recipient.character()))) {
                    WagerJournal.quarantine(table, "Payout character changed: owner=" + recipient.flightTarget() + ", expected=" + expected);
                    return TxResult.failed(TxResult.Reason.PLAYER_SHORT, leg.denars, 0);
                }
            }
            if (leg.from instanceof PlayerAccount player) {
                var character = player.character();
                var captured = table.wagerCharacters().get(player.flightTarget());
                if (character == null || (captured != null && !captured.equals(character)))
                    return TxResult.failed(TxResult.Reason.PLAYER_SHORT, leg.denars, 0);
                characters.put(player.flightTarget(), character);
            }
            if (leg.pool != null && leg.pool[0] < 1) continue;
            MoneyAccount copy = copies.computeIfAbsent(leg.from, source -> {
                MoneyAccount detached = source.planningCopy();
                return detached == null ? source : detached;
            });
            int want = wanted(leg, copy);
            Withdrawal plan = want > 0 ? plan(leg, copy, want) : null;
            if (plan == null) {
                if (leg.mode == Mode.EXACT) {
                    return TxResult.failed(refusal(leg, want), want,
                            leg.from.largestTakeUpTo(want, leg.template));
                }
                continue;
            }
            if (!leg.to.canAccept(plan.denars())) {
                return TxResult.failed(TxResult.Reason.BANK_SHORT, want, 0);
            }
            if (leg.pool != null) {
                leg.pool[0] -= plan.denars();
            }
            if (copy != leg.from) {
                List<Stake> preview = plan.preview();
                int denars = plan.denars();
                if (TableLedger.valueOf(plan.take()) != denars) throw new IllegalStateException("Planning reservation failed");
                Withdrawal captured = leg.from instanceof BankAccount ? leg.from.planTake(denars, leg.template) : null;
                plan = new Withdrawal() {
                    public int denars() { return denars; }
                    public List<Stake> preview() { return preview; }
                    public List<Stake> take() {
                        Withdrawal current = captured != null ? captured : MoneyTx.this.plan(leg, leg.from, denars);
                        return current == null ? List.of() : current.take();
                    }
                };
            }
            planned.add(new Planned(leg, plan));
        }
        if (planned.isEmpty()) {
            return TxResult.nothing();
        }
        boolean ledgerBacked = planned.stream().anyMatch(step -> step.leg().from instanceof BankAccount
                || step.leg().to instanceof BankAccount || step.leg().to instanceof TaxSink);
        if (!ledgerBacked) {
            if (WagerJournal.blocked(table)) return TxResult.failed(TxResult.Reason.BANK_SHORT, 0, 0);
            return completed = apply(planned);
        }
        WagerJournal.Plan ledger = ledgerPlan(planned);
        if (!Denars.taxReady()) return TxResult.failed(TxResult.Reason.BANK_SHORT, 0, 0);
        Map<MoneyAccount, Object> snapshots = new IdentityHashMap<>();
        for (Planned step : planned) {
            MoneyAccount from = step.leg().from;
            if (from instanceof PlayerAccount player) snapshots.put(from, player.snapshot());
            if (from instanceof BucketAccount bucket) snapshots.put(from, bucket.snapshot());
            if (step.leg().to instanceof PlayerAccount player) snapshots.put(player, player.snapshot());
        }
        TxResult result = WagerJournal.apply(table, ledger, id -> operationId = id, () -> {
            for (var entry : snapshots.entrySet()) {
                Object current = entry.getKey() instanceof PlayerAccount player ? player.snapshot()
                        : ((BucketAccount) entry.getKey()).snapshot();
                if (!entry.getValue().equals(current)) throw new IllegalStateException("Physical money or character changed during posting");
            }
            for (Planned step : planned) {
                if (step.leg().from instanceof BankAccount bank) bank.authorizeDebit(step.plan().denars());
                if (step.leg().to instanceof BankAccount bank) bank.authorizeCredit(step.plan().denars());
                if (step.leg().to instanceof TaxSink tax) tax.authorize(step.plan().denars());
            }
            return apply(planned);
        });
        if (result.ok()) completed = result;
        return result;
    }

    private int wanted(Leg leg, MoneyAccount source) {
        if (leg.mode == Mode.ALL) {
            return Integer.MAX_VALUE;
        }
        int asked = leg.pool != null ? Math.min(leg.denars, leg.pool[0]) : leg.denars;
        if (asked < 1) {
            return 0;
        }
        return leg.mode == Mode.UP_TO ? source.largestTakeUpTo(asked, leg.template) : asked;
    }

    private Withdrawal plan(Leg leg, MoneyAccount source, int want) {
        if (leg.mode == Mode.ALL) {
            return source.planTakeAll(leg.street);
        }
        return source.planTake(want, leg.template);
    }

    private WagerJournal.Plan ledgerPlan(List<Planned> planned) {
        List<net.tfminecraft.denareconomy.api.Leg> money = new ArrayList<>();
        List<WagerJournal.Movement> movements = new ArrayList<>();
        int nextFloat = table.houseFloat();
        int profit = 0;
        for (Planned step : planned) {
            Leg leg = step.leg();
            int value = step.plan().denars();
            String from = leg.from.ledgerAccount();
            String to = leg.to.ledgerAccount();
            if (from != null || to != null) {
                money.add(new net.tfminecraft.denareconomy.api.Leg(from == null ? Denars.MINT : from, -value * 100L));
                money.add(new net.tfminecraft.denareconomy.api.Leg(to == null ? Denars.MINT : to, value * 100L));
            }
            if (leg.from instanceof BankAccount) nextFloat = Math.addExact(nextFloat, value);
            if (leg.to instanceof BankAccount) {
                int repaid = Math.min(value, nextFloat);
                nextFloat -= repaid;
                profit = Math.addExact(profit, value - repaid);
            }
            List<String> items = step.plan().preview().stream().map(stake ->
                    Base64.getEncoder().encodeToString(stake.item().serializeAsBytes()) + ":" + stake.unit()
                            + ":" + stake.count() + ":" + stake.streetId()).toList();
            movements.add(new WagerJournal.Movement(identity(leg.from), identity(leg.to), value, items,
                    leg.to instanceof TaxSink tax ? tax.levy() : null));
        }
        return new WagerJournal.Plan(reason, money, movements, table.houseFloat(), nextFloat, profit, Map.copyOf(table.wagerCharacters()), taxQuote);
    }

    private String identity(MoneyAccount account) {
        return account.ledgerAccount() != null ? account.ledgerAccount()
                : account.label() + ":" + account.feltOwner() + ":" + account.flightTarget();
    }

    /** Why an exact leg could not be met, so the player gets told the real reason. */
    private TxResult.Reason refusal(Leg leg, int want) {
        MoneyAccount from = leg.from;
        if ((from instanceof BankAccount || from instanceof MintAccount)
                && ChipItems.unitDenars(leg.template) < 1) {
            return TxResult.Reason.NO_TEMPLATE;
        }
        if (from instanceof MintAccount) {
            return TxResult.Reason.MINT_REFUSED;
        }
        boolean short0 = from.available() < want;
        if (from instanceof BankAccount) {
            return short0 ? TxResult.Reason.BANK_SHORT : TxResult.Reason.NO_CHANGE;
        }
        if (from instanceof PlayerAccount) {
            return short0 ? TxResult.Reason.PLAYER_SHORT : TxResult.Reason.NO_CHANGE;
        }
        return short0 ? TxResult.Reason.FELT_SHORT : TxResult.Reason.NO_CHANGE;
    }

    private TxResult apply(List<Planned> planned) {
        boolean strict = planned.stream().anyMatch(step -> step.leg().from instanceof BankAccount
                || step.leg().to instanceof BankAccount || step.leg().to instanceof TaxSink);
        int feltBefore = table.ledger().total();
        int expected = 0;
        int moved = 0;
        Set<UUID> touched = new LinkedHashSet<>();
        for (Planned step : planned) {
            Leg leg = step.leg();
            // Read the spot before the money leaves, since emptying a bucket can drop its anchor.
            Location from = host.anchorFor(table, leg.from.feltOwner());
            List<Stake> coins = step.plan().take();
            int value = TableLedger.valueOf(coins);
            if (value != step.plan().denars()) {
                if (strict) throw new IllegalStateException("Planned " + step.plan().denars() + " from " + leg.from.label() + " but moved " + value);
                MoneyLog.mismatch(table, "planned " + step.plan().denars() + " out of " + leg.from.label() + " but moved " + value);
            }
            if (value < 1) {
                continue;
            }
            int absorbed = leg.to.accept(coins);
            if (absorbed < value) {
                if (strict) throw new IllegalStateException("Destination accepted " + absorbed + " of " + value);
                MoneyLog.mismatch(table, "Destination only took " + absorbed + " of " + value + ", returning it");
                leg.from.accept(coins);
                continue;
            }
            MoneyLog.move(table, leg.from.label(), leg.to.label(), value, reason);
            if (leg.from instanceof PlayerAccount player)
                table.wagerCharacters().putIfAbsent(player.flightTarget(), characters.get(player.flightTarget()));
            table.roundMoney().recordLeg(leg.from, leg.to, coins);
            if (flights != null && from != null) {
                flights.addAll(host.flights(table, coins, from, leg.to.flightTarget(),
                        leg.to.isTray()));
            }
            if (leg.from.onFelt()) {
                touched.add(leg.from.feltOwner());
                expected -= value;
            }
            if (leg.to.onFelt()) {
                touched.add(leg.to.feltOwner());
                expected += value;
            }
            moved += value;
        }
        host.moneyMoved(table, touched);
        int delta = table.ledger().total() - feltBefore;
        if (delta != expected) {
            MoneyLog.mismatch(table, "felt moved by " + delta + " but the legs add up to "
                    + expected + " (" + reason + ")");
        }
        LedgerAudit.check(table, reason);
        return TxResult.done(moved, flights);
    }
}
