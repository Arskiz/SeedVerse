package arsqz.mod.client.galaxy;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Client-side history of recent map searches (raw query strings), most-recent
 * first, persisted to a small text file in the game directory so it survives
 * across sessions. Only the query text is stored; the target (planet, system,
 * galaxy or cluster) is re-resolved deterministically via {@code NameGenerator}
 * when a history entry is clicked.
 */
public final class SearchHistory {

    /** How many recent searches to keep and show. */
    public static final int MAX = 5;

    private static final String FILE_NAME = "universemod_searches.txt";

    private static final List<String> ENTRIES = new ArrayList<>();
    private static boolean loaded = false;

    private SearchHistory() {}

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
                    if (!s.isEmpty() && ENTRIES.size() < MAX) ENTRIES.add(s);
                }
            }
        } catch (IOException ignored) {
        }
    }

    private static void save() {
        StringBuilder sb = new StringBuilder();
        for (String s : ENTRIES) sb.append(s).append('\n');
        try {
            Files.writeString(file(), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Most-recent-first snapshot of the recent searches. */
    public static synchronized List<String> recent() {
        ensureLoaded();
        return new ArrayList<>(ENTRIES);
    }

    /** Record a successful search, moving it to the front and de-duplicating. */
    public static synchronized void record(String query) {
        if (query == null) return;
        String q = query.trim();
        if (q.isEmpty()) return;
        ensureLoaded();
        ENTRIES.removeIf(s -> s.equalsIgnoreCase(q));
        ENTRIES.add(0, q);
        while (ENTRIES.size() > MAX) ENTRIES.remove(ENTRIES.size() - 1);
        save();
    }
}
