/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.misc.ChatReportsPolicy.ChatAction;
import meteordevelopment.meteorclient.systems.modules.misc.ChatReportsPolicy.EnforcedMode;
import meteordevelopment.orbit.EventHandler;
import meteordevelopment.orbit.EventPriority;
import net.minecraft.network.protocol.game.*;

/**
 * Secure-chat aware NoChatReports, following the behaviour of the open-source No Chat Reports mod:
 * on servers that do NOT enforce secure chat, the chat-session public key is never uploaded and
 * outgoing messages are sent unsigned (so they cannot be reported to Mojang). On servers that DO
 * enforce secure chat, stripping would get you kicked, so the module falls back to normal signed
 * chat (or optionally blocks chat locally) and tells you.
 */
public class NoChatReports extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<EnforcedMode> enforcedMode = sgGeneral.add(new EnumSetting.Builder<EnforcedMode>()
        .name("on-enforcing-servers")
        .description("SignNormally: sign like vanilla to avoid a kick. BlockChat: never sign; outgoing chat is blocked locally.")
        .defaultValue(EnforcedMode.SignNormally)
        .build()
    );

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("notify").description("Tell you on join whether chat is unsigned or signed on this server.").defaultValue(true).build());

    /** null = no login packet seen yet on this connection. */
    private Boolean serverEnforces;
    private boolean sessionSuppressed;
    private boolean sessionSent;

    public NoChatReports() {
        super(Categories.Misc, "no-chat-reports", "Sends chat unsigned on servers that allow it, so messages can't be reported. Safe on enforcing servers.");
        runInMainMenu = true;
    }

    @Override
    public void onActivate() {
        if (mc.getConnection() != null && serverEnforces == null) {
            info("Takes full effect from your next server join (the chat key may already have been sent).");
        }
    }

    @EventHandler
    private void onLeave(GameLeftEvent event) {
        serverEnforces = null;
        sessionSuppressed = false;
        sessionSent = false;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onReceive(PacketEvent.Receive event) {
        if (event.packet instanceof ClientboundLoginPacket p) {
            serverEnforces = p.enforcesSecureChat();
            sessionSuppressed = false;
            sessionSent = false;
            if (notify.get()) {
                if (!serverEnforces) info("Server does not enforce secure chat: your messages will be sent (highlight)unsigned(default).");
                else if (enforcedMode.get() == EnforcedMode.BlockChat) warning("Server enforces secure chat: outgoing chat is (highlight)blocked(default) to avoid signing.");
                else warning("Server enforces secure chat: falling back to (highlight)signed(default) chat to avoid a kick.");
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    private void onSend(PacketEvent.Send event) {
        if (event.packet instanceof ServerboundChatSessionUpdatePacket) {
            if (!sessionSent && ChatReportsPolicy.session(serverEnforces) == ChatReportsPolicy.SessionAction.SUPPRESS) {
                event.cancel();
                sessionSuppressed = true;
            } else {
                sessionSent = true;
                sessionSuppressed = false;
            }
            return;
        }

        if (event.packet instanceof ServerboundChatPacket c) {
            ChatAction a = ChatReportsPolicy.chat(serverEnforces, sessionSuppressed, enforcedMode.get());
            if (a == ChatAction.BLOCK) { event.cancel(); warning("Chat blocked: this server requires signed chat."); }
            else if (a == ChatAction.STRIP_SIGNATURE && c.signature() != null) {
                event.cancel();
                event.sendSilently(new ServerboundChatPacket(c.message(), c.timeStamp(), c.salt(), null, c.lastSeenMessages()));
            }
        } else if (event.packet instanceof ServerboundChatCommandSignedPacket c) {
            ChatAction a = ChatReportsPolicy.chat(serverEnforces, sessionSuppressed, enforcedMode.get());
            if (a == ChatAction.BLOCK) { event.cancel(); warning("Signed command blocked: this server requires signed chat."); }
            else if (a == ChatAction.STRIP_SIGNATURE) {
                event.cancel();
                event.sendSilently(new ServerboundChatCommandPacket(c.command()));
            }
        }
    }
}
