/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import com.google.gson.JsonParseException;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.systems.modules.combat.model.*;
import meteordevelopment.meteorclient.utils.Utils;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * In-world adapter for the P1 opponent models (C11-C20): scans gear, feeds the skill / motion / intent / ledger /
 * consumable / swap / ping models per opponent each tick, and persists what was learned (decision 4) to
 * {@code meteor-client/combat-learning.json}.
 */
public final class OpponentTracker {
    private static final long FORGET_TICKS = 20 * 60;
    private final Map<UUID, OpponentModel> models = new HashMap<>();
    private final Map<UUID, Boolean> wasSwinging = new HashMap<>();
    private final Map<UUID, Long> lastSwingTick = new HashMap<>();
    private final Map<UUID, Float> lastHealth = new HashMap<>();
    private final OpponentMemory memory = new OpponentMemory();
    private final File file = new File(MeteorClient.FOLDER, "combat-learning.json");
    private final PingModel myPing = new PingModel();
    private float myLastHealth = -1;
    private boolean loaded;

    public OpponentModel track(LivingEntity e, long tick) {
        boolean player = e instanceof Player;
        OpponentModel m = models.computeIfAbsent(e.getUUID(), id -> {
            OpponentModel nm = new OpponentModel(e.getName().getString(), player);
            if (player) nm.skill.seed(memory.skillPrior(nm.id));
            return nm;
        });
        m.lastSeenTick = tick;
        String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
        m.mob = player ? MobProfile.GENERIC : MobProfile.of(typeId);
        m.gear = scanGear(e);
        m.motion.update(tick, e.getX(), e.getY(), e.getZ());
        m.swaps.observe(m.gear.mainHand(), tick);
        m.consumables.onAbsorption(e.getAbsorptionAmount(), tick);

        if (mc.player != null) {
            UUID id = e.getUUID();
            // ping (C19)
            if (mc.getConnection() != null) {
                PlayerInfo mine = mc.getConnection().getPlayerInfo(mc.player.getUUID());
                if (mine != null) myPing.sample(mine.getLatency());
                if (player) {
                    PlayerInfo theirs = mc.getConnection().getPlayerInfo(id);
                    if (theirs != null) m.ping.sample(theirs.getLatency());
                }
            }

            // skill samples (C12): swing timing vs their attack cooldown, strafing, crits
            boolean swinging = e.isSwinging();
            boolean wasSw = wasSwinging.getOrDefault(id, false);
            if (swinging && !wasSw) {
                long prev = lastSwingTick.getOrDefault(id, Long.MIN_VALUE / 2);
                double cooldownTicks = 20.0 / Math.max(0.5, player ? attackSpeed(e) : 1.0);
                m.skill.onSwing(Math.min(1.0, (tick - prev) / cooldownTicks));
                lastSwingTick.put(id, tick);
            }
            wasSwinging.put(id, swinging);
            double[] v = m.motion.velocity();
            double tx = mc.player.getX() - e.getX(), tz = mc.player.getZ() - e.getZ();
            double len = Math.hypot(tx, tz);
            if (len > 1e-3 && len < 6 && tick % 4 == 0) {
                double lateral = Math.abs(v[0] * (-tz / len) + v[2] * (tx / len));
                m.skill.onStrafeSample(lateral);
            }

            // hit ledger (C15): our health drop while they're our attacker, their health drop after our hits
            float myHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (myLastHealth >= 0 && myHp < myLastHealth && mc.player.getLastHurtByMob() == e && mc.player.hurtTime > 0) {
                m.ledger.taken(myLastHealth - myHp, tick);
                boolean crit = e.fallDistance > 0 && !e.onGround();
                m.skill.onHitLanded(crit);
            }
            float theirHp = e.getHealth() + e.getAbsorptionAmount();
            Float prevHp = lastHealth.get(id);
            if (prevHp != null && theirHp < prevHp && mc.player.getLastHurtMob() == e) m.ledger.dealt(prevHp - theirHp, tick);
            lastHealth.put(id, theirHp);

            // intent (C14)
            Vec3 look = e.getLookAngle();
            double dist = mc.player.distanceTo(e);
            double facing = len > 1e-3 ? (look.x * tx + look.z * tz) / len : 0;
            int crystals = mc.level == null ? 0 : mc.level.getEntitiesOfClass(EndCrystal.class, mc.player.getBoundingBox().inflate(6)).size();
            m.intent = Intent.classify(new Intent.Features(dist, m.motion.closingSpeed(mc.player.getX(), mc.player.getZ()), facing,
                v[1], false, crystals, e.isInWall(), m.motion.horizontalSpeed() < 0.01 ? (int) Math.min(1000, tick - lastSwingTick.getOrDefault(id, tick)) : 0,
                m.gear.mainHand().ranged()));
        }
        return m;
    }

