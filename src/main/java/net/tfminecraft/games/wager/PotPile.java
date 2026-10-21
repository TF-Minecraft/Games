package net.tfminecraft.games.wager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.inventory.ItemStack;

/**
 * A stack of chips drawn on a table. Pure decoration: the money it depicts lives in
 * {@link TableLedger}, and losing a pile costs nobody anything.
 */
public final class PotPile {

    private final UUID ownerId;
    private final ItemStack item;
    private final String typeKey;
    private final int denars;
    private int pieces;
    private int streetId;
    private double x;
    private double z;
    private final double homeX;
    private final double homeZ;
    private final List<UUID> tokens = new ArrayList<>();
    private final List<Float> layerYaws = new ArrayList<>();

    public PotPile(UUID ownerId, ItemStack item, String typeKey, int denars, double x, double z) {
        this.ownerId = ownerId;
        this.item = item;
        this.typeKey = typeKey;
        this.denars = denars;
        this.x = x;
        this.z = z;
        this.homeX = x;
        this.homeZ = z;
    }

    /** Which bucket this pile draws. The table id means the house tray. */
    public UUID ownerId() {
        return ownerId;
    }

    public ItemStack item() {
        return item;
    }

    public String typeKey() {
        return typeKey;
    }

    /** The coin this pile depicts, for grouping only. */
    public int denars() {
        return denars;
    }

    /** How many chip layers worth of pieces are shown. */
    public int pieces() {
        return pieces;
    }

    public void setPieces(int pieces) {
        this.pieces = Math.max(0, pieces);
    }

    public void addPieces(int amount) {
        if (amount > 0) {
            pieces += amount;
        }
    }

    public int streetId() {
        return streetId;
    }

    public void setStreetId(int streetId) {
        this.streetId = streetId;
    }

    public double x() {
        return x;
    }

    public void setX(double x) {
        this.x = x;
    }

    public double z() {
        return z;
    }

    public void setZ(double z) {
        this.z = z;
    }

    /** Where the layout put this pile. It is drawn at {@link #x()}/{@link #z()}, which steps off any card there. */
    public double homeX() {
        return homeX;
    }

    public double homeZ() {
        return homeZ;
    }

    public List<UUID> tokens() {
        return tokens;
    }

    public List<Float> layerYaws() {
        return layerYaws;
    }
}
