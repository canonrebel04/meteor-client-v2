/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ServerboundSelectTradePacket;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;

import java.util.ArrayList;
import java.util.List;

/** While a villager trade screen is open, repeatedly executes trades that buy or sell chosen items. */
public class AutoTrader extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<List<Item>> buy = sgGeneral.add(new ItemListSetting.Builder()
        .name("buy").description("Trades whose result is one of these items are executed.").defaultValue(Items.EMERALD).build());

    private final Setting<List<Item>> sell = sgGeneral.add(new ItemListSetting.Builder()
        .name("sell").description("Trades whose first cost is one of these items are executed.").build());

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay").description("Ticks between trades.").defaultValue(2).range(0, 20).sliderRange(0, 20).build());

    private final Setting<Integer> maxTrades = sgGeneral.add(new IntSetting.Builder()
        .name("max-trades-per-screen").description("Stop after this many trades per opened screen (0 = unlimited).").defaultValue(0).range(0, 1000).sliderRange(0, 100).build());

    private int timer, done, lastContainer = -1;

    public AutoTrader() {
        super(Categories.World, "auto-trader", "Automatically performs selected villager trades while the trade screen is open.");
    }

    /** Pure: index of the next trade to perform, or -1. */
    public static int nextTrade(List<boolean[]> offers) {
        // each entry: {resultWanted, costWanted, outOfStock, affordable}
        for (int i = 0; i < offers.size(); i++) {
            boolean[] o = offers.get(i);
            if ((o[0] || o[1]) && !o[2] && o[3]) return i;
        }
        return -1;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.getConnection() == null) return;
        if (!(mc.player.containerMenu instanceof MerchantMenu menu)) { lastContainer = -1; return; }
        if (menu.containerId != lastContainer) { lastContainer = menu.containerId; done = 0; timer = 5; }
        if (timer-- > 0) return;
        if (maxTrades.get() > 0 && done >= maxTrades.get()) return;

        MerchantOffers offers = menu.getOffers();
        List<boolean[]> flags = new ArrayList<>(offers.size());
        for (MerchantOffer o : offers) {
            flags.add(new boolean[]{
                buy.get().contains(o.getResult().getItem()),
                sell.get().contains(o.getCostA().getItem()),
                o.isOutOfStock(),
                canAfford(o)
            });
        }
        int idx = nextTrade(flags);
        if (idx < 0) return;

        menu.setSelectionHint(idx);
        menu.tryMoveItems(idx);
        mc.getConnection().send(new ServerboundSelectTradePacket(idx));
        InvUtils.shiftClick().slotId(2);
        done++;
        timer = delay.get();
    }

    private boolean canAfford(MerchantOffer o) {
        int a = InvUtils.find(o.getCostA().getItem()).count();
        if (a < o.getCostA().getCount()) return false;
        if (o.getCostB().isEmpty()) return true;
        return InvUtils.find(o.getCostB().getItem()).count() >= o.getCostB().getCount();
    }
}
