/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

/**
 * Click pacing for the regear stash (PS2 S40/S41). Uses Baritone's shared GUI pacing layer
 * ({@code baritone.api.utils.pacing.GuiPacer}: Fitts cursor travel, jitter, open-read / close delays, server-lag
 * stretch, and one click per tick shared with Get Item) when Baritone is installed; otherwise, or with a fixed
 * delay set, a plain fixed interval.
 */
public interface RegearPacer {
    /** A container just opened showing {@code stacks} stacks. */
    void opened(int stacks);

    /** Call once per tick; true when the click on {@code slot} may be sent now. */
    boolean ready(int slot, int columns);

    /** Ticks to wait after the last click before closing. */
    int closeDelay();

    /** @param fixedDelay ticks between clicks; 0 = auto (Baritone shared pacing, fixed 4 without Baritone) */
    static RegearPacer create(int fixedDelay) {
        try {
            return Shared.create(fixedDelay);
        } catch (LinkageError | RuntimeException e) {
            return new Fixed(fixedDelay > 0 ? fixedDelay : 4);
        }
    }

    /** Fixed interval (no Baritone, or a user-set delay). */
    final class Fixed implements RegearPacer {
        private final int interval;
        private int wait;

        public Fixed(int interval) {
            this.interval = Math.max(1, interval);
        }

        @Override
        public void opened(int stacks) {
            wait = interval + 4; // humans need a moment to see the contents
        }

        @Override
        public boolean ready(int slot, int columns) {
            if (wait > 0) {
                wait--;
                return false;
            }
            wait = interval - 1;
            return true;
        }

        @Override
        public int closeDelay() {
            return 2;
        }
    }

    /** Only loaded when Baritone is present. */
    final class Shared implements RegearPacer {
        private final baritone.api.utils.pacing.GuiPacer pacer;

        private Shared(int fixedDelay) {
            pacer = new baritone.api.utils.pacing.GuiPacer().withOverride(() -> fixedDelay > 0 ? fixedDelay : -1);
        }

        static RegearPacer create(int fixedDelay) {
            return new Shared(fixedDelay);
        }

        @Override
        public void opened(int stacks) {
            pacer.opened(stacks);
        }

        @Override
        public boolean ready(int slot, int columns) {
            return pacer.ready(slot, columns);
        }

        @Override
        public int closeDelay() {
            return pacer.closeDelay();
        }
    }
}
