package arsqz.mod.world;

import arsqz.mod.TwoSixTwo_Arskiz;

import com.mojang.serialization.Codec;

import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

import java.util.HashMap;
import java.util.Map;

/**
 * Server-wide record of who first travelled to each planet. Persisted with the
 * world via the {@link SavedData} system and attached to the overworld's data
 * storage. Keyed by planet world seed -> discoverer's name.
 */
public final class DiscoveryData extends SavedData {

    private final Map<Long, String> discoverers;

    public DiscoveryData(Map<Long, String> discoverers) {
        this.discoverers = new HashMap<>(discoverers);
    }

    /** Seeds are stored as string keys so they nest cleanly in NBT compounds. */
    public static final Codec<DiscoveryData> CODEC =
            Codec.unboundedMap(Codec.STRING, Codec.STRING).xmap(
                    stringMap -> {
                        Map<Long, String> out = new HashMap<>();
                        stringMap.forEach((k, v) -> {
                            try {
                                out.put(Long.parseLong(k), v);
                            } catch (NumberFormatException ignored) {
                                // drop a corrupt key rather than fail the whole load
                            }
                        });
                        return new DiscoveryData(out);
                    },
                    data -> {
                        Map<String, String> out = new HashMap<>();
                        data.discoverers.forEach((k, v) -> out.put(Long.toString(k), v));
                        return out;
                    });

    public static final SavedDataType<DiscoveryData> TYPE = new SavedDataType<>(
            TwoSixTwo_Arskiz.id("discoveries"),
            () -> new DiscoveryData(new HashMap<>()),
            CODEC,
            DataFixTypes.LEVEL);

    /** The name of whoever first discovered this planet, or {@code null} if uncharted. */
    public String discoverer(long planetSeed) {
        return discoverers.get(planetSeed);
    }

    /** Record the discoverer of a planet if it has never been claimed before. */
    public void recordIfAbsent(long planetSeed, String playerName) {
        if (discoverers.putIfAbsent(planetSeed, playerName) == null) {
            setDirty();
        }
    }
}
