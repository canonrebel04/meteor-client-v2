/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.utils.bot;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.commands.Commands;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screens.Screen;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static meteordevelopment.meteorclient.MeteorClient.mc;

/**
 * Localhost-only HTTP bridge for automated/agent testing of Meteor, Baritone,
 * in-game chat, and mod compatibility.
 *
 * <p>Dormant unless {@code -Dmeteor.agentBridge=true} is passed to the JVM.
 * Port defaults to {@code 25580} and can be overridden with {@code -Dmeteor.agentPort=<port>}.
 */
public final class AgentBridge {
    private static final int MAX_CHAT_HISTORY = 250;
    private static final AgentBridge INSTANCE = new AgentBridge();

    private record ChatEntry(long seq, String text) {}

    private final Deque<ChatEntry> chatHistory = new ArrayDeque<>(MAX_CHAT_HISTORY);
    private long nextSeq = 1;
    private HttpServer server;

    private AgentBridge() {}

    public static void init() {
        if (!Boolean.getBoolean("meteor.agentBridge")) return;
        INSTANCE.start();
    }

    private void start() {
        int port = Integer.getInteger("meteor.agentPort", 25580);
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
            server.createContext("/status", this::handleStatus);
            server.createContext("/chat", this::handleChat);
            server.createContext("/meteor", this::handleMeteor);
            server.createContext("/baritone", this::handleBaritone);
            server.createContext("/module", this::handleModule);
            server.setExecutor(Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "Meteor-AgentBridge");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            MeteorClient.EVENT_BUS.subscribe(this);
            MeteorClient.LOG.info("AgentBridge listening on http://127.0.0.1:{}", port);
        } catch (IOException e) {
            MeteorClient.LOG.error("Failed to start AgentBridge on port {}", port, e);
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        String text = event.getMessage().getString();
        synchronized (chatHistory) {
            if (chatHistory.size() >= MAX_CHAT_HISTORY) {
                chatHistory.removeFirst();
            }
            chatHistory.addLast(new ChatEntry(nextSeq++, text));
        }
    }

    private void handleStatus(HttpExchange ex) throws IOException {
        if (!"GET".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }
        String json = runOnClientThread(() -> {
            JsonObject root = new JsonObject();
            boolean inGame = mc.player != null && mc.level != null;
            root.addProperty("inGame", inGame);
            Screen screen = mc.gui != null ? mc.gui.screen() : null;
            root.addProperty("screen", screen != null ? screen.getClass().getSimpleName() : "none");

            if (inGame) {
                root.addProperty("player", mc.player.getName().getString());
                JsonObject pos = new JsonObject();
                pos.addProperty("x", mc.player.getX());
                pos.addProperty("y", mc.player.getY());
                pos.addProperty("z", mc.player.getZ());
                root.add("pos", pos);
                root.addProperty("health", mc.player.getHealth());
                root.addProperty("food", mc.player.getFoodData().getFoodLevel());
                root.addProperty("saturation", mc.player.getFoodData().getSaturationLevel());
                root.addProperty("air", mc.player.getAirSupply());
                root.addProperty("maxAir", mc.player.getMaxAirSupply());
                root.addProperty("dimension", mc.level.dimension().identifier().toString());
            }

            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                root.addProperty("baritonePathing", baritone != null && baritone.getPathingBehavior().isPathing());
            } catch (Exception e) {
                root.addProperty("baritonePathing", false);
            }

            JsonArray active = new JsonArray();
            for (Module m : Modules.get().getActive()) {
                JsonObject mo = new JsonObject();
                mo.addProperty("name", m.name);
                String info = m.getInfoString();
                if (info != null) mo.addProperty("info", info);
                active.add(mo);
            }
            root.add("activeModules", active);
            return root.toString();
        });
        sendJson(ex, 200, json);
    }

    private void handleChat(HttpExchange ex) throws IOException {
        if ("GET".equalsIgnoreCase(ex.getRequestMethod())) {
            long since = parseLongParam(ex.getRequestURI().getQuery(), "since", 0L);
            JsonArray arr = new JsonArray();
            synchronized (chatHistory) {
                for (ChatEntry entry : chatHistory) {
                    if (entry.seq() > since) {
                        JsonObject o = new JsonObject();
                        o.addProperty("seq", entry.seq());
                        o.addProperty("text", entry.text());
                        arr.add(o);
                    }
                }
            }
            sendJson(ex, 200, arr.toString());
            return;
        }

        if ("POST".equalsIgnoreCase(ex.getRequestMethod())) {
            String msg = readBody(ex).trim();
            if (msg.isEmpty()) {
                sendText(ex, 400, "Empty message");
                return;
            }
            String result = runOnClientThread(() -> {
                if (mc.player == null || mc.player.connection == null) return "not_in_game";
                if (msg.startsWith("/")) {
                    mc.player.connection.sendCommand(msg.substring(1));
                } else {
                    mc.player.connection.sendChat(msg);
                }
                return "ok";
            });
            sendText(ex, 200, result);
            return;
        }

        sendText(ex, 405, "Method Not Allowed");
    }

    private void handleMeteor(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }
        String cmd = readBody(ex).trim();
        if (cmd.isEmpty()) {
            sendText(ex, 400, "Empty command");
            return;
        }
        String result = runOnClientThread(() -> {
            try {
                Commands.dispatch(cmd);
                return "ok";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        });
        sendText(ex, 200, result);
    }

    private void handleBaritone(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }
        String cmd = readBody(ex).trim();
        if (cmd.isEmpty()) {
            sendText(ex, 400, "Empty command");
            return;
        }
        String result = runOnClientThread(() -> {
            try {
                IBaritone baritone = BaritoneAPI.getProvider().getPrimaryBaritone();
                if (baritone == null) return "no_baritone";
                return baritone.getCommandManager().execute(cmd) ? "ok" : "rejected";
            } catch (Exception e) {
                return "error: " + e.getMessage();
            }
        });
        sendText(ex, 200, result);
    }

    private void handleModule(HttpExchange ex) throws IOException {
        if (!"POST".equalsIgnoreCase(ex.getRequestMethod())) {
            sendText(ex, 405, "Method Not Allowed");
            return;
        }
        String query = ex.getRequestURI().getQuery();
        String name = parseStringParam(query, "name");
        String action = parseStringParam(query, "action");
        if (name == null || name.isEmpty()) {
            sendText(ex, 400, "Missing ?name=");
            return;
        }
        String result = runOnClientThread(() -> {
            Module m = Modules.get().get(name);
            if (m == null) return "not_found";
            if ("enable".equalsIgnoreCase(action)) {
                if (!m.isActive()) m.toggle();
            } else if ("disable".equalsIgnoreCase(action)) {
                if (m.isActive()) m.toggle();
            } else {
                m.toggle();
            }
            return m.isActive() ? "active" : "inactive";
        });
        sendText(ex, 200, result);
    }

    private static <T> T runOnClientThread(java.util.function.Supplier<T> task) {
        if (mc.isSameThread()) return task.get();
        CompletableFuture<T> future = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String readBody(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void sendJson(HttpExchange ex, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendText(HttpExchange ex, int code, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static long parseLongParam(String query, String key, long def) {
        String val = parseStringParam(query, key);
        if (val == null) return def;
        try {
            return Long.parseLong(val);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private static String parseStringParam(String query, String key) {
        if (query == null || query.isEmpty()) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(key)) {
                return part.substring(eq + 1);
            }
        }
        return null;
    }
}
