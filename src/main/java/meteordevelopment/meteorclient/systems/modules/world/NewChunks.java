/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.material.FluidState;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class NewChunks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<Boolean> detectOldChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-old-chunks")
        .description("Also highlights chunks that appear to be previously generated.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> clearOnDisable = sgGeneral.add(new BoolSetting.Builder()
        .name("clear-on-disable")
        .description("Clears cached chunk classifications when the module is disabled.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxChunks = sgGeneral.add(new IntSetting.Builder()
        .name("max-chunks")
        .description("Maximum number of tracked chunks before clearing.")
        .defaultValue(4096)
        .min(256)
        .sliderRange(256, 16384)
        .build()
    );

    // Render

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y")
        .description("Y level at which chunk highlights are drawn.")
        .defaultValue(0)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How chunk highlights are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> newChunkSideColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunk-fill-color")
        .description("Fill color for newly generated chunks.")
        .defaultValue(new SettingColor(225, 45, 45, 40))
        .build()
    );

    private final Setting<SettingColor> newChunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("new-chunk-line-color")
        .description("Outline color for newly generated chunks.")
        .defaultValue(new SettingColor(225, 45, 45, 200))
        .build()
    );

    private final Setting<SettingColor> oldChunkSideColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunk-fill-color")
        .description("Fill color for old chunks.")
        .defaultValue(new SettingColor(45, 225, 110, 25))
        .visible(detectOldChunks::get)
        .build()
    );

    private final Setting<SettingColor> oldChunkLineColor = sgRender.add(new ColorSetting.Builder()
        .name("old-chunk-line-color")
        .description("Outline color for old chunks.")
        .defaultValue(new SettingColor(45, 225, 110, 140))
        .visible(detectOldChunks::get)
        .build()
    );

    private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();

    public NewChunks() {
        super(Categories.World, "new-chunks", "Detects and highlights newly generated chunks using liquid flow heuristics.");
    }

    @Override
    public void onDeactivate() {
        if (clearOnDisable.get()) {
            newChunks.clear();
            oldChunks.clear();
        }
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton clear = theme.button("Clear");
        clear.action = () -> {
            newChunks.clear();
            oldChunks.clear();
        };
        return clear;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        ChunkPos pos = chunk.getPos();

        if (newChunks.size() + oldChunks.size() > maxChunks.get()) {
            newChunks.clear();
            oldChunks.clear();
        }

        if (newChunks.contains(pos) || oldChunks.contains(pos)) return;

        if (hasFlowingFluid(chunk)) {
            newChunks.add(pos);
        } else if (detectOldChunks.get()) {
            oldChunks.add(pos);
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (isFlowingFluid(event.newState)) {
            markNew(ChunkPos.containing(event.pos));
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundBlockUpdatePacket packet) {
            if (isFlowingFluid(packet.getBlockState())) {
                markNew(ChunkPos.containing(packet.getPos()));
            }
        } else if (event.packet instanceof ClientboundSectionBlocksUpdatePacket packet) {
            packet.runUpdates((pos, state) -> {
                if (isFlowingFluid(state)) {
                    markNew(ChunkPos.containing(pos));
                }
            });
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        int viewDist = mc.options.renderDistance().get() + 2;
        ChunkPos playerChunk = mc.player.chunkPosition();
        double y = renderY.get();

        for (ChunkPos pos : newChunks) {
            if (Math.abs(pos.x() - playerChunk.x()) > viewDist || Math.abs(pos.z() - playerChunk.z()) > viewDist) continue;
            renderChunk(event, pos, y, newChunkSideColor.get(), newChunkLineColor.get());
        }

        if (detectOldChunks.get()) {
            for (ChunkPos pos : oldChunks) {
                if (Math.abs(pos.x() - playerChunk.x()) > viewDist || Math.abs(pos.z() - playerChunk.z()) > viewDist) continue;
                renderChunk(event, pos, y, oldChunkSideColor.get(), oldChunkLineColor.get());
            }
        }
    }

    private void renderChunk(Render3DEvent event, ChunkPos pos, double y, SettingColor side, SettingColor line) {
        double x1 = pos.getMinBlockX();
        double z1 = pos.getMinBlockZ();
        double x2 = pos.getMaxBlockX() + 1;
        double z2 = pos.getMaxBlockZ() + 1;
        event.renderer.box(x1, y, z1, x2, y, z2, side, line, shapeMode.get(), 0);
    }

    private void markNew(ChunkPos pos) {
        oldChunks.remove(pos);
        newChunks.add(pos);
    }

    private static boolean isFlowingFluid(BlockState state) {
        FluidState fluid = state.getFluidState();
        return !fluid.isEmpty() && !fluid.isSource();
    }

    private static boolean hasFlowingFluid(LevelChunk chunk) {
        int minY = chunk.getMinY();
        int maxY = chunk.getMaxY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    pos.set(x, y, z);
                    if (isFlowingFluid(chunk.getBlockState(pos))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public String getInfoString() {
        return Integer.toString(newChunks.size());
    }
}
