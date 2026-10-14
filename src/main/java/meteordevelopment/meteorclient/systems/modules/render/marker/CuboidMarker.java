/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.render.marker;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.core.BlockPos;

public class CuboidMarker extends BaseMarker {
    public static final String type = "Cuboid";

    public enum Mode {
        Full,
        Outline,
        Walls,
        Floor
    }

    /** Thickness of the slabs used for Walls / Floor modes. */
    static final double SLAB = 0.02;

    /**
     * Pure geometry: boxes {x1,y1,z1,x2,y2,z2} to draw for a cuboid [min, max) in the given mode.
     * Full and Outline are one box; Walls is four thin vertical slabs; Floor is one thin bottom slab.
     */
    static double[][] boxes(Mode mode, double x1, double y1, double z1, double x2, double y2, double z2) {
        return switch (mode) {
            case Full, Outline -> new double[][]{{x1, y1, z1, x2, y2, z2}};
            case Floor -> new double[][]{{x1, y1, z1, x2, y1 + SLAB, z2}};
            case Walls -> new double[][]{
                {x1, y1, z1, x2, y2, z1 + SLAB},
                {x1, y1, z2 - SLAB, x2, y2, z2},
                {x1, y1, z1, x1 + SLAB, y2, z2},
                {x2 - SLAB, y1, z1, x2, y2, z2}
            };
        };
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    // General

    private final Setting<BlockPos> pos1 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("pos-1")
        .description("1st corner of the cuboid")
        .build()
    );

    private final Setting<BlockPos> pos2 = sgGeneral.add(new BlockPosSetting.Builder()
        .name("pos-2")
        .description("2nd corner of the cuboid")
        .build()
    );

    // Render

    private final Setting<Mode> mode = sgRender.add(new EnumSetting.Builder<Mode>()
        .name("mode")
        .description("What mode to use for this marker.")
        .defaultValue(Mode.Full)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the shapes are rendered.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> mode.get() != Mode.Outline)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .description("The color of the sides of the blocks being rendered.")
        .defaultValue(new SettingColor(0, 100, 255, 50))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .description("The color of the lines of the blocks being rendered.")
        .defaultValue(new SettingColor(0, 100, 255, 255))
        .build()
    );

    public CuboidMarker() {
        super(type);
    }

    @Override
    public String getTypeName() {
        return type;
    }

    @Override
    protected void render(Render3DEvent event) {
        int minX = Math.min(pos1.get().getX(), pos2.get().getX());
        int minY = Math.min(pos1.get().getY(), pos2.get().getY());
        int minZ = Math.min(pos1.get().getZ(), pos2.get().getZ());
        int maxX = Math.max(pos1.get().getX(), pos2.get().getX());
        int maxY = Math.max(pos1.get().getY(), pos2.get().getY());
        int maxZ = Math.max(pos1.get().getZ(), pos2.get().getZ());

        ShapeMode shape = mode.get() == Mode.Outline ? ShapeMode.Lines : shapeMode.get();
        for (double[] b : boxes(mode.get(), minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1)) {
            event.renderer.box(b[0], b[1], b[2], b[3], b[4], b[5], sideColor.get(), lineColor.get(), shape, 0);
        }
    }
}
