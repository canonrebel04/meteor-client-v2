/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.tactics;

import meteordevelopment.meteorclient.systems.modules.combat.model.WeaponKind;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MeleeTacticsTest {
    private static MeleeTiming.State ground() {
        return new MeleeTiming.State(true, false, 0, false, false, false, false, false, false, true);
    }

    private static MeleeTiming.State falling() {
        return new MeleeTiming.State(false, true, 0.4, false, false, false, false, false, false, false);
    }

    @Test
    void swingsOnlyWhenCharged() {
        assertFalse(MeleeTiming.swingReady(0.85f));
        assertTrue(MeleeTiming.swingReady(0.9f));
        assertEquals(MeleeTiming.Crit.HOLD, MeleeTiming.crit(false, ground(), 0.5f, 5, true, true, 0, 8));
        assertEquals(MeleeTiming.Crit.ATTACK, MeleeTiming.crit(false, ground(), 1f, 0, true, true, 0, 8));
    }

    @Test
    void critSchedulerJumpsHoldsAndHitsOnTheWayDown() {
        assertEquals(MeleeTiming.Crit.JUMP, MeleeTiming.crit(true, ground(), 0.8f, 2, true, true, 0, 8));
        MeleeTiming.State rising = new MeleeTiming.State(false, false, 0, false, false, false, false, false, false, false);
        assertEquals(MeleeTiming.Crit.HOLD, MeleeTiming.crit(true, rising, 1f, 0, true, true, 3, 8));
        assertEquals(MeleeTiming.Crit.ATTACK, MeleeTiming.crit(true, falling(), 1f, 0, true, true, 7, 8));
        // held too long: don't waste the charge
        assertEquals(MeleeTiming.Crit.ATTACK, MeleeTiming.crit(true, rising, 1f, 0, true, true, 9, 8));
        // in water: no jump-crits, plain hit
        MeleeTiming.State wet = new MeleeTiming.State(true, false, 0, true, false, false, false, false, false, false);
        assertEquals(MeleeTiming.Crit.ATTACK, MeleeTiming.crit(true, wet, 1f, 0, true, true, 0, 8));
        assertEquals(5, MeleeTiming.ticksToReady(0.5f, 1.6));
    }

    @Test
    void tapsReleaseForwardOrBlock() {
        boolean[] w = MeleeTiming.tapKeys(MeleeTiming.Tap.W_TAP, 0, true, false);
        assertFalse(w[0]);
        assertTrue(MeleeTiming.tapKeys(MeleeTiming.Tap.W_TAP, 1, true, false)[0]);
        assertTrue(MeleeTiming.tapKeys(MeleeTiming.Tap.S_TAP, 1, true, false)[1]);
        assertTrue(MeleeTiming.tapKeys(MeleeTiming.Tap.BLOCK_HIT, 0, true, true)[3]);
        assertFalse(MeleeTiming.tapKeys(MeleeTiming.Tap.BLOCK_HIT, 0, true, false)[3]);
    }

    private static WeaponArbiter.Situation sit(Set<WeaponKind> have, double dist, boolean blocking, boolean disabled, int sinceDisabled,
                                              double fall, boolean below, boolean sprint, int sinceSwap, WeaponKind holding) {
        return new WeaponArbiter.Situation(have, dist, 3.0, blocking, disabled, sinceDisabled, fall, below, sprint, false, true, sinceSwap, 3, holding);
    }

    @Test
    void weaponArbiter() {
        Set<WeaponKind> kit = EnumSet.of(WeaponKind.SWORD, WeaponKind.AXE, WeaponKind.MACE, WeaponKind.BOW);
        assertEquals(WeaponKind.AXE, WeaponArbiter.best(sit(kit, 2.5, true, false, 0, 0, false, false, 9, WeaponKind.SWORD)));
        assertEquals(WeaponKind.SWORD, WeaponArbiter.best(sit(kit, 2.5, false, true, 3, 0, false, false, 9, WeaponKind.AXE)));
        assertEquals(WeaponKind.AXE, WeaponArbiter.best(sit(kit, 2.5, true, true, 1, 0, false, false, 9, WeaponKind.AXE)));
        assertEquals(WeaponKind.MACE, WeaponArbiter.best(sit(kit, 2.5, false, false, 0, 4, true, false, 9, WeaponKind.SWORD)));
        assertEquals(WeaponKind.BOW, WeaponArbiter.best(sit(kit, 20, false, false, 0, 0, false, false, 9, WeaponKind.SWORD)));
        assertEquals(WeaponKind.SWORD, WeaponArbiter.best(sit(kit, 2, false, false, 0, 0, false, false, 9, WeaponKind.SWORD)));
        // swap budget
        assertFalse(WeaponArbiter.choose(sit(kit, 2.5, false, true, 3, 0, false, false, 1, WeaponKind.AXE)).swapNow());
        assertTrue(WeaponArbiter.choose(sit(kit, 2.5, false, true, 3, 0, false, false, 3, WeaponKind.AXE)).swapNow());
        // urgent axe vs shield swaps with only 1 tick gap
        assertTrue(WeaponArbiter.choose(sit(kit, 2.5, true, false, 0, 0, false, false, 1, WeaponKind.SWORD)).swapNow());
    }

    @Test
    void reachBandsOutspaceWhileCharging() {
        ReachBand.Band charging = ReachBand.band(3.0, 2.5, 10, 3);
        assertTrue(charging.min() >= 2.85 - 1e-9 && charging.min() > 2.5);
        ReachBand.Band ready = ReachBand.band(3.0, 2.5, 1, 3);
        assertTrue(ready.max() <= 3.0 && ready.max() > 2.5);
        // longer-reach opponent: brawl
        assertTrue(ReachBand.band(3.0, 4.5, 10, 3).max() <= 3.0);
        assertTrue(ReachBand.kite(3.0, true).max() > ReachBand.kite(3.0, false).max());
    }

    @Test
    void hitPointMinimisesTurn() {
        double[] eye = {0, 1.62, 0};
        double[] look = {0, 0, 1};
        double[] box = {-0.3, 0, 2.7, 0.3, 1.8, 3.3};
        double[] p = HitPoint.aim(eye, look, box, 0.1);
        assertEquals(1.62, p[1], 1e-6); // already at eye height: no pitch change
        assertTrue(HitPoint.turn(eye, p, 0, 0) < 1.0);
        double[] centre = {0, 0.9, 3.0};
        assertTrue(HitPoint.turn(eye, p, 0, 0) <= HitPoint.turn(eye, centre, 0, 0));
    }

    @Test
    void targetPriorityRangedSweepAndPull() {
        List<TargetPriority.Threat> all = new ArrayList<>();
        all.add(new TargetPriority.Threat(1, 0, 0, 3, 20, 3, false, false, false, true, 3));
        all.add(new TargetPriority.Threat(2, 0, 0, 10, 20, 2, true, false, false, true, 10)); // skeleton
        assertEquals(2, TargetPriority.pick(all, 3, false));
        // clustered zombies: sweeping one hits both
        List<TargetPriority.Threat> pack = new ArrayList<>();
        pack.add(new TargetPriority.Threat(1, 0, 0, 3, 20, 3, false, false, false, true, 3));
        pack.add(new TargetPriority.Threat(2, 1, 0, 3, 20, 3, false, false, false, true, 3.2));
        pack.add(new TargetPriority.Threat(3, 6, 0, 0, 20, 3, false, false, false, true, 3.1));
        assertEquals(1, TargetPriority.sweepCount(pack.get(0), pack, 3));
        assertEquals(0, TargetPriority.sweepCount(pack.get(2), pack, 3));
        // idle mob next to a target: pull risk
        pack.add(new TargetPriority.Threat(4, 7, 0, 1, 20, 3, false, false, false, false, 6));
        assertEquals(1, TargetPriority.pullRisk(pack.get(2), pack));
        assertEquals(-1, TargetPriority.pick(List.of(new TargetPriority.Threat(9, 0, 0, 0, 20, 1, false, false, false, false, 2)), 3, true));
    }

    @Test
    void positioningKnocksTowardHazardsAndAvoidsOurDrops() {
        // lava 2 blocks east of the target; a drop 3 blocks west
        Positioning.Ground g = new Positioning.Ground() {
            public Hazard hazard(int dx, int dz) {
                if (dx == 2 && dz == 0) return Hazard.LAVA;
                if (dx <= -5) return Hazard.DROP;
                return Hazard.NONE;
            }

            public int height(int dx, int dz) {
                return dx <= -5 ? Integer.MIN_VALUE : 0;
            }
        };
        Positioning.Spot s = Positioning.best(g, 2.5, 16, Positioning.Dim.OVERWORLD, false, true);
        assertTrue(s.dx() < 0, "stand west to knock the target east into lava");
        assertEquals(Hazard.LAVA, s.pushInto());
        assertEquals(Hazard.COBWEB, Hazard.ofBlock("minecraft:cobweb"));
        assertTrue(Hazard.ofBlock("minecraft:oak_trapdoor").trap());
        assertEquals(2.0, Positioning.dimMul(Positioning.Dim.END, Hazard.VOID));
    }

    @Test
    void strafeIsIrregularButDeterministic() {
        int flips = 0, prev = Positioning.strafe(42, 0);
        for (int t = 1; t < 400; t++) {
            int d = Positioning.strafe(42, t);
            assertEquals(d, Positioning.strafe(42, t));
            if (d != 0 && prev != 0 && d != prev) flips++;
            if (d != 0) prev = d;
        }
        assertTrue(flips >= 15 && flips <= 50, "flips " + flips);
        assertEquals(-1, Positioning.safeStrafe(1, (dir, u) -> dir == 1 ? 60 : 0));
        assertEquals(0, Positioning.safeStrafe(1, (dir, u) -> 60));
    }

    @Test
    void interceptLeadsAMovingTarget() {
        double[] r = Intercept.solve(0, 0, 10, 0, 0, 0.2, 0.28, 200);
        assertTrue(r[2] > 0);
        assertTrue(r[1] > 0, "aim ahead of the target");
        assertEquals(-1, Intercept.solve(0, 0, 10, 0, 0.5, 0, 0.28, 200)[2]); // faster, running away
    }

    @Test
    void formationFlanksGuardsFocusesAndRallies() {
        List<Formation.Ally> allies = List.of(new Formation.Ally(-3, 0, 20, 7));
        double[] f = Formation.flank(0, 0, allies, 2.5, -3, 1);
        assertTrue(f[0] > 2.0, "opposite the ally");
        assertNull(Formation.guard(0, 0, allies, 6));
        double[] g = Formation.guard(0, 0, List.of(new Formation.Ally(-4, 0, 4, 7)), 6);
        assertTrue(g[0] > -4 && g[0] < 0);
        assertEquals(7, Formation.focus(List.of(new Formation.Ally(0, 0, 20, 7), new Formation.Ally(0, 0, 20, 7), new Formation.Ally(0, 0, 20, 3))));
        double[] r = Formation.rally(allies, List.of(new double[]{5, 0}), 4);
        assertTrue(r[0] < -3);
    }

    @Test
    void breakContact() {
        BreakContact.Input door = new BreakContact.Input(true, false, false, 4, true, false, false, false, false, false, 99, 4);
        assertEquals(BreakContact.Action.CLOSE_DOOR, BreakContact.decide(door));
        BreakContact.Input web = new BreakContact.Input(false, false, true, 3, true, true, true, true, true, true, 99, 4);
        assertEquals(BreakContact.Action.PLACE_WEB, BreakContact.decide(web));
        BreakContact.Input off = new BreakContact.Input(false, false, true, 3, true, true, true, true, true, false, 99, 4);
        assertEquals(BreakContact.Action.NONE, BreakContact.decide(off), "placement is opt-in");
        BreakContact.Input paced = new BreakContact.Input(false, false, true, 3, true, false, false, true, true, true, 2, 4);
        assertEquals(BreakContact.Action.NONE, BreakContact.decide(paced));
        BreakContact.Input mob = new BreakContact.Input(false, false, true, 3, true, false, true, true, true, true, 9, 4);
        assertEquals(BreakContact.Action.PLACE_BLOCK, BreakContact.decide(mob), "solid block for mobs");
        BreakContact.Input ahead = new BreakContact.Input(true, false, true, 3, false, true, true, true, true, true, 99, 4);
        assertEquals(BreakContact.Action.NONE, BreakContact.decide(ahead));
    }

    @Test
    void ballisticsSolvesBowShotsAndLead() {
        assertEquals(1.0, Ballistics.bowPower(20), 1e-9);
        assertTrue(Ballistics.bowPower(10) < 0.7);
        Ballistics.Shot flat = Ballistics.solve(Ballistics.Projectile.ARROW, 1, 20, 0);
        assertTrue(flat.hits(0.05), "miss " + flat.missBy());
        assertTrue(flat.pitch() < 0 && flat.pitch() > -10, "slightly up: " + flat.pitch());
        Ballistics.Shot far = Ballistics.solve(Ballistics.Projectile.ARROW, 1, 60, 0);
        assertTrue(far.pitch() < flat.pitch());
        Ballistics.Shot snow = Ballistics.solve(Ballistics.Projectile.SNOWBALL, 1, 15, 0);
        assertTrue(snow.hits(0.05) && snow.pitch() < flat.pitch());
        double[] lead = Ballistics.lead(Ballistics.Projectile.ARROW, 1, 0, 1.6, 0, 0, 1.0, 20, 0.25, 0, 0);
        assertTrue(lead[2] > 3);
        // target moving +x: the yaw leads toward +x (yaw for +z is 0, +x is -90)
        assertTrue(lead[0] < 0);
        assertEquals(20, Ballistics.drawTicks(30));
    }

    @Test
    void specialWeapons() {
        assertTrue(SpecialWeapons.preloadCrossbow(true, false, 12, false));
        assertFalse(SpecialWeapons.preloadCrossbow(true, false, 3, true));
        assertTrue(SpecialWeapons.linedUp(0, 0, 0, 10, new double[][]{{0.3, 5}}));
        assertEquals(SpecialWeapons.TridentUse.NONE, SpecialWeapons.trident(true, true, false, false, false, false, true, 10, false));
        assertEquals(SpecialWeapons.TridentUse.RIPTIDE_ESCAPE, SpecialWeapons.trident(true, true, false, false, true, false, true, 10, true));
        assertEquals(SpecialWeapons.TridentUse.NONE, SpecialWeapons.trident(true, false, false, false, true, false, true, 10, false), "no loyalty: keep it");
        assertEquals(SpecialWeapons.MaceUse.SMASH, SpecialWeapons.mace(true, 3, true, false, false, false, 2, 20, false));
        assertEquals(SpecialWeapons.MaceUse.NONE, SpecialWeapons.mace(true, 0, false, false, true, true, 2, 20, false), "launch opt-in");
        assertEquals(SpecialWeapons.MaceUse.LAUNCH, SpecialWeapons.mace(true, 0, false, true, true, true, 2, 20, false));
        assertEquals(12, SpecialWeapons.smashBonus(3), 1e-9);
        assertEquals(22 + 2, SpecialWeapons.smashBonus(10), 1e-9);
        assertTrue(SpecialWeapons.spearCharge(true, true, false, 4, true));
        assertEquals(SpecialWeapons.Utility.NONE, SpecialWeapons.utility(false, 6, 1, true, true, true, true, true, true, false, false, false));
        assertEquals(SpecialWeapons.Utility.SNOWBALL_POKE, SpecialWeapons.utility(true, 6, 1, true, true, true, true, true, true, false, false, false));
        assertEquals(SpecialWeapons.Utility.SPLASH_SLOWNESS, SpecialWeapons.utility(true, 6, 5, false, false, false, true, true, true, false, false, true));
        assertEquals(6, SpecialWeapons.rodRetractTicks(0));
        assertEquals(9, SpecialWeapons.rodRetractTicks(2));
        assertEquals(18, SpecialWeapons.rodRetractTicks(20));
        assertEquals(SpecialWeapons.FireUse.NONE, SpecialWeapons.fire(false, true, true, 3, false, true, false, true));
        assertEquals(SpecialWeapons.FireUse.NONE, SpecialWeapons.fire(true, true, true, 3, false, true, true, true), "never near our flammables");
    }

    @Test
    void explosionDamageAndPlanning() {
        // point-blank crystal, no armour, normal: (1+1)/2*7*12+1 = 85
        assertEquals(85, Explosions.raw(Explosions.CRYSTAL, 0, 1, Explosions.Difficulty.NORMAL, true), 1e-9);
        assertEquals(0, Explosions.raw(Explosions.CRYSTAL, 12, 1, Explosions.Difficulty.NORMAL, true), 1e-9);
        assertTrue(Explosions.raw(6, 0, 1, Explosions.Difficulty.HARD, true) > 85);
        Explosions.Defender neth = new Explosions.Defender(20, 12, 16, 0, 0, 20);
        assertTrue(Explosions.taken(85, neth) < 30);
        Explosions.Defender blast = new Explosions.Defender(20, 12, 16, 4, 0, 20);
        assertTrue(Explosions.taken(85, blast) < Explosions.taken(85, neth));
        assertTrue(Explosions.bedExplodes("the_nether"));
        assertFalse(Explosions.bedExplodes("overworld"));
        assertTrue(Explosions.anchorExplodes("overworld"));
        assertFalse(Explosions.anchorExplodes("the_nether"));

        List<Explosions.Candidate> cs = List.of(
            new Explosions.Candidate(0, 0, 0, 12, 4, false, false),
            new Explosions.Candidate(1, 0, 0, 16, 9, false, false),   // too much self damage
            new Explosions.Candidate(2, 0, 0, 14, 3, false, true),    // breaks our floor
            new Explosions.Candidate(3, 1, 0, 13, 2, true, false));   // face-place
        assertEquals(0, Explosions.pick(cs, 8, 20, 20, 8, 6, null).x());
        assertEquals(3, Explosions.pick(cs, 8, 20, 6, 8, 6, null).x(), "face-place a low target");
        Explosions.Candidate prev = new Explosions.Candidate(0, 0, 0, 12, 4, false, false);
        List<Explosions.Candidate> close = List.of(prev, new Explosions.Candidate(4, 0, 0, 12.8, 4, false, false));
        assertEquals(0, Explosions.pick(close, 8, 20, 20, 8, 6, prev).x(), "hold line within 1.5");
        assertEquals(4, Explosions.pick(close, 8, 20, 20, 8, 6, null).x());
        assertNull(Explosions.pick(cs, 8, 5, 20, 8, 6, null), "never a lethal self hit");

        assertEquals(Explosions.Defense.TOTEM_SWAP, Explosions.defend(30, 20, false, true, false, true));
        assertEquals(Explosions.Defense.RETREAT, Explosions.defend(10, 20, true, true, false, true));
        assertEquals(Explosions.Defense.BREAK, Explosions.defend(5, 20, true, true, true, true));
        double r = Explosions.safeRadius(Explosions.TNT, 6, Explosions.Difficulty.NORMAL);
        assertTrue(r > 3 && r < 8, "r " + r);
        assertTrue(Explosions.canOutrun(2, r, 40, 0.28));
        assertFalse(Explosions.canOutrun(0, r, 5, 0.28));
        assertEquals(Explosions.BreakIn.MINE, Explosions.breakIn(true, true, 2, true, true, true, true));
        assertEquals(Explosions.BreakIn.NONE, Explosions.breakIn(true, true, 2, true, true, true, false));
        assertEquals(Explosions.Cornered.SELF_TRAP, Explosions.cornered(true, false, true, true, true, true));
        assertTrue(Explosions.placeAllowed(5, 4, 2, 4));
        assertFalse(Explosions.placeAllowed(2, 4, 0, 4));
        assertFalse(Explosions.breaksBlock(6, 1, 1200), "obsidian holds");
        assertTrue(Explosions.breaksBlock(6, 1, 0.5), "dirt goes");
    }

    @Test
    void encountersAndEnvironment() {
        assertEquals(Encounters.Rule.FLEE, Encounters.rule("minecraft:warden", true, false, false, false));
        assertEquals(Encounters.Rule.BREAK_LOS, Encounters.rule("minecraft:warden", true, true, false, false));
        assertEquals(Encounters.Rule.PRIORITY_CRYSTALS, Encounters.rule("minecraft:ender_dragon", true, false, true, false));
        assertEquals(Encounters.Rule.KITE, Encounters.rule("minecraft:piglin_brute", false, false, false, false));
        assertEquals(Encounters.Rule.NORMAL, Encounters.rule("minecraft:zombie", false, false, false, false));
        assertTrue(Encounters.raidRetreat(4, 1, 1, false, 20));
        assertFalse(Encounters.raidRetreat(3, 0, 0, true, 20));
        assertTrue(Encounters.wantGoldPiece("the_nether", false, true));
        assertEquals(3.0, Encounters.edgeMargin("the_end"));
        assertEquals(Encounters.Env.SURFACE, Encounters.environment(20, 300, false, false, false, Hazard.NONE, 0));
        assertEquals(Encounters.Env.LEAVE_HAZARD, Encounters.environment(300, 300, false, false, false, Hazard.MAGMA, 0));
        assertEquals(Encounters.Env.DOUSE, Encounters.environment(300, 300, true, false, true, Hazard.NONE, 0));
    }

    @Test
    void allegianceAndTerritory() {
        assertEquals(Allegiance.Standing.FRIEND, Allegiance.standing(true, "a", "b", false, false, 5, 10, 100));
        assertEquals(Allegiance.Standing.TEAMMATE, Allegiance.standing(false, "red", "red", false, false, 5, 10, 100));
        assertEquals(Allegiance.Standing.HOSTILE, Allegiance.standing(false, "", "blue", false, false, 5, 10, 100));
        assertEquals(Allegiance.Standing.NEUTRAL, Allegiance.standing(false, "", "blue", false, false, 5, 500, 100));
        assertFalse(Allegiance.mayAttack(Allegiance.Standing.TEAMMATE, true));
        assertEquals(Allegiance.Defend.WARN, Allegiance.territory(true, 10, 32, Allegiance.Standing.NEUTRAL, 5, 100, false));
        assertEquals(Allegiance.Defend.ENGAGE, Allegiance.territory(true, 10, 32, Allegiance.Standing.NEUTRAL, 200, 100, false));
        assertEquals(Allegiance.Defend.PATROL, Allegiance.territory(true, 50, 32, Allegiance.Standing.HOSTILE, 200, 100, false));
        assertEquals(Allegiance.Defend.IDLE, Allegiance.territory(false, 10, 32, Allegiance.Standing.HOSTILE, 200, 100, false));
        assertEquals(1, Allegiance.nextWaypoint(new double[][]{{0, 0}, {10, 0}, {20, 0}}, 0, 0, 0));
        Map<String, Long> hits = new HashMap<>();
        Allegiance.recordHit(hits, "Bob", 5, Set.of("Alice"));
        Allegiance.recordHit(hits, "Alice", 5, Set.of("Alice"));
        assertEquals(1, hits.size());
    }
}
