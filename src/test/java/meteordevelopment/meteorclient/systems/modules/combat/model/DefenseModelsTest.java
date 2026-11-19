/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

/** P1 C51-C60 defense policies and learning persistence. */
class DefenseModelsTest {
    @Test
    void shieldPolicy() {
        // axe in reach: don't block
        assertEquals(ShieldPolicy.Action.LOWER, ShieldPolicy.decide(new ShieldPolicy.Input(true, false, true, WeaponKind.AXE, 2.5, 3, false, false, 0.2f, false, 2)));
        // sword swing coming: raise
        assertEquals(ShieldPolicy.Action.RAISE, ShieldPolicy.decide(new ShieldPolicy.Input(true, false, false, WeaponKind.SWORD, 3, 3, false, false, 0.2f, false, 3)));
        // our cooldown ready: lower to attack
        assertEquals(ShieldPolicy.Action.LOWER, ShieldPolicy.decide(new ShieldPolicy.Input(true, false, true, WeaponKind.SWORD, 3, 3, false, false, 0.95f, true, 3)));
        // arrow incoming
        assertEquals(ShieldPolicy.Action.RAISE, ShieldPolicy.decide(new ShieldPolicy.Input(true, false, false, WeaponKind.BOW, 20, 3, true, false, 0.95f, false, -1)));
    }

    @Test
    void totemPolicy() {
        assertEquals(TotemPolicy.Action.EQUIP_OFFHAND, TotemPolicy.decide(14, 13, 2, false, false));
        assertEquals(TotemPolicy.Action.ALLOW_OTHER_OFFHAND, TotemPolicy.decide(20, 4, 2, false, false));
        assertEquals(TotemPolicy.Action.RETOTEM, TotemPolicy.decide(1, 10, 1, false, true));
        assertEquals(TotemPolicy.Action.NONE, TotemPolicy.decide(1, 10, 0, false, true));
    }

    @Test
    void healPlannerNeverEatsInMeleeWithoutCover() {
        var inMelee = new HealPlanner.Input(10, 20, 6, 2.5, 3, false, true, false, false, false, false, 20);
        assertEquals(HealPlanner.Action.DISENGAGE_FIRST, HealPlanner.decide(inMelee));
        var covered = new HealPlanner.Input(10, 20, 6, 2.5, 3, true, true, false, false, false, false, 20);
        assertEquals(HealPlanner.Action.EAT_GAP, HealPlanner.decide(covered));
        var far = new HealPlanner.Input(10, 20, 0, 20, 3, false, true, false, false, false, false, 20);
        assertEquals(HealPlanner.Action.EAT_GAP, HealPlanner.decide(far));
        var splash = new HealPlanner.Input(6, 20, 6, 2, 3, false, false, false, false, true, false, 20);
        assertEquals(HealPlanner.Action.SPLASH_HEAL, HealPlanner.decide(splash));
    }

    @Test
    void potionsArmorStatusHungerKnockback() {
        var plan = PotionPlanner.plan(PotionPlanner.Phase.FLEEING, new PotionPlanner.Have(true, true, false, false, false, true),
            new PotionPlanner.Active(false, false, false, false, false), 6);
        assertTrue(plan.contains(PotionPlanner.Buff.SPEED));
        assertTrue(plan.contains(PotionPlanner.Buff.INSTANT_HEALTH));
        assertFalse(plan.contains(PotionPlanner.Buff.STRENGTH));

        assertFalse(ArmorPolicy.replaceBroken(0.02, 0.05, true, 3));
        assertTrue(ArmorPolicy.replaceBroken(0.02, 0.05, true, 12));
        assertEquals(ArmorPolicy.ChestChoice.CHESTPLATE, ArmorPolicy.chest(true, false, true, true, true, true));
        assertEquals(ArmorPolicy.ChestChoice.ELYTRA, ArmorPolicy.chest(false, true, false, false, true, true));

        assertEquals(DamageTypeAdvisor.Type.BLAST, DamageTypeAdvisor.dominant(new double[]{1, 2, 9, 0, 0, 0}));
        assertEquals(DamageTypeAdvisor.Response.SHIELD, DamageTypeAdvisor.respond(DamageTypeAdvisor.Type.PROJECTILE, true));

        assertTrue(StatusResponse.respond(EnumSet.of(StatusResponse.Effect.WITHER), 10, true, false, 0).contains(StatusResponse.Response.DRINK_MILK));
        assertFalse(StatusResponse.respond(EnumSet.of(StatusResponse.Effect.POISON), 18, true, true, 0).contains(StatusResponse.Response.DRINK_MILK));

        assertFalse(HungerPlanner.canSprint(6));
        assertTrue(HungerPlanner.canSprint(7));
        assertEquals(HungerPlanner.Action.EAT_NOW, HungerPlanner.decide(7, 0, false, true));

        assertEquals(0.4, KnockbackModel.resistance(4, 0), 1e-9);
        assertEquals(0, KnockbackModel.horizontal(true, 2, 0, true), 1e-9);
        assertTrue(KnockbackModel.horizontal(true, 0, 0, false) > KnockbackModel.horizontal(true, 0, 0.4, false));
    }

    @Test
    void respawnAndRegear() {
        var ungeared = new RespawnPolicy.Kit(0, false, 0, 0, 0);
        var geared = new RespawnPolicy.Kit(4, true, 2, 4, 32);
        var req = RespawnPolicy.Requirement.DEFAULT;
        assertEquals(RespawnPolicy.Mode.GRACE, RespawnPolicy.mode(10, ungeared, req, true, true));
        assertEquals(RespawnPolicy.Mode.REGEAR, RespawnPolicy.mode(500, ungeared, req, true, true));
        assertEquals(RespawnPolicy.Mode.UNGEARED_AVOID, RespawnPolicy.mode(500, ungeared, req, false, true));
        assertEquals(RespawnPolicy.Mode.NORMAL, RespawnPolicy.mode(500, geared, req, true, true));
        assertFalse(RespawnPolicy.mayEngagePlayer(RespawnPolicy.Mode.REGEAR, false));
        assertTrue(RespawnPolicy.mayEngagePlayer(RespawnPolicy.Mode.REGEAR, true));
    }

    @Test
    void learningRoundTripsAndIsClamped() {
        OpponentMemory m = new OpponentMemory();
        for (int i = 0; i < 10; i++) m.recordOutcome("Steve", OpponentMemory.Outcome.LOSS, i);
        m.recordSkill("Steve", 0.9, 10);
        assertEquals(0.8, m.ratioBias("steve"), 1e-9);
        assertEquals(1.0, m.ratioBias("alex"), 1e-9);
        StringWriter w = new StringWriter();
        m.write(w);
        OpponentMemory back = new OpponentMemory();
        back.read(new StringReader(w.toString()));
        assertEquals(10, back.get("STEVE").losses);
        assertEquals(m.skillPrior("steve"), back.skillPrior("steve"), 1e-9);
        assertTrue(back.skillPrior("steve") > 0.5);
    }
}
