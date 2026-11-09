/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure state-machine tests for NewChunks classification transitions
 * (no Minecraft runtime).
 */
class NewChunksClassifierTest {

    enum Class { UNSEEN, PENDING, NEW, OLD }

    /** Mirrors NewChunks.onChunkData / onBlockUpdate decisions. */
    static Class onLoad(Class current, boolean flowingOnLoad, boolean detectOld) {
        if (current == Class.NEW || current == Class.OLD || current == Class.PENDING) return current;
        if (flowingOnLoad) return detectOld ? Class.OLD : Class.PENDING;
        return Class.PENDING;
    }

    static Class onFlowUpdate(Class current, boolean detectNew) {
        if (!detectNew) return current;
        if (current == Class.NEW || current == Class.OLD) return current;
        return Class.NEW;
    }

    @Test
    void loadWithFlowingMarksOld() {
        assertEquals(Class.OLD, onLoad(Class.UNSEEN, true, true));
    }

    @Test
    void loadWithFlowingSkippedWhenDetectOldOff() {
        assertEquals(Class.PENDING, onLoad(Class.UNSEEN, true, false));
    }

    @Test
    void loadWithoutFlowingIsPending() {
        assertEquals(Class.PENDING, onLoad(Class.UNSEEN, false, true));
    }

    @Test
    void laterFlowPromotesPendingToNew() {
        assertEquals(Class.NEW, onFlowUpdate(Class.PENDING, true));
    }

    @Test
    void laterFlowDoesNotOverrideOld() {
        assertEquals(Class.OLD, onFlowUpdate(Class.OLD, true));
    }

    @Test
    void detectNewOffKeepsPending() {
        assertEquals(Class.PENDING, onFlowUpdate(Class.PENDING, false));
    }

    @Test
    void showModeFlags() {
        assertTrue(NewChunks.ShowMode.Both.includesNew());
        assertTrue(NewChunks.ShowMode.Both.includesOld());
        assertTrue(NewChunks.ShowMode.New.includesNew());
        assertFalse(NewChunks.ShowMode.New.includesOld());
        assertFalse(NewChunks.ShowMode.Old.includesNew());
        assertTrue(NewChunks.ShowMode.Old.includesOld());
    }
}
