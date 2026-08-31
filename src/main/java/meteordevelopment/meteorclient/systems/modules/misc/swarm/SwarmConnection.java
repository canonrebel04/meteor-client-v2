/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc.swarm;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;

/**
 * Connection to a swarm worker.
 *
 * <p>Host→Worker: command strings ("swarm ..." dispatched on the worker via writeUTF).
 * Worker→Host: JSON intel lines ({@code SwarmWorkerIntel}) read on a separate reader thread
 * and routed into {@link Swarm#intelReports} via {@link #acceptIntel}.
 */
public class SwarmConnection extends Thread {
    public final Socket socket;
    public String messageToSend;

    public SwarmConnection(Socket socket) {
        this.socket = socket;
        start();
    }

    @Override
    public void run() {
        ChatUtils.infoPrefix("Swarm", "New worker connected on %s.", getIp(socket.getInetAddress().getHostAddress()));

        try {
            DataOutputStream out = new DataOutputStream(socket.getOutputStream());

            // Reader thread: drains worker→host intel lines off the same socket.
            Thread reader = new Thread(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line;
                    while (!isInterrupted() && (line = in.readLine()) != null) {
                        acceptIntel(line);
                    }
                } catch (IOException ignored) {
                    // Socket closed (disconnect) — normal shutdown path.
                }
            }, "Swarm-Intel-Reader");
            reader.setDaemon(true);
            reader.start();

            while (!isInterrupted()) {
                if (messageToSend != null) {
                    try {
                        out.writeUTF(messageToSend);
                        out.flush();
                    } catch (Exception e) {
                        ChatUtils.errorPrefix("Swarm", "Encountered error when sending command.");
                        MeteorClient.LOG.error("An error occurred", e);
                    }

                    messageToSend = null;
                }

                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    break;
                }
            }

            out.close();
        } catch (IOException e) {
            ChatUtils.infoPrefix("Swarm", "Error creating a connection with %s on port %s.", getIp(socket.getInetAddress().getHostAddress()), socket.getPort());
            MeteorClient.LOG.error("An error occurred", e);
        }
    }

    /**
     * Routes a raw worker line into the intel registry. Protocol rule: worker lines are JSON
     * (start with '{'); anything else is ignored for backward compatibility.
     */
    private void acceptIntel(String line) {
        if (line == null || !line.trim().startsWith("{")) return;

        Swarm swarm = Modules_getSwarm();
        if (swarm == null) return;

        try {
            com.google.gson.JsonObject obj = com.google.gson.JsonParser.parseString(line).getAsJsonObject();
            if ("intel".equals(obj.has("type") ? obj.get("type").getAsString() : "")) {
                swarm.intelReports.put(getConnection(), obj);
            }
        } catch (Exception e) {
            MeteorClient.LOG.error("Swarm: bad intel line from {}", getConnection(), e);
        }
    }

    private static Swarm Modules_getSwarm() {
        var module = meteordevelopment.meteorclient.systems.modules.Modules.get()
                .get(meteordevelopment.meteorclient.systems.modules.misc.swarm.Swarm.class);
        return module != null && module.isActive() ? module : null;
    }

    public void disconnect() {
        try {
            socket.close();
        } catch (IOException e) {
            MeteorClient.LOG.error("An error occurred", e);
        }

        ChatUtils.infoPrefix("Swarm", "Worker disconnected on ip: %s.", socket.getInetAddress().getHostAddress());

        interrupt();
    }

    public String getConnection() {
        return getIp(socket.getInetAddress().getHostAddress()) + ":" + socket.getPort();
    }

    private String getIp(String ip) {
        return ip.equals("127.0.0.1") ? "localhost" : ip;
    }
}
