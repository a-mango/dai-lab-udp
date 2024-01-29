package ch.heig.dai.lab.udp.auditor;

import com.google.gson.*;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.lang.reflect.Type;
import java.net.*;
import java.util.HashMap;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Multithreaded program that listens for UDP messages from musicians, maintains a list of musicians and serves it to
 * TCP clients.
 *
 * @author Aubry Mangold <aubry.mangold@heig-vd.ch>
 * @author Hugo Germano <hugo.germano@heig-vd.ch>
 */
public class Main {
    /**
     * The JSON serializer/deserializer. Created using a builder to enable pretty printing custom deserialization.
     */
    private static final Gson gson = new GsonBuilder().setPrettyPrinting().registerTypeAdapter(Musician.class, new MusicianDeserializer()).create();

    /**
     * The list of musicians found by the auditor.
     */
    private static final Set<Musician> musicians = ConcurrentHashMap.newKeySet();

    /**
     * The main entry point of the program.
     */
    public static void main(String[] args) {
        System.out.println("Starting auditor");
        try (final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            executor.execute(new RunnableListener());
            executor.execute(new RunnableServer());
            executor.execute(new RunnableWatcher());

            // Wait for all threads to finish.
            executor.shutdown();
        } catch (Exception e) {
            System.err.println(e.getMessage());
        }
    }

    /**
     * The instrument that a musician plays. The values are in lowercase because they are used as keys in (de)serialization.
     */
    private enum Instrument {
        piano, trumpet, flute, violin, drum
    }

    /**
     * A musician that plays an instrument.
     */
    record Musician(UUID uuid, Instrument instrument, long lastActivity) {
        /**
         * The sounds that each instrument makes.
         */
        private static final HashMap<String, Instrument> sounds = new HashMap<>() {{
            put("ti-ta-ti", Instrument.piano);
            put("pouet", Instrument.trumpet);
            put("trulu", Instrument.flute);
            put("gzi-gzi", Instrument.violin);
            put("boum-boum", Instrument.drum);
        }};

        /**
         * Create a new musician.
         *
         * @param uuid         The UUID of the musician.
         * @param sound        The sound that the musician makes, which determines the instrument.
         * @param lastActivity The last time the musician was active.
         */
        public Musician(UUID uuid, String sound, long lastActivity) {
            this(uuid, sounds.get(sound), lastActivity);
        }

        /**
         * Hash the musician using its UUID.
         *
         * @return The hash code.
         */
        @Override
        public int hashCode() {
            return Objects.hash(uuid);
        }

        /**
         * Check if two musicians are equal.
         *
         * @param other the reference object with which to compare.
         * @return true if this object is the same as the obj argument; false otherwise.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            } else if (other == null) {
                return false;
            } else if (!(other instanceof Musician)) {
                return false;
            }
            Musician musician = (Musician) other;
            return musician.uuid.equals(this.uuid);
        }
    }

    /**
     * A thread that listens for UDP messages from musicians and adds them to the musicians list.
     */
    private static class RunnableListener implements Runnable {
        /**
         * The address on which the listener listens.
         */
        private static final String ADDRESS = "239.255.22.5";

        /**
         * The port on which the listener listens.
         */
        private static final int PORT = 9904;

        /**
         * The maximum size of a UDP datagram.
         */
        private static final int DATAGRAM_SIZE = 1024;

        /**
         * The default network interface to listen on. Always "eth0" in docker.
         */
        private static final String NETWORK_INTERFACE = "eth0";

