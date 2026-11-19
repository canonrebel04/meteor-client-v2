/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.combat.model.WeaponKind;
import meteordevelopment.meteorclient.systems.modules.combat.tactics.*;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.CameraArbiter;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.scores.PlayerTeam;
import org.jspecify.annotations.Nullable;

import java.util.*;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Runtime side of the P2 combat tactics (C21-C40, C48, C61-C90): feeds live game state into the pure models in
 * {@code combat.tactics} and acts on their answers. Camera turns go through {@link CameraArbiter}; item uses,
 * placements and hotbar swaps through {@link CombatPacer} (Baritone's shared pacing layer). Riskier behaviours
 * (knockback taps, combo positioning, utility throws, fire, mace launches, enemy-crystal breaking, explosive offence,
 * block traps, flee walls, territory mode) are off unless enabled in Combat Mode's settings.
 */
public final class CombatTactics implements KillAura.Advisor {
    /** Settings snapshot, refreshed by Combat Mode each tick. */
    public record Config(boolean crits, MeleeTiming.Tap tap, boolean reach, boolean hitSelect, boolean weaponArbiter,
                         boolean priority, boolean comboPositioning, boolean formation, boolean focusFire,
                         boolean ballistic, boolean crossbowPreload, boolean utility, boolean fire, boolean maceLaunch,
                         boolean crystalDefense, boolean breakCrystals, boolean tntRetreat, boolean explosives,
                         double explosiveMaxSelf, boolean corneredBlocks, boolean breakIn, boolean closeDoors,
                         boolean fleeWalls, boolean encounterRules, boolean teamCheck, boolean teamColour,
                         boolean surface, boolean territory, String territoryCenter, int territoryRadius, int warnSeconds) {
        public static final Config DEFAULTS = new Config(true, MeleeTiming.Tap.NONE, true, true, true, true, false, true, true,
            true, true, false, false, false, true, false, true, false, 8.0, false, false, true, false, true, true, false,
            true, false, "", 32, 5);
    }

    private Config cfg = Config.DEFAULTS;
    private final CombatPacer pacer = CombatPacer.create();
    private final Object camOwner = this;
    private long tick;
    private int airborneTicks;
    private long lastHitTick = -1000, lastSwapTick = -1000, lastUseTick = -1000, uses;
    private int lastSelected = -1;
    private long axeOnShieldTick = -1000;
    private int jumpRelease;
    private boolean tapHeld, tapUsedShield;
    private final Map<String, Object> savedCrystalAura = new HashMap<>();
    private @Nullable Entity explosiveThreat;
    private double explosiveRadius;
    private final Set<Class<? extends Module>> enabledByUs = new HashSet<>();
    private @Nullable PendingUse pending;
    private int restoreSlot = -1;
    private final Map<UUID, Integer> intruderTicks = new HashMap<>();
    private String lastAction = "";

    /** A look-then-act action: turn the camera to {@code at}, select {@code slot}, then run {@code act}. */
    private record PendingUse(Vec3 at, int slot, Runnable act, long deadline, String what, @Nullable BlockPos blockHit) {
    }

    public void configure(Config c) {
        cfg = c;
    }

    public Config config() {
        return cfg;
    }

    public String lastAction() {
        return lastAction;
    }

    public void reset() {
        pending = null;
        explosiveThreat = null;
        airborneTicks = 0;
        restoreModules();
        intruderTicks.clear();
        if (tapHeld && mc.options != null) {
            mc.options.keyDown.setDown(false);
            mc.options.keyUse.setDown(false);
        }
        tapHeld = false;
    }

    // ------------------------------------------------------------------ per-tick

    /** Every client tick (post), any brain state. */
    public void tick(boolean engaged, @Nullable LivingEntity target) {
        if (mc.player == null || mc.level == null) return;
        tick++;
        airborneTicks = mc.player.onGround() ? 0 : airborneTicks + 1;
        int sel = mc.player.getInventory().getSelectedSlot();
        if (sel != lastSelected) {
            lastSwapTick = tick;
            lastSelected = sel;
        }
        if (jumpRelease > 0 && --jumpRelease == 0 && mc.options != null) mc.options.keyJump.setDown(false);

        tickPending();
        tickTaps();
        scanExplosives();
        if (cfg.surface()) surface();
        if (engaged && target != null) {
            critJump(target);
            if (cfg.crossbowPreload()) preloadCrossbow(target);
        }
    }

    /** C88: air running out under water: swim up. */
    private void surface() {
        if (!mc.player.isUnderWater()) return;
        Encounters.Env env = Encounters.environment(mc.player.getAirSupply(), mc.player.getMaxAirSupply(), false, false, false, Hazard.NONE, 0);
        if (env == Encounters.Env.SURFACE && mc.options != null) {
            mc.options.keyJump.setDown(true);
            jumpRelease = 2;
            note("surfacing (air low)");
        }
    }

    private MeleeTiming.State selfState() {
        boolean web = mc.level.getBlockState(mc.player.blockPosition()).is(Blocks.COBWEB);
        return new MeleeTiming.State(mc.player.onGround(), mc.player.getDeltaMovement().y < 0, mc.player.fallDistance,
            mc.player.isInWater(), mc.player.onClimbable(), web, mc.player.hasEffect(MobEffects.BLINDNESS),
            mc.player.hasEffect(MobEffects.LEVITATION), mc.player.isPassenger(), mc.player.isSprinting());
    }

    private double attackSpeed() {
        try {
            return mc.player.getAttributeValue(Attributes.ATTACK_SPEED);
        } catch (RuntimeException e) {
            return 1.6;
        }
    }

    private double reach() {
        return Math.min(3.0, mc.player.entityInteractionRange());
    }

    private double distTo(Entity e) {
        AABB b = e.getBoundingBox();
        Vec3 eye = mc.player.getEyePosition();
        double x = Mth.clamp(eye.x, b.minX, b.maxX), y = Mth.clamp(eye.y, b.minY, b.maxY), z = Mth.clamp(eye.z, b.minZ, b.maxZ);
        return eye.distanceTo(new Vec3(x, y, z));
    }

    /** C22: start a jump so the charged swing lands while falling (KillAura's hold does the rest). */
    private void critJump(LivingEntity target) {
        if (!cfg.crits() || mc.options == null || jumpRelease > 0) return;
        float charge = mc.player.getAttackStrengthScale(0.5f);
        double d = distTo(target);
        MeleeTiming.Crit c = MeleeTiming.crit(true, selfState(), charge, MeleeTiming.ticksToReady(charge, attackSpeed()),
            d <= reach(), d <= reach() + 1.0, airborneTicks, 8);
        if (c == MeleeTiming.Crit.JUMP && !mc.player.isShiftKeyDown()) {
            mc.options.keyJump.setDown(true);
            jumpRelease = 2;
        }
    }

    /** Combat Mode's AttackEntityEvent. */
    public void onAttack(Entity target) {
        lastHitTick = tick;
        if (target instanceof Player p && p.isBlocking() && mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.AXES)) {
            axeOnShieldTick = tick;
        }
    }

    /** C23 taps right after a sprint hit (opt-in). */
    private void tickTaps() {
        if (mc.options == null) return;
        MeleeTiming.Tap tap = cfg.tap();
        long since = tick - lastHitTick;
        if (tap == MeleeTiming.Tap.NONE || since > MeleeTiming.tapLength(tap)) {
            if (tapHeld) {
                mc.options.keyDown.setDown(false);
                if (tapUsedShield) mc.options.keyUse.setDown(false);
                tapHeld = false;
                tapUsedShield = false;
            }
            return;
        }
        boolean shield = mc.player.getOffhandItem().is(Items.SHIELD);
        boolean[] k = MeleeTiming.tapKeys(tap, (int) since, mc.options.keyUp.isDown(), shield);
        mc.options.keyUp.setDown(k[0]);
        mc.options.keyDown.setDown(k[1]);
        if (!k[2]) mc.player.setSprinting(false);
        if (tap == MeleeTiming.Tap.BLOCK_HIT) {
            mc.options.keyUse.setDown(k[3]);
            tapUsedShield |= k[3];
        }
        tapHeld = true;
    }

    // ------------------------------------------------------------------ KillAura advisor

    /** Set by the brain (C92 stealth: hold the swing until behind the target). */
    public volatile boolean externalHold;

    @Override
    public boolean holdAttack(Entity primary) {
        if (externalHold) return true;
        if (!cfg.crits()) return false;
        MeleeTiming.State s = selfState();
        if (s.critEligible()) return false;
        // rising in our own jump: the crit comes in a few ticks (never hold more than 8)
        return !s.onGround() && !s.inWater() && !s.climbing() && airborneTicks > 0 && airborneTicks < 8
            && mc.player.getDeltaMovement().y > -0.08;
    }

    @Override
    public @Nullable Vec3 aimPoint(Entity target) {
        if (!cfg.hitSelect()) return null;
        Vec3 eye = mc.player.getEyePosition(), look = mc.player.getLookAngle();
        AABB b = target.getBoundingBox();
        double[] p = HitPoint.aim(new double[]{eye.x, eye.y, eye.z}, new double[]{look.x, look.y, look.z},
            new double[]{b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ}, 0.12);
        return new Vec3(p[0], p[1], p[2]);
    }

    @Override
    public int weaponSlot(Entity primary) {
        if (!cfg.weaponArbiter() || mc.player == null) return -1;
        Map<WeaponKind, Integer> slots = hotbarWeapons();
        if (slots.isEmpty()) return -1;
        boolean blocking = primary instanceof Player p && p.isBlocking();
        boolean disabled = tick - axeOnShieldTick < 100 && !blocking;
        WeaponKind holding = WeaponKind.fromItemId(id(mc.player.getMainHandItem()));
        WeaponArbiter.Situation s = new WeaponArbiter.Situation(EnumSet.copyOf(slots.keySet()), distTo(primary), reach(),
            blocking, disabled, (int) (tick - axeOnShieldTick), mc.player.fallDistance, primary.getY() < mc.player.getY() - 0.5,
            mc.player.isSprinting(), mc.player.isInWaterOrRain(), true, (int) (tick - lastSwapTick), 3, holding);
        WeaponArbiter.Choice c = WeaponArbiter.choose(s);
        // ranged picks are Combat Mode's ranged mode's job; here we only arbitrate melee
        if (c.kind().ranged() && c.kind() != WeaponKind.TRIDENT) return -1;
        Integer slot = slots.get(c.kind());
        if (slot == null) return -1;
        int cur = mc.player.getInventory().getSelectedSlot();
        if (!c.swapNow()) return holding.melee() ? cur : -1;
        int next = pacer.nextSlot(cur, slot);
        if (next == slot && cur != slot) note("weapon -> " + c.kind().name().toLowerCase(Locale.ROOT));
        return next;
    }

    /** Best hotbar slot per weapon kind (higher material tier first). */
    private Map<WeaponKind, Integer> hotbarWeapons() {
        Map<WeaponKind, Integer> out = new EnumMap<>(WeaponKind.class);
        Map<WeaponKind, Integer> tier = new EnumMap<>(WeaponKind.class);
        for (int i = 0; i < 9; i++) {
            ItemStack st = mc.player.getInventory().getItem(i);
            if (st.isEmpty()) continue;
            String id = id(st);
            WeaponKind k = WeaponKind.fromItemId(id);
            if (!k.melee() && !k.ranged()) continue;
            if (st.isDamageableItem() && st.getMaxDamage() - st.getDamageValue() < 10) continue; // about to break
            int t = id.contains("netherite") ? 5 : id.contains("diamond") ? 4 : id.contains("iron") ? 3 : id.contains("stone") || id.contains("copper") ? 2 : id.contains("gold") ? 1 : 0;
            if (!out.containsKey(k) || t > tier.get(k)) {
                out.put(k, i);
                tier.put(k, t);
            }
        }
        return out;
    }

    @Override
    public void sortTargets(List<Entity> targets) {
        if (!cfg.priority() || targets.size() < 2 || mc.level == null) return;
        List<TargetPriority.Threat> all = threats(16);
        boolean sweep = mc.player.getMainHandItem().is(net.minecraft.tags.ItemTags.SWORDS);
        int focus = cfg.focusFire() ? allyFocus() : -1;
        Map<Integer, Double> score = new HashMap<>();
        for (TargetPriority.Threat t : all) {
            score.put(t.id(), TargetPriority.score(t, all, reach(), sweep) + (t.id() == focus ? 25 : 0));
        }
        targets.sort((a, b) -> Double.compare(score.getOrDefault(b.getId(), -1e9), score.getOrDefault(a.getId(), -1e9)));
    }

    /** Live threat list for the priority model. */
    private List<TargetPriority.Threat> threats(double radius) {
        List<TargetPriority.Threat> out = new ArrayList<>();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || le == mc.player || !le.isAlive()) continue;
            double d = le.distanceTo(mc.player);
            if (d > radius) continue;
            boolean player = le instanceof Player;
            if (player && !Friends.get().shouldAttack((Player) le)) continue;
            boolean hostile = le instanceof Enemy || player;
            if (!hostile) continue;
            String id = BuiltInRegistries.ENTITY_TYPE.getKey(le.getType()).getPath();
            boolean ranged = id.equals("skeleton") || id.equals("stray") || id.equals("bogged") || id.equals("pillager")
                || id.equals("blaze") || id.equals("ghast") || id.equals("witch") || id.equals("breeze")
                || (player && WeaponKind.fromItemId(id(le.getMainHandItem())).ranged());
            boolean healer = id.equals("witch") || (player && le.isUsingItem());
            boolean aggressive = player || !(le instanceof net.minecraft.world.entity.Mob m) || m.isAggressive() || m.getTarget() == mc.player || d < 6;
            double dps = player ? 6 : 3;
            out.add(new TargetPriority.Threat(le.getId(), le.getX(), le.getY(), le.getZ(), le.getHealth() + le.getAbsorptionAmount(),
                dps, ranged, healer, player, aggressive, distTo(le)));
        }
        return out;
    }

    // ------------------------------------------------------------------ group (C40, C82, C83, C89)

    private List<Formation.Ally> allies() {
        List<Formation.Ally> out = new ArrayList<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.distanceTo(mc.player) > 32) continue;
            if (standing(p) != Allegiance.Standing.FRIEND && standing(p) != Allegiance.Standing.TEAMMATE) continue;
            out.add(new Formation.Ally(p.getX(), p.getZ(), p.getHealth() + p.getAbsorptionAmount(), lookTarget(p)));
        }
        return out;
    }

    /** Entity an ally is fighting: the hostile nearest their look ray within 5 blocks. */
    private int lookTarget(Player p) {
        Vec3 eye = p.getEyePosition(), look = p.getLookAngle();
        int best = -1;
        double bd = 1.2;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof LivingEntity le) || e == p || e == mc.player || !(le instanceof Enemy || le instanceof Player)) continue;
            Vec3 to = e.getBoundingBox().getCenter().subtract(eye);
            double along = to.dot(look);
            if (along < 0 || along > 5) continue;
            double off = to.subtract(look.scale(along)).length();
            if (off < bd) {
                bd = off;
                best = e.getId();
            }
        }
        return best;
    }

    private int allyFocus() {
        return Formation.focus(allies());
    }

    public Allegiance.Standing standing(Player p) {
        boolean friend = !Friends.get().shouldAttack(p);
        PlayerTeam mine = mc.player.getTeam(), theirs = p.getTeam();
        boolean colour = false;
        if (cfg.teamColour()) {
            var a = mc.player.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).get(DataComponents.DYED_COLOR);
            var b = p.getItemBySlot(net.minecraft.world.entity.EquipmentSlot.CHEST).get(DataComponents.DYED_COLOR);
            colour = a != null && b != null && a.rgb() == b.rgb();
        }
        return Allegiance.standing(friend, cfg.teamCheck() && mine != null ? mine.getName() : null,
            theirs != null ? theirs.getName() : null, colour, cfg.teamColour(), -1, tick, 0);
    }

    /** C85/C89 target filter used by Combat Mode's acquisition. */
    public boolean mayTarget(LivingEntity e) {
        if (e instanceof Player p) {
            Allegiance.Standing s = standing(p);
            if (s == Allegiance.Standing.FRIEND || s == Allegiance.Standing.TEAMMATE) return false;
        }
        if (cfg.encounterRules()) {
            Encounters.Rule r = rule(e);
            return r != Encounters.Rule.FLEE && r != Encounters.Rule.AVOID && r != Encounters.Rule.BREAK_LOS;
        }
        return true;
    }

    public Encounters.Rule rule(LivingEntity e) {
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        boolean geared = mc.player.getArmorValue() >= 15;
        boolean sonic = e instanceof net.minecraft.world.entity.monster.warden.Warden w && w.getTarget() == mc.player && distTo(e) < 15;
        boolean crystalsLeft = false;
        if (id.endsWith("ender_dragon")) {
            for (Entity c : mc.level.entitiesForRendering()) if (c instanceof EndCrystal) {
                crystalsLeft = true;
                break;
            }
        }
        return Encounters.rule(id, geared, sonic, crystalsLeft, false);
    }

    /** C85: a dragon fight: shoot the healing crystals first. Returns the crystal to aim at, or null. */
    public @Nullable Entity dragonCrystal(LivingEntity target) {
        if (!cfg.encounterRules() || rule(target) != Encounters.Rule.PRIORITY_CRYSTALS) return null;
        Entity best = null;
        double bd = 64;
        for (Entity c : mc.level.entitiesForRendering()) {
            if (c instanceof EndCrystal && c.distanceTo(mc.player) < bd) {
                bd = c.distanceTo(mc.player);
                best = c;
            }
        }
        return best;
    }

    /**
     * C27/C32 the safety-bubble distance for this tick: just outside their reach while our swing charges, at our
     * reach edge when it is ready.
     */
    public double bubble(double configured, LivingEntity target) {
        if (!cfg.reach()) return configured;
        double theirReach = target instanceof Player ? 3.0 : 2.0 + target.getBbWidth() / 2;
        if (cfg.encounterRules()) {
            Encounters.Rule r = rule(target);
            if (r == Encounters.Rule.KITE || r == Encounters.Rule.RANGED_ONLY) theirReach += 1.5;
        }
        if (target instanceof net.minecraft.world.entity.monster.Creeper) return configured; // blast radius, not reach
        int toReady = MeleeTiming.ticksToReady(mc.player.getAttackStrengthScale(0.5f), attackSpeed());
        ReachBand.Band band = ReachBand.band(reach(), theirReach, toReady, 3);
        if (toReady <= 3) return configured; // the strike phase takes over
        // charging: hold just outside their reach (up to 1 block more), never closer than that
        return Math.max(band.min(), Math.min(configured, band.min() + 1.0));
    }

    /**
     * C29/C31/C35/C37/C40/C87 positioning around the target: flank allies / guard a weak ally (formation), or
     * the combo spot that knocks the target into a hazard (opt-in). Returns true when it steered this tick.
     */
    public boolean position(LivingEntity target, CombatFollowController fc, double radius) {
        if (mc.player.distanceTo(target) > 8) return false;
        double[] goal = null;
        if (cfg.formation()) {
            List<Formation.Ally> allies = allies();
            if (!allies.isEmpty()) {
                goal = Formation.guard(target.getX(), target.getZ(), allies, 6);
                if (goal == null) goal = Formation.flank(target.getX(), target.getZ(), allies, radius, mc.player.getX(), mc.player.getZ());
            }
        }
        if (goal == null && cfg.comboPositioning()) {
            BlockPos feet = target.blockPosition();
            Positioning.Ground g = new Positioning.Ground() {
                @Override
                public Hazard hazard(int dx, int dz) {
                    return hazardAt(feet.offset(dx, 0, dz));
                }

                @Override
                public int height(int dx, int dz) {
                    BlockPos p = feet.offset(dx, 0, dz);
                    for (int dy = 2; dy >= -3; dy--) {
                        BlockPos q = p.above(dy);
                        if (!mc.level.getBlockState(q.below()).getCollisionShape(mc.level, q.below()).isEmpty()
                            && mc.level.getBlockState(q).getCollisionShape(mc.level, q).isEmpty()) return dy;
                    }
                    return Integer.MIN_VALUE;
                }
            };
            Positioning.Spot s = Positioning.best(g, radius, 16, dim(), true, true);
            if (s != null && s.score() > 0) goal = new double[]{target.getX() + s.dx(), target.getZ() + s.dz()};
        }
        if (goal == null) return false;
        return fc.steerTo(target, goal[0], goal[1]);
    }

    private Positioning.Dim dim() {
        String d = mc.level.dimension().identifier().getPath();
        return d.equals("the_nether") ? Positioning.Dim.NETHER : d.equals("the_end") ? Positioning.Dim.END : Positioning.Dim.OVERWORLD;
    }

    private Hazard hazardAt(BlockPos p) {
        BlockState at = mc.level.getBlockState(p);
        if (!at.getCollisionShape(mc.level, p).isEmpty() && !mc.level.getBlockState(p.above()).getCollisionShape(mc.level, p.above()).isEmpty()) return Hazard.WALL;
        Hazard h = Hazard.ofBlock(BuiltInRegistries.BLOCK.getKey(at.getBlock()).toString());
        if (h != Hazard.NONE) return h;
        Hazard below = Hazard.ofBlock(BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(p.below()).getBlock()).toString());
        if (below == Hazard.MAGMA || below == Hazard.LAVA) return below;
        for (int dy = 1; dy <= 4; dy++) {
            if (!mc.level.getBlockState(p.below(dy)).getCollisionShape(mc.level, p.below(dy)).isEmpty()) return Hazard.NONE;
            if (p.getY() - dy <= mc.level.getMinY()) return Hazard.VOID;
        }
        return Hazard.DROP;
    }

    // ------------------------------------------------------------------ ranged (C61-C63, C66, C69)

    /**
     * C61/C62/C63 aim and release for the held bow / crossbow / trident in Combat Mode's ranged mode. Returns
     * false when nothing ranged is held (the caller swaps).
     */
    public boolean ranged(LivingEntity target, boolean visible) {
        if (!cfg.ballistic() || mc.options == null) return false;
        ItemStack held = mc.player.getMainHandItem();
        Ballistics.Projectile pr;
        if (held.is(Items.BOW)) pr = Ballistics.Projectile.ARROW;
        else if (held.is(Items.CROSSBOW)) pr = Ballistics.Projectile.CROSSBOW_ARROW;
        else if (held.is(Items.TRIDENT)) pr = Ballistics.Projectile.TRIDENT;
        else return false;
        if (!visible) {
            mc.options.keyUse.setDown(false);
            if (mc.player.isUsingItem() && !held.is(Items.CROSSBOW)) mc.player.stopUsingItem();
            return true;
        }
        Entity aimAt = dragonCrystal(target);
        Entity tgt = aimAt != null ? aimAt : target;
        double dist = mc.player.distanceTo(tgt);
        int use = mc.player.getTicksUsingItem();
        double power = pr == Ballistics.Projectile.ARROW ? Ballistics.bowPower(Math.max(use, Ballistics.drawTicks(dist))) : 1.0;
        Vec3 eye = mc.player.getEyePosition();
        Vec3 v = tgt.getDeltaMovement();
        double ty = tgt.getY() + tgt.getBbHeight() * 0.7;
        double[] sol = Ballistics.lead(pr, power, eye.x, eye.y, eye.z, tgt.getX(), ty, tgt.getZ(), v.x, v.y, v.z);
        if (Double.isNaN(sol[1])) {
            mc.options.keyUse.setDown(false);
            return true;
        }
        CameraArbiter.claim(camOwner, CameraArbiter.PRIORITY_COMBAT_AIM + 1, (float) sol[0], (float) sol[1], true);
        boolean aimed = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - (float) sol[0])) < 2.5f && Math.abs(mc.player.getXRot() - (float) sol[1]) < 2.5f;
        if (held.is(Items.CROSSBOW)) {
            if (!CrossbowItem.isCharged(held)) {
                mc.options.keyUse.setDown(true); // load; it fires once charged and aimed
            } else {
                mc.options.keyUse.setDown(false);
                if (aimed && useReady()) {
                    mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                    used("crossbow shot");
                }
            }
            return true;
        }
        if (held.is(Items.TRIDENT)) {
            SpecialWeapons.TridentUse tu = SpecialWeapons.trident(true, ench(held, Enchantments.RIPTIDE) > 0, ench(held, Enchantments.LOYALTY) > 0,
                ench(held, Enchantments.CHANNELING) > 0, mc.player.isInWaterOrRain(), mc.level.isThundering(),
                mc.level.canSeeSky(target.blockPosition()), dist, false);
            if (tu != SpecialWeapons.TridentUse.THROW && tu != SpecialWeapons.TridentUse.CHANNEL) {
                mc.options.keyUse.setDown(false);
                return false; // keep it as a melee weapon
            }
        }
        int need = pr == Ballistics.Projectile.TRIDENT ? 11 : Ballistics.drawTicks(dist);
        if (use >= need && aimed) {
            mc.options.keyUse.setDown(false);
            mc.gameMode.releaseUsingItem(mc.player);
            used(pr == Ballistics.Projectile.TRIDENT ? "trident throw" : "bow shot");
        } else {
            mc.options.keyUse.setDown(true);
        }
        return true;
    }

    /** C62: hold a loaded crossbow before the fight reaches us. */
    private void preloadCrossbow(LivingEntity target) {
        ItemStack held = mc.player.getMainHandItem();
        if (!held.is(Items.CROSSBOW) || mc.options == null) return;
        if (SpecialWeapons.preloadCrossbow(true, CrossbowItem.isCharged(held), mc.player.distanceTo(target), distTo(target) <= reach())) {
            mc.options.keyUse.setDown(true);
        } else if (CrossbowItem.isCharged(held) && mc.player.isUsingItem()) {
            mc.options.keyUse.setDown(false);
        }
    }

    /** C64-C69 utility, mace launch and fire, out of melee (all opt-in). */
    public void utility(LivingEntity target) {
        if (pending != null || !useReady()) return;
        double d = mc.player.distanceTo(target);
        if (cfg.maceLaunch()) {
            FindItemResult wc = InvUtils.findInHotbar(Items.WIND_CHARGE);
            boolean mace = InvUtils.findInHotbar(Items.MACE).found();
            SpecialWeapons.MaceUse mu = SpecialWeapons.mace(mace, mc.player.fallDistance, false, true, wc.found(), mc.player.onGround(), d,
                mc.player.getHealth(), false);
            if (mu == SpecialWeapons.MaceUse.LAUNCH) {
                Vec3 feet = mc.player.position().add(0, 0.05, 0);
                queue(new PendingUse(feet, wc.slot(), () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND), tick + 20, "mace launch", null));
                return;
            }
        }
        if (cfg.utility()) {
            FindItemResult snow = InvUtils.findInHotbar(Items.SNOWBALL, Items.EGG);
            FindItemResult rod = InvUtils.findInHotbar(Items.FISHING_ROD);
            FindItemResult weak = InvUtils.findInHotbar(s -> s.is(Items.SPLASH_POTION) && potion(s, "weakness"));
            FindItemResult slow = InvUtils.findInHotbar(s -> s.is(Items.SPLASH_POTION) && potion(s, "slowness"));
            FindItemResult harm = InvUtils.findInHotbar(s -> s.is(Items.SPLASH_POTION) && potion(s, "harming"));
            double edge = edgeDistance(target);
            SpecialWeapons.Utility u = SpecialWeapons.utility(true, d, edge, snow.found(), rod.found(), false, weak.found(), slow.found(),
                harm.found(), target.hasEffect(MobEffects.WEAKNESS), target.hasEffect(MobEffects.SLOWNESS), target.isInvertedHealAndHarm());
            switch (u) {
                case SNOWBALL_POKE -> throwAt(target, snow.slot(), Ballistics.Projectile.SNOWBALL, "snowball poke");
                case ROD_KNOCK, ROD_PULL -> throwAt(target, rod.slot(), Ballistics.Projectile.SNOWBALL, "rod");
                case SPLASH_WEAKNESS -> throwAt(target, weak.slot(), Ballistics.Projectile.SPLASH_POTION, "splash weakness");
                case SPLASH_SLOWNESS -> throwAt(target, slow.slot(), Ballistics.Projectile.SPLASH_POTION, "splash slowness");
                case SPLASH_HARMING -> throwAt(target, harm.slot(), Ballistics.Projectile.SPLASH_POTION, "splash harming");
                default -> {
                }
            }
            if (pending != null) return;
        }
        if (cfg.fire()) {
            FindItemResult flint = InvUtils.findInHotbar(Items.FLINT_AND_STEEL);
            FindItemResult lava = InvUtils.findInHotbar(Items.LAVA_BUCKET);
            BlockPos under = target.blockPosition().below();
            boolean tunnel = !mc.level.getBlockState(target.blockPosition().above(2)).isAir();
            boolean flammableNearUs = false;
            for (BlockPos p : BlockPos.betweenClosed(mc.player.blockPosition().offset(-2, -1, -2), mc.player.blockPosition().offset(2, 1, 2))) {
                if (mc.level.getBlockState(p).ignitedByLava()) {
                    flammableNearUs = true;
                    break;
                }
            }
            SpecialWeapons.FireUse f = SpecialWeapons.fire(true, flint.found(), lava.found(), d, target.fireImmune() || target.hasEffect(MobEffects.FIRE_RESISTANCE),
                tunnel, flammableNearUs, true);
            if (f != SpecialWeapons.FireUse.NONE && mc.level.getBlockState(target.blockPosition()).isAir()) {
                int slot = f == SpecialWeapons.FireUse.LAVA_BUCKET ? lava.slot() : flint.slot();
                Vec3 at = Vec3.atCenterOf(under).add(0, 0.5, 0);
                queue(new PendingUse(at, slot, this::useOnLookedBlock, tick + 20, f.name().toLowerCase(Locale.ROOT), under));
            }
        }
    }

    private void throwAt(LivingEntity target, int slot, Ballistics.Projectile pr, String what) {
        if (slot < 0) return;
        Vec3 eye = mc.player.getEyePosition();
        double[] sol = Ballistics.lead(pr, 1, eye.x, eye.y, eye.z, target.getX(), target.getY() + target.getBbHeight() * 0.5, target.getZ(),
            target.getDeltaMovement().x, 0, target.getDeltaMovement().z);
        if (Double.isNaN(sol[1])) return;
        Vec3 dir = Vec3.directionFromRotation((float) sol[1], (float) sol[0]);
        queue(new PendingUse(eye.add(dir.scale(5)), slot, () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND), tick + 20, what, null));
    }

    private double edgeDistance(LivingEntity target) {
        BlockPos f = target.blockPosition();
        double best = 99;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            Hazard h = hazardAt(f.offset(dx, 0, dz));
            if (h == Hazard.DROP || h == Hazard.VOID || h == Hazard.LAVA) best = Math.min(best, Math.hypot(dx, dz));
        }
        return best;
    }

    private static boolean potion(ItemStack s, String effect) {
        var c = s.get(DataComponents.POTION_CONTENTS);
        if (c == null) return false;
        for (var e : c.getAllEffects()) if (e.getEffect().getRegisteredName().contains(effect)) return true;
        return false;
    }

    // ------------------------------------------------------------------ explosives (C71-C80)

    /** C74/C77/C78: enemy crystals / primed TNT that would hurt us badly. */
    private void scanExplosives() {
        explosiveThreat = null;
        if (!cfg.crystalDefense() && !cfg.tntRetreat()) return;
        float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        double worst = 0;
        for (Entity e : mc.level.entitiesForRendering()) {
            double d = e.distanceTo(mc.player);
            if (d > 12) continue;
            if (cfg.crystalDefense() && e instanceof EndCrystal crystal) {
                double dmg = DamageUtils.crystalDamage(mc.player, crystal.position());
                boolean offhandTotem = mc.player.getOffhandItem().is(Items.TOTEM_OF_UNDYING);
                boolean haveTotem = InvUtils.find(Items.TOTEM_OF_UNDYING).found();
                Explosions.Defense def = Explosions.defend(dmg, hp, offhandTotem, haveTotem, cfg.breakCrystals(), distTo(crystal) <= 3.0);
                switch (def) {
                    case TOTEM_SWAP -> {
                        enableTemp(AutoTotem.class);
                        note("crystal: totem to offhand");
                    }
                    case BREAK -> {
                        if (useReady() && mc.player.getAttackStrengthScale(0.5f) > 0.5f) {
                            mc.gameMode.attack(mc.player, crystal);
                            PlayerUtils.punch(true);
                            used("broke an enemy crystal");
                        }
                    }
                    case RETREAT -> {
                        if (dmg > worst) {
                            worst = dmg;
                            explosiveThreat = crystal;
                            explosiveRadius = Explosions.CRYSTAL * 2;
                        }
                    }
                    default -> {
                    }
                }
            } else if (cfg.tntRetreat() && e instanceof PrimedTnt tnt) {
                double r = Explosions.safeRadius(Explosions.TNT, Math.max(2, hp * 0.3), Explosions.Difficulty.HARD);
                if (d < r && tnt.getFuse() > 0) {
                    double dmg = Explosions.raw(Explosions.TNT, d, 1, Explosions.Difficulty.HARD, true);
                    if (dmg > worst) {
                        worst = dmg;
                        explosiveThreat = tnt;
                        explosiveRadius = r + 1;
                    }
                }
            }
        }
    }

    /** Explosive to get away from now (or null) and how far. */
    public @Nullable Entity explosiveThreat() {
        return explosiveThreat;
    }

    public double explosiveRadius() {
        return explosiveRadius;
    }

    /**
     * C71-C76 explosive offence (opt-in): drive Crystal/Anchor/Bed Aura with our self-damage cap and the
     * dimension rules, AutoCity break-in, and cornered self-trap/web.
     */
    public void explosives(@Nullable LivingEntity target, boolean cornered) {
        if (target == null) {
            restoreModules();
            return;
        }
        boolean player = target instanceof Player;
        String dimension = mc.level.dimension().identifier().getPath();
        if (cfg.explosives() && player && mc.player.distanceTo(target) <= 8) {
            if (InvUtils.findInHotbar(Items.END_CRYSTAL).found()) {
                CrystalAura ca = Modules.get().get(CrystalAura.class);
                if (ca != null) {
                    setSetting(ca, "max-damage", cfg.explosiveMaxSelf());
                    setSetting(ca, "anti-suicide", true);
                    setSetting(ca, "min-damage", 6.0);
                    setSetting(ca, "face-place", true);
                    setSetting(ca, "face-place-health", 8.0);
                    enableTemp(CrystalAura.class);
                }
            }
            if (Explosions.anchorExplodes(dimension) && InvUtils.findInHotbar(Items.RESPAWN_ANCHOR).found()) enableTemp(AnchorAura.class);
            if (Explosions.bedExplodes(dimension) && InvUtils.findInHotbar(s -> s.is(net.minecraft.tags.ItemTags.BEDS)).found()) enableTemp(BedAura.class);
        }
        if (cfg.breakIn() && player && mc.player.distanceTo(target) <= 5) {
            boolean surrounded = true;
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                if (mc.level.getBlockState(target.blockPosition().relative(dir)).getBlock().getExplosionResistance() < 600) {
                    surrounded = false;
                    break;
                }
            }
            Explosions.BreakIn bi = Explosions.breakIn(surrounded, InvUtils.findInHotbar(s -> s.is(net.minecraft.tags.ItemTags.PICKAXES)).found(), 3,
                InvUtils.findInHotbar(Items.PISTON, Items.STICKY_PISTON).found(), InvUtils.findInHotbar(Items.ANVIL).found(),
                mc.level.getBlockState(target.blockPosition().above(2)).isAir(), true);
            switch (bi) {
                case MINE -> enableTemp(AutoCity.class);
                case PISTON -> enableTemp(PistonPush.class);
                case ANVIL -> enableTemp(AutoAnvil.class);
                default -> {
                }
            }
        }
        if (cfg.corneredBlocks()) {
            boolean enemyCrystals = player && target.getMainHandItem().is(Items.END_CRYSTAL) || target.getOffhandItem().is(Items.END_CRYSTAL);
            Explosions.Cornered c = Explosions.cornered(cornered, false, InvUtils.findInHotbar(Items.OBSIDIAN).found(),
                InvUtils.findInHotbar(Items.COBWEB).found(), InvUtils.findInHotbar(Items.ANVIL).found(), enemyCrystals);
            switch (c) {
                case SELF_TRAP -> enableTemp(SelfTrap.class);
                case SELF_WEB -> enableTemp(SelfWeb.class);
                case SELF_ANVIL -> enableTemp(SelfAnvil.class);
                default -> {
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> void setSetting(Module m, String name, T value) {
        var s = m.settings.get(name);
        if (s != null) {
            try {
                if (!savedCrystalAura.containsKey(name)) savedCrystalAura.put(name, s.get());
                ((meteordevelopment.meteorclient.settings.Setting<T>) s).set(value);
            } catch (ClassCastException ignored) {
            }
        }
    }

    private void enableTemp(Class<? extends Module> k) {
        Module m = Modules.get().get(k);
        if (m != null && !m.isActive()) {
            m.enable();
            enabledByUs.add(k);
        }
    }

    /** Turn off what the tactics layer turned on. */
    @SuppressWarnings("unchecked")
    public void restoreModules() {
        for (Class<? extends Module> k : enabledByUs) {
            Module m = Modules.get().get(k);
            if (m != null && m.isActive()) m.disable();
        }
        enabledByUs.clear();
        CrystalAura ca = Modules.get() != null ? Modules.get().get(CrystalAura.class) : null;
        if (ca != null) {
            for (var e : savedCrystalAura.entrySet()) {
                var st = ca.settings.get(e.getKey());
                if (st != null) ((meteordevelopment.meteorclient.settings.Setting<Object>) st).set(e.getValue());
            }
        }
        savedCrystalAura.clear();
    }

    // ------------------------------------------------------------------ flee (C48)

    /** C48 break contact while fleeing from {@code pursuer}: close a door behind us, or wall/web the gap (opt-in). */
    public void breakContact(LivingEntity pursuer) {
        if (pending != null || mc.player == null) return;
        Vec3 run = mc.player.getDeltaMovement().multiply(1, 0, 1);
        if (run.lengthSqr() < 1e-4) run = mc.player.position().subtract(pursuer.position()).multiply(1, 0, 1);
        if (run.lengthSqr() < 1e-6) return;
        Vec3 dir = run.normalize();
        Vec3 toP = pursuer.position().subtract(mc.player.position()).multiply(1, 0, 1);
        boolean behind = toP.lengthSqr() > 1e-6 && dir.dot(toP.normalize()) < -0.3;
        BlockPos door = null;
        for (int k = 1; k <= 2 && door == null; k++) {
            BlockPos p = BlockPos.containing(mc.player.position().subtract(dir.scale(k)));
            for (BlockPos q : new BlockPos[]{p, p.above()}) {
                BlockState s = mc.level.getBlockState(q);
                if ((s.is(BlockTags.WOODEN_DOORS) || s.is(BlockTags.FENCE_GATES) || s.is(BlockTags.WOODEN_TRAPDOORS))
                    && s.hasProperty(BlockStateProperties.OPEN) && s.getValue(BlockStateProperties.OPEN)
                    && mc.player.getEyePosition().distanceTo(Vec3.atCenterOf(q)) <= 4.0) {
                    door = q;
                    break;
                }
            }
        }
        BlockPos gap = BlockPos.containing(mc.player.position().subtract(dir.scale(1.6)));
        boolean gapOk = mc.level.getBlockState(gap).canBeReplaced() && !mc.level.getBlockState(gap.below()).getCollisionShape(mc.level, gap.below()).isEmpty()
            && !mc.player.getBoundingBox().intersects(new AABB(gap)) && !pursuer.getBoundingBox().intersects(new AABB(gap));
        String pid = BuiltInRegistries.ENTITY_TYPE.getKey(pursuer.getType()).getPath();
        FindItemResult web = InvUtils.findInHotbar(Items.COBWEB);
        FindItemResult obby = InvUtils.findInHotbar(Items.OBSIDIAN, Items.CRYING_OBSIDIAN);
        FindItemResult block = InvUtils.findInHotbar(s -> s.getItem() instanceof net.minecraft.world.item.BlockItem bi
            && bi.getBlock().defaultBlockState().isCollisionShapeFullBlock(mc.level, BlockPos.ZERO) && !s.is(Items.TNT));
        BreakContact.Action a = BreakContact.decide(new BreakContact.Input(door != null, pid.equals("zombie") || pid.equals("vindicator"),
            gapOk, mc.player.distanceTo(pursuer), behind, pursuer instanceof Player, web.found(), obby.found(), block.found(),
            cfg.fleeWalls(), (int) (tick - lastUseTick), pacer.useGap(uses)));
        switch (a) {
            case CLOSE_DOOR -> {
                if (cfg.closeDoors()) queue(new PendingUse(Vec3.atCenterOf(door), -1, this::useOnLookedBlock, tick + 12, "closed a door", door));
            }
            case PLACE_WEB -> placeAt(gap, web.slot(), "web behind");
            case PLACE_BLOCK -> placeAt(gap, obby.found() ? obby.slot() : block.slot(), "wall behind");
            default -> {
            }
        }
    }

    private void placeAt(BlockPos gap, int slot, String what) {
        if (slot < 0) return;
        BlockPos floor = gap.below();
        queue(new PendingUse(Vec3.atCenterOf(floor).add(0, 0.5, 0), slot, this::useOnLookedBlock, tick + 12, what, floor));
    }

    // ------------------------------------------------------------------ territory (C90)

    private double[] baseCenter() {
        String[] p = cfg.territoryCenter().trim().split("[,;\\s]+");
        if (p.length < 2) return null;
        try {
            return p.length >= 3 ? new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[2])} : new double[]{Double.parseDouble(p[0]), Double.parseDouble(p[1])};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * C90 territory defence: returns an intruder to engage (after the warn period), or null. Patrol is left to
     * Combat Mode's idle scan; warnings are local chat lines via {@code warn}.
     */
    public @Nullable Player intruder(java.util.function.Consumer<String> warn) {
        if (!cfg.territory()) return null;
        double[] c = baseCenter();
        if (c == null) return null;
        Player engage = null;
        Set<UUID> seen = new HashSet<>();
        for (Player p : mc.level.players()) {
            if (p == mc.player || !p.isAlive() || p.isCreative() || p.isSpectator()) continue;
            double d = Math.hypot(p.getX() - c[0], p.getZ() - c[1]);
            Allegiance.Standing s = standing(p);
            int inside = intruderTicks.getOrDefault(p.getUUID(), 0);
            Allegiance.Defend act = Allegiance.territory(true, d, cfg.territoryRadius(), s, inside, cfg.warnSeconds() * 20, false);
            if (act == Allegiance.Defend.WARN || act == Allegiance.Defend.ENGAGE) {
                seen.add(p.getUUID());
                intruderTicks.put(p.getUUID(), inside + 1);
                if (inside == 0) warn.accept("Intruder in territory: " + p.getName().getString());
                if (act == Allegiance.Defend.ENGAGE && engage == null) engage = p;
            }
        }
        intruderTicks.keySet().retainAll(seen);
        return engage;
    }

    // ------------------------------------------------------------------ look-then-act machinery (CameraArbiter + pacing)

    private boolean useReady() {
        return tick - lastUseTick >= pacer.useGap(uses);
    }

    private void used(String what) {
        lastUseTick = tick;
        uses++;
        note(what);
    }

    private void note(String what) {
        lastAction = what;
    }

    private void queue(PendingUse p) {
        if (pending == null) pending = p;
    }

    private void tickPending() {
        PendingUse p = pending;
        if (p == null) {
            if (restoreSlot >= 0 && mc.player.getInventory().getSelectedSlot() != restoreSlot) {
                int next = pacer.nextSlot(mc.player.getInventory().getSelectedSlot(), restoreSlot);
                InvUtils.swap(next, false);
                if (next == restoreSlot) restoreSlot = -1;
            } else restoreSlot = -1;
            return;
        }
        if (tick > p.deadline()) {
            pending = null;
            return;
        }
        float yaw = (float) Rotations.getYaw(p.at()), pitch = (float) Rotations.getPitch(p.at());
        CameraArbiter.claim(camOwner, CameraArbiter.PRIORITY_DEFENSE, yaw, pitch, true);
        int cur = mc.player.getInventory().getSelectedSlot();
        if (p.slot() >= 0 && cur != p.slot()) {
            if (restoreSlot < 0) restoreSlot = cur;
            InvUtils.swap(pacer.nextSlot(cur, p.slot()), false);
            return;
        }
        boolean aimed = Math.abs(Mth.wrapDegrees(mc.player.getYRot() - yaw)) < 4f && Math.abs(mc.player.getXRot() - pitch) < 4f;
        if (!aimed) return;
        if (p.blockHit() != null) {
            HitResult hr = mc.hitResult;
            if (!(hr instanceof BlockHitResult bhr) || !bhr.getBlockPos().equals(p.blockHit())) return;
        }
        p.act().run();
        pending = null;
        used(p.what());
    }

    /** Right-click the block under the crosshair (door, floor for placement, fire). */
    private void useOnLookedBlock() {
        if (mc.hitResult instanceof BlockHitResult bhr) BlockUtils.interact(bhr, InteractionHand.MAIN_HAND, true);
    }

    private static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    private static int ench(ItemStack s, net.minecraft.resources.ResourceKey<net.minecraft.world.item.enchantment.Enchantment> k) {
        return Utils.getEnchantmentLevel(s, k);
    }
}
