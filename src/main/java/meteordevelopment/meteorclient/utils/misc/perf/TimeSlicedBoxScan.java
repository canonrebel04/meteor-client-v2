/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.misc.perf;

/**
 * Walks the cells of a {@code (2r+1) x (2h+1) x (2r+1)} box around a centre in rings of growing horizontal
 * distance (nearest first), a bounded amount per tick (plan S86). Allocation free.
 */
public final class TimeSlicedBoxScan {
    /** Visits one cell; return true to stop the scan early. */
    @FunctionalInterface
    public interface Visitor {
        boolean visit(int x, int y, int z);
    }

    private int cx, cy, cz, radius, height;
    private int ring, index, dy;
    private boolean running;
    private boolean done;

    public void start(int cx, int cy, int cz, int radius, int height) {
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.radius = Math.max(0, radius);
        this.height = Math.max(0, height);
        this.ring = 0;
        this.index = 0;
        this.dy = 0;
        this.running = true;
        this.done = false;
    }

    public boolean isRunning() {
        return running;
    }

    public boolean isDone() {
        return done;
    }

    public void cancel() {
        running = false;
    }

    /**
     * Visits cells until the scan finishes, the visitor stops it, or the budget runs out (checked every 16 cells
     * after at least {@code minPerStep} cells).
     *
     * @return true when the scan is finished (fully or stopped by the visitor)
     */
    public boolean step(TickBudget budget, int minPerStep, Visitor visitor) {
        if (!running) return done;
        int count = 0;
        while (ring <= radius) {
            int ringCells = ring == 0 ? 1 : ring * 8;
            while (index < ringCells) {
                int dx, dz;
                if (ring == 0) {
                    dx = 0;
                    dz = 0;
                } else {
                    int side = index / (ring * 2);
                    int off = index % (ring * 2);
                    switch (side) {
                        case 0 -> { dx = -ring + off; dz = -ring; }
                        case 1 -> { dx = ring; dz = -ring + off; }
                        case 2 -> { dx = ring - off; dz = ring; }
                        default -> { dx = -ring; dz = ring - off; }
                    }
                }
                // Vertical order: 0, -1, +1, -2, +2, ...
                int vertical = height * 2 + 1;
                while (dy < vertical) {
                    int yOff = (dy & 1) == 0 ? -(dy / 2) : (dy + 1) / 2;
                    dy++;
                    if (visitor.visit(cx + dx, cy + yOff, cz + dz)) {
                        running = false;
                        done = true;
                        return true;
                    }
                    count++;
                    if (count >= minPerStep && (count & 15) == 0 && budget != null && !budget.hasTime()) {
                        return false;
                    }
                }
                dy = 0;
                index++;
            }
            index = 0;
            ring++;
        }
        running = false;
        done = true;
        return true;
    }
}
