/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import meteordevelopment.meteorclient.MeteorClient;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;

import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;

/**
 * Worker-side intel sender. Writes a JSON intel report over the existing swarm socket
 * once per second (called from the swarm module's tick handler on the worker).
 *
 * <p>Wire format (single line, worker → host):
 * <pre>
 * {
 *   "type": "intel",
 *   "bot": "worker-name",
 *   "pos": {"x": 1.5, "y": 64.0, "z": -3.2},
 *   "health": 18.5,
 *   "food": 20,
 *   "dimension": "minecraft:overworld",
 *   "players": [
 *     {"name": "Steve", "x": 12.1, "y": 63.0, "z": 8.7, "health": 20.0, "held": "diamond_sword"}
 *   ]
 * }
 * </pre>
 */
public class SwarmWorkerIntel extends Thread {
    private static final long INTERVAL_MS = 1000;

    private final Socket socket;
    private final Swarm swarm;
    private final Gson gson = new Gson();
    private boolean running = true;

    public SwarmWorkerIntel(Socket socket, Swarm swarm) {
        this.socket = socket;
        this.swarm = swarm;
        start();
    }

    @Override
    public void run() {
        while (running && !isInterrupted()) {
            try {
                String report = buildReport();
                if (report != null) {
                    sendReport(report);
                }
            } catch (Exception e) {
                MeteorClient.LOG.error("Swarm intel: failed to build report", e);
            }

            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }
        }
    }

    /** Sends the JSON report over the swarm socket, UTF-framed like all swarm traffic. */
    private void sendReport(String json) {
        try {
            synchronized (socket) {
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(json);
                out.flush();
            }
        } catch (IOException e) {
            MeteorClient.LOG.error("Swarm intel: failed to send report", e);
        }
    }

    /**
     * Builds the JSON report from live client state. Returns null before the player exists
     * (login screen / dimension change).
     */
    private String buildReport() {
        var mc = meteordevelopment.meteorclient.MeteorClient.mc;
        if (mc == null || mc.player == null || mc.level == null) return null;

        JsonObject root = new JsonObject();
        root.addProperty("type", "intel");
        root.addProperty("bot", mc.getUser().getName());

        JsonObject pos = new JsonObject();
        pos.addProperty("x", mc.player.getX());
        pos.addProperty("y", mc.player.getY());
        pos.addProperty("z", mc.player.getZ());
        root.add("pos", pos);

        root.addProperty("health", mc.player.getHealth());
        root.addProperty("food", mc.player.getFoodData().getFoodLevel());
        root.addProperty("dimension", mc.level.dimension().identifier().toString());

        JsonArray players = new JsonArray();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity == mc.player) continue;
            if (!(entity instanceof net.minecraft.world.entity.player.Player other)) continue;
            if (mc.player.distanceToSqr(other) > 128 * 128) continue; // beyond useful range

            JsonObject p = new JsonObject();
            p.addProperty("name", other.getName().getString());
            p.addProperty("x", other.getX());
            p.addProperty("y", other.getY());
            p.addProperty("z", other.getZ());
            p.addProperty("health", other.getHealth());
            var held = other.getMainHandItem();
            if (held != null && !held.isEmpty()) {
                p.addProperty("held", BuiltInRegistries.ITEM.getKey(held.getItem()).toString());
            }
            players.add(p);
        }
        root.add("players", players);

        return gson.toJson(root);
    }

    public void shutdown() {
        running = false;
        interrupt();
    }
}
