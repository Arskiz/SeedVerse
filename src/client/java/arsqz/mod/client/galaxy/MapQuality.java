package arsqz.mod.client.galaxy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Client-side render-quality setting for the galaxy map, controlled by the
 * quality slider and persisted to a small file in the game directory.
 *
 * <p>The setting is a float "tier" in {@code [1, 5]}: 1 is the worst, 5 is ultra.
 * A higher tier renders at a smaller effective GUI scale (more pixels, crisper)
 * and allows more star dots on screen at once.
 */
public final class MapQuality {

    private MapQuality() {}

    public static final int TIERS = 5;
    private static final String[] NAME_KEYS = {
            "quality.universemod.very_low", "quality.universemod.low", "quality.universemod.medium",
            "quality.universemod.high", "quality.universemod.ultra"
    };
    private static final String FILE_NAME = "universemod_quality.txt";

    private static final int MIN_STARS = 5_000;    // tier 1
    private static final int MAX_STARS = 70_000;   // tier 5

    // Default tier 4 -> render GUI scale 2.0, the known-good look.
    private static float tier = 4.0f;
    private static boolean loaded = false;

    private static Path file() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve(FILE_NAME);
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        loaded = true;
        try {
            Path p = file();
            if (Files.exists(p)) {
                String s = Files.readString(p, StandardCharsets.UTF_8).trim();
                if (!s.isEmpty()) tier = clampTier(Float.parseFloat(s));
            }
        } catch (Exception ignored) {
        }
    }

    private static float clampTier(float t) {
        return Math.max(1.0f, Math.min(5.0f, t));
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    /** Current quality tier, a float in [1, 5]. */
    public static synchronized float tier() {
        ensureLoaded();
        return tier;
    }

    /** Set the tier (clamped to [1, 5]) and persist it. */
    public static synchronized void setTier(float t) {
        ensureLoaded();
        tier = clampTier(t);
        try {
            Files.writeString(file(), Float.toString(tier), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
        }
    }

    /** Slider position 0..1 (left = worst, right = ultra). */
    public static float slider01() {
        return (tier() - 1.0f) / 4.0f;
    }

    /** Set the tier from a 0..1 slider position. */
    public static void setFromSlider01(float v) {
        setTier(1.0f + clamp01(v) * 4.0f);
    }

    /**
     * Effective GUI scale for the render: ultra (tier 5) -> 1.0 (best, most
     * pixels), worst (tier 1) -> 5.0. A float, so in-between tiers give
     * in-between scales (e.g. tier 3.6 -> 2.4).
     */
    public static double renderGuiScale() {
        return 6.0 - tier();
    }

    /** Maximum star dots drawn at once: worst -> 5k, ultra -> 70k. */
    public static int maxStars() {
        float t = tier();
        return Math.round(MIN_STARS + (t - 1.0f) / 4.0f * (MAX_STARS - MIN_STARS));
    }

    /** Name of the nearest tier, for the slider label. */
    public static String tierName() {
        int idx = Math.round(tier()) - 1;
        idx = Math.max(0, Math.min(NAME_KEYS.length - 1, idx));
        return I18n.get(NAME_KEYS[idx]);
    }
}
