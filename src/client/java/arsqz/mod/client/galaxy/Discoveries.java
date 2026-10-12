package arsqz.mod.client.galaxy;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side cache of planet discovery status, filled on demand from the server
 * ({@code DiscoveryInfoPacket}). Maps planet world seed -> discoverer name, where
 * an empty string means "uncharted". Cleared on disconnect so a different server
 * never shows stale claims.
 */
public final class Discoveries {

    private Discoveries() {}

    private static final Map<Long, String> known = new ConcurrentHashMap<>();
    private static final Set<Long> pending = ConcurrentHashMap.newKeySet();

    /** Cached status: {@code null} = unknown, {@code ""} = uncharted, else the discoverer. */
    public static String get(long seed) {
        return known.get(seed);
    }

    /** Store a server response. */
    public static void put(long seed, String discoverer) {
        known.put(seed, discoverer);
        pending.remove(seed);
    }

    /** Returns true the first time a seed is seen, so we only request it once. */
    public static boolean shouldRequest(long seed) {
        return !known.containsKey(seed) && pending.add(seed);
    }

    /** Drop a single seed so its status is re-fetched next time it's viewed. */
    public static void invalidate(long seed) {
        known.remove(seed);
        pending.remove(seed);
    }

    public static void clear() {
        known.clear();
        pending.clear();
    }
}
