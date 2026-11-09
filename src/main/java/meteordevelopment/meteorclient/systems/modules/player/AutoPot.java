/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.player;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;

/**
 * Heals with splash potions of healing (thrown at your feet) or soup (instant-use soup servers)
 * when your health drops below a threshold.
 */
public class AutoPot extends Module {
    public enum Action { NONE, SOUP, SPLASH }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Double> health = sgGeneral.add(new DoubleSetting.Builder()
        .name("health").description("Heal when health (incl. absorption) is at or below this.").defaultValue(10).range(1, 36).sliderRange(1, 20).build());

    private final Setting<Boolean> splash = sgGeneral.add(new BoolSetting.Builder()
        .name("splash-healing").description("Throw splash potions of healing.").defaultValue(true).build());

    private final Setting<Boolean> soup = sgGeneral.add(new BoolSetting.Builder()
        .name("soup").description("Use mushroom / rabbit / beetroot stew (for soup-PvP servers).").defaultValue(false).build());

    private final Setting<Boolean> dropBowls = sgGeneral.add(new BoolSetting.Builder()
        .name("drop-bowls").description("Drop empty bowls after using soup.").defaultValue(true).visible(soup::get).build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between heals.").defaultValue(10).range(0, 40).sliderRange(0, 40).build());

    private final Setting<Boolean> onlyOnGround = sgGeneral.add(new BoolSetting.Builder()
        .name("splash-only-on-ground").description("Only throw splash potions while on the ground so they land on you.").defaultValue(true).visible(splash::get).build());

    private int timer;

    public AutoPot() {
        super(Categories.Player, "auto-pot", "Automatically heals with splash potions or soup.");
    }

    /** Pure decision: soup is preferred (instant, no travel time) when both are enabled and available. */
    public static Action decide(double hp, double threshold, int cooldown, boolean soupReady, boolean splashReady) {
        if (cooldown > 0 || hp > threshold) return Action.NONE;
        if (soupReady) return Action.SOUP;
        if (splashReady) return Action.SPLASH;
        return Action.NONE;
    }

    @Override
    public void onActivate() {
        timer = 0;
    }

    private static boolean isHealingSplash(ItemStack s) {
        if (s.getItem() != Items.SPLASH_POTION) return false;
        PotionContents c = s.get(DataComponents.POTION_CONTENTS);
        if (c == null) return false;
        for (MobEffectInstance e : c.getAllEffects()) {
            if (e.getEffect().equals(MobEffects.INSTANT_HEALTH)) return true;
        }
        return false;
    }

    private static boolean isSoup(ItemStack s) {
        return s.getItem() == Items.MUSHROOM_STEW || s.getItem() == Items.RABBIT_STEW || s.getItem() == Items.BEETROOT_SOUP;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.gameMode == null) return;
        if (timer > 0) timer--;

        if (soup.get() && dropBowls.get()) {
            FindItemResult bowl = InvUtils.findInHotbar(Items.BOWL);
            if (bowl.found() && !bowl.isOffhand()) InvUtils.drop().slot(bowl.slot());
        }

        FindItemResult soupItem = soup.get() ? InvUtils.findInHotbar(AutoPot::isSoup) : null;
        FindItemResult potItem = splash.get() ? InvUtils.findInHotbar(AutoPot::isHealingSplash) : null;
        boolean splashOk = potItem != null && potItem.found() && (!onlyOnGround.get() || mc.player.onGround());

        double hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
        Action a = decide(hp, health.get(), timer, soupItem != null && soupItem.found(), splashOk);

        switch (a) {
            case SOUP -> use(soupItem);
            case SPLASH -> Rotations.rotate(mc.player.getYRot(), 90, () -> use(potItem));
            default -> { return; }
        }
        timer = delay.get();
    }

    private void use(FindItemResult item) {
        if (item.isOffhand()) {
            mc.gameMode.useItem(mc.player, InteractionHand.OFF_HAND);
            mc.player.swing(InteractionHand.OFF_HAND);
        } else {
            InvUtils.swap(item.slot(), true);
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
            mc.player.swing(InteractionHand.MAIN_HAND);
            InvUtils.swapBack();
        }
    }
}
