/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import meteordevelopment.meteorclient.systems.modules.combat.model.EffectiveDamage;

import java.util.List;

/**
 * Explosion maths and explosive play (C71-C80), pure.
 *
 * <p>Vanilla: impact = (1 - d / (2·power)) · exposure; damage = (impact² + impact) / 2 · 7 · 2·power + 1, scaled by
 * difficulty for players, then armour (toughness), blast protection (EPF ×2) / protection, resistance.
 */
public final class Explosions {
    public static final double CRYSTAL = 6, ANCHOR = 5, BED = 5, TNT = 4, CREEPER = 3, CHARGED_CREEPER = 6;

    public enum Difficulty { PEACEFUL, EASY, NORMAL, HARD }

    public record Defender(double armor, double toughness, int protectionEpf, int blastProtLevels, int resistance, double health) {
    }

    private Explosions() {
    }

    /** Raw damage before armour (C71). */
    public static double raw(double power, double distance, double exposure, Difficulty diff, boolean player) {
        double r = power * 2;
        if (distance >= r) return 0;
        double impact = (1 - distance / r) * Math.max(0, Math.min(1, exposure));
        double d = (impact * impact + impact) / 2 * 7 * r + 1;
        if (!player) return d;
        return switch (diff) {
            case PEACEFUL -> 0;
            case EASY -> Math.min(d / 2 + 1, d);
            case NORMAL -> d;
            case HARD -> d * 1.5;
        };
    }

    /** Damage after armour, blast protection (EPF: prot 1/level + blast 2/level, capped 20) and resistance. */
    public static double taken(double raw, Defender def) {
        double a = EffectiveDamage.afterArmor(raw, def.armor(), def.toughness());
        int epf = Math.min(20, def.protectionEpf() + 2 * def.blastProtLevels());
        return EffectiveDamage.afterProtectionAndResistance(a, epf, def.resistance());
    }

    /** C73: beds explode outside the Overworld; anchors explode outside the Nether. */
    public static boolean bedExplodes(String dimension) {
        return !"overworld".equals(dimension);
    }

    public static boolean anchorExplodes(String dimension) {
        return !"the_nether".equals(dimension);
    }

    public record Candidate(int x, int y, int z, double targetDamage, double selfDamage, boolean facePlace, boolean breaksOurFloor) {
    }

    /**
     * C72/C80 crystal/anchor/bed placement: maximise target damage with self damage ≤ {@code maxSelf}, never a
     * lethal self hit (≤ health − 2), never a blast that would break the floor we stand on; face-place only when
     * the target is low (≤ {@code facePlaceHp}); keep the previous spot (hold-line) unless another is 1.5+ better.
     */
    public static Candidate pick(List<Candidate> cs, double maxSelf, double myHealth, double targetHealth, double facePlaceHp,
                                 double minDamage, Candidate previous) {
        Candidate best = null;
        for (Candidate c : cs) {
            if (c.selfDamage() > maxSelf || c.selfDamage() >= myHealth - 2 || c.breaksOurFloor()) continue;
            if (c.facePlace() && targetHealth > facePlaceHp) continue;
            boolean lethal = c.targetDamage() >= targetHealth;
            if (c.targetDamage() < minDamage && !lethal) continue;
            if (best == null || value(c) > value(best)) best = c;
        }
        if (best != null && previous != null) {
            for (Candidate c : cs) {
                if (c.x() == previous.x() && c.y() == previous.y() && c.z() == previous.z() && cs.contains(c)
                        && c.selfDamage() <= maxSelf && c.selfDamage() < myHealth - 2 && !c.breaksOurFloor()
                        && value(best) - value(c) < 1.5) return c;
            }
        }
        return best;
    }

    private static double value(Candidate c) {
        return c.targetDamage() - 0.5 * c.selfDamage();
    }

    public enum Defense { NONE, TOTEM_SWAP, RETREAT, BREAK }

