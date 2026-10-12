package arsqz.mod.client.galaxy;

import java.util.List;
import java.util.UUID;

/**
 * Client-side cache of the latest player roster received from the server (who's
 * online and which planet/dimension they're in). Updated by the network receiver
 * for {@code PlayerLocationsPacket} and read by the Players tab of the map GUI.
 */
public final class ServerPlayers {

    private ServerPlayers() {}

    public record Entry(UUID id, String name, String dimKey, String location) {}

    private static volatile List<Entry> latest = List.of();

    public static void set(List<Entry> entries) {
        latest = List.copyOf(entries);
    }

    public static List<Entry> get() {
        return latest;
    }
}
