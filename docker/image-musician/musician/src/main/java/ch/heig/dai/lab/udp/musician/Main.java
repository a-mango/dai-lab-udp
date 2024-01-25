package ch.heig.dai.lab.udp.musician;

import com.google.gson.Gson;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.UUID;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Multicast musician that plays an instrument.
 * To capture the datagrams using tcpdump, use `$ tcpdump -i any -n udp port 9904 -Al | grep -o '{.*}'`
 *
 * @author Aubry Mangold <aubry.mangold@heig-vd.ch>
 * @author Hugo Germano <hugo.germano@heig-vd.ch>
 */
public class Main {
    /**
     * The JSON serializer.
     */
    private static final Gson gson = new Gson();

    /**
     * The multicast address.
     */
    private static final String ADDRESS = "239.255.22.5";

    /**
     * The multicast port.
     */
    private static final int PORT = 9904;

    /**
     * The instruments that a musician can play.
     */
    private final static HashMap<Instrument, String> instruments = new HashMap<>() {{
        put(Instrument.piano, "ti-ta-ti");
        put(Instrument.trumpet, "pouet");
        put(Instrument.flute, "trulu");
        put(Instrument.violin, "gzi-gzi");
        put(Instrument.drum, "boum-boum");
    }};

    /**
     * The main entry point of the program.
     */
    public static void main(String[] args) {
        if (args.length != 1) {
            System.out.println("Usage: java -jar musician.jar [piano|trumpet|flute|violin|drum]");
            System.exit(1);
        }

        // Parse the instrument from the command line.
        Instrument instrument = null;
        try {
            instrument = Instrument.valueOf(args[0]);
        } catch (IllegalArgumentException e) {
            System.err.println("Invalid instrument.");
            System.exit(1);
        }

        // Create the musician socket.
        try (DatagramSocket socket = new DatagramSocket()) {
            final InetSocketAddress dest_address = new InetSocketAddress(ADDRESS, PORT);
            final UUID uuid = UUID.randomUUID();

            // Send the musician's data every second.
            while (true) {
                final var data = new Musician(uuid, instrument);
                final var payload = data.toBytes();
                final var packet = new DatagramPacket(payload, payload.length, dest_address);

                socket.send(packet);
                System.out.println("Sent: " + data);
                Thread.sleep(1000);
            }
        } catch (IOException e) {
            System.err.println(e.getMessage());
        } catch (InterruptedException e) {
            System.err.println(e.getMessage());
            System.exit(1);
        }
    }

    /**
     * The instrument that a musician plays.
     */
    private enum Instrument {
        piano, trumpet, flute, violin, drum
    }

    /**
     * A musician that plays an instrument.
     */
    private record Musician(UUID uuid, String sound, long timestamp) {
        /**
         * Create a new musician with the current timestamp.
         */
        public Musician(UUID uuid, Instrument instrument) {
            this(uuid, instruments.get(instrument), System.currentTimeMillis());
        }

        /**
         * Convert the musician to a binary JSON string.
         */
        public byte[] toBytes() {
            return gson.toJson(this).getBytes(UTF_8);
        }
    }
}