    /**
     * C74 enemy crystal / anchor defence: predicted self damage {@code dmg} if it goes off now. Lethal: totem to
     * the offhand first; heavy: step out of the blast (we have {@code ticksToLeave}); break it ourselves only
     * opt-in and only when we'd survive it comfortably (it is going to be detonated anyway).
     */
    public static Defense defend(double dmg, double health, boolean totemInOffhand, boolean haveTotem, boolean breakAllowed,
                                 boolean inReach) {
        if (dmg <= 0.5) return Defense.NONE;
        if (dmg >= health && !totemInOffhand && haveTotem) return Defense.TOTEM_SWAP;
        if (breakAllowed && inReach && dmg < health * 0.4) return Defense.BREAK;
        if (dmg >= health * 0.3) return Defense.RETREAT;
        return Defense.NONE;
    }

    /** C77/C78: blocks to keep from a fused explosive so the hit is ≤ {@code tolerable} (exposure 1, no armour). */
    public static double safeRadius(double power, double tolerable, Difficulty diff) {
        for (double d = 0; d < power * 2; d += 0.25) {
            if (raw(power, d, 1, diff, true) <= tolerable) return d;
        }
        return power * 2;
    }

    /** C77: can we get out of the blast in time? (sprint ≈ 0.28 blocks/tick) */
    public static boolean canOutrun(double distance, double safeRadius, int fuseTicks, double speedPerTick) {
        if (distance >= safeRadius) return true;
        return (safeRadius - distance) / Math.max(0.05, speedPerTick) < fuseTicks - 2;
    }

    public enum BreakIn { NONE, MINE, PISTON, ANVIL }

    /** C75 surround break-in, in order of reliability/safety. */
    public static BreakIn breakIn(boolean targetSurrounded, boolean havePickaxe, double mineSeconds, boolean havePiston,
                                  boolean haveAnvil, boolean openAbove, boolean allowed) {
        if (!allowed || !targetSurrounded) return BreakIn.NONE;
        if (havePickaxe && mineSeconds <= 4.5) return BreakIn.MINE;
        if (havePiston) return BreakIn.PISTON;
        if (haveAnvil && openAbove) return BreakIn.ANVIL;
        if (havePickaxe) return BreakIn.MINE;
        return BreakIn.NONE;
    }

    public enum Cornered { NONE, HOLE, SELF_TRAP, SELF_WEB, SELF_ANVIL }

    /** C76 cornered tactics (verdict says we can't run). */
    public static Cornered cornered(boolean cornered, boolean holeNearby, boolean haveObsidian, boolean haveWeb, boolean haveAnvil,
                                    boolean enemyHasCrystals) {
        if (!cornered) return Cornered.NONE;
        if (holeNearby) return Cornered.HOLE;
        if (enemyHasCrystals && haveObsidian) return Cornered.SELF_TRAP;
        if (haveWeb) return Cornered.SELF_WEB;
        if (haveObsidian) return Cornered.SELF_TRAP;
        if (haveAnvil) return Cornered.SELF_ANVIL;
        return Cornered.NONE;
    }

    /** C79: combat block placements are rate-limited (anticheat "scaffold/place" checks). */
    public static boolean placeAllowed(int ticksSinceLastPlace, int minGap, int placesLastSecond, int maxPerSecond) {
        return ticksSinceLastPlace >= minGap && placesLastSecond < maxPerSecond;
    }

    /**
     * C80 terrain vetting: would our blast at distance {@code d} from a floor block of blast resistance
     * {@code resistance} break it? (vanilla ray intensity ≈ (0.7..1.3)·power, minus (res+0.3)·0.3 per 0.3 step)
     */
    public static boolean breaksBlock(double power, double d, double resistance) {
        double intensity = power * 0.7; // worst-case low roll: conservative = "would it break even on a low roll"
        double steps = d / 0.3;
        intensity -= steps * 0.225 + steps * (resistance + 0.3) * 0.3;
        return intensity > 0;
    }
}
