package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.table.Table;

/**
 * The guild bank behind an auto dealer table. It holds a balance rather than coins, so money
 * coming out of it has to be shaped like a coin the felt can hold: the amount is checked
 * against that coin before anything is withdrawn, which is why nothing ever has to be put back.
 */
public final class BankAccount implements MoneyAccount {

    private final Table table;
    private final int streetId;
    private Integer planningBalance;
    private int authorizedDebit;
    private int authorizedCredit;

    BankAccount(Table table, int streetId) {
        this.table = table;
        this.streetId = streetId;
    }

    @Override
    public MoneyAccount planningCopy() {
        BankAccount copy = new BankAccount(table, streetId);
        copy.planningBalance = available();
        return copy;
    }

    @Override
    public String ledgerAccount() { return GuildBank.account(table); }

    void authorizeDebit(int denars) { authorizedDebit = Math.addExact(authorizedDebit, denars); }
    void authorizeCredit(int denars) { authorizedCredit = Math.addExact(authorizedCredit, denars); }

    @Override
    public String label() {
        return "guild bank";
    }

    @Override
    public int available() {
        return planningBalance != null ? planningBalance : GuildBank.balance(table);
    }

    @Override
    public Withdrawal planTake(int denars, ItemStack template) {
        int unit = ChipItems.unitDenars(template);
        if (denars < 1 || unit < 1 || denars % unit != 0) {
            return null;
        }
        if (available() < denars) {
            return null;
        }
        return new BankWithdrawal(template, unit, denars / unit, denars);
    }

    @Override
    public Withdrawal planTakeAll(Integer street) {
        // A bank balance is not a pile you can sweep off the felt.
        return null;
    }

    @Override
    public int largestTakeUpTo(int denars, ItemStack template) {
        int unit = ChipItems.unitDenars(template);
        if (denars < 1 || unit < 1) {
            return 0;
        }
        return Math.min(denars, available()) / unit * unit;
    }

    @Override
    public boolean canAccept(int denars) {
        return denars < 1 || GuildBank.canHold(table);
    }

    @Override
    public int accept(List<Stake> stakes) {
        int value = TableLedger.valueOf(stakes);
        if (value < 1) {
            return 0;
        }
        if (authorizedCredit < value) return 0;
        authorizedCredit -= value;
        int repaid = Math.min(value, table.houseFloat());
        table.setHouseFloat(table.houseFloat() - repaid);
        return value;
    }

    private final class BankWithdrawal implements Withdrawal {

        private final ItemStack template;
        private final int unit;
        private final int count;
        private final int denars;

        private BankWithdrawal(ItemStack template, int unit, int count, int denars) {
            this.template = template;
            this.unit = unit;
            this.count = count;
            this.denars = denars;
        }

        @Override
        public int denars() {
            return denars;
        }

        @Override
        public List<Stake> preview() {
            return coins();
        }

        @Override
        public List<Stake> take() {
            if (planningBalance != null) {
                if (planningBalance < denars) return new ArrayList<>();
                planningBalance -= denars;
                return coins();
            }
            if (authorizedDebit < denars) return new ArrayList<>();
            authorizedDebit -= denars;
            table.setHouseFloat(Math.addExact(table.houseFloat(), denars));
            return coins();
        }

        private List<Stake> coins() {
            ItemStack one = template.clone();
            one.setAmount(1);
            List<Stake> out = new ArrayList<>();
            out.add(new Stake(one, ChipItems.typeKey(one), unit, count, streetId));
            return out;
        }
    }
}
