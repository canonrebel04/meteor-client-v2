/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.debug;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.Property;

/**
 * Feeds log events from Meteor and Baritone loggers into {@link DebugLog}'s ring buffer. Kept in its own class
 * so a missing log4j-core only disables capture instead of breaking {@link DebugLog}.
 */
final class Log4jCapture extends AbstractAppender {
    private Log4jCapture() {
        super("MeteorV2DebugCapture", null, null, true, Property.EMPTY_ARRAY);
    }

    static void install() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        Log4jCapture appender = new Log4jCapture();
        appender.start();
        config.getRootLogger().addAppender(appender, Level.ALL, null);
        ctx.updateLoggers();
    }

    @Override
    public void append(LogEvent event) {
        String name = event.getLoggerName();
        if (name == null) return;
        if (!name.startsWith("Meteor") && !name.startsWith("Baritone") && !name.startsWith("meteordevelopment") && !name.startsWith("baritone")) return;

        String message = event.getMessage().getFormattedMessage();
        DebugLog.record(event.getLevel().name(), message, event.getThrown());
    }
}
