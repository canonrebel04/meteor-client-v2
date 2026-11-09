package meteordevelopment.meteorclient.systems.modules;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Source-level guard so the discoverability aliases for fork-only modules don't silently disappear. */
class ForkModuleAliasesTest {
    private static final Path ROOT = Path.of("src/main/java/meteordevelopment/meteorclient/systems/modules");

    @Test
    void aliasesPresent() throws Exception {
        Map<String, String> expected = Map.of(
            "misc/AIChat.java", "\"llm\"",
            "misc/FleetIntelModule.java", "\"fleet\"",
            "movement/AutoJumpReset.java", "\"jump-reset\"",
            "combat/ShieldAutoSwapModule.java", "\"auto-shield\"",
            "combat/overlay/ThreatIndicatorModule.java", "\"threat-arrows\""
        );
        for (var e : expected.entrySet()) {
            String src = Files.readString(ROOT.resolve(e.getKey()));
            assertTrue(src.contains(e.getValue()), e.getKey() + " missing alias " + e.getValue());
        }
    }
}
