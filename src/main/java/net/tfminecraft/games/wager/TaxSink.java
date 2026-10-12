package net.tfminecraft.games.wager;

import java.util.List;
import org.bukkit.inventory.ItemStack;

/** Withheld physical chips become a credit to the captured jurisdiction in MoneyTx's posting. */
final class TaxSink implements MoneyAccount {
    private final CitizenTax.Levy levy;
    private int authorized;
    private int received;
    TaxSink(CitizenTax.Levy levy) { this.levy = levy; }
    CitizenTax.Levy levy() { return levy; }
    int received() { return received; }
    void authorize(int denars) { authorized = Math.addExact(authorized, denars); }
    @Override public String ledgerAccount() { return levy.account(); }
    @Override public String label() { return "citizen tax"; }
    @Override public int available() { return 0; }
    @Override public Withdrawal planTake(int denars, ItemStack template) { return null; }
    @Override public Withdrawal planTakeAll(Integer street) { return null; }
    @Override public int largestTakeUpTo(int denars, ItemStack template) { return 0; }
    @Override public boolean canAccept(int denars) { return levy.account() != null; }
    @Override public int accept(List<Stake> stakes) {
        int value = TableLedger.valueOf(stakes);
        if (authorized < value) return 0;
        authorized -= value;
        received += value;
        return value;
    }
}
