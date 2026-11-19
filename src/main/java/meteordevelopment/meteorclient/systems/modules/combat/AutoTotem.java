/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.friends.Friends;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.DamageUtils;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.PlayerUtils;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EndCrystal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public class AutoTotem extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Mode> mode = sgGeneral.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("Determines when to hold a totem, strict will always hold.")
        .defaultValue(Mode.Smart)
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("The ticks between slot movements.")
        .defaultValue(0)
        .min(0)
        .build()
    );

    private final Setting<Integer> health = sgGeneral.add(new IntSetting.Builder()
        .name("health")
        .description("The health to hold a totem at.")
        .defaultValue(10)
        .range(0, 36)
        .sliderMax(36)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> elytra = sgGeneral.add(new BoolSetting.Builder()
        .name("elytra")
        .description("Will always hold a totem when flying with elytra.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> fall = sgGeneral.add(new BoolSetting.Builder()
        .name("fall")
        .description("Will hold a totem when fall damage could kill you.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> explosion = sgGeneral.add(new BoolSetting.Builder()
        .name("explosion")
        .description("Will hold a totem when explosion damage could kill you.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    private final Setting<Boolean> chainCrystalPredict = sgGeneral.add(new BoolSetting.Builder()
        .name("chain-crystal-predict")
        .description("Locks a totem when combined top crystal damage plus enemy-placeable crystal threat is lethal.")
        .defaultValue(true)
        .visible(() -> mode.get() == Mode.Smart)
        .build()
    );

    public boolean locked;
    private int totems, ticks;

    public AutoTotem() {
        super(Categories.Combat, "auto-totem", "Automatically equips a totem in your offhand.");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onTick(TickEvent.Pre event) {
        FindItemResult result = InvUtils.find(Items.TOTEM_OF_UNDYING);
        totems = result.count();

        if (totems <= 0) locked = false;
        else if (ticks >= delay.get()) {
            float totalHp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            boolean low = totalHp - PlayerUtils.possibleHealthReductions(explosion.get(), fall.get()) <= health.get();
            boolean chainLethal = chainCrystalPredict.get() && (totalHp - predictChainCrystalDamage() <= health.get());
            boolean ely = elytra.get() && mc.player.getItemBySlot(EquipmentSlot.CHEST).getItem() == Items.ELYTRA && mc.player.isFallFlying();

            locked = mode.get() == Mode.Strict || (mode.get() == Mode.Smart && (low || chainLethal || ely));

            if (locked && mc.player.getOffhandItem().getItem() != Items.TOTEM_OF_UNDYING) {
                InvUtils.move().from(result.slot()).toOffhand();
            }

            ticks = 0;
            return;
        }

        ticks++;
    }

    public static float predictChainCrystalDamage() {
        var client = MeteorClient.mc;
        if (client.player == null || client.level == null) return 0f;

        Vec3 playerPos = client.player.position();
        AABB playerBox = client.player.getBoundingBox();

        float top1 = 0f;
        float top2 = 0f;
        for (Entity entity : ((LevelAccessor) client.level).meteor$getEntityLookup().getAll()) {
            if (!(entity instanceof EndCrystal) || !entity.isAlive()) continue;
            if (entity.distanceToSqr(client.player) > 144.0) continue;
            float dmg = DamageUtils.crystalDamage(client.player, playerPos, playerBox, entity.position(), DamageUtils.HIT_FACTORY);
            if (dmg > top1) {
                top2 = top1;
                top1 = dmg;
            } else if (dmg > top2) {
                top2 = dmg;
            }
        }

        List<Player> nearbyEnemies = new ArrayList<>(4);
        for (Player player : client.level.players()) {
            if (player == client.player || player.isDeadOrDying() || !player.isAlive() || player.isCreative()) continue;
            if (!Friends.get().shouldAttack(player)) continue;
            if (player.distanceToSqr(client.player) <= 100.0) {
                nearbyEnemies.add(player);
                if (nearbyEnemies.size() >= 4) break;
            }
        }

        float bestPotentialDmg = 0f;
        if (!nearbyEnemies.isEmpty()) {
            float diffScale = client.level.getDifficulty() == Difficulty.HARD ? 1.5f : 1.0f;
            BlockPos center = client.player.blockPosition();
            BlockPos.MutableBlockPos basePos = new BlockPos.MutableBlockPos();
            BlockPos.MutableBlockPos crystalPos = new BlockPos.MutableBlockPos();
            for (int dx = -5; dx <= 5; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -5; dz <= 5; dz++) {
                        basePos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                        Block baseBlock = client.level.getBlockState(basePos).getBlock();
                        if (baseBlock != Blocks.OBSIDIAN && baseBlock != Blocks.BEDROCK) continue;

                        crystalPos.set(basePos.getX(), basePos.getY() + 1, basePos.getZ());
                        if (!client.level.isEmptyBlock(crystalPos)) continue;

                        double cx = basePos.getX() + 0.5;
                        double cy = basePos.getY() + 1.0;
                        double cz = basePos.getZ() + 0.5;
                        double distToPlayerSq = playerPos.distanceToSqr(cx, cy, cz);
                        if (distToPlayerSq > 144.0) continue;

                        double distToPlayer = Math.sqrt(distToPlayerSq);
                        double maxImpact = Math.max(0.0, 1.0 - (distToPlayer / 12.0));
                        float maxRaw = (float) (((maxImpact * maxImpact + maxImpact) * 42.0 + 1.0) * diffScale);
                        if (maxRaw <= bestPotentialDmg) continue;

                        boolean enemyInReach = false;
                        for (Player enemy : nearbyEnemies) {
                            Vec3 eyes = enemy.getEyePosition();
                            if (eyes.distanceToSqr(cx, cy, cz) <= 25.0) {
                                enemyInReach = true;
                                break;
                            }
                        }
                        if (!enemyInReach) continue;

                        float dmg = DamageUtils.crystalDamage(client.player, playerPos, playerBox, new Vec3(cx, cy, cz), DamageUtils.HIT_FACTORY);
                        if (dmg > bestPotentialDmg) {
                            bestPotentialDmg = dmg;
                        }
                    }
                }
            }
        }

        return top1 + top2 + bestPotentialDmg;
    }

    @EventHandler(priority = EventPriority.HIGH)
    private void onReceivePacket(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundEntityEventPacket p)) return;
        if (p.getEventId() != EntityEvent.PROTECTED_FROM_DEATH) return;

        Entity entity = p.getEntity(mc.level);
        if (entity == null || !(entity.equals(mc.player))) return;

        ticks = 0;
    }

    public boolean isLocked() {
        return isActive() && locked;
    }

    @Override
    public String getInfoString() {
        return String.valueOf(totems);
    }

    public enum Mode {
        Smart,
        Strict
    }
}
