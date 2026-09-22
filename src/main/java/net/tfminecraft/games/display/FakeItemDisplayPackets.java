package net.tfminecraft.games.display;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.InternalStructure;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedDataWatcher.Serializer;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;

import net.tfminecraft.games.Games;

/**
 * Client-side ItemDisplay packets. Motion is transform metadata only (no teleport).
 * Indices match 1.21 Display + ItemDisplay (same scale/billboard slots as RPCharacters TextDisplay).
 */
public final class FakeItemDisplayPackets {

    private static final int META_INTERPOLATION_DELAY = 8;
    private static final int META_INTERPOLATION_DURATION = 9;
    private static final int META_TELEPORT_DURATION = 10;
    private static final int META_TRANSLATION = 11;
    private static final int META_SCALE = 12;
    private static final int META_LEFT_ROTATION = 13;
    private static final int META_RIGHT_ROTATION = 14;
    private static final int META_BILLBOARD = 15;
    private static final int META_ITEM = 23;
    private static final int META_ITEM_TRANSFORM = 24;

    private static final byte BILLBOARD_FIXED = 0;

    private final ProtocolManager protocolManager;

    public FakeItemDisplayPackets(ProtocolManager protocolManager) {
        this.protocolManager = protocolManager;
    }

    public void spawn(Player viewer, int entityId, UUID entityUuid, Location location, ItemStack item,
            DisplayPose pose) {
        if (viewer == null || location == null || location.getWorld() == null) {
            return;
        }
        send(viewer, createSpawnPacket(entityId, entityUuid, location));
        // Duration 0 can leave left_rotation at identity on the client; 1 tick snaps the pose.
        send(viewer, createMetadataPacket(entityId, item, pose, 0, 1));
    }

    public void update(Player viewer, int entityId, ItemStack item, DisplayPose pose, int interpolationDelay,
            int interpolationDuration) {
        send(viewer, createMetadataPacket(entityId, item, pose, interpolationDelay, interpolationDuration));
    }

    public void destroy(Player viewer, List<Integer> entityIds) {
        if (viewer == null || entityIds == null || entityIds.isEmpty()) {
            return;
        }
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().write(0, entityIds);
        send(viewer, packet);
    }

