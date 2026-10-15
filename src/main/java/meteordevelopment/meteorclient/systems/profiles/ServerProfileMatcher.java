/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.profiles;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** Pure server-address pattern matching for per-server profiles. */
public final class ServerProfileMatcher {
    private ServerProfileMatcher() {}

    /** A profile name with its address patterns and whether it is the default fallback. */
    public record Candidate(String name, List<String> patterns, boolean fallback) {}

    /** Lowercase, trim, drop a trailing dot and the default :25565 port. */
    public static String normalize(String address) {
        if (address == null) return "";
        String a = address.trim().toLowerCase(Locale.ROOT);
        if (a.endsWith(":25565")) a = a.substring(0, a.length() - 6);
        if (a.endsWith(".")) a = a.substring(0, a.length() - 1);
        return a;
    }

    /**
     * Glob match: {@code *} matches any run of characters. A pattern without a port matches any port.
     * {@code *.example.com} also matches {@code example.com} itself.
     */
    public static boolean matches(String pattern, String address) {
        String p = normalize(pattern);
        String a = normalize(address);
        if (p.isEmpty() || a.isEmpty()) return false;
        if (!p.contains(":")) {
            int colon = a.lastIndexOf(':');
            if (colon > 0) a = a.substring(0, colon);
        }
        if (p.startsWith("*.") && a.equals(p.substring(2))) return true;
        StringBuilder re = new StringBuilder();
        for (String part : p.split("\\*", -1)) {
            if (!re.isEmpty()) re.append(".*");
            re.append(Pattern.quote(part));
        }
        return a.matches(re.toString());
    }

    /** Specificity = number of literal (non-*) characters; more literal = more specific. */
    public static int specificity(String pattern) {
        return normalize(pattern).replace("*", "").length();
    }

    /**
     * Picks the profile to load for {@code address}: the most specific matching pattern wins (ties: list order).
     * If nothing matches and {@code multiplayer} is true, the first fallback profile is returned. Otherwise null.
     */
    public static String pick(List<Candidate> candidates, String address, boolean multiplayer) {
        String best = null;
        int bestScore = -1;
        for (Candidate c : candidates) {
            for (String pat : c.patterns()) {
                if (matches(pat, address)) {
                    int s = specificity(pat);
                    if (s > bestScore) { bestScore = s; best = c.name(); }
                }
            }
        }
        if (best != null || !multiplayer) return best;
        for (Candidate c : candidates) if (c.fallback()) return c.name();
        return null;
    }
}
