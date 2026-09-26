package net.tfminecraft.games.gui;

import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import net.tfminecraft.games.table.TableHouse;

public final class TableOptionsHolder implements InventoryHolder {

    private final boolean requireDeck;
    private final Location pendingHit;
    private final UUID editTableId;
    private final String gameId;
    private final TableHouse house;
    private Inventory inventory;

    public TableOptionsHolder(boolean requireDeck, Location pendingHit, UUID editTableId, String gameId,
            TableHouse house) {
        this.requireDeck = requireDeck;
        this.pendingHit = pendingHit != null ? pendingHit.clone() : null;
        this.editTableId = editTableId;
        this.gameId = gameId;
        this.house = house;
    }

    public boolean requireDeck() {
        return requireDeck;
    }

    public Location pendingHit() {
        return pendingHit;
    }

    public UUID editTableId() {
        return editTableId;
    }

    public String gameId() {
        return gameId;
    }

    public TableHouse house() {
        return house;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
