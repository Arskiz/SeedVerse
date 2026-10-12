package arsqz.mod.universe;

/**
 * Pure, dependency-free math for the procedural universe.
 *
 * <p>The universe is never stored. Everything (positions, colors, names, seeds)
 * is derived deterministically from a dot's coordinates via 64-bit hashing.
 *
 * <p>Hierarchy:
 * <pre>
 *   Universe  -> galaxy clusters      (~1845 of them)
 *   Cluster   -> galaxies             (100,000,000 per cluster)
 *   Galaxy    -> stars / MC seeds     (100,000,000 per galaxy)
 * </pre>
 *
 * Minecraft Java has 2^64 = 18,446,744,073,709,551,616 seeds.
 * 2^64 / 1e8 (stars per galaxy) = 184,467,440,737 galaxies total.
 * 184,467,440,737 / 1e8 (galaxies per cluster) = ~1845 clusters.
 */
public final class UniverseMath {

    private UniverseMath() {}

    /** Stars (== Minecraft seeds) in a single galaxy. */
    public static final long STARS_PER_GALAXY = 100_000_000L;

    /** Galaxies in a single cluster. */
    public static final long GALAXIES_PER_CLUSTER = 100_000_000L;

    /** Total galaxies in the universe (2^64 / STARS_PER_GALAXY). */
    public static final long TOTAL_GALAXIES = 184_467_440_737L;

    /** Total clusters (ceil(TOTAL_GALAXIES / GALAXIES_PER_CLUSTER)). */
    public static final long NUM_CLUSTERS = 1845L;

    /** View levels, from most zoomed-out to most zoomed-in. */
    public enum Level {
        UNIVERSE, // dots are clusters
        CLUSTER,  // dots are galaxies
        GALAXY    // dots are stars (== seeds)
    }

    // ---------------------------------------------------------------------
    // Hashing (SplitMix64 finalizer) - fast, well-distributed, deterministic.
    // ---------------------------------------------------------------------

    static final long GOLDEN = 0x9E3779B97F4A7C15L;
    private static final long MIX_C1 = 0xBF58476D1CE4E5B9L;
    private static final long MIX_C2 = 0x94D049BB133111EBL;
    private static final long MIX_C1_INV = modInverse(MIX_C1);
    private static final long MIX_C2_INV = modInverse(MIX_C2);

    public static long mix64(long z) {
        z += GOLDEN;
        z = (z ^ (z >>> 30)) * MIX_C1;
        z = (z ^ (z >>> 27)) * MIX_C2;
        return z ^ (z >>> 31);
    }

    /**
     * The exact inverse of {@link #mix64(long)}. The SplitMix64 finalizer is a
     * bijection over 64 bits, so every hash can be walked back to its input.
     * This is what lets a planet's world seed be turned back into its host
     * system's coordinate (see {@link #systemForPlanetSeed}).
     */
    public static long mix64inv(long z) {
        z = invXorShiftRight(z, 31);
        z *= MIX_C2_INV;
        z = invXorShiftRight(z, 27);
        z *= MIX_C1_INV;
        z = invXorShiftRight(z, 30);
        return z - GOLDEN;
    }

    /** Invert {@code y = x ^ (x >>> s)} (a self-invertible-shape xorshift). */
    private static long invXorShiftRight(long y, int s) {
        long x = y;
        for (int i = s; i < 64; i += s) x = y ^ (x >>> s);
        return x;
    }

    /** Modular inverse of an odd value mod 2^64, via Newton's iteration. */
    private static long modInverse(long a) {
        long x = a;                       // correct to 3 low bits
        for (int i = 0; i < 6; i++) x *= 2 - a * x;
        return x;
    }

    /** Combine several longs into one well-mixed hash. */
    public static long hash(long a, long b) {
        return mix64(mix64(a) ^ (b + 0x9E3779B97F4A7C15L));
    }

    public static long hash(long a, long b, long c) {
        return mix64(hash(a, b) ^ (c * 0xD6E8FEB86659FD93L));
    }

    /** Deterministic double in [0,1) from a hash. */
    public static double toUnit(long h) {
        // Use top 53 bits for a clean double mantissa.
        return (h >>> 11) * 0x1.0p-53;
    }

    /** Deterministic double in [-1,1) from a hash. */
    public static double toSigned(long h) {
        return toUnit(h) * 2.0 - 1.0;
    }

    // ---------------------------------------------------------------------
    // Coordinate <-> seed mapping
    // ---------------------------------------------------------------------

    /**
     * Compute the Minecraft world seed for a star.
     *
     * @param cluster      cluster index      [0, NUM_CLUSTERS)
     * @param galaxyLocal  galaxy in cluster   [0, GALAXIES_PER_CLUSTER)
     * @param star         star in galaxy      [0, STARS_PER_GALAXY)
     */
    public static long seedFor(long cluster, long galaxyLocal, long star) {
        return cluster * (STARS_PER_GALAXY * GALAXIES_PER_CLUSTER)
                + galaxyLocal * STARS_PER_GALAXY
                + star;
    }

    /** A stable id for a galaxy (used for naming / child generation). */
    public static long galaxyId(long cluster, long galaxyLocal) {
        return cluster * GALAXIES_PER_CLUSTER + galaxyLocal;
    }

    // ---------------------------------------------------------------------
    // Absolute spatial embedding (for light-year distances between seeds)
    // ---------------------------------------------------------------------

    // Rough real-universe scales, in light years.
    private static final double UNIVERSE_SIZE = 9.0e10; // ~ observable universe
    private static final double CLUSTER_SIZE  = 3.0e8;  // supercluster spread
    private static final double GALAXY_SIZE    = 1.0e5;  // galaxy diameter

