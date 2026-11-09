package meteordevelopment.meteorclient.systems.modules.combat;

import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SuperKnockbackTest {
    @Test
    void sequences() {
        assertEquals(List.of(Action.STOP_SPRINTING, Action.START_SPRINTING), SuperKnockback.packetSequence(true));
        assertEquals(List.of(Action.START_SPRINTING), SuperKnockback.packetSequence(false));
    }
}
