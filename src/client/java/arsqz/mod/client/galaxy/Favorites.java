package arsqz.mod.client.galaxy;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side favourites: starred galaxies and planets, persisted to a small
 * file in the game directory. Lists are unbounded and most-recent-first. Like
 * the rest of the map, nothing about the universe is stored - only the ids and
 * indices needed to recompute names, colours and positions deterministically.
 */
public final class Favorites {

    /** A favourited planet: its host system seed and orbital index. */
    public record PlanetRef(long systemSeed, int planetIndex) {}

    private static final List<PlanetRef> PLANETS = new ArrayList<>();
    private static final List<Long> GALAXIES = new ArrayList<>();
    private static boolean loaded = false;

    private static final String FILE_NAME = "universemod_favorites.txt";

    private Favorites() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        PLANETS.clear();
        GALAXIES.clear();
        try {
            Path p = file();
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    String[] t = s.split("\\s+");
                    try {
                        if (t[0].equals("P") && t.length >= 3) {
                            PLANETS.add(new PlanetRef(Long.parseLong(t[1]), Integer.parseInt(t[2])));
                        } else if (t[0].equals("G") && t.length >= 2) {
                            GALAXIES.add(Long.parseLong(t[1]));
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (PlanetRef r : PLANETS) {
            sb.append("P ").append(r.systemSeed()).append(' ').append(r.planetIndex()).append('\n');
        }
        for (Long g : GALAXIES) {
            sb.append("G ").append(g).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    public static synchronized List<PlanetRef> planets() {
        ensureLoaded();
        return new ArrayList<>(PLANETS);
    }

    public static synchronized List<Long> galaxies() {
        ensureLoaded();
        return new ArrayList<>(GALAXIES);
    }

    public static synchronized boolean isPlanet(long systemSeed, int index) {
        ensureLoaded();
        for (PlanetRef r : PLANETS) if (r.systemSeed() == systemSeed && r.planetIndex() == index) return true;
        return false;
    }

    public static synchronized boolean isGalaxy(long galaxyId) {
        ensureLoaded();
        return GALAXIES.contains(galaxyId);
    }

    public static synchronized void togglePlanet(long systemSeed, int index) {
        ensureLoaded();
        PlanetRef found = null;
        for (PlanetRef r : PLANETS) if (r.systemSeed() == systemSeed && r.planetIndex() == index) { found = r; break; }
        if (found != null) PLANETS.remove(found);
        else PLANETS.add(0, new PlanetRef(systemSeed, index));
        save();
    }

    public static synchronized void toggleGalaxy(long galaxyId) {
        ensureLoaded();
        if (!GALAXIES.remove(galaxyId)) GALAXIES.add(0, galaxyId);
        save();
    }

    public static synchronized void removePlanet(PlanetRef ref) {
        ensureLoaded();
        PLANETS.removeIf(r -> r.systemSeed() == ref.systemSeed() && r.planetIndex() == ref.planetIndex());
        save();
    }

    public static synchronized void removeGalaxy(long galaxyId) {
        ensureLoaded();
        GALAXIES.remove(galaxyId);
        save();
    }
}
