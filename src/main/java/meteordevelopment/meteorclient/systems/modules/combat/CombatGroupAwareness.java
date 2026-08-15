package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import java.util.*;

/**
 * Computes a group-level threat snapshot every update cycle.
 * Filters to close threats (< 10 blocks) for encirclement and flank analysis.
 */
public class CombatGroupAwareness {

    /** Cardinal + intercardinal quadrants relative to the player's facing direction. */
    public enum Quadrant { FRONT, FRONT_LEFT, LEFT, BACK_LEFT, BACK, BACK_RIGHT, RIGHT, FRONT_RIGHT }

    /**
     * Immutable snapshot of the threat situation at a given moment.
     */
    public record GroupSnapshot(
        List<LivingEntity> targets,
        LivingEntity frontlineTarget,
        Vec3 centroid,
        double encirclementDeg,
        double largestGapBearing,
        Set<Quadrant> exposedQuadrants,
        boolean isSurrounded,
        boolean hasRearThreats
    ) {
        public LivingEntity primaryTarget() {
            return frontlineTarget != null ? frontlineTarget : (targets.isEmpty() ? null : targets.get(0));
        }
    }

    private static final double SURROUNDED_THRESHOLD = 240.0;
    private static final double CLOSE_THREAT_MAX_DIST = 10.0;

    public static GroupSnapshot compute(LocalPlayer player, List<LivingEntity> scored) {
        if (scored.isEmpty()) {
            return new GroupSnapshot(
                Collections.emptyList(),
                null,
                player.position(),
                0.0,
                player.getYRot() + 180.0,
                EnumSet.noneOf(Quadrant.class),
                false,
                false
            );
        }

        Vec3 playerPos = player.position();

        // 1. Find closest / frontline target
        LivingEntity frontline = null;
        double closestDistSq = Double.MAX_VALUE;
        for (LivingEntity e : scored) {
            double dSq = e.distanceToSqr(player);
            if (dSq < closestDistSq) {
                closestDistSq = dSq;
                frontline = e;
            }
        }

        // 2. Filter close threats (< 10 blocks) for encirclement/flank awareness
        List<LivingEntity> closeThreats = new ArrayList<>();
        double cx = 0, cz = 0;
        for (LivingEntity e : scored) {
            if (e.distanceTo(player) <= CLOSE_THREAT_MAX_DIST) {
                closeThreats.add(e);
                cx += e.getX();
                cz += e.getZ();
            }
        }

        if (closeThreats.isEmpty()) {
            // No enemies within 10 blocks: centroid is the highest-scored target
            LivingEntity primary = scored.get(0);
            Vec3 centroid = new Vec3(primary.getX(), playerPos.y, primary.getZ());
            return new GroupSnapshot(
                Collections.unmodifiableList(scored),
                frontline != null ? frontline : primary,
                centroid,
                0.0,
                player.getYRot() + 180.0,
                EnumSet.noneOf(Quadrant.class),
                false,
                false
            );
        }

        cx /= closeThreats.size();
        cz /= closeThreats.size();
        Vec3 centroid = new Vec3(cx, playerPos.y, cz);

        // 3. Compute threat angles for close threats
        List<Double> mathAngles = new ArrayList<>(closeThreats.size());
        for (LivingEntity e : closeThreats) {
            double dx = e.getX() - playerPos.x;
            double dz = e.getZ() - playerPos.z;
            mathAngles.add(Math.toDegrees(Math.atan2(-dz, dx)));
        }

        double encirclement = computeEncirclementDegrees(mathAngles);
        double gapMathAngle = findLargestGapAngle(mathAngles);
        double gapBearing = mathAngleToMinecraftYaw(gapMathAngle);
        Set<Quadrant> exposed = computeExposedQuadrants(player, closeThreats);

        boolean hasRearThreats = exposed.contains(Quadrant.BACK)
            || exposed.contains(Quadrant.BACK_LEFT)
            || exposed.contains(Quadrant.BACK_RIGHT);

        boolean surrounded = (encirclement >= SURROUNDED_THRESHOLD && hasRearThreats)
            || (closeThreats.size() >= 4 && hasRearThreats && (exposed.contains(Quadrant.LEFT) || exposed.contains(Quadrant.RIGHT)));

        return new GroupSnapshot(
            Collections.unmodifiableList(scored),
            frontline,
            centroid,
            encirclement,
            gapBearing,
            exposed,
            surrounded,
            hasRearThreats
        );
    }

    private static double computeEncirclementDegrees(List<Double> mathAngles) {
        if (mathAngles.isEmpty()) return 0.0;
        if (mathAngles.size() == 1) return 20.0;

        List<Double> sorted = new ArrayList<>();
        for (double a : mathAngles) sorted.add(((a % 360) + 360) % 360);
        Collections.sort(sorted);

        // Calculate maximum span of angles (360 - largest empty gap)
        double largestGap = 0;
        for (int i = 0; i < sorted.size(); i++) {
            double a = sorted.get(i);
            double b = (i + 1 < sorted.size()) ? sorted.get(i + 1) : sorted.get(0) + 360.0;
            double gap = b - a;
            if (gap > largestGap) largestGap = gap;
        }
        return Math.max(0.0, 360.0 - largestGap);
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
