package meteordevelopment.meteorclient.systems.modules.combat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AntiBotHeuristicsTest {
    private static final AntiBotHeuristics.Config DEFAULTS = new AntiBotHeuristics.Config(true, true, false, 0, false, false, true);

    private static AntiBotHeuristics.Signals real(String name) {
        return new AntiBotHeuristics.Signals(name, true, true, 50, 400, false, true, true);
    }

    @Test
    void realPlayerPasses() {
        assertFalse(AntiBotHeuristics.isBot(real("Notch_123"), DEFAULTS));
    }

    @Test
    void notInTab() {
        var s = new AntiBotHeuristics.Signals("Steve", false, false, 0, 400, false, true, false);
        assertTrue(AntiBotHeuristics.isBot(s, DEFAULTS));
    }

    @Test
    void invalidNames() {
        assertTrue(AntiBotHeuristics.isBot(real("§cNPC"), DEFAULTS));
        assertTrue(AntiBotHeuristics.isBot(real("ab"), DEFAULTS));
        assertTrue(AntiBotHeuristics.isBot(real("averyveryverylongname"), DEFAULTS));
    }

    @Test
    void optionalChecksOffByDefault() {
        var s = new AntiBotHeuristics.Signals("Steve", true, true, 0, 1, true, false, true);
        assertFalse(AntiBotHeuristics.isBot(s, DEFAULTS));
        var strict = new AntiBotHeuristics.Config(true, true, true, 20, true, true, true);
        assertTrue(AntiBotHeuristics.isBot(s, strict));
    }

    @Test
    void tabNameMismatch() {
        var s = new AntiBotHeuristics.Signals("Steve", true, true, 50, 400, false, true, false);
        assertTrue(AntiBotHeuristics.isBot(s, DEFAULTS));
    }
}
