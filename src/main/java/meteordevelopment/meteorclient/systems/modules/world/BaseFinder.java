/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.pathing.PathManagers;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.text.RunnableClickEvent;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds likely player bases by counting player-placed / non-natural blocks per chunk.
 * <p>
 * Scanning is incremental: each chunk is scanned once on {@link ChunkDataEvent} (sections whose
 * palette cannot contain a target block are skipped via {@code maybeHas}), then kept up to date
 * with {@link BlockUpdateEvent}. Chunks containing natural-structure markers (spawners, bells, …)
 * are ignored to cut village / dungeon / shipwreck false positives.
 */
public class BaseFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgNotify = settings.createGroup("Notify");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General ---

    private final Setting<List<Block>> blocks = sgGeneral.add(new BlockListSetting.Builder()
        .name("blocks")
        .description("Blocks that indicate player activity.")
        .defaultValue(defaultBlocks())
        .onChanged(l -> rescanSoon())
        .build()
    );

    private final Setting<Integer> minBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("min-blocks")
        .description("Minimum matching blocks in one chunk before it counts as a base.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 64)
        .build()
    );

    private final Setting<Boolean> excludeStructures = sgGeneral.add(new BoolSetting.Builder()
        .name("exclude-structures")
        .description("Ignore chunks containing natural structure markers (see structure-markers).")
        .defaultValue(true)
        .build()
    );

    private final Setting<List<Block>> structureMarkers = sgGeneral.add(new BlockListSetting.Builder()
        .name("structure-markers")
        .description("Blocks that only generate in natural structures; chunks with these are skipped.")
        .defaultValue(defaultStructureMarkers())
        .visible(excludeStructures::get)
        .onChanged(l -> rescanSoon())
        .build()
    );

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y")
        .description("Ignore matches below this Y level (filters deep dark / mineshaft noise).")
        .defaultValue(-64)
        .sliderRange(-64, 320)
        .build()
    );

    private final Setting<Integer> maxBases = sgGeneral.add(new IntSetting.Builder()
        .name("max-bases")
        .description("Cap on remembered base chunks; the farthest are dropped first.")
        .defaultValue(256)
        .min(1)
        .sliderRange(16, 2048)
        .build()
    );

    // --- Notify ---

    private final Setting<Boolean> notifyChat = sgNotify.add(new BoolSetting.Builder()
        .name("chat-notify")
        .description("Announce newly found bases in chat with clickable coordinates.")
        .defaultValue(true)
        .build()
    );

    // --- Render ---

    private final Setting<Integer> drawDistance = sgRender.add(new IntSetting.Builder()
        .name("draw-distance")
        .description("Only render bases within this many chunks.")
        .defaultValue(32)
        .min(2)
        .sliderRange(2, 64)
        .build()
    );

    private final Setting<Boolean> renderBlocks = sgRender.add(new BoolSetting.Builder()
        .name("render-blocks")
        .description("Box the individual matching blocks.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxBlocksPerChunk = sgRender.add(new IntSetting.Builder()
        .name("max-rendered-per-chunk")
        .description("Cap on boxed blocks per base chunk.")
        .defaultValue(32)
        .min(1)
        .sliderRange(1, 256)
        .visible(renderBlocks::get)
        .build()
    );

    private final Setting<Boolean> renderChunk = sgRender.add(new BoolSetting.Builder()
        .name("render-chunk")
        .description("Draw a flat outline of the whole base chunk.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("fill-color")
        .description("Fill colour.")
        .defaultValue(new SettingColor(255, 170, 0, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("Line colour.")
        .defaultValue(new SettingColor(255, 170, 0, 220))
        .build()
    );

    /** Per-chunk scan state. Positions are capped; count is exact. */
    private static final class ChunkData {
        final Set<BlockPos> matches = ConcurrentHashMap.newKeySet();
        volatile boolean hasMarker;
        volatile boolean announced;
    }

    private static final int STORED_PER_CHUNK = 256;

    private final Map<ChunkPos, ChunkData> chunks = new ConcurrentHashMap<>();
    private volatile Set<Block> targetSet = Set.of();
    private volatile Set<Block> markerSet = Set.of();
    private volatile boolean rescan;

    public BaseFinder() {
        super(Categories.World, "base-finder", "Highlights chunks with many player-placed blocks (likely bases).");
    }

    @Override
    public void onActivate() {
        rebuildSets();
        chunks.clear();
        scanLoaded();
    }

    @Override
    public void onDeactivate() {
        chunks.clear();
    }

    @Override
    public String getInfoString() {
        return String.valueOf(countBases());
    }

    private void rescanSoon() {
        rescan = true;
    }

    private void rebuildSets() {
        targetSet = new HashSet<>(blocks.get());
        markerSet = new HashSet<>(structureMarkers.get());
    }

    private void scanLoaded() {
        if (mc.level == null || mc.player == null) return;
        int r = mc.options.renderDistance().get();
        ChunkPos p = mc.player.chunkPosition();
        for (int x = p.x() - r; x <= p.x() + r; x++) {
            for (int z = p.z() - r; z <= p.z() + r; z++) {
                LevelChunk c = mc.level.getChunkSource().getChunkNow(x, z);
                if (c != null) scanChunk(c);
            }
        }
    }

    // --- Events ---

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        chunks.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (rescan) {
            rescan = false;
            rebuildSets();
            chunks.clear();
            scanLoaded();
        }
        if (event.chunk() != null) scanChunk(event.chunk());
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        BlockPos pos = event.pos;
        if (pos == null) return;
        ChunkPos cpos = new ChunkPos(pos.getX() >> 4, pos.getZ() >> 4);
        ChunkData data = chunks.get(cpos);

        boolean wasMatch = event.oldState != null && isTarget(event.oldState, pos);
        boolean isMatch = event.newState != null && isTarget(event.newState, pos);
        boolean isMarker = event.newState != null && markerSet.contains(event.newState.getBlock());

        if (data == null) {
            if (!isMatch && !isMarker) return;
            data = new ChunkData();
            chunks.put(cpos, data);
        }

        if (isMarker) data.hasMarker = true;
        if (wasMatch && !isMatch) data.matches.remove(pos);
        if (isMatch && data.matches.size() < STORED_PER_CHUNK) data.matches.add(pos.immutable());

        afterUpdate(cpos, data);
    }

    // --- Scanning ---

    private boolean isTarget(BlockState state, BlockPos pos) {
        return pos.getY() >= minY.get() && targetSet.contains(state.getBlock());
    }

    private void scanChunk(LevelChunk chunk) {
        Set<Block> targets = targetSet;
        Set<Block> markers = markerSet;
        if (targets.isEmpty()) return;

        ChunkPos cpos = chunk.getPos();
        ChunkData data = new ChunkData();
        LevelChunkSection[] sections = chunk.getSections();
        int baseY = chunk.getMinY();
        int floor = minY.get();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;
            int sy = baseY + i * 16;
            if (sy + 15 < floor) continue;

            boolean anyTarget = section.maybeHas(s -> targets.contains(s.getBlock()));
            boolean anyMarker = excludeStructures.get() && section.maybeHas(s -> markers.contains(s.getBlock()));
            if (!anyTarget && !anyMarker) continue;

            for (int y = 0; y < 16; y++) {
                int wy = sy + y;
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        Block b = section.getBlockState(x, y, z).getBlock();
                        if (anyMarker && markers.contains(b)) data.hasMarker = true;
                        if (anyTarget && wy >= floor && targets.contains(b) && data.matches.size() < STORED_PER_CHUNK) {
                            data.matches.add(m.set(cpos.getMinBlockX() + x, wy, cpos.getMinBlockZ() + z).immutable());
                        }
                    }
                }
            }
        }

        if (data.matches.isEmpty() && !data.hasMarker) {
            chunks.remove(cpos);
            return;
        }
        ChunkData prev = chunks.put(cpos, data);
        if (prev != null) data.announced = prev.announced;
        afterUpdate(cpos, data);
    }

    private boolean isBase(ChunkData d) {
        return BaseFinderScoring.isBase(d.matches.size(), minBlocks.get(), d.hasMarker, excludeStructures.get());
    }

    private int countBases() {
        int n = 0;
        for (ChunkData d : chunks.values()) if (isBase(d)) n++;
        return n;
    }

    private void afterUpdate(ChunkPos cpos, ChunkData data) {
        if (!isBase(data)) return;
        if (!data.announced) {
            data.announced = true;
            if (notifyChat.get()) mc.execute(() -> announce(cpos, data.matches.size()));
        }
        enforceCap();
    }

    private void enforceCap() {
        int cap = maxBases.get();
        if (mc.player == null) return;
        List<ChunkPos> bases = new ArrayList<>();
        for (Map.Entry<ChunkPos, ChunkData> e : chunks.entrySet()) if (isBase(e.getValue())) bases.add(e.getKey());
        if (bases.size() <= cap) return;
        ChunkPos p = mc.player.chunkPosition();
        bases.sort(Comparator.comparingInt(c -> -distSq(c, p)));
        for (int i = 0; i < bases.size() - cap; i++) chunks.remove(bases.get(i));
    }

    private static int distSq(ChunkPos a, ChunkPos b) {
        int dx = a.x() - b.x();
        int dz = a.z() - b.z();
        return dx * dx + dz * dz;
    }

    private void announce(ChunkPos cpos, int count) {
        int bx = cpos.getMiddleBlockX();
        int bz = cpos.getMiddleBlockZ();
        MutableComponent coords = Component.literal(bx + ", " + bz)
            .setStyle(Style.EMPTY
                .withColor(ChatFormatting.WHITE)
                .applyFormat(ChatFormatting.UNDERLINE)
                .withHoverEvent(new HoverEvent.ShowText(Component.literal("Path to base")))
                .withClickEvent(new RunnableClickEvent(() -> PathManagers.get().moveTo(new BlockPos(bx, 0, bz), true))));

        MutableComponent msg = Component.literal("Possible base at ")
            .withStyle(ChatFormatting.GRAY)
            .append(Component.literal("[").withStyle(ChatFormatting.GRAY))
            .append(coords)
            .append(Component.literal("]").withStyle(ChatFormatting.GRAY))
            .append(Component.literal(" (" + count + " blocks).").withStyle(ChatFormatting.GRAY));
        ChatUtils.sendMsg(msg);
    }

    // --- Render ---

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;
        ChunkPos p = mc.player.chunkPosition();
        int dd = drawDistance.get();
        int ddSq = dd * dd;
        int perChunk = maxBlocksPerChunk.get();

        for (Map.Entry<ChunkPos, ChunkData> e : chunks.entrySet()) {
            ChunkData d = e.getValue();
            if (!isBase(d)) continue;
            ChunkPos c = e.getKey();
            if (distSq(c, p) > ddSq) continue;

            if (renderChunk.get()) {
                double x1 = c.getMinBlockX();
                double z1 = c.getMinBlockZ();
                double y = mc.player.getY() - 1;
                event.renderer.box(x1, y, z1, x1 + 16, y + 0.05, z1 + 16, sideColor.get(), lineColor.get(), ShapeMode.Lines, 0);
            }
            if (renderBlocks.get()) {
                int n = 0;
                for (BlockPos b : d.matches) {
                    if (n++ >= perChunk) break;
                    event.renderer.box(b, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
                }
            }
        }
    }

    private static List<Block> defaultBlocks() {
        List<Block> list = new ArrayList<>(List.of(
            Blocks.CRAFTING_TABLE, Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL, Blocks.ENDER_CHEST,
            Blocks.SHULKER_BOX, Blocks.HOPPER, Blocks.ANVIL, Blocks.ENCHANTING_TABLE,
            Blocks.BREWING_STAND, Blocks.BEACON, Blocks.RESPAWN_ANCHOR, Blocks.NETHER_PORTAL,
            Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN, Blocks.SPRUCE_SIGN, Blocks.SPRUCE_WALL_SIGN,
            Blocks.BIRCH_SIGN, Blocks.BIRCH_WALL_SIGN,
            Blocks.GLASS, Blocks.GLASS_PANE,
            Blocks.TINTED_GLASS,
            Blocks.IRON_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.NETHERITE_BLOCK, Blocks.EMERALD_BLOCK,
            Blocks.QUARTZ_BLOCK, Blocks.SEA_LANTERN, Blocks.REDSTONE_LAMP,
            Blocks.OBSERVER, Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.COMPARATOR, Blocks.REPEATER,
            Blocks.TORCH, Blocks.WALL_TORCH
        ));
        list.addAll(Blocks.BED.asList());
        list.addAll(Blocks.WOOL.asList());
        list.addAll(Blocks.STAINED_GLASS.asList());
        list.addAll(Blocks.CONCRETE.asList());
        return list;
    }

    private static List<Block> defaultStructureMarkers() {
        return List.of(
            Blocks.SPAWNER, Blocks.TRIAL_SPAWNER, Blocks.VAULT, Blocks.BELL,
            Blocks.END_PORTAL_FRAME, Blocks.MOSSY_COBBLESTONE, Blocks.SCULK_SHRIEKER,
            Blocks.REINFORCED_DEEPSLATE, Blocks.SUSPICIOUS_SAND, Blocks.SUSPICIOUS_GRAVEL,
            Blocks.CHISELED_STONE_BRICKS, Blocks.PURPUR_BLOCK, Blocks.PRISMARINE_BRICKS,
            Blocks.GILDED_BLACKSTONE, Blocks.COBWEB
        );
    }
}
