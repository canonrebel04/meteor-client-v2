/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player.autofood.tasks;

import meteordevelopment.meteorclient.mixin.LevelAccessor;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodContext;
import meteordevelopment.meteorclient.systems.modules.player.autofood.FoodEvaluator;
import meteordevelopment.meteorclient.utils.bot.BotTask;
import meteordevelopment.meteorclient.utils.bot.TaskStatus;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.EntityHitResult;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Trades emeralds with nearby Farmer or Butcher villagers for high-value food.
 */
public class VillagerTradeTask implements BotTask<FoodContext> {
    private Villager targetVillager;
    private int cooldown;
    private int ticks;

    @Override
    public String name() {
        return "Trading with villager";
    }

    @Override
    public double score(FoodContext ctx) {
        if (!ctx.tradeVillagers || mc.player == null || mc.level == null) return 0.0;
        if (!ctx.needsMoreFood()) return 0.0;
        if (!InvUtils.find(Items.EMERALD).found()) return 0.0;

        Villager villager = findFoodVillager(ctx);
        if (villager == null) return 0.0;
        double dist = Math.sqrt(villager.distanceToSqr(mc.player));
        return Math.max(15.0, 64.0 - dist * 0.6);
    }

    @Override
    public void onStart(FoodContext ctx) {
        targetVillager = findFoodVillager(ctx);
        cooldown = 0;
        ticks = 0;
        ctx.navigator.resetProgress();
    }

    @Override
    public TaskStatus tick(FoodContext ctx) {
        ticks++;
        if (mc.player == null || mc.level == null || mc.gameMode == null) return TaskStatus.FAILED;
        if (ticks > 280 || targetVillager == null || !targetVillager.isAlive()) return TaskStatus.FAILED;

        if (cooldown > 0) {
            cooldown--;
            return TaskStatus.RUNNING;
        }

        if (mc.player.containerMenu instanceof MerchantMenu menu) {
            ctx.navigator.stop();
            MerchantOffers offers = menu.getOffers();
            for (int i = 0; i < offers.size(); i++) {
                MerchantOffer offer = offers.get(i);
                if (offer.isOutOfStock()) continue;
                ItemStack result = offer.getResult();
                if (FoodEvaluator.scoreFood(result) >= 5.0 && offer.getBaseCostA().is(Items.EMERALD)
                    && InvUtils.find(Items.EMERALD).count() >= offer.getCostA().getCount()) {
                    if (mc.getConnection() != null) {
                        mc.getConnection().send(new ServerboundSelectTradePacket(i));
                    }
                    InvUtils.shiftClick().slotId(2);
                    cooldown = Math.max(3, ctx.actionDelayTicks + 1);
                    return TaskStatus.RUNNING;
                }
            }
            mc.player.closeContainer();
            return TaskStatus.SUCCEEDED;
        }

        ctx.renderEntityTarget = targetVillager;
        if (mc.player.distanceToSqr(targetVillager) > 12.0) {
            if (ctx.navigator.isStuck(ctx.stuckTimeoutTicks)) {
                ctx.navigator.blacklistEntity(targetVillager.getId(), 600);
                return TaskStatus.FAILED;
            }
            ctx.navigator.followEntity(targetVillager);
            return TaskStatus.RUNNING;
        }

        ctx.navigator.stop();
        Villager v = targetVillager;
        Rotations.rotate(Rotations.getYaw(v), Rotations.getPitch(v), 55, () -> {
            if (mc.gameMode != null && mc.player != null && v.isAlive()) {
                mc.gameMode.interact(mc.player, v, new EntityHitResult(v), InteractionHand.MAIN_HAND);
            }
        });
        cooldown = Math.max(6, ctx.actionDelayTicks + 4);
        return TaskStatus.RUNNING;
    }

    @Override
    public void onStop(FoodContext ctx) {
        ctx.navigator.stop();
        if (mc.player != null && mc.player.containerMenu instanceof MerchantMenu) {
            mc.player.closeContainer();
        }
        targetVillager = null;
        ctx.clearTargets();
    }

    private Villager findFoodVillager(FoodContext ctx) {
        Villager best = null;
        double bestDistSq = (double) ctx.searchRadius * ctx.searchRadius;
        for (Entity entity : ((LevelAccessor) mc.level).meteor$getEntityLookup().getAll()) {
            if (!(entity instanceof Villager villager) || !villager.isAlive() || villager.isBaby()) continue;
            if (ctx.navigator.isEntityBlacklisted(villager.getId())) continue;
            var prof = villager.getVillagerData().profession().unwrapKey().orElse(null);
            if (prof != VillagerProfession.FARMER && prof != VillagerProfession.BUTCHER) continue;
            double dSq = villager.distanceToSqr(mc.player);
            if (dSq < bestDistSq) {
                bestDistSq = dSq;
                best = villager;
            }
        }
        return best;
    }
}
