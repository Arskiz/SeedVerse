package arsqz.mod.universe;

/**
 * Deterministic, realistic-sounding names for stars, galaxies and clusters.
 *
 * <p>Given the same id you always get the same name, so a planet keeps its
 * name across sessions without anything being stored.
 */
public final class NameGenerator {

    private NameGenerator() {}

    // Pronounceable building blocks.
    private static final String[] ONSET = {
            "b", "br", "c", "cr", "d", "dr", "f", "g", "gr", "h", "j", "k",
            "kr", "l", "m", "n", "p", "pr", "q", "r", "s", "st", "str", "t",
            "tr", "th", "v", "vr", "x", "z", "ph", "sh", "kh", "zh"
    };
    private static final String[] NUCLEUS = {
            "a", "e", "i", "o", "u", "ae", "ai", "ea", "io", "ou", "ya", "eo"
    };
    private static final String[] CODA = {
            "n", "r", "s", "l", "th", "x", "sh", "ne", "ra", "us", "is", "os",
            "ar", "on", "an", "ex", "or", "el", "ia", "ys", "um"
    };

    // Catalog-style prefixes, like real star designations.
    private static final String[] CATALOG = {
            "Kepler", "Gliese", "HD", "Wolf", "Ross", "Cygnus", "Proxima",
            "Vega", "Tau Ceti", "Trappist", "Kapteyn", "Luyten", "Barnard"
    };

    // Roman-numeral suffixes for that "system designation" feel.
    private static final String[] ROMAN = {
            "II", "III", "IV", "V", "VI", "VII", "IX", "XI", "XII"
    };

    private static final String[] GALAXY_SUFFIX = {
            "Galaxy", "Nebula", "Spiral", "Whirl", "Veil", "Expanse"
    };

    private static final String[] CLUSTER_SUFFIX = {
            "Cluster", "Supercluster", "Group", "Reach", "Deep", "Void"
    };

