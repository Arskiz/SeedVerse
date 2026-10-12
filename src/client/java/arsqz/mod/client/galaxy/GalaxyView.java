package arsqz.mod.client.galaxy;

import arsqz.mod.universe.UniverseMath;
import arsqz.mod.universe.UniverseMath.Level;

import net.minecraft.client.resources.language.I18n;

import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;

/**
 * Procedurally generates the dots for one view (a cluster of galaxies, a
 * galaxy of stars, etc.). Generation is deterministic from the parent id,
 * so the same place always looks the same.
 *
 * <p>Dots are sorted brightest-first so the map can reveal only the brightest
 * ones when zoomed out and progressively show fainter ones as you zoom in
 * (level of detail). We never generate more than {@link #maxDots}.
 */
public final class GalaxyView {

    // Star-temperature palette (0xRRGGBB), roughly blue -> white -> red.
    private static final int[] PALETTE = {
            0x9BB0FF, // blue
            0xCAD7FF, // blue-white
            0xF8F7FF, // white
            0xFFF4EA, // warm white
            0xFFEDD8, // yellow-white
            0xFFD2A1, // orange
            0xFFB56B, // deep orange
            0xFF9776  // red
    };
    // Weights bias toward common yellow/white stars.
    private static final int[] PALETTE_WEIGHT = {6, 10, 16, 18, 16, 10, 6, 4};
    private static final int PALETTE_TOTAL;
    static {
        int t = 0;
        for (int w : PALETTE_WEIGHT) t += w;
        PALETTE_TOTAL = t;
    }

    private final Level level;
    private final long clusterIndex;   // valid for CLUSTER / GALAXY
    private final long galaxyLocal;    // valid for GALAXY
    private final StarPoint[] dots;    // sorted brightest-first
    private final int maxDots;

    private GalaxyView(Level level, long clusterIndex, long galaxyLocal, StarPoint[] dots, int maxDots) {
        this.level = level;
        this.clusterIndex = clusterIndex;
        this.galaxyLocal = galaxyLocal;
        this.dots = dots;
        this.maxDots = maxDots;
    }

    public Level level() { return level; }
    public long clusterIndex() { return clusterIndex; }
    public long galaxyLocal() { return galaxyLocal; }
    public StarPoint[] dots() { return dots; }
    public int maxDots() { return maxDots; }

    /** Top-level view: the galaxy clusters of the universe, spread out in 3D. */
    public static GalaxyView universe() {
        int max = (int) Math.min(UniverseMath.NUM_CLUSTERS, 1845);
        return buildUniverseField(max);
    }

    /**
     * All galaxy clusters distributed through a lumpy 3D volume (the cosmic web
     * at its largest scale): a handful of supercluster nodes with clusters
     * scattered around them and voids in between, given real (x,y,z) so the map
     * can rotate them in full 3D.
     */
    // Supercluster node centres of the cosmic web, computed once and shared by
    // the sampled universe field and clusterAt() so any cluster index maps to the
    // exact same position (kept in lockstep - see the determinism note above).
    private static double[][] uNodes;
    private static double[] uNodeTight;

    private static synchronized void ensureUniverseNodes() {
        if (uNodes != null) return;
        Random pr = new Random(0xF00DL);
        int nodes = 6 + pr.nextInt(5);                // supercluster centres
        double[][] node = new double[nodes][3];
        double[] tight = new double[nodes];
        for (int n = 0; n < nodes; n++) {
            node[n][0] = (pr.nextDouble() * 2.0 - 1.0) * 0.85;
            node[n][1] = (pr.nextDouble() * 2.0 - 1.0) * 0.85;
            node[n][2] = (pr.nextDouble() * 2.0 - 1.0) * 0.85;
            tight[n] = 0.16 + pr.nextDouble() * 0.30;
        }
        uNodes = node;
        uNodeTight = tight;
    }

    /** The deterministic universe-view dot for a single cluster index. */
    public static StarPoint clusterAt(long index) {
        ensureUniverseNodes();
        Random r = new Random(UniverseMath.hash(0xF00DL, index));
        int n = r.nextInt(uNodes.length);
        double s = uNodeTight[n];
        double x = uNodes[n][0] + r.nextGaussian() * s;
        double y = uNodes[n][1] + r.nextGaussian() * s;
        double z = uNodes[n][2] + r.nextGaussian() * s;
        double brightness = 0.35 + r.nextDouble() * 0.65;
        float size = (float) (1.0 + brightness * 2.2);
        return new StarPoint(index, x, y, z, size, pickColor(r), brightness);
    }

