/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.debug;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MessageFormatter;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * Tagged debug logging for meteor-client-v2.
 * <p>
 * Lines are written to the game log as {@code [Meteor/<Tag>] message}. Debug lines are dropped unless debug
 * logging is enabled with the JVM argument {@code -Dmeteorv2.debug=true}, the {@code debug-logging} option in
 * Config, or {@code .debug on}. When enabled they are written at INFO with a {@code [debug]} marker so the stock
 * Minecraft log config keeps them in latest.log.
 * <p>
 * The last {@value #CAPACITY} Meteor/Baritone log lines (plus all debug lines, even when disabled) are kept in a
 * ring buffer that {@code .debug dump} writes out.
 */
public final class DebugLog {
    public static final String PROPERTY = "meteorv2.debug";
    public static final int CAPACITY = 200;

    private static final Logger LOG = LoggerFactory.getLogger("Meteor Client");
    private static final boolean PROPERTY_ENABLED = Boolean.getBoolean(PROPERTY);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final ArrayDeque<String> BUFFER = new ArrayDeque<>(CAPACITY);

    private static volatile boolean enabled = PROPERTY_ENABLED;
    /** True once {@link Log4jCapture} feeds every Meteor/Baritone log event into the buffer. */
    static volatile boolean captureInstalled = false;

    private DebugLog() {
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /** Toggles debug output. The JVM property, when set, always keeps it on. */
    public static void setEnabled(boolean value) {
        enabled = PROPERTY_ENABLED || value;
    }

    public static boolean isForcedByProperty() {
        return PROPERTY_ENABLED;
    }

    /** Debug line with SLF4J-style {@code {}} placeholders; a trailing Throwable is logged with its stack trace. */
    public static void debug(String tag, String message, Object... args) {
        FormattingTuple t = MessageFormatter.arrayFormat(message, args);
        String line = "[Meteor/" + tag + "] " + (enabled ? "[debug] " : "") + t.getMessage();

        if (enabled) {
            LOG.info(line, t.getThrowable());
            // With capture installed the INFO event above is recorded by the appender.
            if (!captureInstalled) record("DEBUG", line, t.getThrowable());
        } else {
            // Not written to latest.log, but kept so a dump still shows what happened.
            record("DEBUG", line, t.getThrowable());
            if (LOG.isDebugEnabled()) LOG.debug(line, t.getThrowable());
        }
    }

    public static void warn(String tag, String message, Object... args) {
        FormattingTuple t = MessageFormatter.arrayFormat(message, args);
        String line = "[Meteor/" + tag + "] " + t.getMessage();
        LOG.warn(line, t.getThrowable());
        if (!captureInstalled) record("WARN", line, t.getThrowable());
    }

    public static void error(String tag, String message, Object... args) {
        FormattingTuple t = MessageFormatter.arrayFormat(message, args);
        String line = "[Meteor/" + tag + "] " + t.getMessage();
        LOG.error(line, t.getThrowable());
        if (!captureInstalled) record("ERROR", line, t.getThrowable());
    }

    static void record(String level, String line, Throwable throwable) {
        StringBuilder sb = new StringBuilder()
            .append(LocalTime.now().format(TIME)).append(" [")
            .append(Thread.currentThread().getName()).append('/').append(level).append("] ")
            .append(line);

        if (throwable != null) {
            StringWriter sw = new StringWriter();
            throwable.printStackTrace(new PrintWriter(sw));
            String[] frames = sw.toString().split("\n");
            for (int i = 0; i < Math.min(frames.length, 25); i++) sb.append('\n').append("    ").append(frames[i].stripTrailing());
            if (frames.length > 25) sb.append("\n    ... ").append(frames.length - 25).append(" more");
        }

        synchronized (BUFFER) {
            if (BUFFER.size() >= CAPACITY) BUFFER.pollFirst();
            BUFFER.addLast(sb.toString());
        }
    }

    public static List<String> snapshot() {
        synchronized (BUFFER) {
            return new ArrayList<>(BUFFER);
        }
    }

    /**
     * Logs any exception that kills a thread (Meteor executors, network threads, Baritone worker threads, ...)
     * with a [Meteor/Crash] tag, then hands it to the previous handler.
     */
    public static void installUncaughtExceptionHandler() {
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            try {
                error("Crash", "Uncaught exception in thread '{}'", thread.getName(), throwable);
            } catch (Throwable ignored) {
                // Never let logging hide the original failure
            }
            if (previous != null) previous.uncaughtException(thread, throwable);
        });
    }

    /** Installs the log4j ring-buffer capture if log4j-core is present (it is in normal Minecraft installs). */
    public static void init() {
        installUncaughtExceptionHandler();
        try {
            Log4jCapture.install();
            captureInstalled = true;
        } catch (Throwable t) {
            LOG.warn("[Meteor/Debug] Could not attach log capture, dump will only contain Meteor debug lines", t);
        }
        if (enabled) LOG.info("[Meteor/Debug] Debug logging enabled ({})", PROPERTY_ENABLED ? "-D" + PROPERTY + "=true" : "config");
    }
}
