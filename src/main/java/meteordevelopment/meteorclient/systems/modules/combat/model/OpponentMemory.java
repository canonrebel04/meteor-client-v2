/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.combat.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;

/**
 * Persisted learning (decision 4): per-opponent skill estimate and fight outcomes, serialized as JSON to the
 * mod config folder. The bias it yields is clamped so a few bad fights can't make the brain fearless or frozen.
 */
public final class OpponentMemory {
    public static final class Entry {
        public double skill = 0.5;
        public int wins;
        public int losses;
        public int escapes;
        public long lastSeen;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type TYPE = new TypeToken<HashMap<String, Entry>>() {}.getType();
    private final Map<String, Entry> entries = new HashMap<>();
    private boolean dirty;

    public Entry get(String name) {
        return entries.get(key(name));
    }

    public Entry getOrCreate(String name) {
        return entries.computeIfAbsent(key(name), k -> new Entry());
    }

    private static String key(String name) {
        return name == null ? "" : name.toLowerCase(java.util.Locale.ROOT);
    }

    public void recordSkill(String name, double skill, long now) {
        Entry e = getOrCreate(name);
        e.skill = e.skill + 0.3 * (Math.max(0, Math.min(1, skill)) - e.skill);
        e.lastSeen = now;
        dirty = true;
    }

    public enum Outcome { WIN, LOSS, ESCAPED }

    public void recordOutcome(String name, Outcome outcome, long now) {
        Entry e = getOrCreate(name);
        switch (outcome) {
            case WIN -> e.wins++;
            case LOSS -> e.losses++;
            case ESCAPED -> e.escapes++;
        }
        e.lastSeen = now;
        dirty = true;
    }

    /**
     * Kill-ratio multiplier from history: (wins+1)/(losses+1) smoothed, clamped to [0.8, 1.2]. Losing repeatedly
     * to someone makes us more careful against them.
     */
    public double ratioBias(String name) {
        Entry e = get(name);
        if (e == null) return 1.0;
        double r = Math.sqrt((e.wins + 1.0) / (e.losses + 1.0));
        return Math.max(0.8, Math.min(1.2, r));
    }

    public double skillPrior(String name) {
        Entry e = get(name);
        return e == null ? 0.5 : e.skill;
    }

    public int size() {
        return entries.size();
    }

    public boolean dirty() {
        return dirty;
    }

    public void write(Writer w) {
        GSON.toJson(entries, TYPE, w);
        dirty = false;
    }

    public void read(Reader r) {
        Map<String, Entry> m = GSON.fromJson(r, TYPE);
        entries.clear();
        if (m != null) {
            m.forEach((k, v) -> {
                if (k != null && v != null) entries.put(k, v);
            });
        }
        dirty = false;
    }
}