    private static GalaxyView buildUniverseField(int count) {
        StarPoint[] dots = new StarPoint[count];
        for (int i = 0; i < count; i++) {
            dots[i] = clusterAt(i);
        }
        Arrays.sort(dots, Comparator.comparingDouble(StarPoint::brightness).reversed());
        return new GalaxyView(Level.UNIVERSE, 0, 0, dots, count);
    }

    /** Zoom into a cluster to see its galaxies (spread out in 3D). */
    public static GalaxyView cluster(long clusterIndex) {
        long parent = UniverseMath.mix64(clusterIndex ^ 0xC1057E9L);
        // A representative sample of the cluster's galaxies (the full ~1e8 can't be
        // rendered). Denser at higher quality. Galaxy positions are keyed to their
        // index, so raising the count only reveals more galaxies - it never moves
        // the ones already there, keeping searches and pins consistent.
        int count = 6000 + (int) (MapQuality.tier() * 3000.0);   // ~9k .. 21k
        return buildClusterField(clusterIndex, parent, count);
    }

    /**
     * The deterministic 3D structure of a cluster: several galaxy sub-groups
     * placed inside a roughly spherical (mildly, randomly squashed) volume. Shared
     * by the sampled field and {@link #galaxyAt} so any galaxy index maps to the
     * same position. Deliberately volumetric in all three axes - not a thin rod -
     * so the cluster has real depth when rotated.
     */
    private record ClusterForm(int clumps, double[] cx, double[] cy, double[] cz,
                               double[] tight, double[] wcum) {}

    private static ClusterForm clusterForm(long parentSeed) {
        Random pr = new Random(parentSeed);
        int clumps = 4 + pr.nextInt(5);                 // 4-8 galaxy groups
        double[] cx = new double[clumps];
        double[] cy = new double[clumps];
        double[] cz = new double[clumps];
        double[] tight = new double[clumps];
        double[] wcum = new double[clumps];
        // Mild per-axis scaling: a lumpy ellipsoid, never a rod. The spread is
        // deliberately large in depth (z included) so galaxies sit at genuinely
        // different distances - otherwise the perspective factor barely varies and
        // every galaxy renders the same size.
        double ax = 0.7 + pr.nextDouble() * 0.3;
        double ay = 0.7 + pr.nextDouble() * 0.3;
        double az = 0.7 + pr.nextDouble() * 0.3;
        double wsum = 0;
        for (int c = 0; c < clumps; c++) {
            cx[c] = pr.nextGaussian() * 1.05 * ax;
            cy[c] = pr.nextGaussian() * 1.05 * ay;
            cz[c] = pr.nextGaussian() * 1.05 * az;
            tight[c] = 0.18 + pr.nextDouble() * 0.40;
            wsum += 0.4 + pr.nextDouble();
            wcum[c] = wsum;
        }
        return new ClusterForm(clumps, cx, cy, cz, tight, wcum);
    }

    /** The deterministic 3D position/colour of a single galaxy, for any index. */
    private static StarPoint genClusterGalaxy(long clusterIndex, long parentSeed, ClusterForm f, long i) {
        Random r = new Random(UniverseMath.hash(parentSeed, i));
        double pick = r.nextDouble() * f.wcum()[f.clumps() - 1];
        int c = 0;
        while (c < f.clumps() - 1 && pick > f.wcum()[c]) c++;
        double t = f.tight()[c];
        double x = f.cx()[c] + r.nextGaussian() * t;
        double y = f.cy()[c] + r.nextGaussian() * t;
        double z = f.cz()[c] + r.nextGaussian() * t;
        double brightness = 0.3 + r.nextDouble() * 0.7;
        float size = (float) (0.9 + brightness * 2.2);
        int color = pickColor(r);
        return new StarPoint(UniverseMath.galaxyId(clusterIndex, i), x, y, z, size, color, brightness);
    }

