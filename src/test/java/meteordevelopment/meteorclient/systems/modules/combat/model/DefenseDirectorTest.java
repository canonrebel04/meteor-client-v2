/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** C51-C59: the defense director resolves the policies into one plan. */
class DefenseDirectorTest {
    private static final double[] NO_DAMAGE = new double[DamageTypeAdvisor.Type.values().length];
    private static final PotionPlanner.Have NO_POTS = new PotionPlanner.Have(false, false, false, false, false, false);
    private static final PotionPlanner.Active NONE_ACTIVE = new PotionPlanner.Active(false, false, false, false, false);

    /** Builder-ish defaults: healthy, no enemy close, nothing special. */
    private static DefenseDirector.Input in(double hp, double enemyDist, boolean cover, boolean gap, int totemsInv, boolean offTotem,
                                            double dps, double[] dmg, double edge, int food) {
        return new DefenseDirector.Input(hp, 20, dps, dps, enemyDist, 3.0, cover, gap, false, false, false, false,
            food, 5f, totemsInv, offTotem, false, dmg, true, false, false, enemyDist < 6, true, true, false, 1.0, false,
            NO_POTS, NONE_ACTIVE, false, false, 0, true, 1, edge, 12);
    }

    @Test
    void healthyFightNoActions() {
        DefenseDirector.Plan p = DefenseDirector.decide(in(20, 3, false, true, 2, false, 4, NO_DAMAGE, 99, 20));
        assertEquals(HealPlanner.Action.NONE, p.heal());
        assertFalse(p.runAutoGap());
        assertFalse(p.runAutoEat());
        assertFalse(p.holdTotem());
        assertEquals("ok", p.summary());
    }

    @Test
    void lowHpInMeleeWithoutCoverDisengagesAndHoldsTotem() {
        DefenseDirector.Plan p = DefenseDirector.decide(in(9, 2, false, true, 2, true, 6, NO_DAMAGE, 99, 20));
        assertEquals(HealPlanner.Action.DISENGAGE_FIRST, p.heal());
        assertTrue(p.disengageToHeal());
        assertFalse(p.runAutoGap(), "no eating in melee");
        assertTrue(p.holdTotem(), "<=10 HP with a totem in hand keeps it");
    }

    @Test
    void lowHpWithCoverEatsGap() {
        DefenseDirector.Plan p = DefenseDirector.decide(in(10, 2, true, true, 1, false, 2, NO_DAMAGE, 99, 20));
        assertTrue(p.runAutoGap());
        assertFalse(p.disengageToHeal());
        assertEquals(TotemPolicy.Action.EQUIP_OFFHAND, p.totem());
        assertTrue(p.holdTotem());
    }

    @Test
    void hungerOnlyOutOfMelee() {
        assertTrue(DefenseDirector.decide(in(20, 20, false, false, 0, false, 0, NO_DAMAGE, 99, 6)).runAutoEat());
        assertFalse(DefenseDirector.decide(in(20, 2, false, false, 0, false, 0, NO_DAMAGE, 99, 6)).runAutoEat());
    }

    @Test
    void damageTypeAndShieldGivesWayToTotem() {
        double[] proj = NO_DAMAGE.clone();
        proj[DamageTypeAdvisor.Type.PROJECTILE.ordinal()] = 3;
        DefenseDirector.Plan p = DefenseDirector.decide(in(20, 10, false, false, 0, false, 0, proj, 99, 20));
        assertEquals(DamageTypeAdvisor.Type.PROJECTILE, p.dominantDamage());
        assertEquals(DamageTypeAdvisor.Response.SHIELD, p.damageResponse());
        // holding a totem: no shield -> cover/keep distance
        DefenseDirector.Plan q = DefenseDirector.decide(in(8, 10, false, false, 1, true, 0, proj, 99, 20));
        assertEquals(DamageTypeAdvisor.Response.COVER, q.damageResponse());
        assertTrue(q.keepDistance());
    }

    @Test
    void knockbackNearEdge() {
        assertTrue(DefenseDirector.decide(in(20, 3, false, false, 0, false, 4, NO_DAMAGE, 2, 20)).avoidEdges());
        assertFalse(DefenseDirector.decide(in(20, 3, false, false, 0, false, 4, NO_DAMAGE, 99, 20)).avoidEdges());
        assertFalse(DefenseDirector.decide(in(20, 30, false, false, 0, false, 4, NO_DAMAGE, 2, 20)).avoidEdges(), "no fight on");
    }

    @Test
    void armorAndPotions() {
        DefenseDirector.Input base = in(20, 20, false, false, 0, false, 0, NO_DAMAGE, 99, 20);
        DefenseDirector.Input worn = new DefenseDirector.Input(20, 20, 0, 0, 20, 3, false, false, false, false, false, false, 20, 5f,
            0, false, false, NO_DAMAGE, false, false, false, false, true, true, false, 0.05, true,
            new PotionPlanner.Have(false, false, false, true, false, false), NONE_ACTIVE, true, false, 0, false, 0, 99, 12);
        assertFalse(DefenseDirector.decide(base).runAutoArmor());
        DefenseDirector.Plan p = DefenseDirector.decide(worn);
        assertTrue(p.runAutoArmor());
        assertEquals(List.of(PotionPlanner.Buff.FIRE_RESISTANCE), p.potions());
        // elytra on in a ground fight -> chestplate
        DefenseDirector.Input elytra = new DefenseDirector.Input(20, 20, 0, 0, 3, 3, false, false, false, false, false, false, 20, 5f,
            0, false, false, NO_DAMAGE, false, true, false, true, true, true, true, 1.0, false, NO_POTS, NONE_ACTIVE, false, false, 0, false, 0, 99, 12);
        DefenseDirector.Plan e = DefenseDirector.decide(elytra);
        assertEquals(ArmorPolicy.ChestChoice.CHESTPLATE, e.chest());
        assertTrue(e.runAutoArmor());
    }
}
