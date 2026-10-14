/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.world.BlockUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundMerchantOffersPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.entity.npc.villager.VillagerProfession;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.EntityHitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Rerolls a librarian's enchanted-book trade: open trades, check for a wanted book, otherwise break
 * and re-place the lectern and try again. Stops (never buys) when a match is found.
 */
public class AutoLibrarian extends Module {
    private enum Stage { OPEN, WAIT_OFFERS, BREAK, PLACE, WAIT_JOB }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Set<ResourceKey<Enchantment>>> wanted = sgGeneral.add(new EnchantmentListSetting.Builder()
        .name("wanted").description("Enchantments to stop on.").defaultValue(Enchantments.MENDING).build());

    private final Setting<Integer> maxPrice = sgGeneral.add(new IntSetting.Builder()
        .name("max-price").description("Maximum emerald price.").defaultValue(64).range(1, 64).sliderRange(1, 64).build());

    private final Setting<Boolean> requireMaxLevel = sgGeneral.add(new BoolSetting.Builder()
        .name("require-max-level").description("Only accept max-level books.").defaultValue(true).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").description("Search range for villager and lectern.").defaultValue(4).range(2, 6).sliderRange(2, 6).build());

    private final Setting<Integer> jobTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("job-timeout").description("Ticks to wait for the villager to take the lectern before rerolling again.").defaultValue(80).range(20, 400).sliderRange(20, 200).build());

    private final Setting<Integer> maxAttempts = sgGeneral.add(new IntSetting.Builder()
        .name("max-attempts").description("Give up after this many rerolls (0 = unlimited).").defaultValue(0).range(0, 10000).sliderRange(0, 500).build());

    private Stage stage;
    private Villager villager;
    private BlockPos lectern;
    private int timer, attempts;
    private List<MerchantOffer> pendingOffers;
    private int pendingXp;

    public AutoLibrarian() {
        super(Categories.World, "auto-librarian", "Rerolls a librarian until it offers a wanted enchanted book.");
    }

    @Override
    public void onActivate() {
        stage = Stage.OPEN;
        villager = null;
        lectern = null;
        timer = 0;
        attempts = 0;
        pendingOffers = null;
        if (!InvUtils.findInHotbar(Items.LECTERN).found()) {
            error("Put a lectern in your hotbar.");
            toggle();
        }
    }

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundMerchantOffersPacket p && stage == Stage.WAIT_OFFERS) {
            pendingOffers = new ArrayList<>(p.getOffers());
            pendingXp = p.getVillagerXp();
        }
    }

    private boolean isLibrarian(Villager v) {
        return v.getVillagerData().profession().is(VillagerProfession.LIBRARIAN);
    }

    private Villager findVillager() {
        Villager best = null;
        double bestD = range.get() * range.get();
        for (Entity e : mc.level.entitiesForRendering()) {
            if (!(e instanceof Villager v) || v.isBaby()) continue;
            double d = mc.player.distanceToSqr(v);
            if (d <= bestD) { bestD = d; best = v; }
        }
        return best;
    }

    private BlockPos findLectern() {
        int r = (int) Math.ceil(range.get());
        BlockPos origin = villager != null ? villager.blockPosition() : mc.player.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(origin.offset(-r, -2, -r), origin.offset(r, 2, r))) {
            if (mc.level.getBlockState(p).getBlock() != Blocks.LECTERN) continue;
            if (mc.player.getEyePosition().distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p)) > range.get() * range.get() + 4) continue;
            double d = origin.distSqr(p);
            if (d < bestD) { bestD = d; best = p.immutable(); }
        }
        return best;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.level == null || mc.gameMode == null) return;
        if (timer > 0) { timer--; return; }

        switch (stage) {
            case OPEN -> {
                villager = findVillager();
                if (villager == null) { error("No villager in range."); toggle(); return; }
                if (lectern == null) lectern = findLectern();
                if (lectern == null) { error("No lectern near the villager."); toggle(); return; }
                if (!isLibrarian(villager)) { stage = Stage.WAIT_JOB; timer = 0; return; }
                pendingOffers = null;
                mc.gameMode.interact(mc.player, villager, new EntityHitResult(villager, villager.getBoundingBox().getCenter()), InteractionHand.MAIN_HAND);
                stage = Stage.WAIT_OFFERS;
                timer = 40;
            }
            case WAIT_OFFERS -> {
                if (pendingOffers == null) { stage = Stage.OPEN; return; } // timed out, retry
                if (pendingXp > 0) {
                    error("This villager has already been traded with; its trades are locked.");
                    mc.player.closeContainer();
                    toggle();
                    return;
                }
                AutoLibrarianLogic.BookOffer match = AutoLibrarianLogic.findMatch(toBookOffers(pendingOffers), wantedIds(), maxPrice.get(), requireMaxLevel.get());
                if (match != null) {
                    info("Found (highlight)%s %d(default) for (highlight)%d(default) emeralds after %d reroll(s).", match.enchantment(), match.level(), match.price(), attempts);
                    toggle();
                    return;
                }
                mc.player.closeContainer();
                attempts++;
                if (maxAttempts.get() > 0 && attempts >= maxAttempts.get()) { warning("Gave up after %d rerolls.", attempts); toggle(); return; }
                stage = Stage.BREAK;
            }
            case BREAK -> {
                if (mc.level.getBlockState(lectern).isAir()) { stage = Stage.PLACE; timer = 2; return; }
                BlockUtils.breakBlock(lectern, true);
            }
            case PLACE -> {
                FindItemResult lec = InvUtils.findInHotbar(Items.LECTERN);
                if (!lec.found()) { error("Out of lecterns."); toggle(); return; }
                if (BlockUtils.place(lectern, lec, true, 0)) {
                    stage = Stage.WAIT_JOB;
                    timer = 0;
                    jobWait = 0;
                }
            }
            case WAIT_JOB -> {
                if (villager == null || !villager.isAlive()) { stage = Stage.OPEN; return; }
                if (isLibrarian(villager)) { stage = Stage.OPEN; timer = 5; return; }
                if (++jobWait > jobTimeout.get()) { stage = Stage.BREAK; jobWait = 0; }
            }
        }
    }

    private int jobWait;

    private Set<String> wantedIds() {
        Set<String> s = new HashSet<>();
        for (ResourceKey<Enchantment> k : wanted.get()) s.add(k.identifier().toString());
        return s;
    }

    private static List<AutoLibrarianLogic.BookOffer> toBookOffers(List<MerchantOffer> offers) {
        List<AutoLibrarianLogic.BookOffer> out = new ArrayList<>();
        for (MerchantOffer o : offers) {
            if (o.getResult().getItem() != Items.ENCHANTED_BOOK) continue;
            int price = o.getCostA().getItem() == Items.EMERALD ? o.getCostA().getCount() : o.getCostB().getCount();
            ItemEnchantments ench = o.getResult().getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
            for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
                String id = e.getKey().unwrapKey().map(k -> k.identifier().toString()).orElse("?");
                out.add(new AutoLibrarianLogic.BookOffer(id, e.getIntValue(), e.getKey().value().getMaxLevel(), price));
            }
        }
        return out;
    }
}