    private static GalaxyView buildClusterField(long clusterIndex, long parentSeed, int count) {
        StarPoint[] dots = new StarPoint[count];
        ClusterForm form = clusterForm(parentSeed);
        for (int i = 0; i < count; i++) {
            dots[i] = genClusterGalaxy(clusterIndex, parentSeed, form, i);
        }
        Arrays.sort(dots, Comparator.comparingDouble(StarPoint::brightness).reversed());
        return new GalaxyView(Level.CLUSTER, clusterIndex, 0, dots, count);
    }

    /** Position/colour of galaxy {@code i} in a cluster, even outside the sampled subset. */
    public static StarPoint galaxyAt(long clusterIndex, long i) {
        long parentSeed = UniverseMath.mix64(clusterIndex ^ 0xC1057E9L);
        return genClusterGalaxy(clusterIndex, parentSeed, clusterForm(parentSeed), i);
    }

    /** Max stars generated for a single galaxy (LOD picks how many to draw). */
    public static final int GALAXY_STARS = 16000;

    // Galaxy morphologies (Hubble-ish). Chosen deterministically per galaxy.
    public static final int TYPE_SPIRAL = 0;
    public static final int TYPE_BARRED = 1;
    public static final int TYPE_ELLIPTICAL = 2;
    public static final int TYPE_IRREGULAR = 3;
    private static final String[] TYPE_KEYS = {
            "galaxytype.universemod.spiral", "galaxytype.universemod.barred_spiral",
            "galaxytype.universemod.elliptical", "galaxytype.universemod.irregular"
    };

    /**
     * Event-horizon radius of a galaxy's central supermassive black hole, in
     * galaxy-local units. The map's SMBH world radius derives from this, so
     * changing it here rescales the rendered black hole too.
     */
    public static final double SMBH_LOCAL_R = 0.0035;

    /**
     * Stars are cleared from this local radius around the centre of galaxies that
     * host an SMBH, so no star system spawns underneath the black hole. Sized to
     * clear the accretion disk (which reaches ~7 event-horizon radii).
     */
    public static final double CORE_CLEAR_R = SMBH_LOCAL_R * 8.0;

    /** Whether a galaxy hosts a central supermassive black hole. */
    public static boolean galaxyHasBH(long galaxyId) {
        long parent = UniverseMath.mix64(galaxyId ^ 0x6A1AC7L);
        return formOf(parent).hasBH();
    }

    /** Human-readable morphology of a galaxy, for labels. */
    public static String galaxyTypeName(long galaxyId) {
        long parent = UniverseMath.mix64(galaxyId ^ 0x6A1AC7L);
        return I18n.get(TYPE_KEYS[formOf(parent).type()]);
    }

    /**
     * All the deterministic shape parameters of one galaxy, derived from its
     * parent seed. Computed identically by {@link #buildRealisticGalaxy} and
     * {@link #starAt} so a star's index always maps to the same position.
     */
    private record GalaxyForm(int type, int arms, double pitch, double a, double coreR,
                              double baseAngle, double dir, double barLen, double ellipQ,
                              double axisAngle, double[] clumpX, double[] clumpY, double[] clumpR,
                              boolean hasBH) {}

    private static int pickType(double x) {
        if (x < 0.45) return TYPE_SPIRAL;        // spirals are the most common
        if (x < 0.75) return TYPE_BARRED;
        if (x < 0.93) return TYPE_ELLIPTICAL;
        return TYPE_IRREGULAR;
    }

    private static GalaxyForm formOf(long parentSeed) {
        Random pr = new Random(parentSeed);
        int type = pickType(pr.nextDouble());
        int arms = 2 + pr.nextInt(3);                 // 2-4 arms
        double pitch = 0.20 + pr.nextDouble() * 0.16; // spiral tightness (b)
        double a = 0.020 + pr.nextDouble() * 0.020;   // spiral base radius
        double coreR = 0.10 + pr.nextDouble() * 0.05; // bulge radius
        double baseAngle = pr.nextDouble() * Math.PI * 2.0;
        double dir = pr.nextBoolean() ? 1.0 : -1.0;   // winding direction
        double barLen = 0.28 + pr.nextDouble() * 0.28; // central bar half-length
        double ellipQ = 0.55 + pr.nextDouble() * 0.40; // elliptical axis ratio
        double axisAngle = pr.nextDouble() * Math.PI;  // orientation of ellipse/bar
        int nc = 3 + pr.nextInt(4);                    // irregular clumps
        double[] cx = new double[nc], cy = new double[nc], cr = new double[nc];
        for (int c = 0; c < nc; c++) {
            cx[c] = (pr.nextDouble() * 2.0 - 1.0) * 0.6;
            cy[c] = (pr.nextDouble() * 2.0 - 1.0) * 0.6;
            cr[c] = 0.12 + pr.nextDouble() * 0.28;
        }
        // Not every galaxy hosts a central SMBH: massive spirals/ellipticals almost
        // always do, small irregular galaxies mostly don't.
        double bhRoll = pr.nextDouble();
        boolean hasBH = (type == TYPE_IRREGULAR) ? bhRoll < 0.25 : bhRoll < 0.92;
        return new GalaxyForm(type, arms, pitch, a, coreR, baseAngle, dir, barLen, ellipQ, axisAngle,
                cx, cy, cr, hasBH);
    }

