package net.tfminecraft.games.layout;

import java.util.List;

import org.joml.Vector3f;

import net.tfminecraft.games.display.DisplayPose;

/**
 * Keeps chip piles off the cards lying on the felt. A chip model is taller than a flat card, so a
 * pile drawn over one hides it; the pile is drawn at the nearest spot that leaves the card showing.
 */
public final class FeltClearance {

    /** The face in a card texture: 10 of 16 pixels wide and 14 of 16 tall, centred. */
    static final float FACE_HALF_WIDTH = 5f / 16f;
    static final float FACE_HALF_HEIGHT = 7f / 16f;
    /** How finely, and how far past the gap, a pile looks for room beside the cards. */
    static final double STEP = 0.02;
    static final double REACH = 1.0;
    static final int DIRECTIONS = 32;

    private FeltClearance() {}

    /** Where a pile may be drawn, such as on the felt and out of the shoe. */
    @FunctionalInterface
    public interface SpotFilter {
        boolean allows(double x, double z);
    }

    /** A card's face flattened onto the table: world X/Z of its four corners, in order. */
    public record Footprint(double[] xs, double[] zs) {}

    /** The face of a card display at world {@code x}/{@code z} (its translation included) drawn with {@code pose}. */
    public static Footprint footprint(double x, double z, DisplayPose pose) {
        float[][] corners = {
                {-FACE_HALF_WIDTH, -FACE_HALF_HEIGHT},
                {FACE_HALF_WIDTH, -FACE_HALF_HEIGHT},
                {FACE_HALF_WIDTH, FACE_HALF_HEIGHT},
                {-FACE_HALF_WIDTH, FACE_HALF_HEIGHT}
        };
        Vector3f scale = pose.scale();
        double[] xs = new double[4];
        double[] zs = new double[4];
        for (int i = 0; i < 4; i++) {
            Vector3f corner = new Vector3f(corners[i][0], corners[i][1], 0f);
            pose.rightRotation().transform(corner);
            corner.mul(scale);
            pose.leftRotation().transform(corner);
            xs[i] = x + corner.x;
            zs[i] = z + corner.z;
        }
        return new Footprint(xs, zs);
    }

    /** How far {@code x}/{@code z} is from the card, or 0 on top of it. */
    public static double distance(double x, double z, Footprint card) {
        double[] xs = card.xs();
        double[] zs = card.zs();
        boolean anyLeft = false;
        boolean anyRight = false;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < 4; i++) {
            int j = (i + 1) % 4;
            double ex = xs[j] - xs[i];
            double ez = zs[j] - zs[i];
            double cross = ex * (z - zs[i]) - ez * (x - xs[i]);
            if (cross > 0) {
                anyLeft = true;
            } else if (cross < 0) {
                anyRight = true;
            }
            best = Math.min(best, segmentDistance(x, z, xs[i], zs[i], ex, ez));
        }
        // Inside is on one side of every edge. A card squashed to a point has no inside.
        return anyLeft != anyRight ? 0 : best;
    }

    private static double segmentDistance(double x, double z, double ax, double az, double ex, double ez) {
        double lengthSq = ex * ex + ez * ez;
        double t = lengthSq > 0 ? ((x - ax) * ex + (z - az) * ez) / lengthSq : 0;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(x - (ax + ex * t), z - (az + ez * t));
    }

    /** True when a pile at {@code x}/{@code z} keeps at least {@code gap} from every card. */
    public static boolean clear(double x, double z, List<Footprint> cards, double gap) {
        for (Footprint card : cards) {
            if (distance(x, z, card) < gap) {
                return false;
            }
        }
        return true;
    }

    /**
     * The closest spot to {@code x}/{@code z} that is clear of the cards and that {@code filter}
     * allows, searched out to {@link #REACH} past the gap. The spot itself when it is already
     * clear, and also when nothing in reach is, so a pile never jumps somewhere arbitrary.
     */
    public static double[] nearestClear(double x, double z, List<Footprint> cards, double gap, SpotFilter filter) {
        if (cards.isEmpty() || gap <= 0 || clear(x, z, cards, gap)) {
            return new double[] {x, z};
        }
        int rings = (int) Math.round((REACH + gap) / STEP);
        for (int ring = 1; ring <= rings; ring++) {
            double radius = ring * STEP;
            for (int i = 0; i < DIRECTIONS; i++) {
                double angle = Math.PI * 2 * i / DIRECTIONS;
                double cx = x + Math.cos(angle) * radius;
                double cz = z + Math.sin(angle) * radius;
                if (clear(cx, cz, cards, gap) && filter.allows(cx, cz)) {
                    return new double[] {cx, cz};
                }
            }
        }
        return new double[] {x, z};
    }
}
