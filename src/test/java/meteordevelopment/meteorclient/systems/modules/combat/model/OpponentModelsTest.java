/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/** P1 C11-C20 opponent models. */
class OpponentModelsTest {
    private static OpponentGear.Piece piece(String id, double dur) {
        return new OpponentGear.Piece(id, OpponentGear.Tier.fromItemId(id), dur, 4, 0, 0, 0, false);
    }

    @Test
    void gearTiersAndSummary() {
        OpponentGear g = new OpponentGear(Arrays.asList(piece("minecraft:netherite_boots", 1), null,
            piece("minecraft:netherite_chestplate", 1), piece("minecraft:diamond_helmet", 1)),
            WeaponKind.fromItemId("minecraft:netherite_sword"), "minecraft:netherite_sword", WeaponKind.fromItemId("minecraft:totem_of_undying"),
            false, 5, 0, 0, 0, 0);
        assertEquals(3, g.pieces());
        assertEquals(2, g.netheritePieces());
        assertEquals(WeaponKind.SWORD, g.mainHand());
        assertTrue(g.totemOffhand());
        assertEquals((5 + 5 + 4) / 4.0, g.averageTier(), 1e-9);
        assertTrue(g.summary().contains("S5"));
    }

    @Test
    void skillRisesWithGoodPlay() {
        SkillEstimator bad = new SkillEstimator(), good = new SkillEstimator();
        for (int i = 0; i < 30; i++) {
            bad.onSwing(0.3);
            bad.onHitLanded(false);
            bad.onStrafeSample(0.0);
            bad.onReaction(15);
            good.onSwing(1.0);
            good.onHitLanded(true);
            good.onStrafeSample(0.25);
            good.onReaction(3);
        }
        assertTrue(good.skill() > 0.7, "good " + good.skill());
        assertTrue(bad.skill() < 0.35, "bad " + bad.skill());
        assertTrue(good.dpsMultiplier() > bad.dpsMultiplier());
        SkillEstimator fresh = new SkillEstimator();
        assertEquals(0.5, fresh.skill(), 0.05);
    }

    @Test
    void predictorLeadsLinearMotion() {
        MovementPredictor p = new MovementPredictor();
        for (int t = 0; t < 20; t++) p.update(t, t * 0.28, 64, 0);
        assertTrue(p.ready());
        double[] at = p.predict(5);
        assertEquals(19 * 0.28 + 5 * 0.28, at[0], 0.15);
        assertEquals(0.28, p.horizontalSpeed(), 0.03);
    }

    @Test
    void intentRules() {
        assertEquals(Intent.CRYSTALLING, Intent.classify(new Intent.Features(4, 0, 0.9, 0, false, 2, false, 0, false)));
        assertEquals(Intent.CHASING, Intent.classify(new Intent.Features(8, 0.2, 0.9, 0, false, 0, false, 0, false)));
        assertEquals(Intent.FLEEING, Intent.classify(new Intent.Features(8, -0.25, -0.8, 0, false, 0, false, 0, false)));
        assertEquals(Intent.BURROWED, Intent.classify(new Intent.Features(3, 0, 0.9, 0, false, 0, true, 0, false)));
        assertTrue(Intent.CRYSTALLING.threatMultiplier() > Intent.CHASING.threatMultiplier());
    }

    @Test
    void ledgerAndConsumables() {
        HitLedger l = new HitLedger();
        for (int i = 0; i < 5; i++) l.taken(6, i);
        l.dealt(2, 10);
        assertTrue(l.tradeRatio() < 0.2);
        assertEquals(0.7, l.observedCorrection(), 1e-9);

        ConsumableTracker c = new ConsumableTracker();
        c.onAbsorption(0, 0);
        c.onAbsorption(4, 1);
        c.onAbsorption(0, 50);
        c.onAbsorption(16, 60);
        assertEquals(2, c.gapsEaten());
        assertEquals(1, c.enchantedGaps());
        c.onPearlThrown(100);
        assertTrue(c.pearlOnCooldown(110));
        assertFalse(c.pearlOnCooldown(121));
        c.onTotemPop(200);
        assertTrue(c.inRetotemWindow(202));
        assertFalse(c.inRetotemWindow(210));
    }

    @Test
    void swapsAndDurabilityAndPing() {
        WeaponSwapDetector d = new WeaponSwapDetector();
        d.observe(WeaponKind.SWORD, 0);
        assertNotNull(d.observe(WeaponKind.AXE, 5));
        assertEquals(WeaponSwapDetector.Expectation.SHIELD_BREAK, d.expectation(10, 40));
        assertEquals(WeaponSwapDetector.Expectation.NONE, d.expectation(100, 40));
        assertNull(d.observe(WeaponKind.AXE, 6));

        var armor = Arrays.asList(piece("minecraft:diamond_boots", 1), piece("minecraft:diamond_leggings", 1),
            piece("minecraft:diamond_chestplate", 0.05), piece("minecraft:diamond_helmet", 1));
        assertTrue(ArmorDurability.weakSpot(armor));
        assertEquals(12.0 / 20.0, ArmorDurability.armorRemainingAfter(armor, 10, 0.01), 1e-9);

        PingModel me = new PingModel(), them = new PingModel();
        me.sample(100);
        them.sample(200);
        assertEquals(1.0, me.oneWayTicks(), 1e-9);
        assertEquals(0.28 * 3, PingModel.reachMargin(me, them, 0.28), 1e-9);
    }

    @Test
    void mobProfiles() {
        assertTrue(MobProfile.of("minecraft:warden").boss());
        assertTrue(MobProfile.of("minecraft:creeper").explosive());
        assertTrue(MobProfile.of("minecraft:skeleton").threatRange() >= 15);
        assertSame(MobProfile.GENERIC, MobProfile.of("minecraft:unknown_mob"));
    }
}