    /** Build a pronounceable proper name of {@code syllables} syllables. */
    private static String proper(long h, int syllables) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < syllables; i++) {
            h = UniverseMath.mix64(h + i * 0x100000001B3L);
            sb.append(pick(ONSET, h));
            h = UniverseMath.mix64(h);
            sb.append(pick(NUCLEUS, h));
            // Coda only sometimes, and more often on the final syllable.
            h = UniverseMath.mix64(h);
            boolean addCoda = (i == syllables - 1) ? UniverseMath.toUnit(h) < 0.75
                                                   : UniverseMath.toUnit(h) < 0.35;
            if (addCoda) {
                h = UniverseMath.mix64(h);
                sb.append(pick(CODA, h));
            }
        }
        return capitalize(sb.toString());
    }

    private static final String[] PLANET_DESIG = {
            "Prime", "II", "III", "IV", "V", "VI", "VII", "VIII"
    };

    // Syllable budgets for the reversible codec (see NameCodec).
    public static final int CLUSTER_SYLLABLES = 2;   // up to 65,536 clusters
    public static final int GALAXY_SYLLABLES = 5;    // up to ~1.1e12 global galaxies
    public static final int SYSTEM_SYLLABLES = 8;    // full 2^64 system seeds

    /**
     * A star system's name: a reversible codeword of its 64-bit system seed, so
     * the search bar can decode it straight back into coordinates.
     */
    public static String systemName(long systemSeed) {
        return NameCodec.encode(systemSeed, SYSTEM_SYLLABLES);
    }

    /**
     * A parsed search target. level: 0 cluster, 1 galaxy, 2 system, 3 planet.
     */
    public record Match(int level, long cluster, long galaxyLocal, long starIndex,
                        int planetIndex, long seed) {}

    /** Decode a typed name (or raw seed) into a navigable target, or null. */
    public static Match parse(String input) {
        if (input == null) return null;
        String s = input.trim();
        if (s.isEmpty()) return null;
        String[] t = s.split("\\s+");
        String last = t[t.length - 1];
        String core = t[0];

        int desig = designationIndex(last);
        if (t.length >= 2 && desig >= 0) {                       // planet: "<system> III"
            Long seed = NameCodec.decode(core, SYSTEM_SYLLABLES);
            if (seed != null) {
                long sd = seed;
                return new Match(3, clusterOf(sd), galaxyLocalOf(sd), starOf(sd), desig, sd);
            }
        }
        if (t.length >= 2 && contains(CLUSTER_SUFFIX, last)) {    // cluster
            Long v = NameCodec.decode(core, CLUSTER_SYLLABLES);
            if (v != null && v >= 0 && v < UniverseMath.NUM_CLUSTERS)
                return new Match(0, v, 0, 0, -1, 0);
        }
        if (t.length >= 2 && contains(GALAXY_SUFFIX, last)) {     // galaxy
            Long g = NameCodec.decode(core, GALAXY_SYLLABLES);
            if (g != null) {
                long c = Long.divideUnsigned(g, UniverseMath.GALAXIES_PER_CLUSTER);
                long gl = Long.remainderUnsigned(g, UniverseMath.GALAXIES_PER_CLUSTER);
                if (c < UniverseMath.NUM_CLUSTERS) return new Match(1, c, gl, 0, -1, 0);
            }
        }
        Long seed = NameCodec.decode(s, SYSTEM_SYLLABLES);        // system (no suffix)
        if (seed != null) {
            long sd = seed;
            return new Match(2, clusterOf(sd), galaxyLocalOf(sd), starOf(sd), -1, sd);
        }
        Long planet = parseSeedNumber(s);                        // raw number == a PLANET's world seed
        if (planet != null) {
            // Seeds live ONLY on planets. Resolve the seed back to its host system
            // so the planet we land on is the exact one that displays this seed.
            long sys = UniverseMath.systemForPlanetSeed(planet, 0);
            if (UniverseMath.isStellarBlackHole(sys))            // black holes have no planets
                return new Match(2, clusterOf(sys), galaxyLocalOf(sys), starOf(sys), -1, sys);
            return new Match(3, clusterOf(sys), galaxyLocalOf(sys), starOf(sys), 0, sys);
        }
        return null;
    }

    /**
     * Parse a Minecraft world seed. Accepts signed values (e.g. a negative seed
     * like {@code -8271043186120821046}) as well as large unsigned ones.
     */
    private static Long parseSeedNumber(String s) {
        try {
            return Long.parseLong(s);            // signed: handles negatives + normal range
        } catch (NumberFormatException ignored) {
        }
        try {
            return Long.parseUnsignedLong(s);    // very large positive seeds
        } catch (NumberFormatException ignored) {
        }
        return null;
    }

    /**
     * Where a world seed sits in the universe, as display names:
     * {@code [cluster, galaxy, system, planet]}. The seed is treated as a planet's
     * world seed and resolved back to its host system.
     */
    public static String[] locate(long worldSeed) {
        long sys = UniverseMath.systemForPlanetSeed(worldSeed, 0);
        long cluster = clusterOf(sys);
        long galaxyLocal = galaxyLocalOf(sys);
        long galId = UniverseMath.galaxyId(cluster, galaxyLocal);
        String planet = UniverseMath.isStellarBlackHole(sys)
                ? "\u2014 (a black hole; no planet)"
                : planetName(sys, 0);
        return new String[]{clusterName(cluster), galaxyName(galId), systemName(sys), planet};
    }

    private static long clusterOf(long seed) {
        return Long.divideUnsigned(seed, UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER);
    }
    private static long galaxyLocalOf(long seed) {
        long block = UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER;
        return Long.divideUnsigned(Long.remainderUnsigned(seed, block), UniverseMath.STARS_PER_GALAXY);
    }
    private static long starOf(long seed) {
        return Long.remainderUnsigned(seed, UniverseMath.STARS_PER_GALAXY);
    }
    private static int designationIndex(String w) {
        for (int i = 0; i < PLANET_DESIG.length; i++) if (PLANET_DESIG[i].equalsIgnoreCase(w)) return i;
        return -1;
    }
    private static boolean contains(String[] arr, String w) {
        for (String a : arr) if (a.equalsIgnoreCase(w)) return true;
        return false;
    }

    /**
     * A planet's name: its host system's name plus a designation, so planets
     * clearly belong to their star. Example: "Fostruum Prime", "Fostruum III".
     */
    public static String planetName(long systemSeed, int index) {
        String base = systemName(systemSeed);
        String desig = PLANET_DESIG[Math.max(0, Math.min(PLANET_DESIG.length - 1, index))];
        return base + " " + desig;
    }

    /** A star / planet name, e.g. "Tarion", "Vex Prime", "Kepler-447 IV". */
    public static String starName(long seed) {
        long h = UniverseMath.mix64(seed ^ 0x5DEECE66DL);
        double style = UniverseMath.toUnit(h);

        if (style < 0.20) {
            // Catalog designation: "Kepler-1381", optionally " IV".
            String cat = pick(CATALOG, UniverseMath.mix64(h + 1));
            int num = (int) (UniverseMath.toUnit(UniverseMath.mix64(h + 2)) * 9000) + 100;
            String name = cat + "-" + num;
            if (UniverseMath.toUnit(UniverseMath.mix64(h + 3)) < 0.4) {
                name += " " + pick(ROMAN, UniverseMath.mix64(h + 4));
            }
            return name;
        }

        int syllables = 2 + (int) (UniverseMath.toUnit(UniverseMath.mix64(h + 5)) * 2); // 2-3
        String name = proper(h, syllables);

        double deco = UniverseMath.toUnit(UniverseMath.mix64(h + 6));
        if (deco < 0.12) {
            name += " Prime";
        } else if (deco < 0.20) {
            name += " Major";
        } else if (deco < 0.28) {
            name += " Minor";
        } else if (deco < 0.40) {
            name += " " + pick(ROMAN, UniverseMath.mix64(h + 7));
        } else if (deco < 0.46) {
            name += "-" + (char) ('b' + (int) (UniverseMath.toUnit(UniverseMath.mix64(h + 8)) * 5));
        }
        return name;
    }

    /** A galaxy name: reversible codeword of its global galaxy index + a suffix. */
    public static String galaxyName(long galaxyId) {
        String core = NameCodec.encode(galaxyId, GALAXY_SYLLABLES);
        return core + " " + pick(GALAXY_SUFFIX, UniverseMath.mix64(galaxyId ^ 0xA5A5A5A5A5A5A5A5L));
    }

    /** A cluster name: reversible codeword of its index + a suffix. */
    public static String clusterName(long clusterIndex) {
        String core = NameCodec.encode(clusterIndex, CLUSTER_SYLLABLES);
        return core + " " + pick(CLUSTER_SUFFIX, UniverseMath.mix64(clusterIndex ^ 0x1234567890ABCDEFL));
    }

    // Spectral classes with roughly realistic frequencies (M dwarfs dominate).
    private static final char[] SPECTRAL = {'O', 'B', 'A', 'F', 'G', 'K', 'M'};
    private static final int[] SPECTRAL_WEIGHT = {1, 2, 6, 30, 76, 121, 764};
    private static final String[] SPECTRAL_DESC = {
            "Blue supergiant", "Blue-white star", "White star", "Yellow-white star",
            "Yellow dwarf", "Orange dwarf", "Red dwarf"
    };

    // Representative colors per spectral class (O..M).
    private static final int[] SPECTRAL_COLOR = {
            0x9BB0FF, 0xAABFFF, 0xCAD7FF, 0xF8F7FF, 0xFFF4A0, 0xFFD2A1, 0xFF9060
    };

    /** Spectral class index 0..6 (O,B,A,F,G,K,M), realistic frequencies. */
    private static int spectralIndex(long seed) {
        long h = UniverseMath.mix64(seed ^ 0x57A121C7L);
        int total = 0;
        for (int w : SPECTRAL_WEIGHT) total += w;
        int roll = (int) Math.floorMod(h, total);
        int acc = 0;
        for (int i = 0; i < SPECTRAL.length; i++) {
            acc += SPECTRAL_WEIGHT[i];
            if (roll < acc) return i;
        }
        return 4;
    }

    /** The host star's spectral type, e.g. "G5 \u2014 Yellow dwarf". */
    public static String starType(long seed) {
        int i = spectralIndex(seed);
        int sub = (int) Math.floorMod(UniverseMath.mix64(seed ^ 0x57A121C8L), 10);
        return SPECTRAL[i] + sub + " \u2014 " + SPECTRAL_DESC[i];
    }

    /** The host star's color for rendering. */
    public static int starColor(long seed) {
        return SPECTRAL_COLOR[spectralIndex(seed)];
    }

    // Planet surface palette: rocky, ocean, desert, ice, gas, lava, verdant.
    private static final int[] PLANET_COLOR = {
            0x8A6E5A, 0x3E6FB0, 0xC7A35A, 0xD6E6F0, 0xC08A5A, 0xB4402A, 0x4F8F5A
    };

    /** A deterministic surface color for a planet seed. */
    public static int planetColor(long seed) {
        long h = UniverseMath.mix64(seed ^ 0x9A5C0107L);
        return PLANET_COLOR[(int) Math.floorMod(h, PLANET_COLOR.length)];
    }

    private static final String[] BH_TYPE = {
            "Stellar-mass", "Intermediate-mass", "Rotating (Kerr)", "Charged (Reissner-Nordstrom)"
    };
    private static final String[] BH_SUFFIX = {"Singularity", "X-1", "Void", "Rift", "Maw"};

    /** A stellar black hole's designation, e.g. "Vraikhoel Singularity". */
    public static String blackHoleName(long seed) {
        String base = systemName(seed);
        return base + " " + BH_SUFFIX[(int) Math.floorMod(UniverseMath.mix64(seed ^ 0xB0BEL), BH_SUFFIX.length)];
    }

    /** A galaxy's central supermassive black hole name, e.g. "Andaris A*". */
    public static String smbhName(long galaxyId) {
        return NameCodec.encode(galaxyId, GALAXY_SYLLABLES) + " A*";
    }

    public static String blackHoleType(long seed) {
        return BH_TYPE[(int) Math.floorMod(UniverseMath.mix64(seed ^ 0xB01EL), BH_TYPE.length)];
    }

    // Visual/render variants used by the map's black-hole shader.
    public static final int BH_KERR = 0;      // spinning, strong Doppler + frame-dragging
    public static final int BH_QUASAR = 1;    // active AGN with relativistic jets
    public static final int BH_DORMANT = 2;   // no disk, lensing only
    public static final int BH_MICRO = 3;     // tiny, unstable, Hawking glow
    private static final String[] BH_RENDER_NAMES = {
            "Kerr (spinning)", "Quasar / AGN", "Dormant", "Micro / primordial"
    };

    /**
     * The visual variant a black hole renders as. Supermassive holes (galaxy
     * cores) can be active quasars, spinning Kerr giants or dormant; stellar and
     * smaller holes are Kerr, dormant, or tiny primordial anomalies. Deterministic.
     */
    public static int blackHoleRenderType(long seed, boolean supermassive) {
        double x = UniverseMath.toUnit(UniverseMath.mix64(seed ^ 0xB4C0DEL));
        if (supermassive) {
            if (x < 0.25) return BH_QUASAR;
            if (x < 0.70) return BH_KERR;
            return BH_DORMANT;
        }
        if (x < 0.45) return BH_KERR;
        if (x < 0.75) return BH_DORMANT;
        return BH_MICRO;
    }

    /** Display name of a black hole's visual variant. */
    public static String blackHoleRenderTypeName(long seed, boolean supermassive) {
        return BH_RENDER_NAMES[blackHoleRenderType(seed, supermassive)];
    }

    /** Accretion-disk base colour for a black hole. */
    public static int blackHoleDiskColor(long seed) {
        long h = UniverseMath.mix64(seed ^ 0xACC7E71L);
        int[] pool = {0xFF8A30, 0xFFB050, 0xFF6020, 0xFFCF80, 0xE87040};
        return pool[(int) Math.floorMod(h, pool.length)];
    }

    private static String pick(String[] arr, long h) {
        return arr[(int) Math.floorMod(h, arr.length)];
    }

    private static String capitalize(String s) {
        if (s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
