package net.tfminecraft.games.gui;

import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/**
 * Game-select chest. pendingHit is the precise click on a block, or null if armed from a command.
 */
public final class GameSelectHolder implements InventoryHolder {

    private final boolean requireDeck;
    private final Location pendingHit;
    private final net.tfminecraft.games.ownership.OwnerSession session;
    private Inventory inventory;

    public GameSelectHolder(boolean requireDeck, Location pendingHit) {
        this(requireDeck,pendingHit,null);
    }
    public GameSelectHolder(boolean requireDeck, Location pendingHit, net.tfminecraft.games.ownership.OwnerSession session) {
        this.requireDeck = requireDeck;
        this.pendingHit = pendingHit != null ? pendingHit.clone() : null;
        this.session = session;
    }
    public net.tfminecraft.games.ownership.OwnerSession session() { return session; }

    public boolean requireDeck() {
        return requireDeck;
    }

    public Location pendingHit() {
        return pendingHit;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
