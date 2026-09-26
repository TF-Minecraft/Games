package net.tfminecraft.games.table;

import java.util.UUID;

import org.bukkit.entity.Player;

import net.tfminecraft.games.layout.TableLayout;

/**
 * Place / options snapshot. Guild id is filled in a later batch.
 */
public final class TableHouse {

    public static final String STAFF_PERM = "games.autodealer.staff";

    private UUID ownerPlayer;
    private String ownerGuildId;
    private boolean autoDealer;
    private boolean staffMint;
    private int minBet;
    private int maxBet;
    private int maxBoxes;
    private ShufflePolicy shufflePolicy = ShufflePolicy.SHOE;
    private int smallBlind;
    private int bigBlind;

    public static TableHouse forPlace(Player player, TableLayout layout) {
        TableHouse house = new TableHouse();
        house.ownerPlayer = player.getUniqueId();
        boolean staff = player.hasPermission(STAFF_PERM);
        boolean yamlAuto = layout != null && layout.autoDealer();
        if (staff && yamlAuto) {
            house.autoDealer = true;
            house.staffMint = true;
        }
        if (layout != null) {
            house.minBet = Math.max(1, layout.minBet());
            house.maxBet = Math.max(house.minBet, layout.maxBet());
            house.maxBoxes = layout.maxBoxes();
        } else {
            house.minBet = 1;
            house.maxBet = 1;
        }
        house.shufflePolicy = ShufflePolicy.SHOE;
        if (layout != null) {
            house.smallBlind = Math.max(0, layout.smallBlind());
            house.bigBlind = Math.max(0, layout.bigBlind());
        }
        return house;
    }

    public static TableHouse from(Table table) {
        TableHouse house = new TableHouse();
        house.ownerPlayer = table.ownerPlayer();
        house.ownerGuildId = table.ownerGuildId();
        house.autoDealer = table.autoDealer();
        house.staffMint = table.staffMint();
        house.minBet = Math.max(1, table.minBet());
        house.maxBet = Math.max(house.minBet, table.maxBet());
        house.maxBoxes = table.maxBoxes();
        house.shufflePolicy = table.shufflePolicy();
        house.smallBlind = table.smallBlind();
        house.bigBlind = table.bigBlind();
        return house;
    }

    public void apply(Table table) {
        table.setOwnerPlayer(ownerPlayer);
        table.setOwnerGuildId(ownerGuildId);
        table.setAutoDealer(autoDealer);
        table.setStaffMint(staffMint);
        table.setMinBet(minBet);
        table.setMaxBet(maxBet);
        table.setMaxBoxes(maxBoxes);
        table.setShufflePolicy(shufflePolicy);
        table.setSmallBlind(smallBlind);
        table.setBigBlind(bigBlind);
    }

    public UUID ownerPlayer() {
        return ownerPlayer;
    }

    public void setOwnerPlayer(UUID ownerPlayer) {
        this.ownerPlayer = ownerPlayer;
    }

    public String ownerGuildId() {
        return ownerGuildId;
    }

    public void setOwnerGuildId(String ownerGuildId) {
        this.ownerGuildId = ownerGuildId;
    }

    public boolean autoDealer() {
        return autoDealer;
    }

    public void setAutoDealer(boolean autoDealer) {
        this.autoDealer = autoDealer;
        if (!autoDealer) {
            this.staffMint = false;
        }
    }

    public boolean staffMint() {
        return staffMint;
    }

    public void setStaffMint(boolean staffMint) {
        this.staffMint = staffMint;
        if (staffMint) {
            this.autoDealer = true;
        }
    }

    public int minBet() {
        return minBet;
    }

    public void setMinBet(int minBet) {
        this.minBet = Math.max(1, minBet);
        if (this.maxBet < this.minBet) {
            this.maxBet = this.minBet;
        }
    }

    public int maxBet() {
        return maxBet;
    }

    public void setMaxBet(int maxBet) {
        this.maxBet = Math.max(this.minBet, maxBet);
    }

    public int maxBoxes() {
        return maxBoxes;
    }

    public void setMaxBoxes(int maxBoxes) {
        this.maxBoxes = Math.max(0, maxBoxes);
    }

    public ShufflePolicy shufflePolicy() {
        return shufflePolicy;
    }

    public void setShufflePolicy(ShufflePolicy shufflePolicy) {
        this.shufflePolicy = shufflePolicy;
    }

    public int smallBlind() {
        return smallBlind;
    }

    public void setSmallBlind(int smallBlind) {
        this.smallBlind = Math.max(0, smallBlind);
    }

    public int bigBlind() {
        return bigBlind;
    }

    public void setBigBlind(int bigBlind) {
        this.bigBlind = Math.max(0, bigBlind);
    }
}