    /** Zoom into a galaxy to see its stars (== seeds). */
    public static GalaxyView galaxy(long clusterIndex, long galaxyLocal) {
        long id = UniverseMath.galaxyId(clusterIndex, galaxyLocal);
        long parent = UniverseMath.mix64(id ^ 0x6A1AC7L);
        return buildRealisticGalaxy(clusterIndex, galaxyLocal, parent, GALAXY_STARS);
    }

    /**
     * A physically-inspired galaxy whose morphology (spiral, barred spiral,
     * elliptical or irregular) is picked deterministically from its seed. Spiral
     * types have a bulge/bar, logarithmic arms with dispersion and HII regions;
     * ellipticals are smooth old-red ellipsoids; irregulars are blue, clumpy and
     * star-forming. Colours follow a rough temperature/age gradient.
     */
    private static GalaxyView buildRealisticGalaxy(long clusterIndex, long galaxyLocal,
                                                   long parentSeed, int count) {
        StarPoint[] dots = new StarPoint[count];
        GalaxyForm form = formOf(parentSeed);
        for (int i = 0; i < count; i++) {
            dots[i] = genStar(clusterIndex, galaxyLocal, parentSeed, form, i);
        }
        // NOTE: intentionally NOT sorted by brightness. Each star's component is
        // chosen independently per index, so the natural order is a representative
        // sample of the whole galaxy - this keeps the arms populated at low LOD
        // instead of the bright core hogging the entire budget.
        return new GalaxyView(Level.GALAXY, clusterIndex, galaxyLocal, dots, count);
    }

