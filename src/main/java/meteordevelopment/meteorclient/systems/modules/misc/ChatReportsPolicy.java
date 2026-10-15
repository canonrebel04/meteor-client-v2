/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

/** Pure decision logic for {@link NoChatReports}. */
public final class ChatReportsPolicy {
    private ChatReportsPolicy() {}

    /** What to do on servers that enforce secure chat. */
    public enum EnforcedMode {
        /** Fall back to normal signed chat (no kick, but messages are reportable). */
        SignNormally,
        /** Never sign: block outgoing chat locally with a warning instead of getting kicked. */
        BlockChat
    }

    public enum SessionAction { SUPPRESS, ALLOW }

    public enum ChatAction { STRIP_SIGNATURE, SEND_AS_IS, BLOCK }

    /**
     * Whether to suppress the chat-session (public key) upload. Only safe when the server is known
     * not to enforce secure chat; an unknown state is treated as enforcing.
     */
    public static SessionAction session(Boolean serverEnforces) {
        return Boolean.FALSE.equals(serverEnforces) ? SessionAction.SUPPRESS : SessionAction.ALLOW;
    }

    /**
     * What to do with an outgoing signed chat/command.
     *
     * @param serverEnforces   value from the login packet, null if unknown
     * @param sessionSuppressed whether we withheld the session upload on this connection
     */
    public static ChatAction chat(Boolean serverEnforces, boolean sessionSuppressed, EnforcedMode mode) {
        // Stripping is only valid if the server never received our key; otherwise it expects signatures.
        if (sessionSuppressed && Boolean.FALSE.equals(serverEnforces)) return ChatAction.STRIP_SIGNATURE;
        if (!Boolean.FALSE.equals(serverEnforces) && mode == EnforcedMode.BlockChat) return ChatAction.BLOCK;
        return ChatAction.SEND_AS_IS;
    }
}
