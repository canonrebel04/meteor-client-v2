/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat;

/**
 * Pacing for combat item uses, block placements and hotbar swaps (PS2 shared layer). With Baritone installed it uses
 * {@code baritone.api.utils.pacing} (InteractionRhythm place gaps with jitter + server-lag stretch, HotbarScroll for
 * human-like slot changes, the active Pace from the look profile); otherwise fixed gaps and direct swaps.
 */
public interface CombatPacer {
    /** Ticks to wait between two combat placements / item uses (the n-th action). */
    int useGap(long n);

    /** The hotbar slot to select this tick when heading from {@code current} to {@code target}. */
    int nextSlot(int current, int target);

    /** True on the FAST pace (no human pacing). */
    boolean fast();

    static CombatPacer create() {
        try {
            return Shared.create();
        } catch (LinkageError | RuntimeException e) {
            return new Fixed();
        }
    }

    final class Fixed implements CombatPacer {
        @Override
        public int useGap(long n) {
            return 4;
        }

        @Override
        public int nextSlot(int current, int target) {
            return target;
        }

        @Override
        public boolean fast() {
            return false;
        }
    }

    /** Only loaded when Baritone is present. */
    final class Shared implements CombatPacer {
        static CombatPacer create() {
            baritone.api.utils.pacing.Pace.current(); // link check
            return new Shared();
        }

        @Override
        public int useGap(long n) {
            return baritone.api.utils.pacing.InteractionRhythm.placeGap(baritone.api.utils.pacing.Pace.current(), 3, n,
                baritone.api.utils.pacing.LagModel.global().factor());
        }

        @Override
        public int nextSlot(int current, int target) {
            return baritone.api.utils.pacing.HotbarScroll.next(current, target, baritone.api.utils.pacing.Pace.current());
        }

        @Override
        public boolean fast() {
            return baritone.api.utils.pacing.Pace.current() == baritone.api.utils.pacing.Pace.FAST;
        }
    }
}
