package arsqz.mod.world;

import arsqz.mod.net.PingFocusPacket;
import arsqz.mod.net.PingListPacket;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Server-side registry of what each map-viewing player is looking at, used to
 * relay "space pings" to others:
 * <ul>
 *   <li>players on the cluster-selection screen see a ping over each cluster
 *       that has someone in it;</li>
 *   <li>players inside a cluster see a ping over each galaxy another player in
 *       that same cluster is inspecting.</li>
 * </ul>
 *
 * <p>All access is on the server thread (packet handlers and connection events
 * schedule onto it), so no locking is needed. Updates are event-driven: since a
 * cluster-screen viewer cares about every cluster, any change simply refreshes
 * all current viewers - cheap, as only map-open players are tracked.
 */
public final class SpacePingTracker {

    private SpacePingTracker() {}

    private record Presence(int level, long cluster, long galaxyLocal, String name) {}

    private static final Map<UUID, Presence> PRESENCES = new HashMap<>();

    /** A player changed what they're viewing (or closed the map). */
    public static void update(ServerPlayer player, int level, long cluster, long galaxyLocal) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        if (level == PingFocusPacket.LEVEL_NONE) {
            PRESENCES.remove(player.getUUID());
        } else {
            PRESENCES.put(player.getUUID(), new Presence(level, cluster, galaxyLocal, player.getName().getString()));
        }
        broadcast(server);
    }

    /** A player disconnected: drop them and refresh everyone else. */
    public static void remove(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (PRESENCES.remove(player.getUUID()) != null && server != null) broadcast(server);
    }

    /** Send each tracked viewer the ping list appropriate to their current view. */
    private static void broadcast(MinecraftServer server) {
        for (Map.Entry<UUID, Presence> viewer : PRESENCES.entrySet()) {
            Presence vp = viewer.getValue();
            ServerPlayer q = server.getPlayerList().getPlayer(viewer.getKey());
            if (q == null) continue;

            List<PingListPacket.Entry> list = new ArrayList<>();
            for (Map.Entry<UUID, Presence> other : PRESENCES.entrySet()) {
                if (other.getKey().equals(viewer.getKey())) continue;      // not yourself
                Presence op = other.getValue();
                if (op.level() != PingFocusPacket.LEVEL_CLUSTER) continue;  // only players in a cluster ping

                if (vp.level() == PingFocusPacket.LEVEL_UNIVERSE) {
                    // Cluster-selection screen: show which cluster they're in.
                    list.add(new PingListPacket.Entry(op.name(), op.cluster()));
                } else if (vp.level() == PingFocusPacket.LEVEL_CLUSTER
                        && op.cluster() == vp.cluster() && op.galaxyLocal() >= 0) {
                    // Inside a cluster: show the galaxy they're inspecting (idlers skipped).
                    list.add(new PingListPacket.Entry(op.name(), op.galaxyLocal()));
                }
            }

            int scope = (vp.level() == PingFocusPacket.LEVEL_UNIVERSE)
                    ? PingListPacket.SCOPE_UNIVERSE : PingListPacket.SCOPE_CLUSTER;
            long context = (vp.level() == PingFocusPacket.LEVEL_CLUSTER) ? vp.cluster() : 0L;
            ServerPlayNetworking.send(q, new PingListPacket(scope, context, list));
        }
    }
}