    /** Called once per tick after all targets were tracked. */
    public void endTick(long tick) {
        if (mc.player != null) myLastHealth = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        if (tick % 200 == 0) {
            models.entrySet().removeIf(en -> tick - en.getValue().lastSeenTick > FORGET_TICKS);
            lastHealth.keySet().removeIf(k -> !models.containsKey(k));
            wasSwinging.keySet().removeIf(k -> !models.containsKey(k));
            lastSwingTick.keySet().removeIf(k -> !models.containsKey(k));
        }
    }

    public @Nullable OpponentModel get(LivingEntity e) {
        return models.get(e.getUUID());
    }

    public void onPop(LivingEntity e, long tick) {
        OpponentModel m = models.get(e.getUUID());
        if (m != null) m.consumables.onTotemPop(tick);
    }

    public void onPearl(LivingEntity thrower, long tick, boolean towardsUs) {
        OpponentModel m = models.get(thrower.getUUID());
        if (m != null) {
            m.consumables.onPearlThrown(tick);
            m.skill.onPearlUsed(towardsUs);
        }
    }

    public PingModel myPing() {
        return myPing;
    }

    public OpponentMemory memory() {
        return memory;
    }

    public double learnedBias(OpponentModel m) {
        return m.player ? memory.ratioBias(m.id) : 1.0;
    }

    /** Fight ended: store the outcome and the latest skill estimate. */
    public void recordOutcome(OpponentModel m, OpponentMemory.Outcome outcome, boolean persist) {
        if (!m.player) return;
        long now = System.currentTimeMillis();
        memory.recordSkill(m.id, m.skill.skill(), now);
        memory.recordOutcome(m.id, outcome, now);
        if (persist) save();
    }

    public void load() {
        if (loaded) return;
        loaded = true;
        if (!file.exists()) return;
        try (Reader r = Files.newBufferedReader(file.toPath(), StandardCharsets.UTF_8)) {
            memory.read(r);
        } catch (IOException | JsonParseException | IllegalStateException ex) {
            MeteorClient.LOG.warn("CombatBrain: could not read {}: {}", file, ex.toString());
        }
    }

    public void save() {
        if (!memory.dirty()) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) return;
            try (Writer w = Files.newBufferedWriter(file.toPath(), StandardCharsets.UTF_8)) {
                memory.write(w);
            }
        } catch (IOException ex) {
            MeteorClient.LOG.warn("CombatBrain: could not write {}: {}", file, ex.toString());
        }
    }

    public void clear() {
        models.clear();
        wasSwinging.clear();
        lastSwingTick.clear();
        lastHealth.clear();
        myLastHealth = -1;
    }

    private static double attackSpeed(LivingEntity e) {
        try {
            return e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_SPEED);
        } catch (RuntimeException ex) {
            return 1.6;
        }
    }

    static String id(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /** Gear scan (C11). */
    public static OpponentGear scanGear(LivingEntity e) {
        EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        ArrayList<OpponentGear.Piece> armor = new ArrayList<>(4);
        boolean elytra = false;
        for (EquipmentSlot slot : slots) {
            ItemStack s = e.getItemBySlot(slot);
            if (s.isEmpty()) {
                armor.add(null);
                continue;
            }
            if (s.is(Items.ELYTRA)) elytra = true;
            double dur = s.isDamageableItem() && s.getMaxDamage() > 0 ? 1.0 - (double) s.getDamageValue() / s.getMaxDamage() : 1.0;
            armor.add(new OpponentGear.Piece(id(s), OpponentGear.Tier.fromItemId(id(s)), dur,
                Utils.getEnchantmentLevel(s, Enchantments.PROTECTION),
                Utils.getEnchantmentLevel(s, Enchantments.BLAST_PROTECTION),
                Utils.getEnchantmentLevel(s, Enchantments.PROJECTILE_PROTECTION),
                Utils.getEnchantmentLevel(s, Enchantments.FIRE_PROTECTION),
                Utils.getEnchantmentLevel(s, Enchantments.MENDING) > 0));
        }
        ItemStack main = e.getMainHandItem();
        ItemStack off = e.getOffhandItem();
        String mainId = main.isEmpty() ? "" : id(main);
        return new OpponentGear(armor, main.isEmpty() ? WeaponKind.NONE : WeaponKind.fromItemId(mainId), mainId,
            off.isEmpty() ? WeaponKind.NONE : WeaponKind.fromItemId(id(off)), elytra,
            Utils.getEnchantmentLevel(main, Enchantments.SHARPNESS), Utils.getEnchantmentLevel(main, Enchantments.KNOCKBACK),
            Utils.getEnchantmentLevel(main, Enchantments.FIRE_ASPECT), Utils.getEnchantmentLevel(main, Enchantments.BREACH),
            Utils.getEnchantmentLevel(main, Enchantments.DENSITY));
    }
}
