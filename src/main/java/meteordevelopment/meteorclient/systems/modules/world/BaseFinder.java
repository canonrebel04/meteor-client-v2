/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.gui.GuiTheme;
import meteordevelopment.meteorclient.gui.widgets.WWidget;
import meteordevelopment.meteorclient.gui.widgets.pressable.WButton;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.MeteorToast;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class BaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<List<Block>> baseBlocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("base-blocks")
        .description("Blocks that indicate player-built structures or bases.")
        .defaultValue(
            Blocks.OBSIDIAN,
            Blocks.CRYING_OBSIDIAN,
            Blocks.ENDER_CHEST,
            Blocks.ANVIL,
            Blocks.CHIPPED_ANVIL,
            Blocks.DAMAGED_ANVIL,
            Blocks.ENCHANTING_TABLE,
            Blocks.BEACON,
            Blocks.RESPAWN_ANCHOR,
            Blocks.CRAFTING_TABLE,
            Blocks.FURNACE,
            Blocks.BLAST_FURNACE,
            Blocks.SMOKER,
            Blocks.HOPPER,
            Blocks.NETHER_PORTAL,
            Blocks.SHULKER_BOX
        )
        .build()
    );

    private final Setting<Boolean> includeStorage = sgGeneral.add(new BoolSetting.Builder()
        .name("include-storage")
        .description("Also counts storage block entities (chests, barrels, shulkers) toward the threshold.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> minimumCount = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-count")
        .description("Minimum matching blocks in a chunk to flag it as a potential base.")
        .defaultValue(6)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> notifications = sgGeneral.add(new BoolSetting.Builder()
        .name("notifications")
        .description("Notifies you in chat/toast when a potential base chunk is found.")
        .defaultValue(true)
        .build()
    );

    // Render

    private final Setting<Integer> renderY = sgRender.add(new IntSetting.Builder()
        .name("render-y")
        .description("Y level at which flagged base chunks are highlighted.")
        .defaultValue(64)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How flagged chunks are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill color for flagged base chunks.")
        .defaultValue(new SettingColor(255, 175, 45, 45))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Outline color for flagged base chunks.")
        .defaultValue(new SettingColor(255, 175, 45, 220))
        .build()
    );

    private static final Set<BlockEntityType<?>> STORAGE_TYPE_SET = Set.of(StorageBlockListSetting.STORAGE_BLOCKS);
    private final Set<ChunkPos> foundChunks = ConcurrentHashMap.newKeySet();

    public BaseFinder() {
        super(Categories.World, "base-finder", "Scans loaded chunks for non-natural player-placed blocks and storage to locate bases.");
    }

    @Override
    public void onDeactivate() {
        foundChunks.clear();
    }

    @Override
    public WWidget getWidget(GuiTheme theme) {
        WButton clear = theme.button("Clear");
        clear.action = foundChunks::clear;
        return clear;
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        ChunkPos chunkPos = chunk.getPos();
        if (foundChunks.contains(chunkPos)) return;

        List<Block> targets = baseBlocks.get();
        int count = 0;

        if (includeStorage.get()) {
            for (var be : chunk.getBlockEntities().values()) {
                BlockEntityType<?> type = be.getType();
                if (STORAGE_TYPE_SET.contains(type)) {
                    count++;
                }
            }
        }

        if (!targets.isEmpty() && count < minimumCount.get()) {
            int minY = chunk.getMinY();
            int maxY = chunk.getMaxY();
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

            outer:
            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    for (int y = minY; y < maxY; y++) {
                        pos.set(x, y, z);
                        Block block = chunk.getBlockState(pos).getBlock();
                        if (targets.contains(block)) {
                            count++;
                            if (count >= minimumCount.get()) {
                                break outer;
                            }
                        }
                    }
                }
            }
        }

        if (count >= minimumCount.get()) {
            foundChunks.add(chunkPos);
            if (notifications.get()) {
                int bx = chunkPos.getMiddleBlockX();
                int bz = chunkPos.getMiddleBlockZ();
                info("Potential base at (highlight)%d, %d(default) (%d+ matches).", bx, bz, count);
                mc.gui.toastManager().addToast(new MeteorToast.Builder(title)
                    .icon(Items.ENDER_CHEST)
                    .text("Found base at " + bx + ", " + bz)
                    .build());
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null) return;

        int viewDist = mc.options.renderDistance().get() + 2;
        ChunkPos playerChunk = mc.player.chunkPosition();
        double y = renderY.get();

        for (ChunkPos pos : foundChunks) {
            if (Math.abs(pos.x() - playerChunk.x()) > viewDist || Math.abs(pos.z() - playerChunk.z()) > viewDist) continue;
            event.renderer.box(
                pos.getMinBlockX(), y, pos.getMinBlockZ(),
                pos.getMaxBlockX() + 1, y, pos.getMaxBlockZ() + 1,
                sideColor.get(), lineColor.get(), shapeMode.get(), 0
            );
        }
    }

    @Override
    public String getInfoString() {
        return Integer.toString(foundChunks.size());
    }
}
