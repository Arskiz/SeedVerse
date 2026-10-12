package arsqz.mod;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Server-side configuration, stored in {@code config/universemod.properties}.
 *
 * <p>Currently holds a single setting: how many <em>distinct</em> planets each
 * player is allowed to visit. Revisiting a planet you've already been to never
 * counts again. A value of {@code 0} (or less) means unlimited.
 */
public final class ModConfig {

    private ModConfig() {}

    private static final String FILE_NAME = "universemod.properties";
    private static final String KEY_MAX = "max-planets-per-player";
    private static final int DEFAULT_MAX = 10;

    private static int maxPlanetsPerPlayer = DEFAULT_MAX;

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** Load the config file, creating it with defaults if it doesn't exist. */
    public static synchronized void load() {
        Path p = file();
        if (Files.exists(p)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(p)) {
                props.load(in);
            } catch (IOException e) {
                TwoSixTwo_Arskiz.LOGGER.error("Failed to read {}.", FILE_NAME, e);
            }
            String v = props.getProperty(KEY_MAX);
            if (v != null) {
                try {
                    maxPlanetsPerPlayer = Integer.parseInt(v.trim());
                } catch (NumberFormatException e) {
                    TwoSixTwo_Arskiz.LOGGER.warn("Invalid {} value '{}', using {}.",
                            KEY_MAX, v, maxPlanetsPerPlayer);
                }
            }
        }
        save(); // (re)write so the file always exists and is normalised
    }

    private static synchronized void save() {
        Properties props = new Properties();
        props.setProperty(KEY_MAX, Integer.toString(maxPlanetsPerPlayer));
        try {
            Path p = file();
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            try (OutputStream out = Files.newOutputStream(p)) {
                props.store(out, "UniverseMod config. " + KEY_MAX + ": how many distinct planets "
                        + "each player may visit (0 or less = unlimited).");
            }
        } catch (IOException e) {
            TwoSixTwo_Arskiz.LOGGER.error("Failed to write {}.", FILE_NAME, e);
        }
    }

    /** Max distinct planets a player may visit; {@code <= 0} means unlimited. */
    public static synchronized int maxPlanetsPerPlayer() {
        return maxPlanetsPerPlayer;
    }

    /** Update the limit and persist it immediately. */
    public static synchronized void setMaxPlanetsPerPlayer(int value) {
        maxPlanetsPerPlayer = value;
        save();
    }
}
