package net.tfminecraft.games.display;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.InternalStructure;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.utility.MinecraftReflection;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import com.comphenix.protocol.wrappers.WrappedDataWatcher.Serializer;
import net.tfminecraft.games.Games;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.mockbukkit.mockbukkit.MockBukkit;

/** Verifies our ProtocolLib calls; actual NMS encoding requires a running Paper server. */
class FakeItemDisplayPacketsTest {
    private Games previousPlugin;
    private Logger logger;
    private ProtocolManager protocol;
    private FakeItemDisplayPackets displays;
    private Player viewer;
    private Location location;
    private PacketContainer spawn;
    private PacketContainer metadata;
    private PacketContainer destroy;
    private MockedStatic<MinecraftReflection> nms;
    private MockedStatic<WrappedDataWatcher.Registry> registry;
    private MockedStatic<WrappedDataValue> wrappedValues;
    private MockedConstruction<WrappedDataValue> valueConstruction;
    private Serializer intSerializer;
    private Serializer vectorSerializer;
    private Serializer quaternionSerializer;
    private Serializer byteSerializer;
    private Serializer itemSerializer;
    private final Object convertedHandle = new Object();
    private final List<ItemStack> convertedItems = new ArrayList<>();

    @BeforeEach
    void setUp() {
        previousPlugin = Games.plugin;
        Games.plugin = mock(Games.class);
        logger = mock(Logger.class);
        when(Games.plugin.getLogger()).thenReturn(logger);
        viewer = MockBukkit.getMock().addPlayer();
        location = viewer.getLocation().clone().add(1.25, 2.5, -3.75);
        location.setYaw(75);
        location.setPitch(20);
        // ProtocolLib's wrappers otherwise discover NMS handles during class initialization.
        nms = mockStatic(MinecraftReflection.class);
        nms.when(() -> MinecraftReflection.getMinecraftItemStack(any(ItemStack.class))).thenAnswer(call -> {
            convertedItems.add(call.getArgument(0));
            return convertedHandle;
        });
        registry = mockStatic(WrappedDataWatcher.Registry.class);
        intSerializer = mock(Serializer.class);
        vectorSerializer = mock(Serializer.class);
        quaternionSerializer = mock(Serializer.class);
        byteSerializer = mock(Serializer.class);
        itemSerializer = mock(Serializer.class);
        registry.when(() -> WrappedDataWatcher.Registry.get((Type) Integer.class, false)).thenReturn(intSerializer);
        registry.when(() -> WrappedDataWatcher.Registry.get((Type) Vector3f.class, false)).thenReturn(vectorSerializer);
        registry.when(() -> WrappedDataWatcher.Registry.get((Type) Quaternionf.class, false)).thenReturn(quaternionSerializer);
        registry.when(() -> WrappedDataWatcher.Registry.get((Type) Byte.class, false)).thenReturn(byteSerializer);
        registry.when(() -> WrappedDataWatcher.Registry.getItemStackSerializer(false)).thenReturn(itemSerializer);
        valueConstruction = mockConstruction(WrappedDataValue.class, (value, context) -> {
            when(value.getIndex()).thenReturn((Integer) context.arguments().get(0));
            when(value.getSerializer()).thenReturn((Serializer) context.arguments().get(1));
            when(value.getValue()).thenReturn(context.arguments().get(2));
        });
        wrappedValues = mockStatic(WrappedDataValue.class);
        wrappedValues.when(() -> WrappedDataValue.fromWrappedValue(anyInt(), any(Serializer.class), any()))
                .thenAnswer(call -> new WrappedDataValue(call.getArgument(0), call.getArgument(1), call.getArgument(2)));
        // PacketContainer's static setup converts an empty item once per JVM. Run it now, under
        // the same mocks, so it is never mistaken for a conversion made by the code under test.
        try {
            Class.forName(PacketContainer.class.getName(), true, PacketContainer.class.getClassLoader());
        } catch (ClassNotFoundException ex) {
            throw new AssertionError(ex);
        }
        convertedItems.clear();
        protocol = mock(ProtocolManager.class);
        spawn = mock(PacketContainer.class, RETURNS_DEEP_STUBS);
        metadata = mock(PacketContainer.class, RETURNS_DEEP_STUBS);
        destroy = mock(PacketContainer.class, RETURNS_DEEP_STUBS);
        when(protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY, true)).thenReturn(spawn);
        when(protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA)).thenReturn(metadata);
        when(protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY)).thenReturn(destroy);
        when(spawn.getDoubles().size()).thenReturn(6);
        when(spawn.getBytes().size()).thenReturn(3);
        displays = new FakeItemDisplayPackets(protocol);
    }

    @AfterEach
    void tearDown() {
        if (wrappedValues != null) wrappedValues.close();
        if (valueConstruction != null) valueConstruction.close();
        if (registry != null) registry.close();
        if (nms != null) nms.close();
        Games.plugin = previousPlugin;
    }

    @ParameterizedTest
    @CsvSource({"3, 0", "3, 2", "6, 3"})
    void spawnWritesLegacyPositionAndZeroEntityRotationBeforePose(int doubleFields, int angleFields) {
        when(spawn.getDoubles().size()).thenReturn(doubleFields);
        when(spawn.getBytes().size()).thenReturn(angleFields);
        UUID uuid = UUID.randomUUID();
        displays.spawn(viewer, 42, uuid, location, new ItemStack(Material.PAPER), DisplayPose.identity(0.5f));
        verify(spawn.getIntegers()).write(0, 42);
        verify(spawn.getUUIDs()).write(0, uuid);
        verify(spawn.getEntityTypeModifier()).write(0, EntityType.ITEM_DISPLAY);
        verify(spawn.getDoubles()).write(0, location.getX());
        verify(spawn.getDoubles()).write(1, location.getY());
        verify(spawn.getDoubles()).write(2, location.getZ());
        if (doubleFields == 6) {
            verify(spawn.getDoubles()).write(3, 0.0);
            verify(spawn.getDoubles()).write(4, 0.0);
            verify(spawn.getDoubles()).write(5, 0.0);
        } else {
            verify(spawn.getDoubles(), never()).write(eq(3), any());
        }
        for (int index = 0; index < angleFields; index++) {
            verify(spawn.getBytes()).write(index, (byte) 0);
        }
        verify(spawn.getBytes(), never()).write(eq(angleFields), any());
        var order = inOrder(protocol);
        order.verify(protocol).sendServerPacket(viewer, spawn);
        order.verify(protocol).sendServerPacket(viewer, metadata);
        assertEquals(0, metadataValues().get(8).getValue());
        assertEquals(1, metadataValues().get(9).getValue(), "Spawn pose must interpolate for one tick to apply rotation");
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "1, 1", "2, 2"})
    void spawnSupportsStructuredPositionWithOptionalVelocityAndYaw(int vectors, int floats) {
        when(spawn.getDoubles().size()).thenReturn(0);
        InternalStructure movement = mock(InternalStructure.class, RETURNS_DEEP_STUBS);
        when(spawn.getStructures().getValues()).thenReturn(List.of(movement));
        when(movement.getVectors().size()).thenReturn(vectors);
        when(movement.getFloat().size()).thenReturn(floats);
        displays.spawn(viewer, 7, UUID.randomUUID(), location, null, DisplayPose.identity(1));
        if (vectors == 0) {
            verify(movement.getVectors(), never()).write(anyInt(), any());
            verify(movement.getFloat(), never()).write(anyInt(), any());
        } else {
            verify(movement.getVectors()).write(0, new Vector(location.getX(), location.getY(), location.getZ()));
            verify(movement.getFloat()).write(0, 0f);
        }
        if (vectors == 2) {
            verify(movement.getVectors()).write(1, new Vector());
        } else {
            verify(movement.getVectors(), never()).write(eq(1), any());
        }
        if (floats == 2) {
            verify(movement.getFloat()).write(1, 0f);
        } else {
            verify(movement.getFloat(), never()).write(eq(1), any());
        }
        verify(protocol).sendServerPacket(viewer, spawn);
        assertEquals(Material.AIR, convertedItems.getFirst().getType());
    }

    @Test
    void updateEncodesTransformInterpolationAndClonedItemWithoutRespawning() {
        ItemStack item = new ItemStack(Material.DIAMOND, 3);
        DisplayPose pose = new DisplayPose(new Vector3f(1, 2, 3), new Quaternionf().rotateY(0.75f),
                new Vector3f(0.25f, 0.5f, 0.75f), new Quaternionf().rotateX(0.25f), (byte) 4);
        displays.update(viewer, 12, item, pose, 2, 9);
        verify(metadata.getIntegers()).write(0, 12);
        Map<Integer, WrappedDataValue> values = metadataValues();
        assertEquals(10, values.size());
        assertValue(values, 8, intSerializer, 2);
        assertValue(values, 9, intSerializer, 9);
        assertValue(values, 10, intSerializer, 0);
        assertValue(values, 11, vectorSerializer, pose.translation());
        assertValue(values, 12, vectorSerializer, pose.scale());
        assertValue(values, 13, quaternionSerializer, pose.leftRotation());
        assertValue(values, 14, quaternionSerializer, pose.rightRotation());
        assertValue(values, 15, byteSerializer, (byte) 0);
        assertValue(values, 23, itemSerializer, convertedHandle);
        assertValue(values, 24, byteSerializer, (byte) 4);
        wrappedValues.verify(() -> WrappedDataValue.fromWrappedValue(eq(13), same(quaternionSerializer), eq(pose.leftRotation())));
        wrappedValues.verify(() -> WrappedDataValue.fromWrappedValue(eq(14), same(quaternionSerializer), eq(pose.rightRotation())));
        assertEquals(item, convertedItems.getFirst());
        assertNotSame(item, convertedItems.getFirst());
        convertedItems.getFirst().setAmount(1);
        assertEquals(3, item.getAmount());
        verify(protocol).sendServerPacket(viewer, metadata);
        verify(protocol, never()).createPacket(PacketType.Play.Server.SPAWN_ENTITY, true);
    }

    @Test
    void incompatibleSpawnPositionLayoutFailsBeforeSendingPartialEntity() {
        when(spawn.getDoubles().size()).thenReturn(0);
        when(spawn.getStructures().getValues()).thenReturn(List.of());
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> displays.spawn(viewer, 8, UUID.randomUUID(), location, null, DisplayPose.identity(1)));
        assertTrue(failure.getMessage().contains("no writable position structure"));
        verify(protocol, never()).sendServerPacket(any(), any());
    }

    @Test
    void destroySendsExactBatch() {
        displays.destroy(viewer, List.of(2, 5, 9));
        verify(destroy.getIntLists()).write(0, List.of(2, 5, 9));
        verify(protocol).sendServerPacket(viewer, destroy);
    }

    @Test
    void sendFailureWarnsWithViewerAndDoesNotCrashCaller() {
        doThrow(new IllegalStateException("connection closed")).when(protocol).sendServerPacket(viewer, destroy);
        assertDoesNotThrow(() -> displays.destroy(viewer, List.of(2)));
        verify(logger).warning("[Games] Failed to send ItemDisplay packet to " + viewer.getName() + ": connection closed");
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Map<Integer, WrappedDataValue> metadataValues() {
        ArgumentCaptor<List<WrappedDataValue>> values = ArgumentCaptor.forClass((Class) List.class);
        verify(metadata.getDataValueCollectionModifier()).write(eq(0), values.capture());
        return values.getValue().stream().collect(Collectors.toMap(WrappedDataValue::getIndex, Function.identity()));
    }

    private static void assertValue(Map<Integer, WrappedDataValue> values, int index, Serializer serializer, Object value) {
        assertSame(serializer, values.get(index).getSerializer());
        assertEquals(value, values.get(index).getValue());
    }
}
