package arsqz.mod.client.galaxy;

import java.util.List;

/**
 * Client-side cache of the latest "space pings" from the server. Holds two views:
 * cluster-level pings for the universe (cluster-selection) screen, and galaxy-level
 * pings for whichever cluster you're currently inside. Each is replaced wholesale
 * when the matching {@code PingListPacket} arrives.
 */
public final class SpacePings {

    private SpacePings() {}

    /** A player shown over a cluster on the universe screen. */
    public record ClusterPing(String name, long cluster) {}

    /** A player shown over a galaxy inside a cluster. */
    public record GalaxyPing(String name, long galaxyLocal) {}

    private static volatile List<ClusterPing> universePings = List.of();
    private static volatile long galaxyCluster = Long.MIN_VALUE;
    private static volatile List<GalaxyPing> galaxyPings = List.of();

    public static void setUniverse(List<ClusterPing> pings) {
        universePings = List.copyOf(pings);
    }

    public static void setCluster(long cluster, List<GalaxyPing> pings) {
        galaxyCluster = cluster;
        galaxyPings = List.copyOf(pings);
    }

    public static List<ClusterPing> universe() {
        return universePings;
    }

    public static long galaxyCluster() {
        return galaxyCluster;
    }

    public static List<GalaxyPing> galaxy() {
        return galaxyPings;
    }

    public static void clear() {
        universePings = List.of();
        galaxyCluster = Long.MIN_VALUE;
        galaxyPings = List.of();
    }
}
