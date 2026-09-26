package net.tfminecraft.games.display;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import net.tfminecraft.games.Games;
import net.tfminecraft.games.Messages;
import net.tfminecraft.games.cache.Cache;

/**
 * Packet ItemDisplay tokens. Games tell this what to show; it does not know poker.
 */
public final class DisplayManager implements Listener {

    private static final DisplayManager INSTANCE = new DisplayManager();
    private static final AtomicInteger ENTITY_IDS = new AtomicInteger(-10_000_000);

    private final Map<UUID, Token> tokens = new HashMap<>();

    private DisplayManager() {}

    public static DisplayManager get() {
        return INSTANCE;
    }

    public boolean spawn(UUID tokenId, Location origin, ItemStack defaultItem, DisplayPose pose) {
        if (origin.getWorld() == null || !ProtocolLibBridge.isReady()) {
            return false;
        }
        despawn(tokenId);
        ItemStack item = defaultItem != null ? defaultItem.clone() : null;
        DisplayPose start = pose != null ? pose : DisplayPose.identity(Cache.cardScale);
        Token token = new Token(tokenId, ENTITY_IDS.getAndDecrement(), UUID.randomUUID(), origin.clone(), item, start);
        tokens.put(tokenId, token);
        try {
            for (Player viewer : nearby(origin)) {
                show(viewer, token);
            }
        } catch (RuntimeException ex) {
            destroyForViewers(token);
            tokens.remove(tokenId);
            Games.plugin.getLogger().warning("[Games] Failed to spawn display token: " + ex.getMessage());
            return false;
        }
        return true;
    }

    public void despawn(UUID tokenId) {
        Token token = tokens.remove(tokenId);
        if (token == null) {
            return;
        }
        destroyForViewers(token);
    }

    public void setItem(UUID tokenId, ItemStack item) {
        Token token = tokens.get(tokenId);
        if (token == null) {
            return;
        }
        token.defaultItem = item != null ? item.clone() : null;
        refreshItem(token);
    }

    public void setItemFor(UUID tokenId, Player player, ItemStack item) {
        Token token = tokens.get(tokenId);
        if (token == null) {
            return;
        }
        if (item == null) {
            token.overrides.remove(player.getUniqueId());
        } else {
            token.overrides.put(player.getUniqueId(), item.clone());
        }
        if (token.viewers.contains(player.getUniqueId())) {
            sendUpdate(player, token, 0, 0);
        }
    }

    public void clearItemFor(UUID tokenId, Player player) {
        setItemFor(tokenId, player, null);
    }

    public Location worldLocation(UUID tokenId) {
        Token token = tokens.get(tokenId);
        if (token == null) {
            return null;
        }
        Location loc = token.origin.clone();
        var t = token.pose.translation();
        loc.add(t.x, t.y, t.z);
        return loc;
    }

    public DisplayPose poseOf(UUID tokenId) {
        Token token = tokens.get(tokenId);
        if (token == null) {
            return null;
        }
        return copyPose(token.pose);
    }

    public DisplayPose otherPoseOf(UUID tokenId) {
        Token token = tokens.get(tokenId);
        if (token == null) {
            return null;
        }
        DisplayPose pose = token.otherPose != null ? token.otherPose : token.pose;
        return copyPose(pose);
    }

    public void setLayoutOwner(UUID tokenId, UUID playerId) {
        Token token = tokens.get(tokenId);
        if (token == null) {
            return;
        }
        token.layoutOwner = playerId;
    }

    public void setTransform(UUID tokenId, DisplayPose pose, int durationTicks) {
        Token token = tokens.get(tokenId);
        if (token == null || pose == null) {
            return;
        }
        if (token.otherPose == null && token.pose.matches(pose)) {
            return;
        }
        token.pose = pose;
        token.otherPose = null;
        pushTransform(token, durationTicks);
    }

    public void setTransformSplit(UUID tokenId, DisplayPose ownerPose, DisplayPose otherPose, int durationTicks) {
        Token token = tokens.get(tokenId);
        if (token == null || ownerPose == null) {
            return;
        }
        DisplayPose others = otherPose != null ? otherPose : ownerPose;
        if (token.pose.matches(ownerPose)
                && token.otherPose != null && token.otherPose.matches(others)) {
            return;
        }
        token.pose = ownerPose;
        token.otherPose = others;
        pushTransform(token, durationTicks);
    }

