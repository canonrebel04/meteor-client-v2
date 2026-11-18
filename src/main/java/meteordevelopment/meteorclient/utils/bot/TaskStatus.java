/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.bot;

/**
 * Execution result returned by a {@link BotTask} on each tick.
 */
public enum TaskStatus {
    RUNNING,
    SUCCEEDED,
    FAILED
}
