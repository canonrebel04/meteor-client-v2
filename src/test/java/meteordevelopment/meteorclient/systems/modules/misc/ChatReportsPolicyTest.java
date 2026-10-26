package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.systems.modules.misc.ChatReportsPolicy.ChatAction;
import meteordevelopment.meteorclient.systems.modules.misc.ChatReportsPolicy.EnforcedMode;
import meteordevelopment.meteorclient.systems.modules.misc.ChatReportsPolicy.SessionAction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatReportsPolicyTest {
    @Test
    void sessionOnlySuppressedWhenServerKnownNotToEnforce() {
        assertEquals(SessionAction.SUPPRESS, ChatReportsPolicy.session(false));
        assertEquals(SessionAction.ALLOW, ChatReportsPolicy.session(true));
        assertEquals(SessionAction.ALLOW, ChatReportsPolicy.session(null));
    }

    @Test
    void stripOnlyIfKeyWasWithheld() {
        assertEquals(ChatAction.STRIP_SIGNATURE, ChatReportsPolicy.chat(false, true, EnforcedMode.SignNormally));
        // Key already uploaded (module enabled mid-session): server expects signatures, so don't strip.
        assertEquals(ChatAction.SEND_AS_IS, ChatReportsPolicy.chat(false, false, EnforcedMode.SignNormally));
    }

    @Test
    void enforcingServers() {
        assertEquals(ChatAction.SEND_AS_IS, ChatReportsPolicy.chat(true, false, EnforcedMode.SignNormally));
        assertEquals(ChatAction.BLOCK, ChatReportsPolicy.chat(true, false, EnforcedMode.BlockChat));
        assertEquals(ChatAction.BLOCK, ChatReportsPolicy.chat(null, false, EnforcedMode.BlockChat));
        assertEquals(ChatAction.SEND_AS_IS, ChatReportsPolicy.chat(null, false, EnforcedMode.SignNormally));
    }
}
