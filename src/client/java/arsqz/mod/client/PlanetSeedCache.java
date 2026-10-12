package arsqz.mod.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class PlanetSeedCache {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>() {}.getType();
    private static final Map<String, Long> DIMENSION_SEEDS = new ConcurrentHashMap<>();
    private static boolean loaded = false;

    private PlanetSeedCache() {}

    private static Path getFilePath() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("universemod_planet_seeds.json");
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        Path path = getFilePath();
        if (!Files.exists(path)) return;

        try (Reader reader = Files.newBufferedReader(path)) {
            Map<String, Long> fromDisk = GSON.fromJson(reader, MAP_TYPE);
            if (fromDisk != null) {
                DIMENSION_SEEDS.putAll(fromDisk);
            }
        } catch (Exception ignored) {}
    }

    private static synchronized void save() {
        Path path = getFilePath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(DIMENSION_SEEDS, writer);
            }
        } catch (Exception ignored) {}
    }

    public static void setPlanetSeed(String dimensionKey, long seed) {
        load();
        DIMENSION_SEEDS.put(dimensionKey, seed);
        save();
    }

    public static void clear() {
        // Ei välttämättä tarvitse edes poistaa levyä,
        // mutta jos halutaan tyhjentää kyseisen session tila:
    }

    public static Long getSeedForDimension(ResourceKey<Level> dimKey) {
        if (dimKey == null || dimKey.equals(Level.OVERWORLD)) {
            return null;
        }

        load();
        String keyStr = dimKey.identifier().toString();
        Long cached = DIMENSION_SEEDS.get(keyStr);
        if (cached != null) {
            return cached;
        }

        // Luetaan siemen suoraan tunnisteesta: "universemod:planet_SEED"
        try {
            if ("universemod".equals(dimKey.identifier().getNamespace())) {
                String path = dimKey.identifier().getPath(); // esim. "planet_0" tai "planet_-4928104"
                if (path.startsWith("planet_")) {
                    String seedPart = path.substring("planet_".length());
                    long seed = Long.parseLong(seedPart);
                    
                    // Tallennetaan kätköön ja levylle tulevia hakuja varten
                    setPlanetSeed(keyStr, seed);
                    return seed;
                }
            }
        } catch (Exception ignored) {
            // Jos nimeämiskäytäntö ei mätsääkään, palautetaan null jolloin SeedMapper tekee fallbackin
        }

        return null;
    }
}