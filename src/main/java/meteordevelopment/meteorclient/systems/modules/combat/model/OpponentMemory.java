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
        /** C94: our weapon kind -> {wins, losses} against this opponent. Null in files written before P3. */
        public HashMap<String, int[]> byWeapon;
    }

    /** Fights per opponent (or per weapon) before old results are halved, so the model keeps adapting. */
    public static final int DECAY_AT = 40;
    public static final int MAX_ENTRIES = 512;

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
        recordOutcome(name, outcome, null, now);
    }

    /** As {@link #recordOutcome(String, Outcome, long)} also crediting {@code myWeapon} (C94). */
    public void recordOutcome(String name, Outcome outcome, String myWeapon, long now) {
        Entry e = getOrCreate(name);
        switch (outcome) {
            case WIN -> e.wins++;
            case LOSS -> e.losses++;
            case ESCAPED -> e.escapes++;
        }
        if (e.wins + e.losses > DECAY_AT) {
            e.wins /= 2;
            e.losses /= 2;
            e.escapes /= 2;
        }
        if (myWeapon != null && !myWeapon.isEmpty() && outcome != Outcome.ESCAPED) {
            if (e.byWeapon == null) e.byWeapon = new HashMap<>();
            int[] wl = e.byWeapon.computeIfAbsent(myWeapon, k -> new int[2]);
            wl[outcome == Outcome.WIN ? 0 : 1]++;
            if (wl[0] + wl[1] > DECAY_AT) {
                wl[0] /= 2;
                wl[1] /= 2;
            }
        }
        e.lastSeen = now;
        evict();
        dirty = true;
    }

    private void evict() {
        while (entries.size() > MAX_ENTRIES) {
            String oldest = null;
            long t = Long.MAX_VALUE;
            for (Map.Entry<String, Entry> me : entries.entrySet()) {
                if (me.getValue().lastSeen < t) {
                    t = me.getValue().lastSeen;
                    oldest = me.getKey();
                }
            }
            if (oldest == null) return;
            entries.remove(oldest);
        }
    }

    /**
     * C94: how our weapon kind has fared across all remembered fights, smoothed and clamped to [0.85, 1.15]. With
     * an opponent name, results against that opponent count double.
     */
    public double weaponBias(String myWeapon, String name) {
        if (myWeapon == null || myWeapon.isEmpty()) return 1.0;
        double w = 0, l = 0;
        Entry mine = name == null ? null : get(name);
        for (Entry e : entries.values()) {
            if (e.byWeapon == null) continue;
            int[] wl = e.byWeapon.get(myWeapon);
            if (wl == null) continue;
            double k = e == mine ? 2 : 1;
            w += k * wl[0];
            l += k * wl[1];
        }
        double r = Math.sqrt((w + 2.0) / (l + 2.0));
        return Math.max(0.85, Math.min(1.15, r));
    }

    /** Opponent history times weapon history, clamped to [0.75, 1.25] so learning can only nudge a verdict. */
    public double combinedBias(String name, String myWeapon) {
        return Math.max(0.75, Math.min(1.25, ratioBias(name) * weaponBias(myWeapon, name)));
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
