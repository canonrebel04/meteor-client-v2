/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Runs the P1 defense policies together each tick and resolves their conflicts into one plan (C51-C59): heal
 * ({@link HealPlanner}), potions ({@link PotionPlanner}), totem ({@link TotemPolicy}), armor ({@link ArmorPolicy}),
 * damage type ({@link DamageTypeAdvisor}), hunger ({@link HungerPlanner}) and knockback ({@link KnockbackModel}).
 * The plan says which helper modules (AutoGap, AutoEat, AutoTotem/Offhand, AutoArmor, AutoPot) should be running
 * and what the brain itself should do (disengage to heal, keep away from edges, keep distance). Pure.
 *
 * <p>Offhand priority: a needed totem beats a shield, which beats a gap/potion. The brain never moves a shield
 * into the offhand while {@link Plan#holdTotem()} is set.</p>
 */
public final class DefenseDirector {
    public record Input(
        double hp, double maxHp, double incomingDps, double incomingBurst, double enemyDistance, double enemyReach,
        boolean cover, boolean hasGap, boolean hasEgap, boolean hasHealPotion, boolean hasSplashHeal, boolean regenerating,
        int food, float saturation,
        int totemsInInv, boolean offhandTotem, boolean justPopped,
        double[] recentDamageByType, boolean haveShield,
        boolean wearingElytra, boolean airEscapePlanned, boolean fightOn, boolean onGround, boolean haveChestplate,
        boolean haveElytra, double worstArmorDurability, boolean haveSpareArmor,
        PotionPlanner.Have potions, PotionPlanner.Active active, boolean burning, boolean fleeing,
        int netheritePieces, boolean enemySprintHits, int enemyKnockbackLevel, double edgeDistance,
        double healThreshold
    ) {
    }

    public record Plan(
        HealPlanner.Action heal, boolean runAutoGap, boolean runAutoEat, boolean disengageToHeal,
        TotemPolicy.Action totem, boolean holdTotem,
        List<PotionPlanner.Buff> potions, boolean runAutoPot,
        boolean runAutoArmor, ArmorPolicy.ChestChoice chest,
        DamageTypeAdvisor.Type dominantDamage, DamageTypeAdvisor.Response damageResponse,
        boolean avoidEdges, boolean keepDistance, String summary
    ) {
    }

    public static final double ARMOR_REPLACE_AT = 0.10;

    private DefenseDirector() {
    }

    public static Plan decide(Input in) {
        List<String> why = new ArrayList<>();
        boolean lowHp = in.hp() < in.healThreshold();

        // C52 totem first: it decides whether the offhand is free for anything else
        TotemPolicy.Action totem = TotemPolicy.decide(in.hp(), in.incomingBurst(), in.totemsInInv(), in.offhandTotem(), in.justPopped());
        boolean holdTotem = totem == TotemPolicy.Action.EQUIP_OFFHAND || totem == TotemPolicy.Action.RETOTEM
            || (in.offhandTotem() && (in.hp() - in.incomingBurst() <= 2.0 || in.hp() <= 10.0));
        if (holdTotem) why.add("totem " + (totem == TotemPolicy.Action.NONE ? "hold" : totem.name().toLowerCase()));

        // C51/C53 heal: only below the user's heal threshold (a full heal plan for low HP, regen otherwise)
        HealPlanner.Action heal = lowHp ? HealPlanner.decide(new HealPlanner.Input(in.hp(), in.maxHp(), in.incomingDps(),
            in.enemyDistance(), in.enemyReach(), in.cover(), in.hasGap(), in.hasEgap(), in.hasHealPotion(), in.hasSplashHeal(),
            in.regenerating(), in.food())) : HealPlanner.Action.NONE;
        boolean runAutoGap = heal == HealPlanner.Action.EAT_GAP || heal == HealPlanner.Action.EAT_EGAP;
        boolean disengage = heal == HealPlanner.Action.DISENGAGE_FIRST;
        if (heal != HealPlanner.Action.NONE) why.add("heal " + heal.name().toLowerCase());

        // C58 hunger: eating food is never done inside melee range (it slows us and stops swings)
        boolean inMelee = in.enemyDistance() <= in.enemyReach() + 1.5;
        HungerPlanner.Action hunger = HungerPlanner.decide(in.food(), in.saturation(), inMelee, in.fleeing());
        boolean runAutoEat = !inMelee && (hunger != HungerPlanner.Action.NONE || heal == HealPlanner.Action.REGEN_WAIT && in.food() < 20);
        if (runAutoEat) why.add("eat");

        // C54 potions by phase
        PotionPlanner.Phase phase = in.burning() ? PotionPlanner.Phase.BURNING
            : in.fleeing() ? PotionPlanner.Phase.FLEEING : in.fightOn() ? PotionPlanner.Phase.FIGHTING : PotionPlanner.Phase.PRE_FIGHT;
        List<PotionPlanner.Buff> potions = in.potions() == null || in.active() == null ? List.of()
            : PotionPlanner.plan(phase, in.potions(), in.active(), in.hp());
        boolean healPotionNow = heal == HealPlanner.Action.DRINK_HEAL || heal == HealPlanner.Action.SPLASH_HEAL;
        boolean runAutoPot = healPotionNow || potions.contains(PotionPlanner.Buff.INSTANT_HEALTH);
        if (!potions.isEmpty()) why.add("potions " + potions);

        // C55 armor: replace near-broken pieces out of melee, chestplate over elytra when fighting on the ground
        ArmorPolicy.ChestChoice chest = ArmorPolicy.chest(in.wearingElytra(), in.airEscapePlanned(), in.fightOn(), in.onGround(),
            in.haveChestplate(), in.haveElytra());
        boolean runAutoArmor = ArmorPolicy.replaceBroken(in.worstArmorDurability(), ARMOR_REPLACE_AT, in.haveSpareArmor(), in.enemyDistance())
            || chest == ArmorPolicy.ChestChoice.CHESTPLATE;
        if (runAutoArmor) why.add("armor");

        // C56 damage type
        DamageTypeAdvisor.Type dom = DamageTypeAdvisor.dominant(in.recentDamageByType() == null ? new double[0] : in.recentDamageByType());
        boolean anyDamage = false;
        if (in.recentDamageByType() != null) for (double v : in.recentDamageByType()) anyDamage |= v > 0.5;
        DamageTypeAdvisor.Response resp = anyDamage ? DamageTypeAdvisor.respond(dom, in.haveShield() && !holdTotem) : null;
        boolean keepDistance = resp == DamageTypeAdvisor.Response.DISTANCE || resp == DamageTypeAdvisor.Response.COVER;
        if (resp != null) why.add(dom.name().toLowerCase() + "->" + resp.name().toLowerCase());

        // C59 knockback near an edge
        double kb = KnockbackModel.horizontal(in.enemySprintHits(), in.enemyKnockbackLevel(),
            KnockbackModel.resistance(in.netheritePieces(), 0), false);
        boolean avoidEdges = in.fightOn() && in.edgeDistance() < 6 && KnockbackModel.dangerous(kb, in.edgeDistance());
        if (avoidEdges) why.add("edge");

        return new Plan(heal, runAutoGap, runAutoEat, disengage, totem, holdTotem, potions, runAutoPot, runAutoArmor, chest,
            dom, resp, avoidEdges, keepDistance, why.isEmpty() ? "ok" : String.join(", ", why));
    }
}
