/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * Combat telemetry and replay log (C93): a per-fight timeline of verdicts, state changes, damage dealt/taken and
 * defense decisions. Bounded (events per fight, fights kept); serialised one JSON object per line so the file in the
 * mod config folder can be appended and trimmed cheaply. Pure apart from the JSON helpers.
 */
public final class CombatTelemetry {
    public enum Kind { START, VERDICT, STATE, DEALT, TAKEN, DEFENSE, ESCAPE, NOTE, END }

    public record Event(long tick, Kind kind, String detail, double value) {
    }

    public static final class Fight {
        public String opponent;
        public long startMs;
        public long startTick;
        public long endTick;
        public String outcome = "";
        public double dealt;
        public double taken;
        public List<Event> events = new ArrayList<>();

        public double durationSeconds() {
            return Math.max(0, endTick - startTick) / 20.0;
        }

        public String summary() {
            return String.format(Locale.ROOT, "%s vs %s: %.1fs, dealt %.1f, taken %.1f, %d events", outcome.isEmpty() ? "ONGOING" : outcome,
                opponent, durationSeconds(), dealt, taken, events.size());
        }

        /** The last {@code n} timeline lines, for chat. */
        public List<String> timeline(int n) {
            List<String> out = new ArrayList<>();
            int from = Math.max(0, events.size() - n);
            for (int i = from; i < events.size(); i++) {
                Event e = events.get(i);
                out.add(String.format(Locale.ROOT, "+%.1fs %s %s%s", (e.tick() - startTick) / 20.0, e.kind(), e.detail(),
                    e.value() != 0 ? String.format(Locale.ROOT, " (%.1f)", e.value()) : ""));
            }
            return out;
        }
    }

    public static final int MAX_EVENTS = 400;
    public static final int MAX_FIGHTS = 20;
    private static final Gson GSON = new GsonBuilder().create();

    private final Deque<Fight> fights = new ArrayDeque<>();
    private Fight current;
    private String lastDetail = "";
    private Kind lastKind;

    public Fight current() {
        return current;
    }

    public Fight last() {
        return current != null ? current : fights.peekLast();
    }

    public List<Fight> history() {
        return new ArrayList<>(fights);
    }

    public void start(String opponent, long tick, long nowMs) {
        if (current != null) end("ABANDONED", tick);
        current = new Fight();
        current.opponent = opponent;
        current.startTick = tick;
        current.startMs = nowMs;
        current.endTick = tick;
        add(tick, Kind.START, opponent, 0);
    }

    /** Record an event; consecutive duplicates of the same kind+detail are collapsed. */
    public void event(long tick, Kind kind, String detail, double value) {
        if (current == null) return;
        if (kind == Kind.DEALT) current.dealt += value;
        if (kind == Kind.TAKEN) current.taken += value;
        if ((kind == Kind.VERDICT || kind == Kind.STATE || kind == Kind.DEFENSE) && kind == lastKind && detail.equals(lastDetail)) return;
        add(tick, kind, detail, value);
    }

    private void add(long tick, Kind kind, String detail, double value) {
        current.endTick = tick;
        lastKind = kind;
        lastDetail = detail;
        if (current.events.size() < MAX_EVENTS) current.events.add(new Event(tick, kind, detail, value));
    }

    /** Close the fight; returns it (for persisting) or null if none was open. */
    public Fight end(String outcome, long tick) {
        if (current == null) return null;
        add(tick, Kind.END, outcome, 0);
        current.outcome = outcome;
        Fight f = current;
        fights.addLast(f);
        while (fights.size() > MAX_FIGHTS) fights.removeFirst();
        current = null;
        lastKind = null;
        lastDetail = "";
        return f;
    }

    public static String toJsonLine(Fight f) {
        return GSON.toJson(f);
    }

    public static Fight fromJsonLine(String line) {
        return GSON.fromJson(line, Fight.class);
    }

    /** Keep the newest {@code max} lines. */
    public static List<String> trim(List<String> lines, int max) {
        return lines.size() <= max ? lines : new ArrayList<>(lines.subList(lines.size() - max, lines.size()));
    }
}
