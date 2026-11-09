package meteordevelopment.meteorclient.systems.profiles;

import meteordevelopment.meteorclient.systems.profiles.ServerProfileMatcher.Candidate;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ServerProfileMatcherTest {
    @Test
    void normalize() {
        assertEquals("play.example.net", ServerProfileMatcher.normalize(" Play.Example.NET:25565 "));
        assertEquals("a.b:25570", ServerProfileMatcher.normalize("a.b:25570"));
        assertEquals("a.b", ServerProfileMatcher.normalize("a.b."));
    }

    @Test
    void globs() {
        assertTrue(ServerProfileMatcher.matches("*.2b2t.org", "connect.2b2t.org"));
        assertTrue(ServerProfileMatcher.matches("*.2b2t.org", "2b2t.org"));
        assertFalse(ServerProfileMatcher.matches("*.2b2t.org", "not2b2t.org"));
        assertTrue(ServerProfileMatcher.matches("hypixel.net", "HYPIXEL.net:25565"));
        assertTrue(ServerProfileMatcher.matches("hypixel.net", "hypixel.net:25570"));
        assertFalse(ServerProfileMatcher.matches("hypixel.net:25570", "hypixel.net"));
        assertTrue(ServerProfileMatcher.matches("*", "anything.com"));
        assertFalse(ServerProfileMatcher.matches("a.b", ""));
    }

    @Test
    void mostSpecificWins() {
        var cands = List.of(
            new Candidate("anarchy", List.of("*.org"), false),
            new Candidate("2b", List.of("*.2b2t.org"), false),
            new Candidate("default", List.of(), true)
        );
        assertEquals("2b", ServerProfileMatcher.pick(cands, "connect.2b2t.org", true));
        assertEquals("anarchy", ServerProfileMatcher.pick(cands, "other.org", true));
        assertEquals("default", ServerProfileMatcher.pick(cands, "hypixel.net", true));
        assertNull(ServerProfileMatcher.pick(cands, "My World", false));
    }

    @Test
    void noFallback() {
        assertNull(ServerProfileMatcher.pick(List.of(new Candidate("x", List.of("a.com"), false)), "b.com", true));
    }
}
