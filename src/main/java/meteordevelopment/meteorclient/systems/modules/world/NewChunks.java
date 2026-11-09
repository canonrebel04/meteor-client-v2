/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Highlights chunks that are likely newly generated vs already explored.
 * <p>
 * Detection (same principle as Wurst / BleachHack NewChunks):
 * <ul>
 *   <li>Worldgen places liquids as <b>still sources</b>. Flowing water/lava only appears later
 *       via block updates after a chunk has been loaded at least once.</li>
 *   <li>If a chunk's initial data already contains flowing fluid → it was loaded from disk before → <b>old</b>.</li>
 *   <li>If flowing fluid appears later via a block update in a unclassified chunk → <b>new</b>.</li>
 * </ul>
 * Palette-based detection (unused local palette entries) requires private container accessors and is
 * left as a follow-up; the fluid heuristic alone matches classic NewChunks behaviour.
 */
public class NewChunks extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- General ---

    private final Setting<Boolean> detectOld = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-old")
        .description("Classify chunks that already contain flowing fluids on first load as old.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> detectNew = sgGeneral.add(new BoolSetting.Builder()
        .name("detect-new")
        .description("Classify chunks as new when a fluid starts flowing after the chunk loaded.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> chatFeedback = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-feedback")
        .description("Print a chat line when a chunk is classified.")
        .defaultValue(false)
        .build()
    );

    // --- Render ---

    private final Setting<ShowMode> show = sgRender.add(new EnumSetting.Builder<ShowMode>()
        .name("show")
        .description("Which classifications to render.")
        .defaultValue(ShowMode.Both)
        .build()
    );

    private final Setting<RenderStyle> style = sgRender.add(new EnumSetting.Builder<RenderStyle>()
        .name("style")
        .description("How each chunk is drawn.")
        .defaultValue(RenderStyle.Flat)
        .build()
    );

    private final Setting<Integer> altitude = sgRender.add(new IntSetting.Builder()
        .name("altitude")
        .description("Y level of flat highlights (relative to world min Y when relative-altitude is on).")
        .defaultValue(0)
        .sliderRange(-64, 320)
        .visible(() -> style.get() == RenderStyle.Flat)
        .build()
    );

    private final Setting<Boolean> relativeAltitude = sgRender.add(new BoolSetting.Builder()
        .name("relative-altitude")
        .description("Treat altitude as an offset from the world's minimum Y.")
        .defaultValue(true)
        .visible(() -> style.get() == RenderStyle.Flat)
        .build()
    );

    private final Setting<Integer> drawDistance = sgRender.add(new IntSetting.Builder()
        .name("draw-distance")
        .description("Only render classified chunks within this many chunks of the player.")
        .defaultValue(32)
        .min(4)
        .sliderRange(4, 64)
        .build()
    );

    private final Setting<Boolean> showReasons = sgRender.add(new BoolSetting.Builder()
        .name("show-reasons")
        .description("Also highlight the flowing-fluid block that caused the classification.")
        .defaultValue(false)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> newSideColor = sgRender.add(new ColorSetting.Builder()
        .name("new-fill-color")
        .description("Fill colour for new chunks.")
        .defaultValue(new SettingColor(255, 0, 0, 40))
        .build()
    );

    private final Setting<SettingColor> newLineColor = sgRender.add(new ColorSetting.Builder()
        .name("new-line-color")
        .description("Line colour for new chunks.")
        .defaultValue(new SettingColor(255, 0, 0, 200))
        .build()
    );

    private final Setting<SettingColor> oldSideColor = sgRender.add(new ColorSetting.Builder()
        .name("old-fill-color")
        .description("Fill colour for old chunks.")
        .defaultValue(new SettingColor(0, 0, 255, 40))
        .build()
    );

    private final Setting<SettingColor> oldLineColor = sgRender.add(new ColorSetting.Builder()
        .name("old-line-color")
        .description("Line colour for old chunks.")
        .defaultValue(new SettingColor(0, 0, 255, 200))
        .build()
    );

    /** Chunks classified as newly generated. */
    private final Set<ChunkPos> newChunks = ConcurrentHashMap.newKeySet();
    /** Chunks that already had flowing fluid on first load. */
    private final Set<ChunkPos> oldChunks = ConcurrentHashMap.newKeySet();
    /** Seen on load with no flowing fluid yet — candidates for later "new" classification. */
    private final Set<ChunkPos> pending = ConcurrentHashMap.newKeySet();
    /** Optional reason block per classified chunk. */
    private final Map<ChunkPos, BlockPos> reasons = new ConcurrentHashMap<>();

    public NewChunks() {
        super(Categories.World, "new-chunks", "Highlights chunks that are likely newly generated vs already explored, using flowing-fluid heuristics.");
    }

    @Override
    public void onActivate() {
        clear();
    }

    @Override
    public void onDeactivate() {
        clear();
    }

    private void clear() {
        newChunks.clear();
        oldChunks.clear();
        pending.clear();
        reasons.clear();
    }

    @Override
    public String getInfoString() {
        return newChunks.size() + "/" + oldChunks.size();
    }

    // --- Detection ---

    @EventHandler
    private void onGameJoin(GameJoinedEvent event) {
        // Dimension / server changes must not carry old classifications over.
        clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        LevelChunk chunk = event.chunk();
        if (chunk == null) return;

        ChunkPos cpos = chunk.getPos();
        if (newChunks.contains(cpos) || oldChunks.contains(cpos) || pending.contains(cpos)) return;

        BlockPos flowing = findFlowingFluid(chunk);
        if (flowing != null) {
            if (!detectOld.get()) {
                pending.add(cpos);
                return;
            }
            oldChunks.add(cpos);
            reasons.put(cpos, flowing.immutable());
            if (chatFeedback.get()) {
                info("Old chunk at (highlight)%d(default), (highlight)%d(default) (flowing fluid on load)",
                    cpos.x(), cpos.z());
            }
            return;
        }

        // No flowing fluid in the initial data — wait for a later flow update.
        pending.add(cpos);
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (!detectNew.get()) return;

        BlockState newState = event.newState;
        if (newState == null) return;

        FluidState fluid = newState.getFluidState();
        if (!isFlowing(fluid)) return;

        ChunkPos pos = new ChunkPos(event.pos.getX() >> 4, event.pos.getZ() >> 4);
        if (newChunks.contains(pos) || oldChunks.contains(pos)) return;

        // Only promote chunks we already saw load without flowing fluid (or never marked old).
        newChunks.add(pos);
        pending.remove(pos);
        reasons.put(pos, event.pos.immutable());
        if (chatFeedback.get()) {
            info("New chunk at (highlight)%d(default), (highlight)%d(default) (fluid started flowing)",
                pos.x(), pos.z());
        }
    }

    /**
     * Scans non-empty sections for a flowing fluid. Sections whose palette cannot
     * contain flowing fluids are skipped via {@link LevelChunkSection#maybeHas}.
     */
    private BlockPos findFlowingFluid(LevelChunk chunk) {
        LevelChunkSection[] sections = chunk.getSections();
        ChunkPos cpos = chunk.getPos();
        int minY = chunk.getMinY();

        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null || section.hasOnlyAir()) continue;

            // Fast reject: palette has no flowing-fluid-bearing states.
            try {
                if (!section.maybeHas(state -> isFlowing(state.getFluidState()))) continue;
            } catch (Throwable ignored) {
                // maybeHas signature can differ across mappings; fall through to full scan.
            }

            int sectionMinY = minY + i * 16;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (isFlowing(section.getFluidState(x, y, z))) {
                            return new BlockPos(cpos.getMinBlockX() + x, sectionMinY + y, cpos.getMinBlockZ() + z);
                        }
                    }
                }
            }
        }
        return null;
    }

    static boolean isFlowing(FluidState fluid) {
        return fluid != null && !fluid.isEmpty() && !fluid.isSource();
    }

    // --- Render ---

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.level == null) return;

        ChunkPos playerChunk = mc.player.chunkPosition();
        int playerChunkX = playerChunk.x();
        int playerChunkZ = playerChunk.z();
        int dd = drawDistance.get();
        int ddSq = dd * dd;

        if (show.get().includesNew()) {
            for (ChunkPos pos : newChunks) {
                renderChunk(event, pos, playerChunkX, playerChunkZ, ddSq, newSideColor.get(), newLineColor.get());
            }
        }
        if (show.get().includesOld()) {
            for (ChunkPos pos : oldChunks) {
                renderChunk(event, pos, playerChunkX, playerChunkZ, ddSq, oldSideColor.get(), oldLineColor.get());
            }
        }
    }

    private void renderChunk(Render3DEvent event, ChunkPos pos, int playerChunkX, int playerChunkZ, int ddSq,
                             SettingColor side, SettingColor line) {
        int cx = pos.x();
        int cz = pos.z();
        int dx = cx - playerChunkX;
        int dz = cz - playerChunkZ;
        if (dx * dx + dz * dz > ddSq) return;

        double x1 = cx << 4;
        double z1 = cz << 4;
        double x2 = x1 + 16;
        double z2 = z1 + 16;

        double y1;
        double y2;
        if (style.get() == RenderStyle.Full) {
            y1 = mc.level.getMinY();
            y2 = y1 + mc.level.dimensionType().height();
        } else {
            y1 = relativeAltitude.get() ? mc.level.getMinY() + altitude.get() : altitude.get();
            y2 = y1 + 0.1;
        }

        event.renderer.box(x1, y1, z1, x2, y2, z2, side, line, shapeMode.get(), 0);

        if (showReasons.get()) {
            BlockPos reason = reasons.get(pos);
            if (reason != null) {
                event.renderer.box(reason, side, line, shapeMode.get(), 0);
            }
        }
    }

    public enum ShowMode {
        New,
        Old,
        Both;

        boolean includesNew() {
            return this == New || this == Both;
        }

        boolean includesOld() {
            return this == Old || this == Both;
        }
    }

    public enum RenderStyle {
        Flat,
        Full
    }
}