    /** The deterministic position/colour of a single star, for any index. */
    private static StarPoint genStar(long clusterIndex, long galaxyLocal, long parentSeed,
                                     GalaxyForm f, long i) {
        Random r = new Random(UniverseMath.hash(parentSeed, i));
        double px, py, brightness, size;
        int color;

        if (f.type() == TYPE_ELLIPTICAL) {
            // Smooth ellipsoid: concentrated centre, old red/yellow population.
            double rad = Math.pow(r.nextDouble(), 2.2) * 1.0;
            double ang = r.nextDouble() * Math.PI * 2.0;
            double ex = Math.cos(ang) * rad;
            double ey = Math.sin(ang) * rad * f.ellipQ();
            double ca = Math.cos(f.axisAngle()), sa = Math.sin(f.axisAngle());
            px = ex * ca - ey * sa + r.nextGaussian() * 0.008;
            py = ex * sa + ey * ca + r.nextGaussian() * 0.008;
            double t = Math.min(1.0, rad);
            brightness = 0.30 - t * 0.10 + r.nextDouble() * 0.12;
            size = 0.8 + (1.0 - t) * 0.8 + r.nextDouble() * 0.3;
            color = lerpColor(0xFFE8C8, 0xFFB070, Math.min(1.0, t * 0.8 + r.nextDouble() * 0.2));
        } else if (f.type() == TYPE_IRREGULAR) {
            // Clumpy, asymmetric, blue star-forming with pink HII knots.
            int c = r.nextInt(f.clumpX().length);
            double rad = Math.abs(r.nextGaussian()) * f.clumpR()[c];
            double ang = r.nextDouble() * Math.PI * 2.0;
            px = f.clumpX()[c] + Math.cos(ang) * rad + r.nextGaussian() * 0.02;
            py = f.clumpY()[c] + Math.sin(ang) * rad + r.nextGaussian() * 0.02;
            if (r.nextDouble() < 0.10) {
                color = lerpColor(0xFF5566, 0xFFA6B4, r.nextDouble());
                brightness = 0.5 + r.nextDouble() * 0.4;
                size = 1.8 + r.nextDouble() * 2.0;
            } else {
                color = lerpColor(0x9FC0FF, 0xE6EEFF, r.nextDouble());
                brightness = 0.28 + r.nextDouble() * 0.42;
                size = 0.8 + r.nextDouble() * 0.8;
            }
        } else {
            // Spiral / barred spiral.
            boolean barred = f.type() == TYPE_BARRED;
            double roll = r.nextDouble();
            if (roll < 0.20) {
                if (barred) {
                    // Central bar instead of a round bulge.
                    double along = (r.nextDouble() * 2.0 - 1.0) * f.barLen();
                    double across = r.nextGaussian() * f.coreR() * 0.32;
                    double ca = Math.cos(f.baseAngle()), sa = Math.sin(f.baseAngle());
                    px = along * ca - across * sa;
                    py = along * sa + across * ca;
                    double t = Math.min(1.0, Math.abs(along) / Math.max(0.01, f.barLen()));
                    brightness = 0.30 - t * 0.08 + r.nextDouble() * 0.10;
                    size = 1.0 + (1.0 - t) * 0.8 + r.nextDouble() * 0.3;
                    color = lerpColor(0xFFF0DA, 0xFFC888, Math.min(1.0, 0.5 + r.nextDouble() * 0.3));
                } else {
                    double rad = Math.abs(r.nextGaussian()) * f.coreR() * 0.9;
                    double ang = r.nextDouble() * Math.PI * 2.0;
                    px = Math.cos(ang) * rad;
                    py = Math.sin(ang) * rad;
                    double t = Math.min(1.0, rad / f.coreR());
                    brightness = 0.28 - t * 0.10 + r.nextDouble() * 0.12;
                    size = 0.9 + (1.0 - t) * 1.0 + r.nextDouble() * 0.3;
                    color = lerpColor(0xFFF0DA, 0xFFC888, Math.min(1.0, t * 0.9 + r.nextDouble() * 0.15));
                }
            } else if (roll < 0.92) {
                int arm = r.nextInt(f.arms());
                double u = r.nextDouble();
                double rad = -Math.log(1.0 - u * 0.985) * 0.28;
                double minR = barred ? Math.max(f.coreR() * 0.5, f.barLen() * 0.92) : f.coreR() * 0.5;
                rad = Math.max(minR, Math.min(1.05, rad));
                double theta = f.dir() * Math.log(rad / f.a()) / Math.max(0.05, f.pitch())
                        + arm * (Math.PI * 2.0 / f.arms()) + f.baseAngle();
                double cx = Math.cos(theta) * rad;
                double cy = Math.sin(theta) * rad;
                double tx = f.dir() * f.pitch() * Math.cos(theta) - Math.sin(theta);
                double ty = f.dir() * f.pitch() * Math.sin(theta) + Math.cos(theta);
                double tl = Math.hypot(tx, ty);
                tx /= tl; ty /= tl;
                double perp = r.nextGaussian() * 0.08 * rad;
                double along = r.nextGaussian() * 0.05 * rad;
                px = cx + (-ty) * perp + tx * along + r.nextGaussian() * 0.005;
                py = cy + (tx) * perp + ty * along + r.nextGaussian() * 0.005;
                if (r.nextDouble() < 0.07) {
                    color = lerpColor(0xFF5566, 0xFFA6B4, r.nextDouble());
                    brightness = 0.5 + r.nextDouble() * 0.4;
                    size = 2.2 + r.nextDouble() * 2.2;
                } else {
                    double bt = r.nextDouble();
                    color = (bt < 0.12) ? 0xFFFFFF : lerpColor(0x9FC0FF, 0xDCE8FF, r.nextDouble());
                    brightness = 0.30 + r.nextDouble() * 0.45;
                    size = 0.8 + r.nextDouble() * 0.9;
                }
            } else {
                double rad = Math.pow(r.nextDouble(), 0.5) * 0.95;
                double ang = r.nextDouble() * Math.PI * 2.0;
                px = Math.cos(ang) * rad;
                py = Math.sin(ang) * rad;
                brightness = 0.14 + r.nextDouble() * 0.22;
                size = 0.7 + r.nextDouble() * 0.6;
                color = (r.nextDouble() < 0.3)
                        ? lerpColor(0xD0A886, 0xFFFFFF, r.nextDouble())
                        : lerpColor(0x9FB0E0, 0xE0E8FF, r.nextDouble());
            }
        }
        // Keep a clear zone around a central SMBH so no system sits under it.
        if (f.hasBH()) {
            double rr = Math.hypot(px, py);
            if (rr < CORE_CLEAR_R) {
                double ang = Math.atan2(py, px);
                double nr = CORE_CLEAR_R + 0.3 * rr;   // push the inner stars past the disk
                px = Math.cos(ang) * nr;
                py = Math.sin(ang) * nr;
            }
        }

        return new StarPoint(UniverseMath.seedFor(clusterIndex, galaxyLocal, i),
                px, py, 0.0, (float) size, color, brightness);
    }

