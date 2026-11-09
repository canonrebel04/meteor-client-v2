package meteordevelopment.meteorclient.systems.modules.world;

import meteordevelopment.meteorclient.systems.modules.world.AutoLibrarianLogic.BookOffer;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class AutoLibrarianLogicTest {
    private static final Set<String> WANT = Set.of("minecraft:mending", "minecraft:protection");

    @Test
    void matchesWanted() {
        var m = AutoLibrarianLogic.findMatch(List.of(new BookOffer("minecraft:mending", 1, 1, 20)), WANT, 64, true);
        assertNotNull(m);
    }

    @Test
    void rejectsPriceLevelAndUnwanted() {
        assertNull(AutoLibrarianLogic.findMatch(List.of(new BookOffer("minecraft:mending", 1, 1, 40)), WANT, 30, true));
        assertNull(AutoLibrarianLogic.findMatch(List.of(new BookOffer("minecraft:protection", 3, 4, 10)), WANT, 64, true));
        assertNotNull(AutoLibrarianLogic.findMatch(List.of(new BookOffer("minecraft:protection", 3, 4, 10)), WANT, 64, false));
        assertNull(AutoLibrarianLogic.findMatch(List.of(new BookOffer("minecraft:unbreaking", 3, 3, 5)), WANT, 64, true));
    }
}
