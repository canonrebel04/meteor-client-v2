/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.bot;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Generic Utility-AI scheduler with active-task hysteresis, minimum commit ticks,
 * and per-task failure cooldowns to prevent thrashing.
 *
 * @param <C> Shared context type passed to registered {@link BotTask} instances.
 */
public class UtilityTaskRunner<C> {
    private final List<BotTask<C>> tasks;
    private final double hysteresisMultiplier;
    private final int minCommitTicks;
    private final int failureCooldownTicks;

    private final Map<BotTask<C>, Long> cooldownUntilTick = new HashMap<>();
    private BotTask<C> activeTask;
    private int activeTicks;
    private long tickCounter;

    public UtilityTaskRunner(List<BotTask<C>> tasks) {
        this(tasks, 1.15, 15, 40);
    }

    public UtilityTaskRunner(List<BotTask<C>> tasks, double hysteresisMultiplier, int minCommitTicks, int failureCooldownTicks) {
        this.tasks = List.copyOf(tasks);
        this.hysteresisMultiplier = hysteresisMultiplier;
        this.minCommitTicks = minCommitTicks;
        this.failureCooldownTicks = failureCooldownTicks;
    }

    public BotTask<C> getActiveTask() {
        return activeTask;
    }

    public String getActiveTaskName() {
        return activeTask != null ? activeTask.name() : "Idle";
    }

    public int getActiveTicks() {
        return activeTicks;
    }

    public void tick(C ctx) {
        tickCounter++;

        BotTask<C> bestTask = selectBestTask(ctx);
        if (bestTask != activeTask) {
            if (activeTask != null) {
                activeTask.onStop(ctx);
            }
            activeTask = bestTask;
            activeTicks = 0;
            if (activeTask != null) {
                activeTask.onStart(ctx);
            }
        }

        if (activeTask == null) return;

        activeTicks++;
        TaskStatus status = activeTask.tick(ctx);
        if (status == TaskStatus.SUCCEEDED || status == TaskStatus.FAILED) {
            if (status == TaskStatus.FAILED && failureCooldownTicks > 0) {
                cooldownUntilTick.put(activeTask, tickCounter + failureCooldownTicks);
            }
            activeTask.onStop(ctx);
            activeTask = null;
            activeTicks = 0;
        }
    }

    public void stopAll(C ctx) {
        if (activeTask != null) {
            activeTask.onStop(ctx);
            activeTask = null;
        }
        activeTicks = 0;
        cooldownUntilTick.clear();
    }

    private BotTask<C> selectBestTask(C ctx) {
        double activeRawScore = 0.0;
        if (activeTask != null) {
            activeRawScore = activeTask.score(ctx);
            if (activeRawScore > 0.0 && activeTicks < minCommitTicks) {
                return activeTask;
            }
        }

        BotTask<C> best = null;
        double bestScore = 0.0;

        for (BotTask<C> task : tasks) {
            if (task != activeTask) {
                Long until = cooldownUntilTick.get(task);
                if (until != null && tickCounter < until) continue;
            }

            double score = task.score(ctx);
            if (score <= 0.0) continue;

            double effective = (task == activeTask) ? score * hysteresisMultiplier : score;
            if (effective > bestScore) {
                bestScore = effective;
                best = task;
            }
        }

        return best;
    }
}