        /**
         * Listen for UDP messages from musicians and add them to the list.
         */
        @Override
        public void run() {
            System.out.println("Starting auditor listener on UDP port " + PORT);

            final InetSocketAddress group_address = new InetSocketAddress(ADDRESS, PORT);
            MulticastSocket socket = null;
            NetworkInterface netif = null;
            try {
                netif = NetworkInterface.getByName(NETWORK_INTERFACE);
                socket = new MulticastSocket(PORT);
                socket.joinGroup(group_address, netif);

                byte[] buffer = new byte[DATAGRAM_SIZE];
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                while (true) {
                    socket.receive(packet);
                    String message = new String(packet.getData(), 0, packet.getLength(), UTF_8);
                    Musician musician = gson.fromJson(message, Musician.class);

                    if (!Main.musicians.add(musician)) {
                        Main.musicians.remove(musician);
                        Main.musicians.add(musician);
                    } else {
                        System.out.println("Auditor listener: found " + musician); // Log only on initial insertion.
                    }
                }
            } catch (Exception e) { // Catch all exceptions to avoid nested catch statements.
                System.err.println(e.getMessage());
            } finally { // Cleanup manually because try-with-resources doesn't work easily with virtual threads.
                if (socket != null) {
                    try {
                        socket.leaveGroup(group_address, netif);
                        socket.close();
                    } catch (IOException e) {
                        System.err.println(e.getMessage());
                    }
                }
            }
        }
    }

    /**
     * A thread that removes inactive musicians from the list.
     */
    private static class RunnableWatcher implements Runnable {

        /**
         * The time between each check of the list.
         */
        private static final long THREAD_TIMEOUT = 1000; // 1 seconds

        /**
         * The maximum time a musician can be inactive before being removed from the list.
         */
        private static final long INACTIVE_TIMEOUT = 5000; // 5 seconds

        /**
         * Remove inactive musicians from the list.
         */
        @Override
        public void run() {
            System.out.println("Starting inactive musician watcher");
            try (final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor()) {
                executorService.scheduleAtFixedRate(() -> {
                    musicians.removeIf(m -> System.currentTimeMillis() - m.lastActivity() > INACTIVE_TIMEOUT);
                }, 0, THREAD_TIMEOUT, TimeUnit.MILLISECONDS);
            }
        }
    }

    /**
     * A thread that serves the list of musicians to clients.
     */
    private static class RunnableServer implements Runnable {
        /**
         * The port on which the server listens.
         */
        private static final int PORT = 2205;

        /**
         * Start the server on the given port and run the client handler on virtual threads.
         */
        @Override
        public void run() {
            System.out.println("Starting auditor server on TCP port " + PORT);
            try (final var serverSocket = new ServerSocket(PORT)) {
                while (true) {
                    try {
                        var socket = serverSocket.accept();
                        try (socket; final var out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), UTF_8))) {
                            System.out.println("Auditor server: sending " + musicians.size() + " musicians to client");
                            String response = gson.toJson(musicians);
                            out.write(response);
                            out.flush();
                        } catch (IOException e) {
                            System.err.println("Error writing to client socket: " + e.getMessage());
                        }
                    } catch (IOException e) {
                        System.err.println("Error opening client socket: " + e.getMessage());
                    }
                }
            } catch (IOException e) {
                System.err.println("Error opening server socket: " + e.getMessage());
            }
        }
    }

    /**
     * A deserializer for the Musician record.
     */
    static class MusicianDeserializer implements JsonDeserializer<Musician> {
        /**
         * Deserialize a musician from a JSON element.
         *
         * @param jsonElement                The JSON element.
         * @param type                       The type of the object.
         * @param jsonDeserializationContext The context.
         * @return A musician record.
         * @throws JsonParseException If the JSON is invalid.
         */
        @Override
        public Musician deserialize(JsonElement jsonElement, Type type, JsonDeserializationContext jsonDeserializationContext) throws JsonParseException {
            JsonObject jsonObject = jsonElement.getAsJsonObject();

            final UUID uuid = UUID.fromString(jsonObject.get("uuid").getAsString());
            final String sound = jsonObject.get("sound").getAsString();
            final long lastActivity = jsonObject.get("timestamp").getAsLong();

            return new Musician(uuid, sound, lastActivity);
        }
    }
}
