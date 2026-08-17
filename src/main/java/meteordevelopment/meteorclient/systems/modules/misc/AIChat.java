/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 *
 * AIChat: an LLM companion that thinks and talks through Minecraft chat (Voyager-style).
 * - Listens to in-game chat, triggers on a trigger word (or all messages), sends context to an
 *   OpenAI-compatible /chat/completions endpoint, and speaks the reply back through chat.
 * - The AI can issue commands: lines starting with the command prefix are executed client-side —
 *   Baritone commands via `b <cmd>` (goto/mine/follow/...), otherwise Meteor commands, with
 *   Baritone as fallback.
 * - API key security: stored only in the local Meteor config, sent ONLY to the configured base
 *   URL as a Bearer header, never included in chat, history, or logs.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.pathing.goals.Goal;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.game.SendMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.entity.EntityUtils;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;
import java.util.regex.Pattern;

public class AIChat extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgApi = settings.createGroup("API");

    // API

    private final Setting<String> apiKey = sgApi.add(new StringSetting.Builder()
        .name("api-key")
        .description("Your LLM API key. Stored only in the local Meteor config and sent ONLY to the base URL as a Bearer header — never in chat, history, or logs.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> baseUrl = sgApi.add(new StringSetting.Builder()
        .name("base-url")
        .description("OpenAI-compatible API base URL (e.g. https://api.openai.com/v1).")
        .defaultValue("https://api.openai.com/v1")
        .build()
    );

    private final Setting<String> model = sgApi.add(new StringSetting.Builder()
        .name("model")
        .description("Model to use.")
        .defaultValue("gpt-4o-mini")
        .build()
    );

    // General

    private final Setting<String> systemPrompt = sgGeneral.add(new StringSetting.Builder()
        .name("system-prompt")
        .description("System prompt that defines the AI's behavior. Mention the command prefix so it knows how to run commands.")
        .defaultValue("You are an AI companion living inside Minecraft chat. You think and talk through chat. To run a command, output it on its own line starting with '!' — use 'b <command>' for Baritone commands (e.g. '!b goto 100 64 100', '!b mine diamond_ore 32', '!b follow <name>', '!b cancel') and plain Meteor commands otherwise (e.g. '!toggle killaura'). You can query your state with '!status' (position, health, pathing). Example exchange: [chat] Steve: assistant, go to my base at 100 64 100 → you reply with '!b goto 100 64 100' followed by a short confirmation. Keep replies short and in character.")
        .build()
    );

    private final Setting<String> ignoreRegex = sgGeneral.add(new StringSetting.Builder()
        .name("ignore-regex")
        .description("Chat lines matching this regex are excluded from context and never trigger a response (e.g. 'Set the time to|joined the game'). Empty = nothing ignored.")
        .defaultValue("")
        .build()
    );

    private final Setting<String> triggerWord = sgGeneral.add(new StringSetting.Builder()
        .name("trigger-word")
        .description("Responds when a chat message contains this word (case-insensitive). Set to your own name.")
        .defaultValue("assistant")
        .build()
    );

    private final Setting<Boolean> respondToAll = sgGeneral.add(new BoolSetting.Builder()
        .name("respond-to-all")
        .description("Responds to every chat message instead of only mentions.")
        .defaultValue(false)
        .build()
    );

    private final Setting<String> commandPrefix = sgGeneral.add(new StringSetting.Builder()
        .name("command-prefix")
        .description("Lines of the AI reply starting with this prefix are executed as commands.")
        .defaultValue("!")
        .build()
    );

    private final Setting<Integer> contextSize = sgGeneral.add(new IntSetting.Builder()
        .name("context-size")
        .description("How many recent chat messages to send to the API.")
        .defaultValue(24)
        .range(4, 64)
        .build()
    );

    private final Setting<Integer> maxTokens = sgGeneral.add(new IntSetting.Builder()
        .name("max-tokens")
        .description("Maximum tokens in the AI reply.")
        .defaultValue(200)
        .range(32, 2048)
        .build()
    );

    private final Setting<Double> temperature = sgGeneral.add(new DoubleSetting.Builder()
        .name("temperature")
        .description("Sampling temperature.")
        .defaultValue(0.7)
        .range(0, 2)
        .sliderMax(2)
        .build()
    );

    private final Setting<Boolean> sendAsChat = sgGeneral.add(new BoolSetting.Builder()
        .name("send-as-chat")
        .description("Sends the AI reply to the server as a normal chat message (Voyager-style). When off, shows it as a client-side system message only.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showThinking = sgGeneral.add(new BoolSetting.Builder()
        .name("show-thinking")
        .description("Shows a 'Thinking…' status while the API call is in flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> timeoutSeconds = sgGeneral.add(new IntSetting.Builder()
        .name("timeout-seconds")
        .description("API request timeout.")
        .defaultValue(45)
        .range(5, 120)
        .build()
    );

    private final Deque<String> history = new ArrayDeque<>();
    private boolean busy;
    private boolean aiSending;
    private String lastSent = "";
    private int statusFeedbackTicks = 0;

    public AIChat() {
        super(Categories.Misc, "ai-chat", "LLM companion that thinks and talks through Minecraft chat and can run Baritone/Meteor commands.");
    }

    @Override
    public void onActivate() {
        history.clear();
        busy = false;
        aiSending = false;
        lastSent = "";
        statusFeedbackTicks = 0;
    }

    @Override
    public void onDeactivate() {
        busy = false;
        aiSending = false;
        statusFeedbackTicks = 0;
    }

    /** Public entry point for the .ai command. */
    public void ask(String message) {
        if (mc.player == null) return;
        if (busy) {
            ChatUtils.infoPrefix("[AI]", "Busy…");
            return;
        }
        history.addLast("[you] " + message);
        trimHistory();
        respond();
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!(event.packet instanceof ClientboundSystemChatPacket packet)) return;
        String text = packet.content().getString();
        if (text.isBlank()) return;
        if (isNoise(text)) return; // command echoes + ignore-regex (mindcraft/Voyager pattern)

        history.addLast(aiSending ? "[ai] " + text : "[chat] " + text);
        trimHistory();

        if (busy || aiSending || text.equals(lastSent)) return;

        String trigger = triggerWord.get().toLowerCase();
        if (respondToAll.get() || (!trigger.isEmpty() && text.toLowerCase().contains(trigger))) {
            respond();
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        // Delayed command-result feedback: snapshot Baritone state a few ticks after a
        // command so the LLM sees the outcome in its next context (mindcraft ActionManager pattern).
        if (statusFeedbackTicks > 0) {
            statusFeedbackTicks--;
            if (statusFeedbackTicks == 0) {
                history.addLast("[status] " + snapshotStatus());
                trimHistory();
            }
        }
    }

    @EventHandler
    private void onSendMessage(SendMessageEvent event) {
        if (aiSending) return; // our own AI message
        history.addLast("[you] " + event.message);
        trimHistory();
    }

    private void respond() {
        busy = true;
        if (showThinking.get()) ChatUtils.infoPrefix("[AI]", "Thinking…");

        String[] snapshot = history.toArray(new String[0]);

        new Thread(() -> {
            try {
                String reply = callApi(snapshot);
                mc.execute(() -> {
                    try {
                        processReply(reply);
                    } finally {
                        busy = false;
                    }
                });
            } catch (Exception e) {
                String err = e.getMessage() == null ? e.toString() : e.getMessage();
                if (err.length() > 200) err = err.substring(0, 200);
                String finalErr = err;
                mc.execute(() -> {
                    ChatUtils.errorPrefix("[AI]", "Error: %s", finalErr);
                    busy = false;
                });
            }
        }, "AIChat").start();
    }

    private String callApi(String[] snapshot) throws Exception {
        if (apiKey.get().isBlank()) throw new IllegalStateException("API key not set (ai-chat -> api-key)");

        String base = baseUrl.get().trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);

        HttpURLConnection conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey.get());
        conn.setConnectTimeout(timeoutSeconds.get() * 1000);
        conn.setReadTimeout(timeoutSeconds.get() * 1000);
        conn.setDoOutput(true);

        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", systemPrompt.get());
        messages.add(sys);

        int from = Math.max(0, snapshot.length - contextSize.get());
        for (int i = from; i < snapshot.length; i++) {
            String line = snapshot[i];
            if (isNoise(line)) continue; // never leak command echoes into context
            JsonObject m = new JsonObject();
            m.addProperty("role", "user");
            m.addProperty("content", line);
            messages.add(m);
        }

        JsonObject body = new JsonObject();
        body.addProperty("model", model.get());
        body.add("messages", messages);
        body.addProperty("max_tokens", maxTokens.get());
        body.addProperty("temperature", temperature.get());

        try (OutputStream os = conn.getOutputStream()) {
            os.write(new Gson().toJson(body).getBytes(StandardCharsets.UTF_8));
        }

        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            String errBody = "";
            InputStream es = conn.getErrorStream();
            if (es != null) errBody = new String(es.readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("API " + code + ": " + errBody);
        }

        try (InputStream is = conn.getInputStream()) {
            String resp = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(resp).getAsJsonObject();
            JsonObject message = root.getAsJsonArray("choices").get(0).getAsJsonObject().getAsJsonObject("message");
            return message.get("content").isJsonNull() ? "" : message.get("content").getAsString();
        }
    }

    private void processReply(String reply) {
        if (reply.isBlank()) return;

        String prefix = commandPrefix.get();
        StringBuilder display = new StringBuilder();

        for (String rawLine : reply.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) continue;

            if (line.startsWith(prefix)) {
                String cmd = line.substring(prefix.length()).trim();
                if (!cmd.isEmpty()) {
                    ChatUtils.infoPrefix("[AI]", "> %s", cmd);
                    executeCommand(cmd);
                }
            } else {
                if (display.length() > 0) display.append(' ');
                display.append(line);
            }
        }

        String text = display.toString().trim();
        if (text.isEmpty()) return;

        lastSent = text;
        aiSending = true;
        try {
            if (sendAsChat.get() && !startsCommandLike(text)) {
                ChatUtils.sendPlayerMsg(text, false);
            } else {
                ChatUtils.infoPrefix("[AI]", text);
            }
        } finally {
            aiSending = false;
        }
    }

    private boolean startsCommandLike(String text) {
        return text.startsWith(Config.get().prefix.get())
            || (BaritoneAPI.getProvider() != null && text.startsWith(BaritoneAPI.getSettings().prefix.value));
    }

    private void executeCommand(String cmd) {
        if (cmd.startsWith("b ")) {
            if (baritone(cmd.substring(2))) statusFeedbackTicks = 40;
            return;
        }

        // Client-side query commands (mindcraft pattern): give the LLM eyes on its state.
        String q = cmd.toLowerCase();
        switch (q) {
            case "status", "pos", "path", "target", "pathstatus", "where" -> {
                String status = snapshotStatus();
                ChatUtils.infoPrefix("[AI]", "Status: %s", status);
                history.addLast("[status] " + status);
                trimHistory();
                return;
            }
            case "stop", "cancel" -> {
                if (!baritone("cancel") && !baritone("stop")) {
                    ChatUtils.warningPrefix("[AI]", "Could not cancel pathing.");
                }
                return;
            }
        }

        String meteorPrefix = Config.get().prefix.get();
        String stripped = cmd.startsWith(meteorPrefix) ? cmd.substring(meteorPrefix.length()) : cmd;

        try {
            Commands.dispatch(stripped);
            return;
        } catch (CommandSyntaxException ignored) {
            // not a Meteor command — try Baritone
        }

        if (!baritone(cmd)) {
            ChatUtils.warningPrefix("[AI]", "Unknown command: %s", cmd);
        } else {
            statusFeedbackTicks = 40;
        }
    }

    /** Compact client state snapshot fed back to the LLM as [status] context. */
    private String snapshotStatus() {
        if (mc.player == null) return "not in game";
        StringBuilder sb = new StringBuilder();
        sb.append(String.format(Locale.US, "pos=%.1f,%.1f,%.1f health=%.0f",
            mc.player.getX(), mc.player.getY(), mc.player.getZ(),
            EntityUtils.getTotalHealth(mc.player)));
        try {
            IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
            sb.append(" pathing=").append(baritone.getPathingBehavior().isPathing());
            Goal goal = baritone.getPathingBehavior().getGoal();
            if (goal != null) sb.append(" goal=").append(goal);
            var pc = baritone.getPathingControlManager().mostRecentCommand();
            if (pc.isPresent()) sb.append(" state=").append(pc.get().commandType);
        } catch (Exception ignored) {
            // Baritone not loaded or API changed — status still useful without it
        }
        return sb.toString();
    }

    private boolean isNoise(String line) {
        if (line.startsWith("/")) return true; // command echoes (Voyager onChat pattern)
        String regex = ignoreRegex.get();
        return !regex.isEmpty() && Pattern.compile(regex).matcher(line).find();
    }

    private boolean baritone(String cmd) {
        try {
            return BaritoneAPI.getProvider().getPrimaryBaritone().getCommandManager().execute(cmd);
        } catch (Exception e) {
            return false;
        }
    }

    private void trimHistory() {
        while (history.size() > Math.max(contextSize.get(), 8)) {
            history.removeFirst();
        }
    }
}