    private PacketContainer createSpawnPacket(int entityId, UUID entityUuid, Location location) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.SPAWN_ENTITY, true);
        packet.getIntegers().write(0, entityId);
        packet.getUUIDs().write(0, entityUuid);
        packet.getEntityTypeModifier().write(0, EntityType.ITEM_DISPLAY);
        writeEntityPosition(packet, location);
        return packet;
    }

    private void writeEntityPosition(PacketContainer packet, Location location) {
        StructureModifier<Double> doubles = packet.getDoubles();
        if (doubles.size() >= 3) {
            doubles.write(0, location.getX());
            doubles.write(1, location.getY());
            doubles.write(2, location.getZ());
            if (doubles.size() >= 6) {
                doubles.write(3, 0.0);
                doubles.write(4, 0.0);
                doubles.write(5, 0.0);
            }
            writeRotationBytes(packet);
            return;
        }

        InternalStructure movement = readFirstStructure(packet);
        StructureModifier<Vector> vectors = movement.getVectors();
        if (vectors.size() >= 1) {
            vectors.write(0, new Vector(location.getX(), location.getY(), location.getZ()));
        }
        if (vectors.size() >= 2) {
            vectors.write(1, new Vector(0, 0, 0));
        }
        // Facing is handled entirely by left_rotation; entity pitch and yaw stay 0.
        StructureModifier<Float> floats = movement.getFloat();
        if (floats.size() >= 1) {
            floats.write(0, 0f); // pitch
        }
        if (floats.size() >= 2) {
            floats.write(1, 0f); // yaw
        }
    }

    private static InternalStructure readFirstStructure(PacketContainer packet) {
        List<InternalStructure> structures = packet.getStructures().getValues();
        if (structures == null || structures.isEmpty()) {
            throw new IllegalStateException("Packet has no writable position structure: " + packet.getType());
        }
        return structures.get(0);
    }

    /**
     * SPAWN_ENTITY angle bytes: index 0 = pitch, index 1 = yaw, index 2 = head yaw.
     * Facing is carried by left_rotation, so all three are written as 0.
     */
    private static void writeRotationBytes(PacketContainer packet) {
        StructureModifier<Byte> bytes = packet.getBytes();
        if (bytes.size() >= 1) {
            bytes.write(0, (byte) 0); // pitch
        }
        if (bytes.size() >= 2) {
            bytes.write(1, (byte) 0); // yaw
        }
        if (bytes.size() >= 3) {
            bytes.write(2, (byte) 0); // head yaw
        }
    }

    private static byte angleToByte(float angle) {
        return (byte) (int) (angle * 256.0F / 360.0F);
    }

    private PacketContainer createMetadataPacket(int entityId, ItemStack item, DisplayPose pose, int delay,
            int duration) {
        PacketContainer packet = protocolManager.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, entityId);
        packet.getDataValueCollectionModifier().write(0, buildMetadata(item, pose, delay, duration));
        return packet;
    }

    private List<WrappedDataValue> buildMetadata(ItemStack item, DisplayPose pose, int delay, int duration) {
        List<WrappedDataValue> values = new ArrayList<>();
        Serializer intSerializer = WrappedDataWatcher.Registry.get((java.lang.reflect.Type) Integer.class, false);
        Serializer vectorSerializer = WrappedDataWatcher.Registry.get((java.lang.reflect.Type) Vector3f.class, false);
        Serializer quaternionSerializer = WrappedDataWatcher.Registry.get((java.lang.reflect.Type) Quaternionf.class, false);
        Serializer byteSerializer = WrappedDataWatcher.Registry.get((java.lang.reflect.Type) Byte.class, false);

        values.add(new WrappedDataValue(META_INTERPOLATION_DELAY, intSerializer, delay));
        values.add(new WrappedDataValue(META_INTERPOLATION_DURATION, intSerializer, duration));
        values.add(new WrappedDataValue(META_TELEPORT_DURATION, intSerializer, 0));
        values.add(new WrappedDataValue(META_TRANSLATION, vectorSerializer, pose.translation()));
        values.add(new WrappedDataValue(META_SCALE, vectorSerializer, pose.scale()));
        values.add(quaternionValue(META_LEFT_ROTATION, quaternionSerializer, pose.leftRotation()));
        values.add(quaternionValue(META_RIGHT_ROTATION, quaternionSerializer, pose.rightRotation()));
        values.add(new WrappedDataValue(META_BILLBOARD, byteSerializer, BILLBOARD_FIXED));
        values.add(new WrappedDataValue(META_ITEM, itemSerializer(), toNmsItem(item)));
        values.add(new WrappedDataValue(META_ITEM_TRANSFORM, byteSerializer, pose.itemTransform()));
        return values;
    }

    private static WrappedDataValue quaternionValue(int index, Serializer serializer, Quaternionf rotation) {
        Quaternionf value = new Quaternionf(rotation.x, rotation.y, rotation.z, rotation.w);
        return WrappedDataValue.fromWrappedValue(index, serializer, value);
    }

    private static Serializer itemSerializer() {
        return WrappedDataWatcher.Registry.getItemStackSerializer(false);
    }

    /** ProtocolLib converts Bukkit stacks to the handle the entity-data encoder expects. */
    private static Object toNmsItem(ItemStack item) {
        ItemStack bukkit = item != null ? item.clone() : new ItemStack(Material.AIR);
        return MinecraftReflection.getMinecraftItemStack(bukkit);
    }

    private void send(Player viewer, PacketContainer packet) {
        try {
            protocolManager.sendServerPacket(viewer, packet);
        } catch (Exception e) {
            if (Games.plugin != null) {
                Games.plugin.getLogger().warning(
                        "[Games] Failed to send ItemDisplay packet to " + viewer.getName() + ": " + e.getMessage());
            }
        }
    }
}
