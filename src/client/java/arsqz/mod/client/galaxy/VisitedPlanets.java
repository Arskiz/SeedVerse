package arsqz.mod.client.galaxy;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side "recently visited planets" history.
 *
 * <p>Keeps the last {@value #MAX} planets the player travelled to, most-recent
 * first, persisted to a small text file in the game directory so the list
 * survives across sessions. Nothing about the universe itself is stored: each
 * entry is just a host-system seed plus a planet index, and every display value
 * (name, colour, distance) is recomputed deterministically from those.
 */
public final class VisitedPlanets {

    /** A single visited planet: its host system seed and orbital index. */
    public record Entry(long systemSeed, int planetIndex) {}

    /** How many planets are remembered (the full latest-visited history). */
    public static final int MAX = 300;

    /** How many to show in the quick history drop-down on the map. */
    public static final int QUICK = 9;

    private static final String FILE_NAME = "universemod_visited.txt";

    private static final List<Entry> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    private VisitedPlanets() {}

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        ENTRIES.clear();
        Path p = file();
        try {
            if (Files.exists(p)) {
                for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.isEmpty()) continue;
                    String[] t = s.split("\\s+");
                    if (t.length < 2) continue;
                    try {
                        long sys = Long.parseLong(t[0]);
                        int idx = Integer.parseInt(t[1]);
                        ENTRIES.add(new Entry(sys, idx));
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
        } catch (IOException ignored) {
        }
        trim();
    }

    private static void trim() {
        while (ENTRIES.size() > MAX) ENTRIES.remove(ENTRIES.size() - 1);
    }

    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : ENTRIES) {
            sb.append(e.systemSeed()).append(' ').append(e.planetIndex()).append('\n');
        }
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Most-recent-first snapshot of the visited planets. */
    public static synchronized List<Entry> recent() {
        ensureLoaded();
        return new ArrayList<>(ENTRIES);
    }

    /** Record a visit, moving it to the front and de-duplicating. */
    public static synchronized void record(long systemSeed, int planetIndex) {
        ensureLoaded();
        ENTRIES.removeIf(e -> e.systemSeed() == systemSeed && e.planetIndex() == planetIndex);
        ENTRIES.add(0, new Entry(systemSeed, planetIndex));
        trim();
        save();
    }
}
