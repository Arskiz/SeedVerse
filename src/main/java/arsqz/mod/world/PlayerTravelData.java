package arsqz.mod.world;

import arsqz.mod.TwoSixTwo_Arskiz;

import com.mojang.serialization.Codec;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Remembers, per player, the last position they stood at in each dimension, so
 * travelling back to a planet (or home to the overworld) drops them exactly
 * where they left instead of at a fixed spawn. Persisted across restarts and
 * kept through death via the Fabric attachment API.
 */
public final class PlayerTravelData {

    private PlayerTravelData() {}

    /** dimension id string -> last position in that dimension. */
    public static final AttachmentType<Map<String, Vec3>> LAST_POSITIONS =
            AttachmentRegistry.<Map<String, Vec3>>create(
                    TwoSixTwo_Arskiz.id("last_positions"),
                    builder -> builder
                            .persistent(Codec.unboundedMap(Codec.STRING, Vec3.CODEC))
                            .initializer(HashMap::new)
                            .copyOnDeath());

    /**
     * The distinct planet (overworld) seeds a player has ever travelled to. Used
     * to enforce the server's per-player planet-visit limit. Survives death so
     * dying can't be used to reset your allowance.
     */
    public static final AttachmentType<List<Long>> VISITED_PLANETS =
            AttachmentRegistry.<List<Long>>create(
                    TwoSixTwo_Arskiz.id("visited_planets"),
                    builder -> builder
                            .persistent(Codec.LONG.listOf())
                            .initializer(ArrayList::new)
                            .copyOnDeath());

    /** Force class-load so the attachments are registered during mod init. */
    public static void init() {}

    /** How many distinct planets the player has visited. */
    public static int visitedCount(ServerPlayer player) {
        List<Long> list = player.getAttached(VISITED_PLANETS);
        return list == null ? 0 : list.size();
    }

    /** Whether the player has already visited the given planet seed. */
    public static boolean hasVisited(ServerPlayer player, long planetSeed) {
        List<Long> list = player.getAttached(VISITED_PLANETS);
        return list != null && list.contains(planetSeed);
    }

    /** Record a planet as visited (no-op if already recorded). */
    public static void markVisited(ServerPlayer player, long planetSeed) {
        List<Long> current = player.getAttachedOrCreate(VISITED_PLANETS);
        if (current.contains(planetSeed)) return;
        List<Long> updated = new ArrayList<>(current);
        updated.add(planetSeed);
        player.setAttached(VISITED_PLANETS, updated);
    }

    /** Record where the player currently is in {@code dim}. */
    public static void remember(ServerPlayer player, ResourceKey<Level> dim, Vec3 pos) {
        Map<String, Vec3> updated = new HashMap<>(player.getAttachedOrCreate(LAST_POSITIONS));
        updated.put(dim.identifier().toString(), pos);
        player.setAttached(LAST_POSITIONS, updated);
    }

    /** The player's last position in {@code dim}, or {@code null} if never visited. */
    public static Vec3 recall(ServerPlayer player, ResourceKey<Level> dim) {
        Map<String, Vec3> map = player.getAttached(LAST_POSITIONS);
        return map == null ? null : map.get(dim.identifier().toString());
    }
}
