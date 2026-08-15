package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Computes a group-level threat snapshot every update cycle.
 *
 * CombatBrainModule calls compute() every few ticks and stores the resulting
 * GroupSnapshot so downstream systems share the same threat landscape picture.
 */
public class CombatGroupAwareness {

    /** Cardinal + intercardinal quadrants relative to the player's facing direction. */
    public enum Quadrant { FRONT, FRONT_LEFT, LEFT, BACK_LEFT, BACK, BACK_RIGHT, RIGHT, FRONT_RIGHT }

    /**
     * Immutable snapshot of the threat situation at a given moment.
     */
    public record GroupSnapshot(
        List<LivingEntity> targets,
        Vec3 centroid,
        double encirclementDeg,
        double largestGapBearing,
        Set<Quadrant> exposedQuadrants,
        boolean isSurrounded
    ) {
        public LivingEntity primaryTarget() {
            return targets.isEmpty() ? null : targets.get(0);
        }
    }

    // Thresholds (degrees)
    private static final double SURROUNDED_THRESHOLD = 220.0;
    private static final double THREAT_ANGULAR_WIDTH = 30.0;

    public static GroupSnapshot compute(LocalPlayer player, List<LivingEntity> scored) {
        if (scored.isEmpty()) {
            return new GroupSnapshot(
                Collections.emptyList(),
                player.position(),
                0.0,
                player.getYRot() + 180.0,
                EnumSet.noneOf(Quadrant.class),
                false
            );
        }

        Vec3 playerPos = player.position();

        double cx = 0, cz = 0;
        for (LivingEntity e : scored) { cx += e.getX(); cz += e.getZ(); }
        cx /= scored.size(); cz /= scored.size();
        Vec3 centroid = new Vec3(cx, playerPos.y, cz);

        List<Double> mathAngles = new ArrayList<>(scored.size());
        for (LivingEntity e : scored) {
            double dx = e.getX() - playerPos.x;
            double dz = e.getZ() - playerPos.z;
            mathAngles.add(Math.toDegrees(Math.atan2(-dz, dx)));
        }

        double encirclement = computeEncirclementDegrees(mathAngles);
        double gapMathAngle = findLargestGapAngle(mathAngles);
        double gapBearing = mathAngleToMinecraftYaw(gapMathAngle);
        Set<Quadrant> exposed = computeExposedQuadrants(player, scored);
        boolean surrounded = encirclement >= SURROUNDED_THRESHOLD;

        return new GroupSnapshot(
            Collections.unmodifiableList(scored),
            centroid,
            encirclement,
            gapBearing,
            exposed,
            surrounded
        );
    }

    private static double computeEncirclementDegrees(List<Double> mathAngles) {
        if (mathAngles.isEmpty()) return 0.0;
        double half = THREAT_ANGULAR_WIDTH / 2.0;
        List<double[]> expanded = new ArrayList<>();
        for (double a : mathAngles) {
            double norm = ((a % 360) + 360) % 360;
            double s = norm - half, e = norm + half;
            if (s < 0) { expanded.add(new double[]{s + 360, 360}); expanded.add(new double[]{0, e}); }
            else if (e > 360) { expanded.add(new double[]{s, 360}); expanded.add(new double[]{0, e - 360}); }
            else expanded.add(new double[]{s, e});
        }
        expanded.sort(Comparator.comparingDouble(a -> a[0]));
        double total = 0, curStart = -1, curEnd = -1;
        for (double[] arc : expanded) {
            if (arc[0] > curEnd) { if (curEnd >= 0) total += curEnd - curStart; curStart = arc[0]; curEnd = arc[1]; }
            else curEnd = Math.max(curEnd, arc[1]);
        }
        if (curEnd >= 0) total += curEnd - curStart;
        return Math.min(360.0, total);
    }

    private static double findLargestGapAngle(List<Double> mathAngles) {
        if (mathAngles.isEmpty()) return 0.0;
        if (mathAngles.size() == 1) return ((mathAngles.get(0) + 180) % 360 + 360) % 360;
        List<Double> sorted = new ArrayList<>();
        for (double a : mathAngles) sorted.add(((a % 360) + 360) % 360);
        Collections.sort(sorted);
        double largestGap = 0, gapMidAngle = 0;
        for (int i = 0; i < sorted.size(); i++) {
            double a = sorted.get(i);
            double b = (i + 1 < sorted.size()) ? sorted.get(i + 1) : sorted.get(0) + 360;
            double gap = b - a;
            if (gap > largestGap) { largestGap = gap; gapMidAngle = a + gap / 2.0; }
        }
        return ((gapMidAngle % 360) + 360) % 360;
    }

    private static double mathAngleToMinecraftYaw(double mathAngle) {
        double mcYaw = -(mathAngle - 90.0);
        return ((mcYaw % 360) + 360) % 360;
    }

    private static Set<Quadrant> computeExposedQuadrants(LocalPlayer player, List<LivingEntity> threats) {
        Set<Quadrant> result = EnumSet.noneOf(Quadrant.class);
        double playerYaw = player.getYRot();
        for (LivingEntity e : threats) {
            double dx = e.getX() - player.getX();
            double dz = e.getZ() - player.getZ();
            double bearing = Math.toDegrees(Math.atan2(dx, dz));
            double relative = ((bearing - playerYaw) % 360 + 360 + 180) % 360 - 180;
            int sector = (int) Math.floor((relative + 180) / 45.0) % 8;
            result.add(Quadrant.values()[sector]);
        }
        return Collections.unmodifiableSet(result);
    }
}
