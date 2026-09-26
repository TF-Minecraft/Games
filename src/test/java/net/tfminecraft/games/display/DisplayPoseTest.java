package net.tfminecraft.games.display;

import static org.junit.jupiter.api.Assertions.*;

import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import net.tfminecraft.games.cache.Cache;

class DisplayPoseTest {
    @Test
    void identityAndDerivedPosesPreserveOriginalComponents() {
        DisplayPose identity = DisplayPose.identity(2);
        assertEquals(new Vector3f(), identity.translation());
        assertEquals(new Vector3f(2), identity.scale());
        assertEquals(new Quaternionf(), identity.leftRotation());
        assertEquals(new Quaternionf(), identity.rightRotation());
        assertEquals(DisplayPose.ITEM_NONE, identity.itemTransform());
        DisplayPose moved = identity.withTranslation(1, 2, 3);
        DisplayPose scaled = moved.withScale(4);
        assertEquals(new Vector3f(1, 2, 3), moved.translation());
        assertEquals(new Vector3f(2), moved.scale());
        assertEquals(new Vector3f(1, 2, 3), scaled.translation());
        assertEquals(new Vector3f(4), scaled.scale());
        assertEquals(new Vector3f(), identity.translation());
    }

    @Test
    void constructorAndAccessorsDefensivelyCopyMutableVectorsAndQuaternions() {
        Vector3f translation = new Vector3f(1, 2, 3);
        Vector3f scale = new Vector3f(2, 3, 4);
        Quaternionf left = new Quaternionf().rotateX(0.5f);
        Quaternionf right = new Quaternionf().rotateY(0.25f);
        DisplayPose pose = new DisplayPose(translation, left, scale, right, (byte) 3);
        DisplayPose expected = new DisplayPose(translation, left, scale, right, (byte) 3);
        translation.zero();
        scale.zero();
        left.identity();
        right.identity();
        pose.translation().zero();
        pose.scale().zero();
        pose.leftRotation().identity();
        pose.rightRotation().identity();
        assertTrue(pose.matches(expected));
        assertEquals(3, pose.itemTransform());
    }

    @Test
    void matchesChecksEveryComponentAndTreatsQuaternionSignsAsEquivalent() {
        DisplayPose identity = DisplayPose.identity(1);
        assertTrue(identity.matches(DisplayPose.identity(1)));
        assertFalse(identity.matches(null));
        assertFalse(identity.matches(pose(new Quaternionf(), new Quaternionf(), (byte) 1)));
        assertFalse(identity.matches(identity.withTranslation(0.001f, 0, 0)));
        assertFalse(identity.matches(identity.withScale(1.001f)));
        assertFalse(identity.matches(pose(new Quaternionf().rotateX(0.5f), new Quaternionf(), (byte) 0)));
        assertFalse(identity.matches(pose(new Quaternionf(), new Quaternionf().rotateY(0.5f), (byte) 0)));
        assertTrue(identity.matches(identity.withTranslation(0.00001f, 0, 0)));
        assertTrue(identity.matches(pose(new Quaternionf(0, 0, 0, -1),
                new Quaternionf(0, 0, 0, -1), (byte) 0)));
    }

    @Test
    void rotationsUseDegreesAndApplyRollThenPitchThenYaw() {
        assertVector(1, 0, 0, DisplayRotator.fromYawPitchRoll(90, 0, 0).transform(new Vector3f(0, 0, 1)));
        assertVector(0, -1, 0, DisplayRotator.fromYawPitchRoll(0, 90, 0).transform(new Vector3f(0, 0, 1)));
        assertVector(0, 1, 0, DisplayRotator.fromYawPitchRoll(0, 0, 90).transform(new Vector3f(1, 0, 0)));
        Quaternionf combined = DisplayRotator.fromYawPitchRoll(90, 90, 90);
        assertVector(1, 0, 0, combined.transform(new Vector3f(1, 0, 0)));
        assertVector(0, 0, 1, combined.transform(new Vector3f(0, 1, 0)));
        assertVector(0, -1, 0, combined.transform(new Vector3f(0, 0, 1)));
    }

    @Test
    void flatPoseUsesMinecraftYawAndConfiguredOffsets() {
        float yaw = Cache.tableCardYawOffset;
        float pitch = Cache.tableCardPitch;
        float roll = Cache.tableCardRoll;
        try {
            Cache.tableCardYawOffset = 0;
            Cache.tableCardPitch = 0;
            Cache.tableCardRoll = 0;
            DisplayPose flat = DisplayPose.flatOnTable(0.5f, 2, 90);
            assertEquals(new Vector3f(0, 2, 0), flat.translation());
            assertEquals(new Vector3f(0.5f), flat.scale());
            assertEquals(new Quaternionf(), flat.rightRotation());
            assertVector(-1, 0, 0, flat.leftRotation().transform(new Vector3f(0, 0, 1)));
            DisplayPose pitched = DisplayPose.flatOnTable(0.5f, 2, 90, 90);
            assertVector(0, -1, 0, pitched.leftRotation().transform(new Vector3f(0, 0, 1)));
            Cache.tableCardYawOffset = 90;
            Cache.tableCardPitch = 90;
            Cache.tableCardRoll = 90;
            DisplayPose configured = DisplayPose.flatOnTable(1, 0, 0);
            assertVector(0, 0, 1, configured.leftRotation().transform(new Vector3f(0, 1, 0)));
        } finally {
            Cache.tableCardYawOffset = yaw;
            Cache.tableCardPitch = pitch;
            Cache.tableCardRoll = roll;
        }
    }

    private static DisplayPose pose(Quaternionf left, Quaternionf right, byte transform) {
        return new DisplayPose(new Vector3f(), left, new Vector3f(1), right, transform);
    }

    private static void assertVector(float x, float y, float z, Vector3f actual) {
        assertEquals(x, actual.x, 1e-6);
        assertEquals(y, actual.y, 1e-6);
        assertEquals(z, actual.z, 1e-6);
    }
}