    /** Position/colour of star {@code index} in a galaxy, even outside the sampled subset. */
    public static StarPoint starAt(long clusterIndex, long galaxyLocal, long index) {
        long id = UniverseMath.galaxyId(clusterIndex, galaxyLocal);
        long parentSeed = UniverseMath.mix64(id ^ 0x6A1AC7L);
        return genStar(clusterIndex, galaxyLocal, parentSeed, formOf(parentSeed), index);
    }

    private static int lerpColor(int c1, int c2, double t) {
        t = Math.max(0.0, Math.min(1.0, t));
        int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
        int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
        int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static GalaxyView build(Level level, long clusterIndex, long galaxyLocal,
                                    long parentSeed, int count, boolean spiral) {
        StarPoint[] dots = new StarPoint[count];
        int arms = 2 + (int) (UniverseMath.toUnit(UniverseMath.mix64(parentSeed)) * 4); // 2-5 arms
        double twist = 2.5 + UniverseMath.toUnit(UniverseMath.mix64(parentSeed + 1)) * 3.0;

        for (int i = 0; i < count; i++) {
            long h = UniverseMath.hash(parentSeed, i);
            Random r = new Random(h);

            double px, py;
            if (spiral) {
                double rad = Math.pow(r.nextDouble(), 0.5);           // concentrate the core
                int arm = i % arms;
                double armOffset = arm * (Math.PI * 2.0 / arms);
                double armSpread = (0.18 * (1.0 - rad) + 0.05);       // arms tighten outward
                double angle = armOffset + rad * twist + r.nextGaussian() * armSpread;
                px = Math.cos(angle) * rad + r.nextGaussian() * 0.015;
                py = Math.sin(angle) * rad + r.nextGaussian() * 0.015;
            } else {
                // Elliptical cloud for clusters / the universe.
                double rad = Math.pow(r.nextDouble(), 0.6);
                double angle = r.nextDouble() * Math.PI * 2.0;
                px = Math.cos(angle) * rad + r.nextGaussian() * 0.04;
                py = Math.sin(angle) * rad * 0.85 + r.nextGaussian() * 0.04;
            }

            // Brightness: bias so a few are bright and many are faint.
            double brightness = Math.pow(r.nextDouble(), 2.2);
            float size = (float) (0.9 + brightness * 2.2);
            int color = pickColor(r);

            long id = childId(level, clusterIndex, galaxyLocal, i);
            dots[i] = new StarPoint(id, px, py, 0.0, size, color, brightness);
        }

        // Brightest first for level-of-detail reveal.
        Arrays.sort(dots, Comparator.comparingDouble(StarPoint::brightness).reversed());
        return new GalaxyView(level, clusterIndex, galaxyLocal, dots, count);
    }

    /** Map a dot index to the id of what it represents at this level. */
    private static long childId(Level level, long clusterIndex, long galaxyLocal, int index) {
        return switch (level) {
            case UNIVERSE -> index;                                        // cluster index
            case CLUSTER  -> UniverseMath.galaxyId(clusterIndex, index);   // galaxy id
            case GALAXY   -> UniverseMath.seedFor(clusterIndex, galaxyLocal, index); // seed
        };
    }

    private static int pickColor(Random r) {
        int roll = r.nextInt(PALETTE_TOTAL);
        int acc = 0;
        for (int i = 0; i < PALETTE.length; i++) {
            acc += PALETTE_WEIGHT[i];
            if (roll < acc) return PALETTE[i];
        }
        return PALETTE[2];
    }
}
