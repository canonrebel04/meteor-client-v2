/*
 * This file is part of the Meteor Client distribution (https://github.com/MeteorDevelopment/meteor-client).
 * Copyright (c) Meteor Development.
 */

package meteordevelopment.meteorclient.systems.modules.misc;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.misc.swarm.Swarm;
import meteordevelopment.meteorclient.utils.player.ChatUtils;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fleet intelligence bridge — connects the headless Mineflayer fleet's state server
 * (TCP :7500, newline-delimited JSON) into the client.
 *
 * <p>Provides two integrations:
 * <ol>
 *   <li><b>Radar + alerts</b>: player sightings reported by scout bots raise chat alerts and
 *       are queryable via {@code .fleet}.</li>
 *   <li><b>Swarm relay</b> (host only): sightings are injected into {@link Swarm#intelReports}
 *       so {@code .swarm intel} shows fleet contacts alongside worker contacts, making them
 *       visible to every swarm member through the normal swarm channel.</li>
 * </ol>
 */
public class FleetIntelModule extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> stateHost = sgGeneral.add(new StringSetting.Builder()
        .name("state-host")
        .description("Host running the Mineflayer fleet state server.")
        .defaultValue("127.0.0.1")
        .build()
    );

    private final Setting<Integer> statePort = sgGeneral.add(new IntSetting.Builder()
        .name("state-port")
        .description("State server TCP port.")
        .defaultValue(7500)
        .range(1, 65535)
        .noSlider()
        .build()
    );

    private final Setting<Boolean> alerts = sgGeneral.add(new BoolSetting.Builder()
        .name("alerts")
        .description("Chat-alert when a scout reports a player sighting.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> relayToSwarm = sgGeneral.add(new BoolSetting.Builder()
        .name("relay-to-swarm")
        .description("As swarm host: relay fleet contacts into .swarm intel.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> contactTimeoutSec = sgGeneral.add(new IntSetting.Builder()
        .name("contact-timeout")
        .description("Seconds a fleet contact stays fresh without an update.")
        .defaultValue(15)
        .min(3)
        .sliderMax(120)
        .build()
    );

    /** Fresh fleet-reported player contacts, keyed by player name. */
    private final ConcurrentHashMap<String, FleetContact> contacts = new ConcurrentHashMap<>();

    private Thread readerThread;
    public FleetIntelModule() {
        super(Categories.Misc, "fleet-intel", "Bridges the headless Mineflayer fleet (scout sightings) into the client: radar, alerts, and swarm relay.");
    }

    @Override
    public void onActivate() {
        startReader();
    }

    @Override
    public void onDeactivate() {
        if (readerThread != null) {
            readerThread.interrupt();
            readerThread = null;
        }
        contacts.clear();
    }

    @Override
    public String getInfoString() {
        return contacts.size() + " contacts";
    }

    private void startReader() {
        readerThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try (Socket sock = new Socket()) {
                    sock.connect(new InetSocketAddress(stateHost.get(), statePort.get()), 3000);
                    sock.setSoTimeout(5000);
                    BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream()));

                    String line;
                    while (!Thread.currentThread().isInterrupted() && (line = in.readLine()) != null) {
                        handleLine(line);
                    }
                } catch (Exception ignored) {
                    // Fleet not running / connection dropped — retry after a pause.
                }

                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    break;
                }
            }
        }, "FleetIntel-Reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }

    private void handleLine(String line) {
        try {
            JsonObject obj = JsonParser.parseString(line).getAsJsonObject();
            String type = obj.has("type") ? obj.get("type").getAsString() : "";
            long now = System.currentTimeMillis();

            if (type.equals("players") || type.equals("botstate")) {
                String bot = obj.has("bot") ? obj.get("bot").getAsString() : "unknown";

                if (type.equals("players") && obj.has("players")) {
                    for (JsonElement el : obj.getAsJsonArray("players")) {
                        JsonObject p = el.getAsJsonObject();
                        String name = p.get("name").getAsString();
                        FleetContact contact = new FleetContact(
                                name,
                                p.get("x").getAsDouble(),
                                p.get("y").getAsDouble(),
                                p.get("z").getAsDouble(),
                                p.has("health") ? p.get("health").getAsDouble() : -1,
                                p.has("held") ? p.get("held").getAsString() : "",
                                bot, now
                        );
                        contacts.put(name, contact);
                        onNewSighting(contact);
                    }
                }
            }
        } catch (Exception ignored) {
            // Malformed line — skip.
        }
    }

    private void onNewSighting(FleetContact contact) {
        if (alerts.get()) {
            String held = contact.held.isEmpty() ? "" : " holding " + contact.held;
            String hp = contact.health >= 0 ? String.format(" [%.0f hp]", contact.health) : "";
            warning("Fleet contact: (highlight)%s(default) at %.0f %.0f %.0f%s%s (scout: %s)",
                    contact.name, contact.x, contact.y, contact.z, hp, held, contact.scout);
        }

        if (relayToSwarm.get()) {
            relayToSwarm(contact);
        }
    }

    /** Relay a fleet sighting into the swarm host's intel registry (option-2 bridge). */
    private void relayToSwarm(FleetContact contact) {
        Swarm swarm = getSwarm();
        if (swarm == null || !swarm.isHost()) return;

        JsonObject report = new JsonObject();
        report.addProperty("type", "intel");
        report.addProperty("bot", "fleet");
        JsonObject pos = new JsonObject();
        pos.addProperty("x", contact.x);
        pos.addProperty("y", contact.y);
        pos.addProperty("z", contact.z);
        report.add("pos", pos);
        JsonArray players = new JsonArray();
        JsonObject p = new JsonObject();
        p.addProperty("name", contact.name);
        p.addProperty("x", contact.x);
        p.addProperty("y", contact.y);
        p.addProperty("z", contact.z);
        if (contact.health >= 0) p.addProperty("health", contact.health);
        if (!contact.held.isEmpty()) p.addProperty("held", contact.held);
        players.add(p);
        report.add("players", players);

        // Namespace key so fleet relays don't collide with worker connection ids.
        swarm.intelReports.put("fleet:" + contact.scout, report);
    }

    private Swarm getSwarm() {
        Swarm swarm = meteordevelopment.meteorclient.systems.modules.Modules.get().get(Swarm.class);
        return swarm != null && swarm.isActive() ? swarm : null;
    }

    /** Drops stale contacts; called from the module tick. */
    private void prune() {
        long cutoff = System.currentTimeMillis() - contactTimeoutSec.get() * 1000L;
        contacts.values().removeIf(c -> c.seenAt < cutoff);
    }

    // FleetContact -----------------------------------------------------------

    public static class FleetContact {
        public final String name;
        public final double x, y, z;
        public final double health;
        public final String held;
        public final String scout;
        public final long seenAt;

        FleetContact(String name, double x, double y, double z, double health, String held, String scout, long seenAt) {
            this.name = name;
            this.x = x;
            this.y = y;
            this.z = z;
            this.health = health;
            this.held = held;
            this.scout = scout;
            this.seenAt = seenAt;
        }
    }

    public java.util.Collection<FleetContact> getContacts() {
        prune();
        return contacts.values();
    }
}