    /** Deterministic 3D position (light years) for any seed's home planet. */
    public static double[] position(long seed) {
        long block = STARS_PER_GALAXY * GALAXIES_PER_CLUSTER; // 1e16
        long cluster = Long.divideUnsigned(seed, block);
        long rem = Long.remainderUnsigned(seed, block);
        long galaxyLocal = Long.divideUnsigned(rem, STARS_PER_GALAXY);
        long star = Long.remainderUnsigned(rem, STARS_PER_GALAXY);

        double[] p = new double[3];
        addOffset(p, cluster, 0x1111, UNIVERSE_SIZE);
        addOffset(p, hash(galaxyLocal, cluster), 0x2222, CLUSTER_SIZE);
        addOffset(p, hash(star, galaxyLocal), 0x3333, GALAXY_SIZE);
        return p;
    }

    private static void addOffset(double[] p, long key, long salt, double size) {
        p[0] += toSigned(mix64(key ^ salt)) * size * 0.5;
        p[1] += toSigned(mix64(key ^ (salt + 1))) * size * 0.5;
        p[2] += toSigned(mix64(key ^ (salt + 2))) * size * 0.5;
    }

    /** Number of planets orbiting a star system (1..8), deterministic. */
    public static int planetCount(long systemSeed) {
        return 1 + (int) Math.floorMod(mix64(systemSeed ^ 0x50A1E7L), 8);
    }

    /**
     * Whether a star system has collapsed into a stellar-mass black hole instead
     * of hosting planets. Roughly 1 in 2000 systems, fully deterministic.
     */
    public static boolean isStellarBlackHole(long systemSeed) {
        return Long.remainderUnsigned(mix64(systemSeed ^ 0xB1AC70L), 2000L) == 0L;
    }

    /** A deterministic world seed for the k-th planet of a system. */
    public static long planetSeed(long systemSeed, int index) {
        return mix64(systemSeed ^ ((index + 1L) * GOLDEN));
    }

    /**
     * The host system coordinate whose planet #{@code index} has the given world
     * seed. Exact inverse of {@link #planetSeed(long, int)}, so a planet seed you
     * type into the search bar resolves back to the very planet that displays it.
     */
    public static long systemForPlanetSeed(long planetSeed, int index) {
        return mix64inv(planetSeed) ^ ((index + 1L) * GOLDEN);
    }

    /**
     * If {@code planetSeed} belongs to a planet of {@code systemSeed}, return its
     * orbital index; otherwise -1. Lets us tell "same system, different planet"
     * from "another system entirely" without guessing.
     */
    public static int planetIndexInSystem(long systemSeed, long planetSeed) {
        int n = planetCount(systemSeed);
        for (int i = 0; i < n; i++) if (planetSeed(systemSeed, i) == planetSeed) return i;
        return -1;
    }

    // ---- intra-system (solar-system scale) geometry, in astronomical units ----

    /** A planet's orbital radius in AU: a Titius-Bode-like outward spread. */
    public static double planetOrbitAu(long systemSeed, int index) {
        double base = 0.39 * Math.pow(1.6, index);
        double jitter = 0.85 + 0.30 * toUnit(mix64(systemSeed ^ (0x0B17A1L + index)));
        return base * jitter;
    }

    /** A planet's (deterministic) orbital phase angle in radians. */
    public static double planetPhase(long systemSeed, int index) {
        return toUnit(mix64(systemSeed ^ (0x0F3A5E0L + index * 0x9E3779B1L))) * (Math.PI * 2.0);
    }

    /** Distance in AU between two planets of the same star system. */
    public static double intraSystemDistanceAu(long systemSeed, int a, int b) {
        double ra = planetOrbitAu(systemSeed, a), ta = planetPhase(systemSeed, a);
        double rb = planetOrbitAu(systemSeed, b), tb = planetPhase(systemSeed, b);
        double dx = ra * Math.cos(ta) - rb * Math.cos(tb);
        double dy = ra * Math.sin(ta) - rb * Math.sin(tb);
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static final double KM_PER_AU = 1.495978707e8;

    /** Human-friendly solar-system distance (AU + kilometers). */
    public static String formatAu(double au) {
        double km = au * KM_PER_AU;
        if (au < 0.01) return String.format("%,.0f km", km);
        if (au < 1.0)  return String.format("%.3f AU (%.1f million km)", au, km / 1e6);
        return String.format("%.2f AU (%.0f million km)", au, km / 1e6);
    }

    /** Straight-line distance in light years between two system coordinates. */
    public static double distanceLightYears(long a, long b) {
        double[] pa = position(a);
        double[] pb = position(b);
        double dx = pa[0] - pb[0], dy = pa[1] - pb[1], dz = pa[2] - pb[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Map distance (light years) between the planet the player is currently on
     * and a target planet. Zero when they are the same planet. Distances are
     * measured between the planets' host systems on the galactic map, not from
     * the raw seed numbers.
     *
     * @param targetSystem the target planet's host system coordinate
     * @param targetPlanet the target planet's world seed
     * @param currentPlanet the world seed of the planet the player is on
     */
    public static double travelDistanceLy(long targetSystem, long targetPlanet, long currentPlanet) {
        if (targetPlanet == currentPlanet) return 0.0;
        return distanceLightYears(targetSystem, systemForPlanetSeed(currentPlanet, 0));
    }

    /** Human-friendly light-year distance. */
    public static String formatDistance(double ly) {
        if (ly < 1_000) return String.format("%.0f ly", ly);
        if (ly < 1_000_000) return String.format("%,.0f ly", ly);
        if (ly < 1_000_000_000) return String.format("%.2f million ly", ly / 1e6);
        if (ly < 1_000_000_000_000L) return String.format("%.2f billion ly", ly / 1e9);
        return String.format("%.2f trillion ly", ly / 1e12);
    }
}