    private void pushTransform(Token token, int durationTicks) {
        int duration = Math.max(0, durationTicks);
        for (UUID viewerId : token.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendUpdate(viewer, token, 0, duration);
            }
        }
    }

    private static DisplayPose copyPose(DisplayPose pose) {
        return new DisplayPose(
                pose.translation(), pose.leftRotation(), pose.scale(), pose.rightRotation(), pose.itemTransform());
    }

    public void shutdown() {
        for (Token token : new ArrayList<>(tokens.values())) {
            destroyForViewers(token);
        }
        tokens.clear();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // A new connection starts with no entities, whatever a token remembers sending before. A token
        // spawned by a later quit listener can still list the player who was leaving.
        for (Token token : tokens.values()) {
            token.viewers.remove(player.getUniqueId());
        }
        Bukkit.getScheduler().runTask(Games.plugin, () -> showNearby(player));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hideAll(event.getPlayer());
        UUID id = event.getPlayer().getUniqueId();
        for (Token token : tokens.values()) {
            token.overrides.remove(id);
        }
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent event) {
        hideAll(event.getPlayer());
        showNearby(event.getPlayer());
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        World world = event.getWorld();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (Token token : tokens.values()) {
            Location origin = token.origin;
            if (!origin.getWorld().equals(world)) {
                continue;
            }
            if ((origin.getBlockX() >> 4) != cx || (origin.getBlockZ() >> 4) != cz) {
                continue;
            }
            for (Player viewer : nearby(origin)) {
                if (!token.viewers.contains(viewer.getUniqueId())) {
                    show(viewer, token);
                } else {
                    sendUpdate(viewer, token, 0, 0);
                }
            }
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.isCancelled()) {
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Entity entity = event.getRightClicked();
        if (WorldAnchors.tableId(entity) != null) {
            return;
        }
        String tokenId = WorldAnchors.tokenId(entity);
        if (tokenId == null) {
            return;
        }
        event.setCancelled(true);
        event.getPlayer().sendMessage(Messages.get("display.clicked", "token", tokenId));
    }

    private void showNearby(Player viewer) {
        if (!viewer.isOnline() || !ProtocolLibBridge.isReady()) {
            return;
        }
        Location loc = viewer.getLocation();
        for (Token token : tokens.values()) {
            if (inRange(loc, token.origin) && !token.viewers.contains(viewer.getUniqueId())) {
                show(viewer, token);
            }
        }
    }

    private void hideAll(Player viewer) {
        List<Integer> ids = new ArrayList<>();
        for (Token token : tokens.values()) {
            if (token.viewers.remove(viewer.getUniqueId())) {
                ids.add(token.entityId);
            }
        }
        if (!ids.isEmpty() && ProtocolLibBridge.isReady()) {
            ProtocolLibBridge.getPackets().destroy(viewer, ids);
        }
    }

    private void show(Player viewer, Token token) {
        if (!ProtocolLibBridge.isReady()) {
            return;
        }
        ProtocolLibBridge.getPackets().spawn(
                viewer, token.entityId, token.entityUuid, token.origin, itemFor(viewer, token), poseFor(viewer, token));
        token.viewers.add(viewer.getUniqueId());
    }

    private void sendUpdate(Player viewer, Token token, int delay, int duration) {
        if (!ProtocolLibBridge.isReady()) {
            return;
        }
        ProtocolLibBridge.getPackets().update(
                viewer, token.entityId, itemFor(viewer, token), poseFor(viewer, token), delay, duration);
    }

    private void refreshItem(Token token) {
        for (UUID viewerId : token.viewers) {
            Player viewer = Bukkit.getPlayer(viewerId);
            if (viewer != null) {
                sendUpdate(viewer, token, 0, 0);
            }
        }
    }

    private void destroyForViewers(Token token) {
        if (!ProtocolLibBridge.isReady()) {
            token.viewers.clear();
            return;
        }
        FakeItemDisplayPackets packets = ProtocolLibBridge.getPackets();
        List<Integer> ids = List.of(token.entityId);
        Iterator<UUID> it = token.viewers.iterator();
        while (it.hasNext()) {
            Player viewer = Bukkit.getPlayer(it.next());
            if (viewer != null) {
                packets.destroy(viewer, ids);
            }
            it.remove();
        }
    }

    private static DisplayPose poseFor(Player viewer, Token token) {
        if (token.otherPose != null && token.layoutOwner != null
                && !token.layoutOwner.equals(viewer.getUniqueId())) {
            return token.otherPose;
        }
        return token.pose;
    }

    private static ItemStack itemFor(Player viewer, Token token) {
        ItemStack override = token.overrides.get(viewer.getUniqueId());
        if (override != null) {
            return override;
        }
        return token.defaultItem;
    }

    private static List<Player> nearby(Location origin) {
        List<Player> out = new ArrayList<>();
        double range = Cache.displayRange;
        double rangeSq = range * range;
        for (Player player : origin.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(origin) <= rangeSq) {
                out.add(player);
            }
        }
        return out;
    }

    private static boolean inRange(Location a, Location b) {
        if (!a.getWorld().equals(b.getWorld())) {
            return false;
        }
        double range = Cache.displayRange;
        return a.distanceSquared(b) <= range * range;
    }

    private static final class Token {
        private final UUID tokenId;
        private final int entityId;
        private final UUID entityUuid;
        private final Location origin;
        private ItemStack defaultItem;
        private DisplayPose pose;
        private DisplayPose otherPose;
        private UUID layoutOwner;
        private final Map<UUID, ItemStack> overrides = new HashMap<>();
        private final java.util.Set<UUID> viewers = new java.util.HashSet<>();

        private Token(UUID tokenId, int entityId, UUID entityUuid, Location origin, ItemStack defaultItem,
                DisplayPose pose) {
            this.tokenId = tokenId;
            this.entityId = entityId;
            this.entityUuid = entityUuid;
            this.origin = origin;
            this.defaultItem = defaultItem;
            this.pose = pose;
        }
    }
}
