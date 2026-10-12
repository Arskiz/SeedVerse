package arsqz.mod.client.galaxy;

/**
 * A single dot in the map. Depending on the view level this represents a
 * cluster, a galaxy, or a star (== Minecraft seed).
 *
 * @param id         stable id of the thing this dot represents
 * @param px         plane/space x position, roughly in [-1, 1]
 * @param py         plane/space y position, roughly in [-1, 1]
 * @param pz         space z position (depth). 0 for flat/2D distributions.
 * @param size       render size in pixels (before zoom)
 * @param color      0xRRGGBB base color
 * @param brightness 0..1, higher = revealed earlier at low zoom (LOD)
 */
public record StarPoint(long id, double px, double py, double pz,
                        float size, int color, double brightness) {
}
