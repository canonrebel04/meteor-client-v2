/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.world;

import it.unimi.dsi.fastutil.objects.ReferenceArrayList;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.utils.PreInit;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Pool;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.function.BiConsumer;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class BlockIterator {
    private static final Pool<Callback> callbackPool = new Pool<>(Callback::new);
    private static final List<Callback> callbacks = new ReferenceArrayList<>();

    private static final List<Runnable> afterCallbacks = new ReferenceArrayList<>();

    private static final BlockPos.MutableBlockPos blockPos = new BlockPos.MutableBlockPos();
    private static int hRadius, vRadius;

    private static boolean disableCurrent;

    private BlockIterator() {
    }

    @PreInit
    public static void init() {
        MeteorClient.EVENT_BUS.subscribe(BlockIterator.class);
    }

    @EventHandler(priority = EventPriority.LOWEST - 1)
    private static void onTick(TickEvent.Pre event) {
        if (!Utils.canUpdate()) return;

        int px = mc.player.getBlockX();
        int py = mc.player.getBlockY();
        int pz = mc.player.getBlockZ();

        // Plan S89: no per-block lambda allocation, the block state is only read when some callback wants this
        // position, disabled callbacks go straight back to the pool, and the walk stops once none are left
        int minY = Math.max(mc.level.getMinY(), py - vRadius);
        int maxY = Math.min(mc.level.getMaxY(), py + vRadius);

        outer:
        for (int x = px - hRadius; x <= px + hRadius; x++) {
            for (int z = pz - hRadius; z <= pz + hRadius; z++) {
                for (int y = minY; y <= maxY; y++) {
                    if (callbacks.isEmpty()) break outer;

                    int dx = Math.abs(x - px);
                    int dy = Math.abs(y - py);
                    int dz = Math.abs(z - pz);

                    BlockState blockState = null;
                    for (int i = 0; i < callbacks.size(); i++) {
                        Callback callback = callbacks.get(i);
                        if (dx > callback.hRadius || dy > callback.vRadius || dz > callback.hRadius) continue;

                        if (blockState == null) {
                            blockPos.set(x, y, z);
                            blockState = mc.level.getBlockState(blockPos);
                        }
                        disableCurrent = false;
                        callback.function.accept(blockPos, blockState);
                        if (disableCurrent) {
                            callbacks.remove(i--);
                            callback.function = null;
                            callbackPool.free(callback);
                        }
                    }
                }
            }
        }

        hRadius = 0;
        vRadius = 0;

        callbackPool.freeAll(callbacks);
        callbacks.clear();

        for (Runnable callback : afterCallbacks) callback.run();
        afterCallbacks.clear();
    }

    public static void register(int horizontalRadius, int verticalRadius, BiConsumer<BlockPos, BlockState> function) {
        hRadius = Math.max(hRadius, horizontalRadius);
        vRadius = Math.max(vRadius, verticalRadius);

        Callback callback = callbackPool.get();

        callback.function = function;
        callback.hRadius = horizontalRadius;
        callback.vRadius = verticalRadius;

        callbacks.add(callback);
    }

    public static void disableCurrent() {
        disableCurrent = true;
    }

    public static void after(Runnable callback) {
        afterCallbacks.add(callback);
    }

    private static class Callback {
        public BiConsumer<BlockPos, BlockState> function;
        public int hRadius, vRadius;
    }
}
