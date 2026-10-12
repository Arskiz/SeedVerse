package arsqz.mod.client.galaxy;

import arsqz.mod.TwoSixTwo_Arskiz;
import arsqz.mod.client.PlanetSeedCache;
import arsqz.mod.net.JoinPlayerPacket;
import arsqz.mod.net.PingFocusPacket;
import arsqz.mod.net.RequestDiscoveryPacket;
import arsqz.mod.net.RequestPlayersPacket;
import arsqz.mod.net.ReturnHomePacket;
import arsqz.mod.net.ShareBeaconPacket;
import arsqz.mod.net.TravelToPlanetPacket;
import arsqz.mod.universe.NameGenerator;
import arsqz.mod.universe.UniverseMath;

import com.mojang.blaze3d.systems.RenderSystem;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.textures.GpuTextureView;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerSkin;

import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The universe map GUI.
 *
 * <p>All galaxy visuals are rendered as soft, additive point sprites in a single
 * GPU draw call (see {@link GalaxyRenderer}) and blitted into the GUI as one
 * state, so there is no per-dot {@code fill()} overhead. The interactive layer
 * (buttons, cube, info panel, tooltips, text) stays on the vanilla GUI layer.
 */
public class GalaxyMapScreen extends Screen {

    // ---- cluster-selection (discrete) state ----
    private final GalaxyView universeView;
    private double uYaw = 0.0, uPitch = 0.62, uSpin = 0.0, uZoom = 1.0;
    private double ucyaw = 1, usyaw = 0, ucpit = 1, uspit = 0;   // per-frame trig cache (universe view)

    // ---- continuous zoom state ----
    private boolean clusterMode = false;
    private long clusterIndex = 0;
    private GalaxyView clusterGalaxies;

    private double camX = 0, camY = 0;
    private double scale = 1, targetScale = 1;
    private boolean anchoring = false;
    private double focusWorldX, focusWorldY, focusScreenX, focusScreenY;
    private double spin = 0.0;
    private double cluYaw = 0.0, cluPitch = 0.5;               // 3D orientation of the cluster
    private double cyaw = 1, syaw = 0, cpit = 1, spit = 0;     // per-frame trig cache
    private double transition = 0.0;

    // A galaxy navigated to via search or clicked, kept rendered even when it isn't
    // part of the sampled subset and after any orbit lock ends. Null when not pinned.
    private StarPoint pinnedGalaxy = null;
    // When true, the camera pivots around the pinned galaxy (rotation/zoom orbit it).
    private boolean galaxyLocked = false;

    // ---- selected star system / planet ----
    private FocusedPlanet focus = null;      // the selected star SYSTEM (focus.seed == sun seed)
    private int selectedPlanet = -1;         // index of the planet shown in the info panel, or -1
    private boolean systemActive = false;    // is the solar-system view currently open?
    private boolean forceSystemOpen = false; // keep the system view open after a search jump
    private boolean systemSuppressed = false; // system view hidden while staying orbit-locked on the star
    private double systemAlpha = 0.0;        // fade of the system view
    private double bhAlpha = 0.0;            // subtle reveal of a focused black hole's lens
    private int systemPlanets = 1;
    private boolean seedFetched = false;

    // ---- double-click-to-travel detection ----
    private int lastPlanetClicked = -1;
    private long lastPlanetClickMs = 0L;
    private static final long DOUBLE_CLICK_MS = 350L;

    // ---- copy-seed-to-clipboard feedback ----
    private long seedCopiedMs = 0L;
    private long worldSeedValue = 0;

    // ---- drag tracking ----
    private double pressX, pressY;
    private boolean dragged = false;

    // ---- top tabs ----
    private enum Tab { MAP, FAVORITES, PLAYERS }
    private Tab tab = Tab.MAP;

    // ---- players tab ----
    private double playersScroll = 0.0;
    private long lastPlayersRequestMs = 0L;
    private static final long PLAYERS_REFRESH_MS = 2000L;
    private static final int PLAYER_ROW_H = 22;

    // ---- space pings (event-driven focus reporting) ----
    private int pingLevelSent = PingFocusPacket.LEVEL_NONE;
    private long pingClusterSent = Long.MIN_VALUE;
    private long pingGalaxySent = Long.MIN_VALUE;
    private long lastPingSentMs = 0L;
    private static final long PING_MIN_INTERVAL_MS = 120L;   // debounce rapid navigation
    private enum FavSub { GALAXIES, PLANETS }
    private FavSub favSub = FavSub.PLANETS;
    private double favScroll = 0, latestScroll = 0;   // scroll offsets for the favourites screen

    // ---- recently-visited history panel ----
    private boolean historyOpen = false;

    // ---- quality slider ----
    private boolean draggingSlider = false;

    // ---- search ----
    private EditBox searchBox;
    private NameGenerator.Match previewMatch;
    private String previewText = "";

    // ---- GPU rendering ----
    private GalaxyRenderer renderer;
    private float[] pts = new float[8192 * GalaxyRenderer.PT_STRIDE];
    private int ptCount = 0;
    private float[] tris = new float[4096 * GalaxyRenderer.SOLID_STRIDE];
    private int triCount = 0;
    private float[] linev = new float[2048 * GalaxyRenderer.SOLID_STRIDE];
    private int lineCount = 0;
    private double sxScale = 1, syScale = 1;
    private double dotSizeMul = 1.0;   // quality-driven point-size multiplier (layout stays fixed)
    private double uWidth, uHeight;

    // carried from build() to overlay()
    private String focusGalaxyName;

    private final Map<Long, GalaxyView> galaxyCache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Long, GalaxyView> e) { return size() > 8; }
    };
    private long lastNanos = System.nanoTime();
    private double animTime = 0;

    // Pickable stars for the current frame, stored in parallel primitive arrays
    // to avoid allocating tens of thousands of objects per frame.
    private int rsCount = 0;
    private long[] rsSeed = new long[8192];
    private double[] rsX = new double[8192];
    private double[] rsY = new double[8192];
    private double[] rsR = new double[8192];
    private double[] rsGx3 = new double[8192];
    private double[] rsGy3 = new double[8192];
    private double[] rsGz3 = new double[8192];
    private double[] rsLx = new double[8192];
    private double[] rsLy = new double[8192];
    private double[] rsFactor = new double[8192];
    private int[] rsColor = new int[8192];

    // ---- Helpers ----
    private double toUnivX(double mouseX) {
        return mouseX * (uWidth / Math.max(1.0, (double) this.width));
    }
    private double toUnivY(double mouseY) {
        return mouseY * (uHeight / Math.max(1.0, (double) this.height));
    }
    // Inverse of toUnivX/toUnivY: universe-space (GPU) coords -> GUI screen coords.
    // The GPU geometry is rendered in uWidth/uHeight space and blitted to fill the
    // whole screen, so overlay text/markers must be mapped back or they only line
    // up when the physical-to-GUI scale ratio happens to be exactly 2.
    private double toScreenX(double univX) {
        return univX * (this.width / Math.max(1.0, uWidth));
    }
    private double toScreenY(double univY) {
        return univY * (this.height / Math.max(1.0, uHeight));
    }
    private double univScale() {
        return this.width / Math.max(1.0, uWidth);
    }

    // ---- black holes to render this frame (x,y px * scale, Rs px) ----
    private final float[] bhPos = new float[GalaxyRenderer.MAX_BH * 3];
    private final float[] bhCol = new float[GalaxyRenderer.MAX_BH * 3];
    private final int[] bhType = new int[GalaxyRenderer.MAX_BH];
    private final float[] bhParam = new float[GalaxyRenderer.MAX_BH * 4]; // spin, jet, diskInner, diskOuter
    private int bhCount = 0;

    private static final double GALAXY_R = 0.0035;
    private static final double STAR_R = GALAXY_R * 0.012;
    private static final float SPIN_SPEED = 0.008f;   // slow, stately galaxy rotation
    private static final double GAL_DETAIL_MIN = 30.0;
    private static final int MAX_DETAIL = 6;
    private static final float MAX_POINT = 64f;
    private static final double FOCAL = 3.6;                    // perspective focal distance
    // Schwarzschild radius of a central SMBH, derived from the galaxy-local size
    // so the rendered black hole and the star-free core stay in sync (half the
    // previous 0.007, i.e. 50% smaller).
    private static final double SMBH_WORLD_R = GALAXY_R * GalaxyView.SMBH_LOCAL_R;
    private static final double BH_FOCUS_WORLD_R = STAR_R * 0.22;// stellar black hole radius when focused

    public GalaxyMapScreen() {
        super(Component.translatable("gui.universemod.title"));
        this.universeView = GalaxyView.universe();
        restoreView();   // resume where the map was last closed (this session)
    }

    // ------------------------------------------------------------------
    // Persisted view state: lets the map resume where you left off instead of
    // resetting to the universe overview every time you reopen it. Static so it
    // survives the screen being recreated each time the item is used. Reset when
    // the game client restarts (session-scoped, no disk I/O needed).
    // ------------------------------------------------------------------
    private static boolean savedValid = false;
    private static boolean sClusterMode;
    private static long sClusterIndex;
    private static double sCamX, sCamY, sScale, sTargetScale;
    private static double sCluYaw, sCluPitch;
    private static double sUYaw, sUPitch, sUZoom;
    private static FocusedPlanet sFocus;
    private static StarPoint sPinnedGalaxy;
    private static boolean sGalaxyLocked;
    private static int sSelectedPlanet;
    private static boolean sSystemActive, sForceSystemOpen, sSystemSuppressed;
    private static double sSystemAlpha, sBhAlpha;

    private void saveView() {
        savedValid = true;
        sClusterMode = clusterMode;
        sClusterIndex = clusterIndex;
        sCamX = camX; sCamY = camY; sScale = scale; sTargetScale = targetScale;
        sCluYaw = cluYaw; sCluPitch = cluPitch;
        sUYaw = uYaw; sUPitch = uPitch; sUZoom = uZoom;
        sFocus = focus; sPinnedGalaxy = pinnedGalaxy; sGalaxyLocked = galaxyLocked;
        sSelectedPlanet = selectedPlanet;
        sSystemActive = systemActive; sForceSystemOpen = forceSystemOpen;
        sSystemSuppressed = systemSuppressed;
        sSystemAlpha = systemAlpha; sBhAlpha = bhAlpha;
    }

    private void restoreView() {
        if (!savedValid) return;
        clusterMode = sClusterMode;
        clusterIndex = sClusterIndex;
        if (clusterMode) clusterGalaxies = GalaxyView.cluster(clusterIndex);
        camX = sCamX; camY = sCamY; scale = sScale; targetScale = sTargetScale;
        cluYaw = sCluYaw; cluPitch = sCluPitch;
        uYaw = sUYaw; uPitch = sUPitch; uZoom = sUZoom;
        focus = sFocus; pinnedGalaxy = sPinnedGalaxy; galaxyLocked = sGalaxyLocked;
        selectedPlanet = sSelectedPlanet;
        systemActive = sSystemActive; forceSystemOpen = sForceSystemOpen;
        systemSuppressed = sSystemSuppressed;
        systemAlpha = sSystemAlpha; bhAlpha = sBhAlpha;
        updateProjTrig();
        transition = 0.0;   // no intro fade: appear exactly where we left off
    }

    @Override
    protected void init() {
        int pw = Math.max(1, minecraft.getWindow().getWidth());
        int ph = Math.max(1, minecraft.getWindow().getHeight());
        this.uWidth = pw / 2.0;
        this.uHeight = ph / 2.0;

        // Close is always available; the map-specific controls only show on the map tab.
        addRenderableWidget(Button.builder(Component.translatable("gui.universemod.close"), b -> onClose())
                .bounds(this.width - 70, 10, 60, 20).build());

        if (tab == Tab.MAP) {
            if (clusterMode) {
                addRenderableWidget(Button.builder(Component.translatable("gui.universemod.back"), b -> goToUniverse())
                        .bounds(10, 10, 90, 20).build());
            }
            addRenderableWidget(Button.builder(Component.translatable("gui.universemod.home"), b -> returnHome())
                    .bounds(this.width - 165, 10, 90, 20).build());

            searchBox = new EditBox(this.font, this.width / 2 - 110, 28, 220, 16,
                    Component.translatable("gui.universemod.search_hint"));
            searchBox.setMaxLength(64);
            searchBox.setHint(Component.translatable("gui.universemod.search_hint"));
            searchBox.setResponder(txt -> {
                previewMatch = NameGenerator.parse(txt);
                previewText = describeMatch(previewMatch, txt);
            });
            addRenderableWidget(searchBox);
        } else {
            searchBox = null;
        }
    }

    private void setTab(Tab t) {
        if (tab == t) return;
        tab = t;
        historyOpen = false;
        playClick();
        if (t == Tab.PLAYERS) { playersScroll = 0; requestPlayers(); }
        rebuildWidgets();
    }

    /** Ask the server for the current player roster (throttled). */
    private void requestPlayers() {
        lastPlayersRequestMs = System.currentTimeMillis();
        ClientPlayNetworking.send(new RequestPlayersPacket());
    }

    /**
     * Report the galaxy we're currently inspecting so other players in the same
     * cluster get a ping. Event-driven: only sends when the focus actually
     * changes, with a small debounce to coalesce rapid navigation.
     */
    private void updatePingFocus() {
        int level = PingFocusPacket.LEVEL_NONE;
        long cluster = 0, galaxyLocal = -1;
        if (tab == Tab.MAP) {
            if (!clusterMode) {
                level = PingFocusPacket.LEVEL_UNIVERSE;   // on the cluster-selection screen
            } else {
                level = PingFocusPacket.LEVEL_CLUSTER;
                cluster = clusterIndex;
                if (focus != null) {                                  // inspecting a system / black hole
                    galaxyLocal = galaxyLocalOf(focus.seed());
                } else if (pinnedGalaxy != null) {                    // inspecting a galaxy
                    galaxyLocal = Long.remainderUnsigned(pinnedGalaxy.id(), UniverseMath.GALAXIES_PER_CLUSTER);
                } else {
                    galaxyLocal = -1;                                 // in the cluster, idling
                }
            }
        }
        maybeSendPing(level, cluster, galaxyLocal);
    }

    private void maybeSendPing(int level, long cluster, long galaxyLocal) {
        boolean active = level != PingFocusPacket.LEVEL_NONE;
        boolean changed = level != pingLevelSent
                || (active && (cluster != pingClusterSent || galaxyLocal != pingGalaxySent));
        if (!changed) return;
        long now = System.currentTimeMillis();
        if (now - lastPingSentMs < PING_MIN_INTERVAL_MS) return;   // debounce; retried next frame
        ClientPlayNetworking.send(new PingFocusPacket(level, cluster, galaxyLocal));
        pingLevelSent = level;
        pingClusterSent = cluster;
        pingGalaxySent = galaxyLocal;
        lastPingSentMs = now;
    }

    private String describeMatch(NameGenerator.Match m, String txt) {
        if (txt == null || txt.isBlank()) return "";
        if (m == null) return "no match";
        return switch (m.level()) {
            case 0 -> "\u2192 [Cluster] " + NameGenerator.clusterName(m.cluster());
            case 1 -> "\u2192 [Galaxy] " + NameGenerator.galaxyName(UniverseMath.galaxyId(m.cluster(), m.galaxyLocal()));
            case 2 -> UniverseMath.isStellarBlackHole(m.seed())
                    ? "\u2192 [Black Hole] " + NameGenerator.blackHoleName(m.seed())
                    : "\u2192 [System] " + NameGenerator.systemName(m.seed());
            default -> "\u2192 [Planet] " + NameGenerator.planetName(m.seed(), m.planetIndex());
        };
    }

    private void setClusterData(long c) {
        clusterMode = true;
        clusterIndex = c;
        clusterGalaxies = GalaxyView.cluster(c);
        galaxyCache.clear();
        systemAlpha = 0;
        bhAlpha = 0;
        systemActive = false;
    }

    private StarPoint findGalaxy(long gid) {
        if (clusterGalaxies == null) return null;
        for (StarPoint g : clusterGalaxies.dots()) if (g.id() == gid) return g;
        return null;
    }

    private StarPoint findStar(GalaxyView field, long seed) {
        for (StarPoint s : field.dots()) if (s.id() == seed) return s;
        return null;
    }

    /** Fly straight to any parsed target, across galaxies and clusters. */
    private void navigateTo(NameGenerator.Match m) {
        if (m == null) return;
        setClusterData(m.cluster());
        cluYaw = 0; cluPitch = 0.5; updateProjTrig();
        focus = null; selectedPlanet = -1; anchoring = false;
        forceSystemOpen = false;
        pinnedGalaxy = null; galaxyLocked = false;
        previewText = ""; previewMatch = null;
        transition = 1.0;
        double fit = fitScale();

        if (m.level() == 0) {                       // cluster
            camX = 0; camY = 0; scale = fit * 0.3; targetScale = fit * 0.6;
            rebuildWidgets();
            return;
        }

        // Positions are generated on demand, so any galaxy/star index resolves,
        // not just the sampled subset that happens to be rendered. Pin the target
        // galaxy so it stays rendered even though it isn't in that subset.
        StarPoint gdot = GalaxyView.galaxyAt(m.cluster(), m.galaxyLocal());
        pinnedGalaxy = gdot;
        galaxyLocked = true;   // pivot the view around the target galaxy
        double galTarget = 250.0 / GALAXY_R;
        camX = clX(gdot); camY = clY(gdot);

        if (m.level() == 1) {                        // galaxy
            scale = galTarget * 0.35; targetScale = galTarget;
            rebuildWidgets();
            return;
        }

        StarPoint star = GalaxyView.starAt(m.cluster(), m.galaxyLocal(), m.starIndex());
        double factor = star.size() / 2.0;
        focus = new FocusedPlanet(m.seed(), gdot.px(), gdot.py(), gdot.pz(),
                star.px(), star.py(), factor, star.color(), UniverseMath.isStellarBlackHole(m.seed()));
        selectedPlanet = (m.level() == 3) ? m.planetIndex() : -1;
        bhAlpha = 0;
        // Jump straight to a deep zoom on the target. For an ordinary star system
        // we also force the solar-system view open right away, so a search lands
        // you in the system instead of stopping short and making you zoom in.
        double needed = 30000.0 / GALAXY_R;
        scale = needed; targetScale = needed;
        forceSystemOpen = !focus.bh();
        rebuildWidgets();
    }

    // ------------------------------------------------------------------
    // Stage changes
    // ------------------------------------------------------------------

    private void enterCluster(long index) {
        clusterMode = true;
        clusterIndex = index;
        clusterGalaxies = GalaxyView.cluster(index);
        galaxyCache.clear();
        double fit = fitScale();
        camX = 0; camY = 0;
        scale = fit * 0.30;
        targetScale = fit * 0.6;   // pulled back so the whole filament is visible
        anchoring = false;
        cluYaw = 0; cluPitch = 0.5;
        focus = null;
        selectedPlanet = -1;
        pinnedGalaxy = null; galaxyLocked = false;
        systemAlpha = 0;
        bhAlpha = 0;
        systemActive = false;
        transition = 1.0;
        rebuildWidgets();
    }

    private void goToUniverse() {
        clusterMode = false;
        focus = null;
        selectedPlanet = -1;
        pinnedGalaxy = null; galaxyLocked = false;
        systemAlpha = 0;
        bhAlpha = 0;
        systemActive = false;
        transition = 1.0;
        rebuildWidgets();
    }

    /**
     * Leave the locked solar-system view and pull the camera back out to the
     * galaxy overview. Used by scrolling out and by Backspace, so the system
     * view is no longer a dead end you can only escape with the keyboard.
     */
    private void exitSystemView() {
        if (focus == null) return;
        forceSystemOpen = false;
        selectedPlanet = -1;
        anchoring = false;
        if (focus.bh()) {
            // A black hole has no solar-system view; just release the lock and
            // pull back to the galaxy overview as before.
            focus = null;
            systemSuppressed = false;
            double galTarget = 250.0 / GALAXY_R;
            if (targetScale > galTarget) targetScale = galTarget;
            return;
        }
        // Ordinary system: keep the camera orbit-locked on the star and only hide
        // the solar-system view, pulling back just enough to reveal the surrounding
        // galaxy stars instead of flying all the way out to the overview.
        systemSuppressed = true;
        double exitScale = 30000.0 / GALAXY_R * 0.2;
        if (targetScale > exitScale) targetScale = exitScale;
    }

    private double fitScale() {
        int pw = (minecraft != null && minecraft.getWindow() != null) ? minecraft.getWindow().getWidth() : 1920;
        int ph = (minecraft != null && minecraft.getWindow() != null) ? minecraft.getWindow().getHeight() : 1080;
        double uw = (uWidth > 1) ? uWidth : (pw / 2.0);
        double uh = (uHeight > 1) ? uHeight : (ph / 2.0);
        return Math.min(uw, uh) * 0.42;
    }

    private long worldSeed() {
        if (!seedFetched) {
            seedFetched = true;
            try {
                if (minecraft != null && minecraft.hasSingleplayerServer()) {
                    worldSeedValue = minecraft.getSingleplayerServer().overworld().getSeed();
                }
            } catch (Exception ignored) {
            }
        }
        return worldSeedValue;
    }

    private double advanceTime() {
        long now = System.nanoTime();
        double dt = (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        if (dt > 0.25) dt = 0.25;
        spin += dt * SPIN_SPEED;
        uSpin += dt * SPIN_SPEED;
        animTime += dt;
        if (transition > 0) transition = Math.max(0, transition - dt * 2.2);
        return dt;
    }

    // ------------------------------------------------------------------
    // Camera transforms
    // ------------------------------------------------------------------

    // pan/zoom on the projected plane (rotation + perspective happen in project3*)
    private double w2sx(double wx, double wy) { return (wx - camX) * scale + uWidth / 2.0; }
    private double w2sy(double wx, double wy) { return (wy - camY) * scale + uHeight / 2.0; }
    private double s2wx(double sx, double sy) { return (sx - uWidth / 2.0) / scale + camX; }
    private double s2wy(double sx, double sy) { return (sy - uHeight / 2.0) / scale + camY; }

    // ---- 3D projection of the galaxy cluster (yaw + pitch + perspective) ----
    private void updateProjTrig() {
        cyaw = Math.cos(cluYaw); syaw = Math.sin(cluYaw);
        cpit = Math.cos(cluPitch); spit = Math.sin(cluPitch);
    }
    private double p3x(double x, double y, double z) {
        double rx = x * cyaw + z * syaw;
        double rz = -x * syaw + z * cyaw;
        double rz2 = y * spit + rz * cpit;
        return rx * (FOCAL / (FOCAL + rz2));
    }
    private double p3y(double x, double y, double z) {
        double rz = -x * syaw + z * cyaw;
        double ry2 = y * cpit - rz * spit;
        double rz2 = y * spit + rz * cpit;
        return ry2 * (FOCAL / (FOCAL + rz2));
    }
    private double p3persp(double x, double y, double z) {
        double rz = -x * syaw + z * cyaw;
        double rz2 = y * spit + rz * cpit;
        return FOCAL / (FOCAL + rz2);
    }
    private double clX(StarPoint g) { return p3x(g.px(), g.py(), g.pz()); }
    private double clY(StarPoint g) { return p3y(g.px(), g.py(), g.pz()); }
    private double clPersp(StarPoint g) { return p3persp(g.px(), g.py(), g.pz()); }

    // ------------------------------------------------------------------
    // Point buffer
    // ------------------------------------------------------------------

    private void resetGeometry() { ptCount = 0; triCount = 0; lineCount = 0; bhCount = 0; }

    private void addPoint(double x, double y, double size, int rgb, double a) {
        if (ptCount + GalaxyRenderer.PT_STRIDE > pts.length) {
            pts = Arrays.copyOf(pts, pts.length * 2);
        }
        float sz = (float) Math.min(MAX_POINT, Math.max(1.0, size * sxScale * dotSizeMul));
        pts[ptCount++] = (float) (x * sxScale);
        pts[ptCount++] = (float) (y * syScale);
        pts[ptCount++] = sz;
        pts[ptCount++] = ((rgb >> 16) & 0xFF) / 255f;
        pts[ptCount++] = ((rgb >> 8) & 0xFF) / 255f;
        pts[ptCount++] = (rgb & 0xFF) / 255f;
        pts[ptCount++] = (float) Math.min(1.0, a);
    }

    private void triVertex(double x, double y, int rgb, double a) {
        if (triCount + GalaxyRenderer.SOLID_STRIDE > tris.length) tris = Arrays.copyOf(tris, tris.length * 2);
        tris[triCount++] = (float) (x * sxScale);
        tris[triCount++] = (float) (y * syScale);
        tris[triCount++] = ((rgb >> 16) & 0xFF) / 255f;
        tris[triCount++] = ((rgb >> 8) & 0xFF) / 255f;
        tris[triCount++] = (rgb & 0xFF) / 255f;
        tris[triCount++] = (float) Math.max(0, Math.min(1, a));
    }

    private void lineVertex(double x, double y, int rgb, double a) {
        if (lineCount + GalaxyRenderer.SOLID_STRIDE > linev.length) linev = Arrays.copyOf(linev, linev.length * 2);
        linev[lineCount++] = (float) (x * sxScale);
        linev[lineCount++] = (float) (y * syScale);
        linev[lineCount++] = ((rgb >> 16) & 0xFF) / 255f;
        linev[lineCount++] = ((rgb >> 8) & 0xFF) / 255f;
        linev[lineCount++] = (rgb & 0xFF) / 255f;
        linev[lineCount++] = (float) Math.max(0, Math.min(1, a));
    }

    private void addTri(double[] a, double[] b, double[] c, int rgb, double al) {
        triVertex(a[0], a[1], rgb, al);
        triVertex(b[0], b[1], rgb, al);
        triVertex(c[0], c[1], rgb, al);
    }

    private void addQuad(double[] a, double[] b, double[] c, double[] d, int rgb, double al) {
        addTri(a, b, c, rgb, al);
        addTri(a, c, d, rgb, al);
    }

    private void addLine(double[] a, double[] b, int rgb, double al) {
        lineVertex(a[0], a[1], rgb, al);
        lineVertex(b[0], b[1], rgb, al);
    }

    /** A soft radial glow disc (bright centre, transparent rim) as a triangle fan. */
    private void addSoftDisc(double cx, double cy, double r, int rgb, double centerAlpha) {
        int seg = 26;
        double prevX = cx + r, prevY = cy;
        for (int i = 1; i <= seg; i++) {
            double ang = i * (Math.PI * 2.0 / seg);
            double x = cx + Math.cos(ang) * r;
            double y = cy + Math.sin(ang) * r;
            // centre bright, rim alpha 0
            triVertex(cx, cy, rgb, centerAlpha);
            triVertex(prevX, prevY, rgb, 0.0);
            triVertex(x, y, rgb, 0.0);
            prevX = x; prevY = y;
        }
    }

    /** An isometric planet cube (3 shaded faces + edge lines) built on the GPU. */
    private void addCubeGPU(double cx, double cy, double half, int color, double a) {
        double w = half, th = half * 0.55, sh = half * 1.15, topY = cy - sh / 2.0;
        double[] P0 = {cx, topY - th}, P1 = {cx + w, topY}, P2 = {cx, topY + th}, P3 = {cx - w, topY};
        double[] P1d = {cx + w, topY + sh}, P2d = {cx, topY + th + sh}, P3d = {cx - w, topY + sh};
        addQuad(P1, P2, P2d, P1d, scaleColor(color, 0.82), a);
        addQuad(P3, P2, P2d, P3d, scaleColor(color, 0.58), a);
        addQuad(P0, P1, P2, P3, scaleColor(color, 1.15), a);
        int edge = 0x0A0A12;
        double ea = a * 0.85;
        addLine(P0, P1, edge, ea); addLine(P1, P1d, edge, ea); addLine(P1d, P2d, edge, ea);
        addLine(P2d, P3d, edge, ea); addLine(P3d, P3, edge, ea); addLine(P3, P0, edge, ea);
        addLine(P1, P2, edge, ea); addLine(P3, P2, edge, ea); addLine(P2, P2d, edge, ea);
    }

    private void rsAdd(long seed, double x, double y, double r,
                       double gx3, double gy3, double gz3,
                       double lx0, double ly0, double factor, int color) {
        if (rsCount >= rsSeed.length) {
            int n = rsSeed.length * 2;
            rsSeed = Arrays.copyOf(rsSeed, n);
            rsX = Arrays.copyOf(rsX, n);
            rsY = Arrays.copyOf(rsY, n);
            rsR = Arrays.copyOf(rsR, n);
            rsGx3 = Arrays.copyOf(rsGx3, n);
            rsGy3 = Arrays.copyOf(rsGy3, n);
            rsGz3 = Arrays.copyOf(rsGz3, n);
            rsLx = Arrays.copyOf(rsLx, n);
            rsLy = Arrays.copyOf(rsLy, n);
            rsFactor = Arrays.copyOf(rsFactor, n);
            rsColor = Arrays.copyOf(rsColor, n);
        }
        int i = rsCount++;
        rsSeed[i] = seed; rsX[i] = x; rsY[i] = y; rsR[i] = r;
        rsGx3[i] = gx3; rsGy3[i] = gy3; rsGz3[i] = gz3; rsLx[i] = lx0; rsLy[i] = ly0;
        rsFactor[i] = factor; rsColor[i] = color;
    }

    private void addBH(double screenX, double screenY, double rsPx, int diskRgb,
                       int type, double spin, double jet, double diskInner, double diskOuter) {
        if (bhCount >= GalaxyRenderer.MAX_BH) return;
        int i = bhCount * 3;
        bhPos[i] = (float) (screenX * sxScale);
        bhPos[i + 1] = (float) (screenY * syScale);
        bhPos[i + 2] = (float) Math.max(2.0, rsPx * sxScale);
        bhCol[i] = ((diskRgb >> 16) & 0xFF) / 255f;
        bhCol[i + 1] = ((diskRgb >> 8) & 0xFF) / 255f;
        bhCol[i + 2] = (diskRgb & 0xFF) / 255f;
        bhType[bhCount] = type;
        int i4 = bhCount * 4;
        bhParam[i4] = (float) spin;
        bhParam[i4 + 1] = (float) jet;
        bhParam[i4 + 2] = (float) diskInner;
        bhParam[i4 + 3] = (float) diskOuter;
        bhCount++;
    }

    /**
     * Queue a black hole for rendering, picking its visual variant and shader
     * parameters deterministically from its seed. {@code supermassive} selects
     * the galaxy-core palette of variants (quasar / Kerr / dormant) versus the
     * stellar one (Kerr / dormant / micro).
     */
    private void addBlackHole(double screenX, double screenY, double rsPx, long seed, boolean supermassive) {
        int type = NameGenerator.blackHoleRenderType(seed, supermassive);
        int diskRgb = NameGenerator.blackHoleDiskColor(seed);
        long h = UniverseMath.mix64(seed ^ 0x5B17EL);
        double spinMag = 0.6 + UniverseMath.toUnit(h) * 0.4;              // 0.6 .. 1.0
        double spinSign = ((h & 1L) == 0L) ? 1.0 : -1.0;
        double spin, jet, diskInner, diskOuter;
        switch (type) {
            case NameGenerator.BH_QUASAR -> {
                spin = spinSign * (0.4 + 0.3 * UniverseMath.toUnit(h >>> 1));
                jet = 1.4; diskInner = 2.2; diskOuter = 8.0;
            }
            case NameGenerator.BH_DORMANT -> {
                spin = 0.0; jet = 0.0; diskInner = 99.0; diskOuter = 0.0;   // no disk
            }
            case NameGenerator.BH_MICRO -> {
                spin = spinSign * spinMag; jet = 0.0; diskInner = 1.8; diskOuter = 4.0;
            }
            default -> { // BH_KERR
                spin = spinSign * spinMag; jet = 0.0; diskInner = 2.4; diskOuter = 7.0;
            }
        }
        addBH(screenX, screenY, rsPx, diskRgb, type, spin, jet, diskInner, diskOuter);
    }

    // Deep-space backdrop colour palettes (deterministic, additive glow).
    private static final int[] NEBULA_COLORS = {
            0x5A3A8A, 0x3A5A9A, 0x2A7A7A, 0x8A3A6A, 0x3A8A5A, 0x6A4AA0, 0x9A4A5A
    };

    /** A weighted stellar colour for a backdrop star (mostly white/blue). */
    private static int backdropStarColor(long h) {
        int roll = (int) Math.floorMod(h >> 7, 100);
        if (roll < 30) return 0xFFFFFF;   // white
        if (roll < 55) return 0xC6D4FF;   // blue-white
        if (roll < 70) return 0x9FB6FF;   // blue
        if (roll < 84) return 0xFFF2C0;   // yellow-white
        if (roll < 93) return 0xFFD79A;   // orange
        return 0xFF9E7A;                  // red
    }

    /**
     * A rich, deterministic deep-space backdrop: thousands of colour-varied stars
     * (most faint, a few bright), soft glowing nebulae and a scattering of faint
     * distant galaxies, for a full, beautiful field instead of sparse grey dots.
     * Everything is derived from a fixed seed so it stays stable frame to frame,
     * and the amount scales with the quality tier.
     */
    private void addBackdrop() {
        double q = MapQuality.tier();
        long h = 0x9911EEL;

        // 1) Star field: many faint stars, few bright, realistic colour mix.
        int stars = 1800 + (int) (q * 300.0);
        for (int i = 0; i < stars; i++) {
            h = UniverseMath.mix64(h + 0x9E3779B97F4A7C15L);
            double x = UniverseMath.toUnit(h) * uWidth;
            h = UniverseMath.mix64(h);
            double y = UniverseMath.toUnit(h) * uHeight;
            h = UniverseMath.mix64(h);
            double t = UniverseMath.toUnit(h);
            double bright = t * t * t;                  // skew: mostly faint, a few bright
            double size = 0.7 + bright * 3.2;
            double a = 0.08 + bright * 0.9;
            addPoint(x, y, size, backdropStarColor(h), a);
        }

        // 2) Soft glowing nebulae, each built from many faint additive puffs.
        int nebulae = 4 + (int) q;
        for (int n = 0; n < nebulae; n++) {
            h = UniverseMath.mix64(h + 0x1234567L);
            double cx = UniverseMath.toUnit(h) * uWidth;
            h = UniverseMath.mix64(h);
            double cy = UniverseMath.toUnit(h) * uHeight;
            h = UniverseMath.mix64(h);
            int col = NEBULA_COLORS[(int) Math.floorMod(h, NEBULA_COLORS.length)];
            double rad = (0.06 + UniverseMath.toUnit(h) * 0.10) * Math.min(uWidth, uHeight);
            long hh = h;
            for (int p = 0; p < 44; p++) {
                hh = UniverseMath.mix64(hh + p);
                double ox = UniverseMath.toSigned(hh) * rad;
                hh = UniverseMath.mix64(hh);
                double oy = UniverseMath.toSigned(hh) * rad * 0.72;
                double dist = Math.hypot(ox, oy) / rad;
                double pa = 0.05 * Math.max(0.0, 1.0 - dist);
                double ps = rad * (0.22 + 0.28 * UniverseMath.toUnit(hh));
                addPoint(cx + ox, cy + oy, ps, col, pa);
            }
        }

        // 3) Faint distant galaxies: small flattened, rotated smudges + a core.
        int gals = 3 + (int) q;
        for (int gi = 0; gi < gals; gi++) {
            h = UniverseMath.mix64(h + 0x9A5C07L);
            double cx = UniverseMath.toUnit(h) * uWidth;
            h = UniverseMath.mix64(h);
            double cy = UniverseMath.toUnit(h) * uHeight;
            h = UniverseMath.mix64(h);
            double ang = UniverseMath.toUnit(h) * Math.PI;
            double ca = Math.cos(ang), sa = Math.sin(ang);
            int col = (UniverseMath.toUnit(h) < 0.5) ? 0xFFE8C8 : 0xCFE0FF;
            double rad = (0.02 + UniverseMath.toUnit(h) * 0.03) * Math.min(uWidth, uHeight);
            long hh = h;
            for (int p = 0; p < 28; p++) {
                hh = UniverseMath.mix64(hh + p);
                double u = UniverseMath.toSigned(hh);
                hh = UniverseMath.mix64(hh);
                double v = UniverseMath.toSigned(hh);
                double lx = u * rad, ly = v * rad * 0.4;   // flattened disc
                double rx = lx * ca - ly * sa;
                double ry = lx * sa + ly * ca;
                double pa = 0.06 * Math.max(0.0, 1.0 - Math.hypot(u, v * 2.5));
                addPoint(cx + rx, cy + ry, 1.6, col, pa);
            }
            addPoint(cx, cy, 3.0, col, 0.12);              // bright core
        }
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        // no-op
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        double dt = advanceTime();

        // Report what we're inspecting so others see our space ping (event-driven).
        updatePingFocus();

        // Favourites / Latest tab: a separate screen, no map rendering.
        if (tab == Tab.FAVORITES) {
            g.fillGradient(0, 0, this.width, this.height, 0xFF05060F, 0xFF01010A);
            drawFavoritesScreen(g, mouseX, mouseY);
            drawTabBar(g, mouseX, mouseY);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }

        // Players tab: roster + locations, no map rendering.
        if (tab == Tab.PLAYERS) {
            g.fillGradient(0, 0, this.width, this.height, 0xFF05060F, 0xFF01010A);
            if (System.currentTimeMillis() - lastPlayersRequestMs > PLAYERS_REFRESH_MS) requestPlayers();
            drawPlayersScreen(g, mouseX, mouseY);
            drawTabBar(g, mouseX, mouseY);
            super.extractRenderState(g, mouseX, mouseY, delta);
            return;
        }

        int pw = Math.max(1, minecraft.getWindow().getWidth());
        int ph = Math.max(1, minecraft.getWindow().getHeight());

        // Layout stays on a fixed 2:1 virtual space, so positions, zoom and the
        // apparent size of galaxies never change with the quality setting. The
        // quality slider only scales the DOT sizes (via dotSizeMul): ultra draws
        // small, clean dots so galaxies look crisp; worst draws big, blocky ones.
        // This gives the GUI-scale "dot size" look without the view zooming when
        // you drag the slider.
        this.uWidth = pw / 2.0;
        this.uHeight = ph / 2.0;
        this.sxScale = 2.0;
        this.syScale = 2.0;
        this.dotSizeMul = MapQuality.renderGuiScale() / 2.0;

        resetGeometry();
        if (clusterMode) buildCluster(dt);
        else buildUniverse();

        if (renderer == null) renderer = new GalaxyRenderer();
        GpuTextureView view = renderer.render(pw, ph, pts, ptCount, tris, triCount, linev, lineCount,
                bhPos, bhCol, bhType, bhParam, bhCount, (float) animTime, 0.02f, 0.024f, 0.055f);
        if (view != null) {
            var sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
            // Params are (minU, maxU, minV, maxV). Full width, un-flipped V so
            // that on-screen positions match picking coordinates.
            g.blit(view, sampler, 0, 0, this.width, this.height, 0f, 1f, 0f, 1f);
        } else {
            g.fillGradient(0, 0, this.width, this.height, 0xFF05060F, 0xFF01010A);
        }

        if (clusterMode) overlayCluster(g, mouseX, mouseY);
        else overlayUniverse(g, mouseX, mouseY);

        drawHistory(g, mouseX, mouseY);
        drawQualitySlider(g, mouseX, mouseY);
        drawFavoriteToggle(g, mouseX, mouseY);

        if (transition > 0) {
            g.fill(0, 0, this.width, this.height, ((int) (transition * 255) << 24));
        }
        drawTabBar(g, mouseX, mouseY);
        super.extractRenderState(g, mouseX, mouseY, delta);

        // Drawn on top of the (widget-rendered) search box: either the recent-search
        // dropdown (box focused and empty) or the live search preview (while typing).
        if (searchDropdownVisible()) {
            drawSearchDropdown(g, mouseX, mouseY);
        } else if (previewText != null && !previewText.isEmpty()) {
            g.text(this.font, previewText, this.width / 2 - 108, 47, 0xFFBFE0FF);
            g.text(this.font, I18n.get("gui.universemod.press_enter"), this.width / 2 - 108, 58, 0xFF8A90B0);
        }
    }

    // ------------------------------------------------------------------
    // Recent-search dropdown (shown under the search box when it's focused
    // and empty, so you don't have to remember past seeds/names).
    // ------------------------------------------------------------------

    private static final int SEARCH_DROP_TOP = 44;
    private static final int SEARCH_ROW_H = 13;

    private boolean searchDropdownVisible() {
        return tab == Tab.MAP && searchBox != null && searchBox.isFocused()
                && searchBox.getValue().isEmpty() && !SearchHistory.recent().isEmpty();
    }

    private void drawSearchDropdown(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        List<String> recent = SearchHistory.recent();
        if (recent.isEmpty()) return;
        int bx = this.width / 2 - 110, bw = 220;
        int n = Math.min(recent.size(), SearchHistory.MAX);
        int h = n * SEARCH_ROW_H + 4;
        g.fill(bx, SEARCH_DROP_TOP, bx + bw, SEARCH_DROP_TOP + h, 0xF00A0C1A);
        int bc = 0xFF3A3A6A;
        g.fill(bx, SEARCH_DROP_TOP, bx + bw, SEARCH_DROP_TOP + 1, bc);
        g.fill(bx, SEARCH_DROP_TOP + h - 1, bx + bw, SEARCH_DROP_TOP + h, bc);
        g.fill(bx, SEARCH_DROP_TOP, bx + 1, SEARCH_DROP_TOP + h, bc);
        g.fill(bx + bw - 1, SEARCH_DROP_TOP, bx + bw, SEARCH_DROP_TOP + h, bc);

        for (int i = 0; i < n; i++) {
            int ry = SEARCH_DROP_TOP + 2 + i * SEARCH_ROW_H;
            boolean hover = mouseX >= bx && mouseX < bx + bw && mouseY >= ry && mouseY < ry + SEARCH_ROW_H;
            if (hover) g.fill(bx + 1, ry, bx + bw - 1, ry + SEARCH_ROW_H, 0x402A6AC0);
            String q = recent.get(i);
            NameGenerator.Match m = NameGenerator.parse(q);
            String label = (m != null) ? describeMatch(m, q) : q;
            g.text(this.font, label, bx + 6, ry + 3, hover ? 0xFFFFFFFF : 0xFFBFE0FF);
        }
    }

    private int searchDropdownIndexAt(double mx, double my) {
        int bx = this.width / 2 - 110, bw = 220;
        int n = Math.min(SearchHistory.recent().size(), SearchHistory.MAX);
        for (int i = 0; i < n; i++) {
            int ry = SEARCH_DROP_TOP + 2 + i * SEARCH_ROW_H;
            if (mx >= bx && mx < bx + bw && my >= ry && my < ry + SEARCH_ROW_H) return i;
        }
        return -1;
    }

    /** Re-run a remembered search and fly to it. */
    private void applySearchHistory(int index) {
        List<String> recent = SearchHistory.recent();
        if (index < 0 || index >= recent.size()) return;
        String q = recent.get(index);
        NameGenerator.Match m = NameGenerator.parse(q);
        if (m == null) return;
        playClick();
        SearchHistory.record(q);   // bump it back to the front
        dragged = true;            // consume the paired mouseReleased so it can't re-pick
        navigateTo(m);             // rebuilds widgets (fresh, unfocused search box)
    }

    // ---- Stage 1: cluster selection ----

    // ---- 3D projection for the universe (cluster-selection) view ----
    private void updateUniverseTrig() {
        double ay = uYaw + uSpin;                 // spin folds into yaw
        ucyaw = Math.cos(ay); usyaw = Math.sin(ay);
        ucpit = Math.cos(uPitch); uspit = Math.sin(uPitch);
    }
    private double up3x(double x, double y, double z) {
        double rx = x * ucyaw + z * usyaw;
        double rz = -x * usyaw + z * ucyaw;
        double rz2 = y * uspit + rz * ucpit;
        return rx * (FOCAL / (FOCAL + rz2));
    }
    private double up3y(double x, double y, double z) {
        double rz = -x * usyaw + z * ucyaw;
        double ry2 = y * ucpit - rz * uspit;
        double rz2 = y * uspit + rz * ucpit;
        return ry2 * (FOCAL / (FOCAL + rz2));
    }
    private double up3persp(double x, double y, double z) {
        double rz = -x * usyaw + z * ucyaw;
        double rz2 = y * uspit + rz * ucpit;
        return FOCAL / (FOCAL + rz2);
    }

    private void buildUniverse() {
        addBackdrop();
        updateUniverseTrig();
        double cx = uWidth / 2.0, cy = uHeight / 2.0;
        double radius = Math.min(uWidth, uHeight) * 0.42 * uZoom;
        addPoint(cx, cy, 26, 0x8FA0FF, 0.14);

        for (StarPoint s : universeView.dots()) {
            double persp = up3persp(s.px(), s.py(), s.pz());
            double sx = cx + up3x(s.px(), s.py(), s.pz()) * radius;
            double sy = cy + up3y(s.px(), s.py(), s.pz()) * radius;
            if (sx < -6 || sx > uWidth + 6 || sy < -6 || sy > uHeight + 6) continue;
            double size = Math.max(1.2, (1.4 + s.size() * 1.1) * persp);
            double alpha = (0.32 + s.brightness() * 0.55) * Math.max(0.35, Math.min(1.15, persp));
            addPoint(sx, sy, size, s.color(), Math.min(1.0, alpha));
        }
    }

    private void overlayUniverse(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(this.font, I18n.get("gui.universemod.select_cluster"), 12, this.height - 42, 0xFFBFC6FF);
        g.text(this.font, I18n.get("gui.universemod.help_universe"),
                12, this.height - 28, 0xFF8A90B0);
        g.text(this.font, I18n.get("gui.universemod.clusters_count", UniverseMath.NUM_CLUSTERS),
                12, this.height - 14, 0xFF8A90B0);
        drawUniversePings(g);
        StarPoint hov = pickCluster(mouseX, mouseY);
        if (hov != null) tooltip(g, mouseX, mouseY, "Cluster: " + NameGenerator.clusterName(hov.id()));
    }

    // ---- Stage 2: continuous zoom ----

    private void buildCluster(double dt) {
        updateProjTrig();
        easeZoom(dt);

        // Follow the focused object's orbit (reprojected under the current 3D view).
        if (focus != null) {
            double gpx = p3x(focus.gx3(), focus.gy3(), focus.gz3());
            double gpy = p3y(focus.gx3(), focus.gy3(), focus.gz3());
            double gp = p3persp(focus.gx3(), focus.gy3(), focus.gz3());
            double lx = focus.lx0() * Math.cos(spin) - focus.ly0() * Math.sin(spin);
            double ly = focus.lx0() * Math.sin(spin) + focus.ly0() * Math.cos(spin);
            camX = gpx + lx * GALAXY_R * gp;
            camY = gpy + ly * GALAXY_R * gp;
            anchoring = false;
        } else if (galaxyLocked && pinnedGalaxy != null) {
            // Pivot the view around a focused galaxy: keep its centre fixed so
            // rotating (and zooming) orbits it instead of swinging it off-screen.
            camX = clX(pinnedGalaxy);
            camY = clY(pinnedGalaxy);
            anchoring = false;
        }

        addBackdrop();
        rsCount = 0;
        focusGalaxyName = null;

        StarPoint[] gals = clusterGalaxies.dots();
        double gRnom = GALAXY_R * scale;
        double centerX = uWidth / 2.0, centerY = uHeight / 2.0;

        List<StarPoint> visible = new ArrayList<>();
        for (StarPoint gx : gals) {
            double gsx = w2sx(clX(gx), clY(gx));
            double gsy = w2sy(clX(gx), clY(gx));
            // Include a galaxy whenever any of its STARS could reach the screen, not
            // just when its centre is near. Stars extend ~1.7 galaxy-radii from the
            // centre, so at deep zoom the centre can be far off-screen while the
            // stars you're looking at are dead centre. Culling by the full extent
            // keeps the galaxy from vanishing when the orbit lock ends and you
            // pan/rotate away from the focused star.
            double m = gRnom * clPersp(gx) * 1.7 + 40;
            if (gsx < -m || gsx > uWidth + m || gsy < -m || gsy > uHeight + m) continue;
            visible.add(gx);
        }

        // Ensure the focused galaxy is rendered even if it's outside the sampled
        // subset (e.g. arrived here via search to an arbitrary seed).
        if (focus != null) {
            long gl = galaxyLocalOf(focus.seed());
            long gid = UniverseMath.galaxyId(clusterIndex, gl);
            boolean present = false;
            for (StarPoint v : visible) if (v.id() == gid) { present = true; break; }
            if (!present) visible.add(GalaxyView.galaxyAt(clusterIndex, gl));
        }

        // Keep a searched/pinned galaxy rendered even after any orbit lock ends and
        // even though it is almost never part of the 2600 sampled galaxies. Subject
        // to the same on-screen culling so it behaves like any other galaxy: a dot
        // when zoomed out, detailed when zoomed in, dropped when you pan away.
        if (pinnedGalaxy != null) {
            double pgx = w2sx(clX(pinnedGalaxy), clY(pinnedGalaxy));
            double pgy = w2sy(clX(pinnedGalaxy), clY(pinnedGalaxy));
            double pm = gRnom * clPersp(pinnedGalaxy) * 1.7 + 40;
            boolean onScreen = pgx >= -pm && pgx <= uWidth + pm && pgy >= -pm && pgy <= uHeight + pm;
            boolean present = false;
            for (StarPoint v : visible) if (v.id() == pinnedGalaxy.id()) { present = true; break; }
            if (onScreen && !present) visible.add(pinnedGalaxy);
        }

        boolean canDetail = gRnom >= GAL_DETAIL_MIN;
        if (canDetail && visible.size() > MAX_DETAIL) {
            visible.sort(Comparator.comparingDouble((StarPoint s) ->
                    Math.hypot(w2sx(clX(s), clY(s)) - centerX, w2sy(clX(s), clY(s)) - centerY)));
        }

        double cs = Math.cos(spin), sn = Math.sin(spin);
        int details = 0;

        updateSystemState(dt);
        boolean deepZoom = systemActive || systemAlpha > 0.05;
        double galFade = 1.0 - 0.85 * systemAlpha;

        // Quality budget: cap how many star dots appear at once and scale each
        // galaxy's LOD so the budget can actually be reached at higher tiers.
        int starBudget = MapQuality.maxStars();
        double qMul = starBudget / 30000.0;
        int starsDrawn = 0;

        galaxyLoop:
        for (StarPoint gx : visible) {
            double persp = clPersp(gx);              // farther galaxies are smaller
            double pf = Math.max(0.45, Math.min(2.4, persp));   // clamped depth factor for sizes
            double wpx = clX(gx), wpy = clY(gx);
            double gcx = w2sx(wpx, wpy);
            double gcy = w2sy(wpx, wpy);
            double gRi = gRnom * pf;

            // Central supermassive black hole for ANY on-screen galaxy that hosts
            // one (not just the nearest). It only becomes visible once you're zoomed
            // in enough that it exceeds a few pixels, so far galaxies cost nothing.
            if (GalaxyView.galaxyHasBH(gx.id())) {
                double smbhRs = SMBH_WORLD_R * scale * pf;
                double fade = smoothstep(5.0, 16.0, smbhRs);
                if (fade > 0.02 && gcx > -60 && gcx < uWidth + 60 && gcy > -60 && gcy < uHeight + 60) {
                    addBlackHole(gcx, gcy, smbhRs * fade, gx.id(), true);
                }
            }

            if (!canDetail || details >= MAX_DETAIL) {
                // Depth cue: a galaxy's on-screen radius is sub-pixel at the cluster
                // overview, so its true size (gRi) gets swamped by the minimum dot
                // size and every galaxy looks identical. Fold the perspective factor
                // into the dot size and brightness so nearer galaxies (higher persp)
                // are visibly bigger and brighter than distant ones - a real 3D field.
                double depth = pf * pf;   // exaggerate near/far contrast
                double halo = Math.max(gRi * 1.4, 2.2 * depth);
                double core = Math.max(gRi * 0.4, 1.0 * depth);
                double depthA = Math.min(1.5, 0.30 + 0.55 * depth);
                addPoint(gcx, gcy, Math.max(1.2, halo), gx.color(), 0.10 * depthA);
                addPoint(gcx, gcy, Math.max(0.8, core), 0xFFFFFF, 0.16 * depthA);
                continue;
            }
            details++;
            addPoint(gcx, gcy, gRi * 1.05, 0x44509E, 0.05);
            addPoint(gcx, gcy, gRi * 0.60, 0xFFE2B0, 0.08);
            addPoint(gcx, gcy, gRi * 0.30, 0xFFF2DC, 0.12);
            if (details == 1 && gRi > 70)
                focusGalaxyName = NameGenerator.galaxyName(gx.id())
                        + " (" + GalaxyView.galaxyTypeName(gx.id()) + ")";

            int lod;
            if (deepZoom) lod = 900;
            else if (details == 1) lod = (int) Math.min(GalaxyView.GALAXY_STARS, Math.max(1500, gRi * 30) * qMul);
            else if (details <= 3) lod = (int) Math.min(GalaxyView.GALAXY_STARS, Math.max(500, gRi * 8) * qMul);
            else lod = (int) Math.min(GalaxyView.GALAXY_STARS, Math.max(300, gRi * 3) * qMul);
            GalaxyView field = galaxyField(gx.id());
            StarPoint[] stars = field.dots();
            int n = Math.min(lod, stars.length);
            double off = GALAXY_R * pf;                       // star spread scales with depth
            double starDepth = Math.max(0.55, Math.min(1.7, pf)); // and so do the star dots

            for (int i = 0; i < n; i++) {
                if (starsDrawn >= starBudget) break galaxyLoop;
                StarPoint s = stars[i];
                double lx = s.px() * cs - s.py() * sn;
                double ly = s.px() * sn + s.py() * cs;
                double ssx = w2sx(wpx + lx * off, wpy + ly * off);
                double ssy = w2sy(wpx + lx * off, wpy + ly * off);
                if (ssx < -20 || ssx > uWidth + 20 || ssy < -20 || ssy > uHeight + 20) continue;

                double factor = s.size() / 2.0;
                double sr = STAR_R * scale * factor;
                if (UniverseMath.isStellarBlackHole(s.id())) {
                    // Star-sized, but a distinct pulsing crimson so it stands out
                    // from ordinary stars. It only lenses once you focus it.
                    double size = Math.min(3.0, 1.6 + s.size() * 0.4) * starDepth;
                    double pulse = 0.8 + 0.2 * Math.sin(animTime * 3.0 + (s.id() & 1023));
                    addPoint(ssx, ssy, size, 0xFF3218, 0.9 * galFade * pulse);
                } else {
                    double size = Math.min(3.0, 1.5 + s.size() * 0.4) * starDepth;
                    addPoint(ssx, ssy, size, s.color(), (0.28 + s.brightness() * 0.45) * galFade);
                }
                double pickR = Math.max(6, Math.min(sr * 1.5, 60));
                rsAdd(s.id(), ssx, ssy, pickR, gx.px(), gx.py(), gx.pz(), s.px(), s.py(), factor, s.color());
                starsDrawn++;
            }
        }

        // Focused stellar black hole: lens at screen centre, fading in with
        // bhAlpha (subtle reveal) and growing with zoom.
        if (focus != null && focus.bh() && bhAlpha > 0.01) {
            // Stellar-mass black hole: keep it framed at ~70% of the previous size
            // so it doesn't balloon to fill the whole screen when zoomed in.
            double rs = 0.70 * Math.min(BH_FOCUS_WORLD_R * scale * bhAlpha, Math.min(uWidth, uHeight) * 0.42);
            if (rs > 1.0) addBlackHole(uWidth / 2.0, uHeight / 2.0, rs, focus.seed(), false);
        }

        if (systemActive) buildSystemGeometry();
    }

    private void updateSystemState(double dt) {
        double k = 1.0 - Math.exp(-dt * 6.0);
        if (focus == null) {
            forceSystemOpen = false;
            systemSuppressed = false;
            systemAlpha += (0.0 - systemAlpha) * k;   // fade galaxy back in
            bhAlpha += (0.0 - bhAlpha) * k;
            systemActive = false;                      // never active without a focus
            return;
        }
        // Release the lock when zoomed back out to the galaxy overview.
        if (GALAXY_R * scale < GAL_DETAIL_MIN * 0.8) {
            focus = null;
            selectedPlanet = -1;
            forceSystemOpen = false;
            systemSuppressed = false;
            systemAlpha += (0.0 - systemAlpha) * k;
            bhAlpha += (0.0 - bhAlpha) * k;
            systemActive = false;
            return;
        }
        // Isolated (nothing else on screen) OR a search jump that forces it open,
        // but never while the view has been manually suppressed (orbit-locked but
        // pulled back to look at the galaxy).
        boolean isolated = forceSystemOpen || (!systemSuppressed && isFocusIsolated());
        // Black holes never open the solar-system view; the lens fades in subtly
        // as you zoom in and it becomes isolated (nothing else on screen).
        if (focus.bh()) {
            systemActive = false;
            systemAlpha += (0.0 - systemAlpha) * k;
            bhAlpha += ((isolated ? 1.0 : 0.0) - bhAlpha) * k;
            return;
        }
        systemPlanets = UniverseMath.planetCount(focus.seed());
        systemAlpha += ((isolated ? 1.0 : 0.0) - systemAlpha) * k;
        bhAlpha += (0.0 - bhAlpha) * k;
        systemActive = systemAlpha > 0.02;
    }

    /** True when no other star of the focused galaxy is visible on screen. */
    private boolean isFocusIsolated() {
        long seed = focus.seed();
        long block = UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER;
        long c = Long.divideUnsigned(seed, block);
        long gl = Long.divideUnsigned(Long.remainderUnsigned(seed, block), UniverseMath.STARS_PER_GALAXY);
        GalaxyView field = galaxyField(UniverseMath.galaxyId(c, gl));
        double gpx = p3x(focus.gx3(), focus.gy3(), focus.gz3());
        double gpy = p3y(focus.gx3(), focus.gy3(), focus.gz3());
        double gp = p3persp(focus.gx3(), focus.gy3(), focus.gz3());
        double off = GALAXY_R * gp;
        double cs = Math.cos(spin), sn = Math.sin(spin);
        for (StarPoint s : field.dots()) {
            if (s.id() == seed) continue;
            double lx = s.px() * cs - s.py() * sn;
            double ly = s.px() * sn + s.py() * cs;
            double ssx = w2sx(gpx + lx * off, gpy + ly * off);
            double ssy = w2sy(gpx + lx * off, gpy + ly * off);
            if (ssx >= 0 && ssx < this.width && ssy >= 0 && ssy < this.height) return false;
        }
        return true;
    }

    private void buildSystemGeometry() {
        if (focus == null) return;
        double a = systemAlpha;
        long sys = focus.seed();
        double cy = uHeight / 2.0;
        double sx = sunX();
        int sunCol = NameGenerator.starColor(sys);
        addSoftDisc(sx, cy, 84, sunCol, 0.16 * a);
        addSoftDisc(sx, cy, 54, sunCol, 0.50 * a);
        addSoftDisc(sx, cy, 38, lighten(sunCol), 0.90 * a);
        addSoftDisc(sx, cy, 20, 0xFFFFFF, a);
        double half = planetHalf(systemPlanets);
        for (int kk = 0; kk < systemPlanets; kk++) {
            long pseed = UniverseMath.planetSeed(sys, kk);
            addCubeGPU(planetX(kk, systemPlanets), cy, half, NameGenerator.planetColor(pseed), a);
        }
    }

    // --- Solar-system layout (screen space) ---
    private double sunX() { return uWidth * 0.24; }
    private double planetRowX0() { return uWidth * 0.44; }
    private double planetRowX1() { return uWidth * 0.74; }
    private double planetX(int k, int pc) {
        if (pc <= 1) return (planetRowX0() + planetRowX1()) / 2.0;
        return planetRowX0() + (planetRowX1() - planetRowX0()) * k / (pc - 1);
    }
    private double planetHalf(int pc) {
        double spacing = (pc <= 1) ? 60 : (planetRowX1() - planetRowX0()) / (pc - 1);
        return Math.max(6, Math.min(18, spacing * 0.32));
    }
    private int pickPlanet(double mouseX, double mouseY) {
        if (!systemActive) return -1;
        double mx = toUnivX(mouseX);
        double my = toUnivY(mouseY);
        double cy = uHeight / 2.0;
        double half = planetHalf(systemPlanets) + 4;
        for (int k = 0; k < systemPlanets; k++) {
            double px = planetX(k, systemPlanets);
            if (Math.abs(mx - px) <= half && Math.abs(my - cy) <= half * 1.4) return k;
        }
        return -1;
    }

    private void overlayCluster(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        if (systemActive) drawSystemView(g, mouseX, mouseY);
        drawSelectionMarker(g);
        drawPings(g);

        StringBuilder crumb = new StringBuilder(I18n.get("gui.universemod.breadcrumb_universe"))
                .append(" > ").append(NameGenerator.clusterName(clusterIndex));
        if (focusGalaxyName != null) crumb.append(" > ").append(focusGalaxyName);
        if (focus != null) {
            if (focus.bh()) crumb.append(" > ").append(I18n.get("gui.universemod.breadcrumb_black_hole"))
                    .append(" ").append(NameGenerator.blackHoleName(focus.seed()));
            else crumb.append(" > ").append(I18n.get("gui.universemod.breadcrumb_star_system"))
                    .append(" ").append(NameGenerator.systemName(focus.seed()));
        }
        if (selectedPlanet >= 0 && focus != null && !focus.bh())
            crumb.append(" > ").append(NameGenerator.planetName(focus.seed(), selectedPlanet));
        g.text(this.font, crumb.toString(), 12, this.height - 42, 0xFFBFC6FF);
        g.text(this.font, I18n.get("gui.universemod.help_galaxy"),
                12, this.height - 28, 0xFF8A90B0);
        g.text(this.font, I18n.get("gui.universemod.help_backspace"), 12, this.height - 14, 0xFF8A90B0);

        if (focus != null && focus.bh()) {
            drawBlackHolePanel(g);
        } else if (systemActive) {
            drawInfoPanel(g);
            if (travelModalVisible()) drawTravelModal(g, mouseX, mouseY);
        } else {
            int hov = pickStar(mouseX, mouseY);
            if (hov >= 0) {
                long sd = rsSeed[hov];
                String label = UniverseMath.isStellarBlackHole(sd)
                        ? "Black Hole System: " + NameGenerator.blackHoleName(sd)
                        : "Star System " + NameGenerator.systemName(sd);
                tooltip(g, mouseX, mouseY, label);
            }
        }
    }

    /** Draw pulsing rings over galaxies other players are inspecting in this cluster. */
    private void drawPings(GuiGraphicsExtractor g) {
        if (SpacePings.galaxyCluster() != clusterIndex) return;
        List<SpacePings.GalaxyPing> pings = SpacePings.galaxy();
        if (pings.isEmpty()) return;
        for (SpacePings.GalaxyPing p : pings) {
            StarPoint gdot = GalaxyView.galaxyAt(clusterIndex, p.galaxyLocal());
            if (gdot == null) continue;
            double sx = toScreenX(w2sx(clX(gdot), clY(gdot)));
            double sy = toScreenY(w2sy(clX(gdot), clY(gdot)));
            if (sx < -60 || sx > this.width + 60 || sy < -60 || sy > this.height + 60) continue;
            double phase = (p.name().hashCode() & 0xFFFF) / 65535.0 * Math.PI * 2.0;
            drawPingRing(g, sx, sy, 13.0, 0xFF64E0FF, phase);
            g.centeredText(this.font, p.name(), (int) sx, (int) (sy - 22), 0xFF9AE0FF);
        }
    }

    /** Draw pulsing rings over clusters other players are currently in (universe screen). */
    private void drawUniversePings(GuiGraphicsExtractor g) {
        List<SpacePings.ClusterPing> pings = SpacePings.universe();
        if (pings.isEmpty()) return;
        updateUniverseTrig();
        double cx = uWidth / 2.0, cy = uHeight / 2.0;
        double radius = Math.min(uWidth, uHeight) * 0.42 * uZoom;
        for (SpacePings.ClusterPing p : pings) {
            StarPoint cdot = GalaxyView.clusterAt(p.cluster());
            if (cdot == null) continue;
            double sx = toScreenX(cx + up3x(cdot.px(), cdot.py(), cdot.pz()) * radius);
            double sy = toScreenY(cy + up3y(cdot.px(), cdot.py(), cdot.pz()) * radius);
            if (sx < -60 || sx > this.width + 60 || sy < -60 || sy > this.height + 60) continue;
            double phase = (p.name().hashCode() & 0xFFFF) / 65535.0 * Math.PI * 2.0;
            drawPingRing(g, sx, sy, 11.0, 0xFF64E0FF, phase);
            g.centeredText(this.font, p.name(), (int) sx, (int) (sy - 20), 0xFF9AE0FF);
        }
    }

    /** A single pulsing radar ring, drawn as a fan of short line segments. */
    private void drawPingRing(GuiGraphicsExtractor g, double cx, double cy, double baseR, int rgb, double phase) {
        double pulse = 0.5 + 0.5 * Math.sin(animTime * 4.0 + phase);
        double r = baseR * (0.85 + 0.5 * pulse);
        int alpha = Math.min(255, (int) (90 + 130 * pulse));
        int argb = (alpha << 24) | (rgb & 0xFFFFFF);
        int seg = 30;
        double px = cx + r, py = cy;
        for (int i = 1; i <= seg; i++) {
            double t = i / (double) seg * Math.PI * 2.0;
            double nx = cx + Math.cos(t) * r, ny = cy + Math.sin(t) * r;
            line(g, new double[]{px, py}, new double[]{nx, ny}, argb);
            px = nx; py = ny;
        }
    }

    // Localized display name of a black hole's visual variant. The variant
    // itself is chosen deterministically in NameGenerator (main-side, no I18n);
    // we only map the resulting index to a translation key here.
    private static final String[] BH_TYPE_KEYS = {
            "bh.universemod.kerr", "bh.universemod.quasar",
            "bh.universemod.dormant", "bh.universemod.micro"
    };

    private static String blackHoleRenderTypeName(long seed, boolean supermassive) {
        return I18n.get(BH_TYPE_KEYS[NameGenerator.blackHoleRenderType(seed, supermassive)]);
    }

    private void drawBlackHolePanel(GuiGraphicsExtractor g) {
        long sd = focus.seed();
        int w = 196, x = this.width - w - 12, y = 40, h = 104;
        g.fill(x, y, x + w, y + h, 0xE0060812);
        g.fill(x, y, x + w, y + 1, 0xFF6A3A3A);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF6A3A3A);
        g.fill(x, y, x + 1, y + h, 0xFF6A3A3A);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF6A3A3A);
        int tx = x + 12, ty = y + 10;
        g.centeredText(this.font, I18n.get("gui.universemod.black_hole"), x + w / 2, ty, 0xFFFF9060); ty += 12;
        g.centeredText(this.font, NameGenerator.blackHoleName(sd), x + w / 2, ty, 0xFFFFFFFF); ty += 20;
        g.text(this.font, I18n.get("gui.universemod.type"), tx, ty, 0xFFFF9060); ty += 11;
        g.text(this.font, blackHoleRenderTypeName(sd, false), tx, ty, 0xFFFFFFFF); ty += 18;
        // No SEED: a black hole has no planets, so there is nothing to travel to.
        g.text(this.font, I18n.get("gui.universemod.distance"), tx, ty, 0xFFFF9060); ty += 11;
        double ly = UniverseMath.distanceLightYears(sd, UniverseMath.systemForPlanetSeed(worldSeed(), 0));
        g.text(this.font, UniverseMath.formatDistance(ly), tx, ty, 0xFFFFFFFF);
    }

    // ------------------------------------------------------------------
    // Recently-visited planets (history icon + drop-down list)
    // ------------------------------------------------------------------

    private static final int HIST_PANEL_W = 210;
    private static final int HIST_ROW_H = 18;
    private static final int HIST_HEAD_H = 16;
    private static final int HIST_BG = 0xF00A0C1A;
    private static final int HIST_BORDER = 0xFF3A3A6A;

    /** {x, y, w, h} of the clock/history toggle button (top-left). */
    private int[] historyButtonRect() {
        int size = 22;
        int x = 12;
        int y = clusterMode ? 36 : 12;   // sit below the "Back" button in cluster mode
        return new int[]{x, y, size, size};
    }

    /** Draw the history toggle button and, when open, the visited-planets list. */
    private void drawHistory(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] b = historyButtonRect();
        int bx = b[0], by = b[1], bw = b[2], bh = b[3];
        boolean hover = inRect(mouseX, mouseY, bx, by, bw, bh);
        int btnBg = historyOpen ? 0xF01A2340 : (hover ? 0xF0141A30 : 0xE00A0C1A);
        g.fill(bx, by, bx + bw, by + bh, btnBg);
        g.fill(bx, by, bx + bw, by + 1, HIST_BORDER);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, HIST_BORDER);
        g.fill(bx, by, bx + 1, by + bh, HIST_BORDER);
        g.fill(bx + bw - 1, by, bx + bw, by + bh, HIST_BORDER);
        drawHistoryIcon(g, bx + bw / 2.0, by + bh / 2.0, 7.0,
                (hover || historyOpen) ? 0xFFFFFFFF : 0xFFBFC6FF, btnBg);

        if (hover && !historyOpen) tooltip(g, mouseX, mouseY, "Recently visited");
        if (!historyOpen) return;

        List<VisitedPlanets.Entry> full = VisitedPlanets.recent();
        List<VisitedPlanets.Entry> list = full.subList(0, Math.min(VisitedPlanets.QUICK, full.size()));
        int rows = Math.max(1, list.size());
        int px = bx;
        int py = by + bh + 4;
        int ph = HIST_HEAD_H + rows * HIST_ROW_H + 6;

        g.fill(px, py, px + HIST_PANEL_W, py + ph, HIST_BG);
        g.fill(px, py, px + HIST_PANEL_W, py + 1, HIST_BORDER);
        g.fill(px, py + ph - 1, px + HIST_PANEL_W, py + ph, HIST_BORDER);
        g.fill(px, py, px + 1, py + ph, HIST_BORDER);
        g.fill(px + HIST_PANEL_W - 1, py, px + HIST_PANEL_W, py + ph, HIST_BORDER);

        g.text(this.font, I18n.get("gui.universemod.last_visited", VisitedPlanets.QUICK), px + 8, py + 5, 0xFFFFD070);

        if (list.isEmpty()) {
            g.text(this.font, I18n.get("gui.universemod.no_planets_visited"), px + 8, py + HIST_HEAD_H + 4, 0xFF8A90B0);
            return;
        }

        for (int i = 0; i < list.size(); i++) {
            VisitedPlanets.Entry e = list.get(i);
            int ry = py + HIST_HEAD_H + i * HIST_ROW_H;
            boolean rh = inRect(mouseX, mouseY, px + 2, ry, HIST_PANEL_W - 4, HIST_ROW_H);
            if (rh) g.fill(px + 2, ry, px + HIST_PANEL_W - 2, ry + HIST_ROW_H, 0x402A6AC0);

            long pseed = UniverseMath.planetSeed(e.systemSeed(), e.planetIndex());
            int col = 0xFF000000 | NameGenerator.planetColor(pseed);
            int sw = 8, sx = px + 8, sy = ry + (HIST_ROW_H - sw) / 2;
            g.fill(sx, sy, sx + sw, sy + sw, col);

            String name = NameGenerator.planetName(e.systemSeed(), e.planetIndex());
            g.text(this.font, name, sx + sw + 6, ry + (HIST_ROW_H - 8) / 2, rh ? 0xFFFFFFFF : 0xFFDDE3FF);
        }
    }

    /** A small "clock with a counter-clockwise arrow" history glyph. */
    private void drawHistoryIcon(GuiGraphicsExtractor g, double cx, double cy, double r, int rgb, int bg) {
        int argb = 0xFF000000 | (rgb & 0xFFFFFF);
        // Clock ring: outer disc punched out by the background colour.
        drawDisc(g, cx, cy, r, argb);
        drawDisc(g, cx, cy, r - 2.0, bg);
        // Clock hands.
        line(g, new double[]{cx, cy}, new double[]{cx, cy - r * 0.55}, argb);        // up
        line(g, new double[]{cx, cy}, new double[]{cx + r * 0.5, cy + r * 0.3}, argb); // ~4 o'clock
        // Counter-clockwise arrowhead on the left of the ring.
        double ax = cx - r;
        fillTriangle(g, ax - 3, cy + 1, ax + 3, cy - 3, ax + 3, cy + 5, argb);
    }

    /** Index of the visited-planets row under the cursor, or -1. */
    private int historyEntryAt(double mx, double my) {
        if (!historyOpen) return -1;
        List<VisitedPlanets.Entry> full = VisitedPlanets.recent();
        List<VisitedPlanets.Entry> list = full.subList(0, Math.min(VisitedPlanets.QUICK, full.size()));
        if (list.isEmpty()) return -1;
        int[] b = historyButtonRect();
        int px = b[0], py = b[1] + b[3] + 4;
        for (int i = 0; i < list.size(); i++) {
            int ry = py + HIST_HEAD_H + i * HIST_ROW_H;
            if (inRect(mx, my, px + 2, ry, HIST_PANEL_W - 4, HIST_ROW_H)) return i;
        }
        return -1;
    }

    /** Fly the map to a remembered planet. */
    private void goToVisited(VisitedPlanets.Entry e) {
        long sys = e.systemSeed();
        long block = UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER;
        long cluster = Long.divideUnsigned(sys, block);
        long galaxyLocal = Long.divideUnsigned(Long.remainderUnsigned(sys, block), UniverseMath.STARS_PER_GALAXY);
        long starIndex = Long.remainderUnsigned(sys, UniverseMath.STARS_PER_GALAXY);
        historyOpen = false;
        navigateTo(new NameGenerator.Match(3, cluster, galaxyLocal, starIndex, e.planetIndex(), sys));
    }

    /**
     * Open-navigate straight to a planet by its world seed. Used by shared space
     * beacons (the {@code /verse-goto} client command) so a chat click flies the
     * map to the exact planet.
     */
    public void goToPlanetSeed(long planetSeed) {
        long sys = UniverseMath.systemForPlanetSeed(planetSeed, 0);
        int planetIndex = UniverseMath.planetIndexInSystem(sys, planetSeed);
        long block = UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER;
        long cluster = Long.divideUnsigned(sys, block);
        long galaxyLocal = Long.divideUnsigned(Long.remainderUnsigned(sys, block), UniverseMath.STARS_PER_GALAXY);
        long starIndex = Long.remainderUnsigned(sys, UniverseMath.STARS_PER_GALAXY);
        setTab(Tab.MAP);
        navigateTo(new NameGenerator.Match(3, cluster, galaxyLocal, starIndex, planetIndex, sys));
    }

    private void playClick() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
    }

    private static boolean inRect(double px, double py, int[] r) {
        return inRect(px, py, r[0], r[1], r[2], r[3]);
    }

    // ------------------------------------------------------------------
    // Top tab bar: "Galactic Map" | "Favorites / Latest"
    // ------------------------------------------------------------------

    private static final int TAB_H = 16;

    private static final int TAB_COUNT = 3;

    private int[] tabRect(int i) {
        int w = 120, total = w * TAB_COUNT + 4 * (TAB_COUNT - 1);
        int x0 = this.width / 2 - total / 2;
        return new int[]{x0 + i * (w + 4), 2, w, TAB_H};
    }

    private void drawTabBar(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        String[] names = {
                I18n.get("gui.universemod.tab_map"),
                I18n.get("gui.universemod.tab_favorites"),
                I18n.get("gui.universemod.tab_players")
        };
        Tab[] tabs = {Tab.MAP, Tab.FAVORITES, Tab.PLAYERS};
        for (int i = 0; i < TAB_COUNT; i++) {
            int[] r = tabRect(i);
            boolean active = tab == tabs[i];
            boolean hover = inRect(mouseX, mouseY, r);
            g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3],
                    active ? 0xF02A3560 : (hover ? 0xF01A2240 : 0xC00A0C1A));
            int bc = active ? 0xFF6AC0FF : 0xFF3A3A6A;
            g.fill(r[0], r[1], r[0] + r[2], r[1] + 1, bc);
            g.fill(r[0], r[1] + r[3] - 1, r[0] + r[2], r[1] + r[3], bc);
            g.fill(r[0], r[1], r[0] + 1, r[1] + r[3], bc);
            g.fill(r[0] + r[2] - 1, r[1], r[0] + r[2], r[1] + r[3], bc);
            g.centeredText(this.font, names[i], r[0] + r[2] / 2, r[1] + 4,
                    active ? 0xFFFFFFFF : 0xFFBFC6FF);
        }
    }

    // ------------------------------------------------------------------
    // Players tab: who's online, where they are, and a "Join" button.
    // ------------------------------------------------------------------

    private int[] playersPanel() {
        int pw = Math.min(440, this.width - 40);
        int px = (this.width - pw) / 2;
        int py = 44;
        int ph = this.height - py - 16;
        return new int[]{px, py, pw, ph};
    }

    private int[] joinButtonRect(int px, int pw, int ry) {
        int bw = 52, bh = 16;
        return new int[]{px + pw - bw - 8, ry + (PLAYER_ROW_H - bh) / 2, bw, bh};
    }

    private void drawPlayersScreen(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] p = playersPanel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        g.fill(px, py, px + pw, py + ph, 0xE0060812);
        int bc = 0xFF3A3A6A;
        g.fill(px, py, px + pw, py + 1, bc);
        g.fill(px, py + ph - 1, px + pw, py + ph, bc);
        g.fill(px, py, px + 1, py + ph, bc);
        g.fill(px + pw - 1, py, px + pw, py + ph, bc);

        List<ServerPlayers.Entry> players = ServerPlayers.get();
        g.text(this.font, I18n.get("gui.universemod.players_online", players.size()), px + 8, py + 6, 0xFFFFD070);

        int listY = py + 22;
        int listBottom = py + ph - 4;
        if (players.isEmpty()) {
            g.text(this.font, I18n.get("gui.universemod.players_none"), px + 8, listY + 4, 0xFF8A90B0);
            return;
        }

        UUID self = (minecraft != null && minecraft.player != null) ? minecraft.player.getUUID() : null;
        for (int i = 0; i < players.size(); i++) {
            int ry = listY + i * PLAYER_ROW_H - (int) playersScroll;
            if (ry + PLAYER_ROW_H < listY || ry > listBottom) continue;   // off-panel
            ServerPlayers.Entry e = players.get(i);
            boolean isSelf = self != null && self.equals(e.id());

            boolean rowHover = mouseY >= ry && mouseY < ry + PLAYER_ROW_H
                    && mouseX >= px + 2 && mouseX < px + pw - 2;
            if (rowHover) g.fill(px + 2, ry, px + pw - 2, ry + PLAYER_ROW_H, 0x30204060);

            drawPlayerHead(g, e.id(), px + 6, ry + 3, 16);
            g.text(this.font, e.name(), px + 28, ry + 2, isSelf ? 0xFF9AE0A0 : 0xFFFFFFFF);
            g.text(this.font, e.location(), px + 28, ry + 12, 0xFF8AB0C8);

            if (!isSelf) {
                int[] jb = joinButtonRect(px, pw, ry);
                boolean jh = inRect(mouseX, mouseY, jb);
                g.fill(jb[0], jb[1], jb[0] + jb[2], jb[1] + jb[3], jh ? 0xFF2AA85F : 0xFF1E7D46);
                g.centeredText(this.font, I18n.get("gui.universemod.join"),
                        jb[0] + jb[2] / 2, jb[1] + (jb[3] - 8) / 2, 0xFFFFFFFF);
            } else {
                g.text(this.font, I18n.get("gui.universemod.you"), px + pw - 40, ry + 7, 0xFF6A7A90);
            }
        }
    }

    /** Draw a player's face (base layer + hat overlay) from their skin texture. */
    private void drawPlayerHead(GuiGraphicsExtractor g, UUID id, int x, int y, int size) {
        PlayerSkin skin = null;
        if (minecraft != null && minecraft.getConnection() != null) {
            PlayerInfo info = minecraft.getConnection().getPlayerInfo(id);
            if (info != null) skin = info.getSkin();
        }
        if (skin == null) skin = DefaultPlayerSkin.get(id);
        Identifier tex = skin.body().texturePath();
        // 8x8 face at (8,8) and the hat overlay at (40,8) on a 64x64 skin.
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 8f, 8f, size, size, 8, 8, 64, 64);
        g.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 40f, 8f, size, size, 8, 8, 64, 64);
    }

    /** Handle clicks on the Players tab (the Join buttons). */
    private void playersClick(double mx, double my) {
        int[] p = playersPanel();
        int px = p[0], py = p[1], pw = p[2], ph = p[3];
        int listY = py + 22, listBottom = py + ph - 4;
        List<ServerPlayers.Entry> players = ServerPlayers.get();
        UUID self = (minecraft != null && minecraft.player != null) ? minecraft.player.getUUID() : null;
        for (int i = 0; i < players.size(); i++) {
            int ry = listY + i * PLAYER_ROW_H - (int) playersScroll;
            if (ry + PLAYER_ROW_H < listY || ry > listBottom) continue;
            ServerPlayers.Entry e = players.get(i);
            if (self != null && self.equals(e.id())) continue;
            if (inRect(mx, my, joinButtonRect(px, pw, ry))) {
                playClick();
                ClientPlayNetworking.send(new JoinPlayerPacket(e.id().toString()));
                onClose();   // close the map so the teleport is visible immediately
                return;
            }
        }
    }

    // ------------------------------------------------------------------
    // Favourite toggle (map): star the focused planet or galaxy
    // ------------------------------------------------------------------

    private boolean canFavPlanet() {
        return systemActive && focus != null && !focus.bh() && selectedPlanet >= 0;
    }
    private boolean canFavGalaxy() {
        return !systemActive && galaxyLocked && pinnedGalaxy != null;
    }

    private int[] favToggleRect() {
        int[] h = historyButtonRect();
        return new int[]{h[0], h[1] + h[3] + 6, 22, 22};
    }

    /** A filled 5-point star. */
    private void drawStar(GuiGraphicsExtractor g, double cx, double cy, double rOuter, int argb) {
        double rInner = rOuter * 0.45;
        double[] px = new double[10], py = new double[10];
        for (int i = 0; i < 10; i++) {
            double ang = -Math.PI / 2 + i * Math.PI / 5;
            double rr = (i % 2 == 0) ? rOuter : rInner;
            px[i] = cx + Math.cos(ang) * rr;
            py[i] = cy + Math.sin(ang) * rr;
        }
        for (int i = 0; i < 10; i++) {
            int j = (i + 1) % 10;
            fillTriangle(g, cx, cy, px[i], py[i], px[j], py[j], argb);
        }
    }

    private void drawFavoriteToggle(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        boolean planet = canFavPlanet();
        boolean galaxy = !planet && canFavGalaxy();
        if (!planet && !galaxy) return;
        boolean fav = planet ? Favorites.isPlanet(focus.seed(), selectedPlanet)
                             : Favorites.isGalaxy(pinnedGalaxy.id());
        int[] r = favToggleRect();
        boolean hover = inRect(mouseX, mouseY, r);
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], hover ? 0xF0141A30 : 0xE00A0C1A);
        int bc = 0xFF3A3A6A;
        g.fill(r[0], r[1], r[0] + r[2], r[1] + 1, bc);
        g.fill(r[0], r[1] + r[3] - 1, r[0] + r[2], r[1] + r[3], bc);
        g.fill(r[0], r[1], r[0] + 1, r[1] + r[3], bc);
        g.fill(r[0] + r[2] - 1, r[1], r[0] + r[2], r[1] + r[3], bc);
        drawStar(g, r[0] + r[2] / 2.0, r[1] + r[3] / 2.0, 7.0, fav ? 0xFFFFD54A : 0xFF6A7290);
        if (hover) tooltip(g, mouseX, mouseY,
                fav ? "Unfavorite" : (planet ? "Favorite this planet" : "Favorite this galaxy"));
    }

    // ------------------------------------------------------------------
    // Favourites / Latest screen
    // ------------------------------------------------------------------

    private static final int FAV_ROW_H = 18;

    private int[] favLeftPanel() {
        int pad = 16, top = 34;
        int colW = (this.width - pad * 3) / 2;
        return new int[]{pad, top, colW, this.height - top - pad};
    }
    private int[] favRightPanel() {
        int[] l = favLeftPanel();
        return new int[]{l[0] * 2 + l[2], l[1], l[2], l[3]};
    }
    private int[] favSubRect(int i) {
        int[] l = favLeftPanel();
        int w = (l[2] - 24) / 2;
        return new int[]{l[0] + 8 + i * (w + 8), l[1] + 20, w, 14};
    }

    private void favPanel(GuiGraphicsExtractor g, int[] r) {
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], 0xE0060812);
        int bc = 0xFF3A3A6A;
        g.fill(r[0], r[1], r[0] + r[2], r[1] + 1, bc);
        g.fill(r[0], r[1] + r[3] - 1, r[0] + r[2], r[1] + r[3], bc);
        g.fill(r[0], r[1], r[0] + 1, r[1] + r[3], bc);
        g.fill(r[0] + r[2] - 1, r[1], r[0] + r[2], r[1] + r[3], bc);
    }

    private void favSmallTab(GuiGraphicsExtractor g, int[] r, String label, boolean active, int mx, int my) {
        boolean hover = inRect(mx, my, r);
        g.fill(r[0], r[1], r[0] + r[2], r[1] + r[3], active ? 0xF02A3560 : (hover ? 0xF01A2240 : 0xC00A0C1A));
        g.centeredText(this.font, label, r[0] + r[2] / 2, r[1] + 3, active ? 0xFFFFFFFF : 0xFFBFC6FF);
    }

    private void favRow(GuiGraphicsExtractor g, int x, int y, int w, boolean hover,
                        int swatchRgb, String name, boolean removable) {
        if (hover) g.fill(x + 2, y, x + w - 2, y + FAV_ROW_H, 0x402A6AC0);
        int sw = 8, sx = x + 8, sy = y + (FAV_ROW_H - sw) / 2;
        g.fill(sx, sy, sx + sw, sy + sw, 0xFF000000 | swatchRgb);
        g.text(this.font, name, sx + sw + 6, y + (FAV_ROW_H - 8) / 2, hover ? 0xFFFFFFFF : 0xFFDDE3FF);
        if (removable) g.text(this.font, "\u00D7", x + w - 15, y + (FAV_ROW_H - 8) / 2, 0xFFFF6060);
    }

    private void drawFavoritesScreen(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] lp = favLeftPanel(), rp = favRightPanel();
        favPanel(g, lp);
        favPanel(g, rp);

        g.text(this.font, I18n.get("gui.universemod.favorites"), lp[0] + 8, lp[1] + 6, 0xFFFFD070);
        favSmallTab(g, favSubRect(0), I18n.get("gui.universemod.galaxies"), favSub == FavSub.GALAXIES, mouseX, mouseY);
        favSmallTab(g, favSubRect(1), I18n.get("gui.universemod.planets"), favSub == FavSub.PLANETS, mouseX, mouseY);

        int listY = lp[1] + 40, listH = lp[1] + lp[3] - listY - 6;
        if (favSub == FavSub.GALAXIES) {
            List<Long> gs = Favorites.galaxies();
            if (gs.isEmpty()) g.text(this.font, I18n.get("gui.universemod.no_favorite_galaxies"), lp[0] + 8, listY + 4, 0xFF8A90B0);
            for (int i = 0; i < gs.size(); i++) {
                int ry = listY + i * FAV_ROW_H - (int) favScroll;
                if (ry < listY - FAV_ROW_H || ry > listY + listH) continue;
                long id = gs.get(i);
                favRow(g, lp[0], ry, lp[2], inRect(mouseX, mouseY, lp[0] + 2, ry, lp[2] - 4, FAV_ROW_H),
                        0x8FA0FF, NameGenerator.galaxyName(id), true);
            }
        } else {
            List<Favorites.PlanetRef> ps = Favorites.planets();
            if (ps.isEmpty()) g.text(this.font, I18n.get("gui.universemod.no_favorite_planets"), lp[0] + 8, listY + 4, 0xFF8A90B0);
            for (int i = 0; i < ps.size(); i++) {
                int ry = listY + i * FAV_ROW_H - (int) favScroll;
                if (ry < listY - FAV_ROW_H || ry > listY + listH) continue;
                Favorites.PlanetRef pr = ps.get(i);
                int col = NameGenerator.planetColor(UniverseMath.planetSeed(pr.systemSeed(), pr.planetIndex()));
                favRow(g, lp[0], ry, lp[2], inRect(mouseX, mouseY, lp[0] + 2, ry, lp[2] - 4, FAV_ROW_H),
                        col, NameGenerator.planetName(pr.systemSeed(), pr.planetIndex()), true);
            }
        }

        g.text(this.font, I18n.get("gui.universemod.latest_visited"), rp[0] + 8, rp[1] + 6, 0xFFFFD070);
        int rY = rp[1] + 24, rH = rp[1] + rp[3] - rY - 6;
        List<VisitedPlanets.Entry> vs = VisitedPlanets.recent();
        if (vs.isEmpty()) g.text(this.font, I18n.get("gui.universemod.nothing_visited"), rp[0] + 8, rY + 4, 0xFF8A90B0);
        for (int i = 0; i < vs.size(); i++) {
            int ry = rY + i * FAV_ROW_H - (int) latestScroll;
            if (ry < rY - FAV_ROW_H || ry > rY + rH) continue;
            VisitedPlanets.Entry e = vs.get(i);
            int col = NameGenerator.planetColor(UniverseMath.planetSeed(e.systemSeed(), e.planetIndex()));
            favRow(g, rp[0], ry, rp[2], inRect(mouseX, mouseY, rp[0] + 2, ry, rp[2] - 4, FAV_ROW_H),
                    col, NameGenerator.planetName(e.systemSeed(), e.planetIndex()), false);
        }
    }

    /** Handle a click on the favourites screen. Returns true if consumed. */
    private boolean favoritesClick(double mx, double my) {
        if (inRect(mx, my, favSubRect(0))) { favSub = FavSub.GALAXIES; favScroll = 0; playClick(); return true; }
        if (inRect(mx, my, favSubRect(1))) { favSub = FavSub.PLANETS; favScroll = 0; playClick(); return true; }

        int[] lp = favLeftPanel(), rp = favRightPanel();
        int listY = lp[1] + 40, listH = lp[1] + lp[3] - listY - 6;
        if (inRect(mx, my, lp[0], listY, lp[2], listH)) {
            int i = (int) ((my - listY + (int) favScroll) / FAV_ROW_H);
            boolean onX = mx >= lp[0] + lp[2] - 18;
            if (favSub == FavSub.GALAXIES) {
                List<Long> gs = Favorites.galaxies();
                if (i >= 0 && i < gs.size()) {
                    if (onX) Favorites.removeGalaxy(gs.get(i)); else goToGalaxy(gs.get(i));
                    playClick();
                }
            } else {
                List<Favorites.PlanetRef> ps = Favorites.planets();
                if (i >= 0 && i < ps.size()) {
                    Favorites.PlanetRef pr = ps.get(i);
                    if (onX) Favorites.removePlanet(pr); else goToPlanetRef(pr.systemSeed(), pr.planetIndex());
                    playClick();
                }
            }
            return true;
        }

        int rY = rp[1] + 24, rH = rp[1] + rp[3] - rY - 6;
        if (inRect(mx, my, rp[0], rY, rp[2], rH)) {
            List<VisitedPlanets.Entry> vs = VisitedPlanets.recent();
            int i = (int) ((my - rY + (int) latestScroll) / FAV_ROW_H);
            if (i >= 0 && i < vs.size()) {
                goToPlanetRef(vs.get(i).systemSeed(), vs.get(i).planetIndex());
                playClick();
            }
            return true;
        }
        return false;
    }

    private void goToGalaxy(long galaxyId) {
        long cluster = Long.divideUnsigned(galaxyId, UniverseMath.GALAXIES_PER_CLUSTER);
        long gl = Long.remainderUnsigned(galaxyId, UniverseMath.GALAXIES_PER_CLUSTER);
        setTab(Tab.MAP);
        navigateTo(new NameGenerator.Match(1, cluster, gl, 0, -1, 0));
    }

    private void goToPlanetRef(long sys, int idx) {
        long block = UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER;
        long cluster = Long.divideUnsigned(sys, block);
        long gl = Long.divideUnsigned(Long.remainderUnsigned(sys, block), UniverseMath.STARS_PER_GALAXY);
        long star = Long.remainderUnsigned(sys, UniverseMath.STARS_PER_GALAXY);
        setTab(Tab.MAP);
        navigateTo(new NameGenerator.Match(3, cluster, gl, star, idx, sys));
    }

    // ------------------------------------------------------------------
    // Quality slider (centre-bottom): float value 1..5, worst -> ultra
    // ------------------------------------------------------------------

    private static final int SLIDER_W = 170;

    /** {trackX, trackY, trackW, trackH} of the slider's track. */
    private int[] sliderRect() {
        int trackW = SLIDER_W;
        int x = this.width / 2 - trackW / 2;
        int y = this.height - 16;
        return new int[]{x, y, trackW, 5};
    }

    private boolean inSliderArea(double mx, double my) {
        int[] r = sliderRect();
        return mx >= r[0] - 6 && mx <= r[0] + r[2] + 6
                && my >= r[1] - 9 && my <= r[1] + r[3] + 9;
    }

    private void setSliderFromMouse(double mouseX) {
        int[] r = sliderRect();
        MapQuality.setFromSlider01((float) ((mouseX - r[0]) / (double) r[2]));
    }

    private void drawQualitySlider(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] r = sliderRect();
        int tx = r[0], ty = r[1], tw = r[2], th = r[3];
        int px = tx - 12, pw = tw + 24;
        int py = ty - 26, ph = 40;

        // Backing panel so it stays readable over any background.
        g.fill(px, py, px + pw, py + ph, 0xE00A0C1A);
        g.fill(px, py, px + pw, py + 1, 0xFF3A3A6A);
        g.fill(px, py + ph - 1, px + pw, py + ph, 0xFF3A3A6A);
        g.fill(px, py, px + 1, py + ph, 0xFF3A3A6A);
        g.fill(px + pw - 1, py, px + pw, py + ph, 0xFF3A3A6A);

        g.centeredText(this.font, I18n.get("gui.universemod.quality", MapQuality.tierName()), this.width / 2, py + 6, 0xFFFFD070);

        // Track + filled portion.
        g.fill(tx, ty, tx + tw, ty + th, 0xFF2A3050);
        float v = MapQuality.slider01();
        int knobX = tx + (int) (v * tw);
        g.fill(tx, ty, knobX, ty + th, 0xFF4A78C0);

        // The five tier marks.
        for (int i = 0; i < MapQuality.TIERS; i++) {
            int mxk = tx + (int) (i / (double) (MapQuality.TIERS - 1) * tw);
            g.fill(mxk, ty - 3, mxk + 1, ty + th + 3, 0xFF6A7290);
        }

        // Knob.
        boolean hot = draggingSlider || inSliderArea(mouseX, mouseY);
        int kw = 5, kh = 12;
        int kx = knobX - kw / 2;
        int ky = ty + th / 2 - kh / 2;
        g.fill(kx, ky, kx + kw, ky + kh, hot ? 0xFFFFFFFF : 0xFFCADBFF);
    }

    // ------------------------------------------------------------------
    // Travel confirmation modal (bottom-right, above the breadcrumbs)
    // ------------------------------------------------------------------

    private boolean travelModalVisible() {
        return systemActive && focus != null && !focus.bh()
                && selectedPlanet >= 0 && selectedPlanet < systemPlanets;
    }

    /** {mx, my, mw, mh} of the modal box. */
    private int[] travelModalRect() {
        int mw = 214, mh = 60;
        int mx = this.width - mw - 12;
        int my = this.height - 42 - mh - 8;
        return new int[]{mx, my, mw, mh};
    }

    /** {yesX, noX, by, bw, bh} button geometry for hit-testing and drawing. */
    private int[] travelModalButtons() {
        int[] r = travelModalRect();
        int mx = r[0], my = r[1], mw = r[2], mh = r[3];
        int bw = (mw - 30) / 2, bh = 18, by = my + mh - bh - 8;
        int yesX = mx + 10, noX = yesX + bw + 10;
        return new int[]{yesX, noX, by, bw, bh};
    }

    private void drawTravelModal(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        int[] r = travelModalRect();
        int mx = r[0], my = r[1], mw = r[2], mh = r[3];
        g.fill(mx, my, mx + mw, my + mh, 0xF00A0C1A);
        g.fill(mx, my, mx + mw, my + 1, 0xFF6AC0FF);
        g.fill(mx, my + mh - 1, mx + mw, my + mh, 0xFF6AC0FF);
        g.fill(mx, my, mx + 1, my + mh, 0xFF6AC0FF);
        g.fill(mx + mw - 1, my, mx + mw, my + mh, 0xFF6AC0FF);

        String name = NameGenerator.planetName(focus.seed(), selectedPlanet);
        g.centeredText(this.font, I18n.get("gui.universemod.travel_to"), mx + mw / 2, my + 7, 0xFFE0E4FF);
        g.centeredText(this.font, I18n.get("gui.universemod.travel_confirm", name), mx + mw / 2, my + 18, 0xFFFFE9A8);

        int[] b = travelModalButtons();
        int yesX = b[0], noX = b[1], by = b[2], bw = b[3], bh = b[4];
        boolean yesHover = inRect(mouseX, mouseY, yesX, by, bw, bh);
        boolean noHover = inRect(mouseX, mouseY, noX, by, bw, bh);
        drawModalButton(g, yesX, by, bw, bh, I18n.get("gui.universemod.travel_yes"), yesHover, 0xFF1E7D46, 0xFF2AA85F);
        drawModalButton(g, noX, by, bw, bh, I18n.get("gui.universemod.travel_no"), noHover, 0xFF7D2A2A, 0xFFA83F3F);
    }

    private void drawModalButton(GuiGraphicsExtractor g, int x, int y, int w, int h,
                                 String label, boolean hover, int base, int hot) {
        g.fill(x, y, x + w, y + h, hover ? hot : base);
        g.centeredText(this.font, label, x + w / 2, y + (h - 8) / 2, 0xFFFFFFFF);
    }

    private static boolean inRect(double px, double py, int x, int y, int w, int h) {
        return px >= x && px <= x + w && py >= y && py <= y + h;
    }

    /** Ask the server to send us back to the overworld, then close the map. */
    private void returnHome() {
        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        PlanetSeedCache.clear();
        ClientPlayNetworking.send(new ReturnHomePacket());
        onClose();
    }

    /** Fire the travel: sound, packet, close the map. */
    private void confirmTravel() {
        long sys = focus.seed();
        long pseed = UniverseMath.planetSeed(sys, selectedPlanet);
        String name = NameGenerator.planetName(sys, selectedPlanet);
        String dimKey = TwoSixTwo_Arskiz.planetDimensionId(pseed).toString();

        if (minecraft != null) {
            minecraft.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0f));
        }
        PlanetSeedCache.setPlanetSeed(dimKey, pseed);
        ClientPlayNetworking.send(new TravelToPlanetPacket(pseed, name, dimKey));
        VisitedPlanets.record(sys, selectedPlanet);
        Discoveries.invalidate(pseed);   // we may have just charted it; re-fetch next view
        onClose();
    }

    /** Labels/selection/hover for the system view (sun & cubes are on the GPU). */
    private void drawSystemView(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        long sys = focus.seed();
        // The sun & cubes live in universe space; map to screen space so labels
        // and markers track them at every GUI scale, not just at a 2:1 ratio.
        double s = univScale();
        double cy = toScreenY(uHeight / 2.0);
        double sx = toScreenX(sunX());
        g.centeredText(this.font, I18n.get("gui.universemod.star_system_label", NameGenerator.systemName(sys)),
                (int) sx, (int) (cy - 100 * s), 0xFFFFE9A8);
        g.centeredText(this.font, NameGenerator.starType(sys), (int) sx, (int) (cy + 96 * s), 0xFFBFC6FF);

        if (selectedPlanet >= 0 && selectedPlanet < systemPlanets) {
            double px = toScreenX(planetX(selectedPlanet, systemPlanets));
            double half = planetHalf(systemPlanets) * s;
            g.fill((int) (px - half - 3), (int) (cy - half * 1.5 - 3),
                    (int) (px + half + 3), (int) (cy - half * 1.5 - 2), 0xFFFFFFFF);
            g.fill((int) (px - half - 3), (int) (cy + half * 1.5 + 2),
                    (int) (px + half + 3), (int) (cy + half * 1.5 + 3), 0xFFFFFFFF);
        }

        int hp = pickPlanet(mouseX, mouseY);
        if (hp >= 0) tooltip(g, mouseX, mouseY, "Planet: " + NameGenerator.planetName(sys, hp));
    }

    /**
     * A small red selection marker under the currently focused star system's dot:
     * a very-red underline (about as wide as the dot) with a red arrow pointing
     * up at it, so it's clear which system you clicked. Only shown in the galaxy
     * view for an ordinary star system (not black holes, not the open system view).
     */
    private void drawSelectionMarker(GuiGraphicsExtractor g) {
        if (focus == null || focus.bh() || systemActive) return;

        // The focused star's on-screen position (the camera centres on it), rounded
        // to a whole pixel so the underline, arrow and dot all share one axis.
        double gpx = p3x(focus.gx3(), focus.gy3(), focus.gz3());
        double gpy = p3y(focus.gx3(), focus.gy3(), focus.gz3());
        double gp = p3persp(focus.gx3(), focus.gy3(), focus.gz3());
        double lx = focus.lx0() * Math.cos(spin) - focus.ly0() * Math.sin(spin);
        double ly = focus.lx0() * Math.sin(spin) + focus.ly0() * Math.cos(spin);
        double off = GALAXY_R * gp;
        int cx = (int) Math.round(toScreenX(w2sx(gpx + lx * off, gpy + ly * off)));
        int cy = (int) Math.round(toScreenY(w2sy(gpx + lx * off, gpy + ly * off)));

        // Approximate the dot's on-screen radius so the underline matches it.
        double dotUniv = Math.min(3.0, 1.5 + 0.8 * focus.factor());
        int dotR = Math.max(2, (int) Math.round(dotUniv * dotSizeMul * univScale() / 2.0));

        final int red = 0xFFFF0000;   // very red

        // Underline directly beneath the dot, about as wide as the dot, centred on cx.
        int uw = Math.max(4, dotR * 2);
        int uy = cy + dotR + 2;
        g.fill(cx - uw / 2, uy, cx - uw / 2 + uw, uy + 2, red);

        // A clean upward arrow (sharp head + thin shaft) below the underline,
        // apex pointing up at the dot. Fixed size so it stays crisp, not blocky.
        int ay = uy + 4;              // apex (top of the head)
        int headH = 7, headHalf = 4; // arrow head
        int shaftLen = 5;            // arrow shaft
        fillTriangle(g, cx, ay, cx - headHalf, ay + headH, cx + headHalf, ay + headH, red);
        g.fill(cx - 1, ay + headH, cx + 2, ay + headH + shaftLen, red);   // 3px shaft, centred on cx
    }

    private void drawInfoPanel(GuiGraphicsExtractor g) {
        if (focus == null) return;
        long sys = focus.seed();
        int w = 196;
        int x = this.width - w - 12;
        int y = 40;
        int h = (selectedPlanet >= 0) ? 176 : 140;   // extra room for discovery status + Share
        g.fill(x, y, x + w, y + h, 0xE0060812);
        g.fill(x, y, x + w, y + 1, 0xFF3A3A6A);
        g.fill(x, y + h - 1, x + w, y + h, 0xFF3A3A6A);
        g.fill(x, y, x + 1, y + h, 0xFF3A3A6A);
        g.fill(x + w - 1, y, x + w, y + h, 0xFF3A3A6A);

        int tx = x + 12;
        int ty = y + 10;
        if (selectedPlanet >= 0) {
            long pseed = UniverseMath.planetSeed(sys, selectedPlanet);
            g.centeredText(this.font, I18n.get("gui.universemod.planet"), x + w / 2, ty, 0xFFFFD070); ty += 12;
            g.centeredText(this.font, NameGenerator.planetName(sys, selectedPlanet), x + w / 2, ty, 0xFFFFFFFF); ty += 20;
            g.text(this.font, I18n.get("gui.universemod.seed"), tx, ty, 0xFF7FA8FF);
            boolean copied = System.currentTimeMillis() - seedCopiedMs < 1200;
            int[] cr = seedCopyRect();
            g.text(this.font, I18n.get(copied ? "gui.universemod.copied" : "gui.universemod.copy_seed"),
                    cr[0], ty, copied ? 0xFF7DE0A0 : 0xFF8AB0FF);
            ty += 11;
            g.text(this.font, Long.toString(pseed), tx, ty, 0xFFFFFFFF); ty += 18;
            g.text(this.font, I18n.get("gui.universemod.distance"), tx, ty, 0xFF7FA8FF); ty += 11;
            long cur = worldSeed();
            int curIdx = UniverseMath.planetIndexInSystem(sys, cur);
            String distText; int distColor = 0xFFFFFFFF;
            if (cur == pseed) {                                  // the exact planet you're on
                distText = I18n.get("gui.universemod.you_are_here"); distColor = 0xFF7DE0A0;
            } else if (curIdx >= 0) {                            // sibling planet, same system
                distText = UniverseMath.formatAu(
                        UniverseMath.intraSystemDistanceAu(sys, selectedPlanet, curIdx));
            } else {                                             // a different star system
                distText = UniverseMath.formatDistance(UniverseMath.travelDistanceLy(sys, pseed, cur));
            }
            g.text(this.font, distText, tx, ty, distColor); ty += 18;
            g.text(this.font, I18n.get("gui.universemod.host_star"), tx, ty, 0xFF7FA8FF); ty += 11;
            g.text(this.font, NameGenerator.starType(sys), tx, ty, 0xFFFFFFFF); ty += 18;

            // Exploration claim: who first travelled here (fetched from the server).
            String disc = Discoveries.get(pseed);
            if (disc == null) {
                if (Discoveries.shouldRequest(pseed)) {
                    ClientPlayNetworking.send(new RequestDiscoveryPacket(pseed));
                }
                g.text(this.font, I18n.get("gui.universemod.status"), tx, ty, 0xFF7FA8FF); ty += 11;
                g.text(this.font, I18n.get("gui.universemod.checking"), tx, ty, 0xFFBFC6FF);
            } else if (disc.isEmpty()) {
                g.text(this.font, I18n.get("gui.universemod.status"), tx, ty, 0xFF7FA8FF); ty += 11;
                g.text(this.font, I18n.get("gui.universemod.uncharted"), tx, ty, 0xFFFFB060);
            } else {
                g.text(this.font, I18n.get("gui.universemod.discovered_by"), tx, ty, 0xFF7FA8FF); ty += 11;
                g.text(this.font, disc, tx, ty, 0xFF7DE0A0);
            }

            // Share-beacon button along the bottom of the panel.
            int[] sb = shareButtonRect();
            g.fill(sb[0], sb[1], sb[0] + sb[2], sb[1] + sb[3], 0xFF2A4A8A);
            g.fill(sb[0], sb[1], sb[0] + sb[2], sb[1] + 1, 0xFF6AC0FF);
            g.centeredText(this.font, I18n.get("gui.universemod.share"),
                    sb[0] + sb[2] / 2, sb[1] + (sb[3] - 8) / 2, 0xFFFFFFFF);
        } else {
            g.centeredText(this.font, I18n.get("gui.universemod.star_system"), x + w / 2, ty, 0xFFFFD070); ty += 12;
            g.centeredText(this.font, NameGenerator.systemName(sys), x + w / 2, ty, 0xFFFFFFFF); ty += 20;
            g.text(this.font, I18n.get("gui.universemod.star_type"), tx, ty, 0xFF7FA8FF); ty += 11;
            g.text(this.font, NameGenerator.starType(sys), tx, ty, 0xFFFFFFFF); ty += 18;
            g.text(this.font, I18n.get("gui.universemod.planets_count"), tx, ty, 0xFF7FA8FF); ty += 11;
            g.text(this.font, Integer.toString(systemPlanets), tx, ty, 0xFFFFFFFF); ty += 18;
            g.text(this.font, I18n.get("gui.universemod.distance"), tx, ty, 0xFF7FA8FF); ty += 11;
            if (UniverseMath.planetIndexInSystem(sys, worldSeed()) >= 0) {
                g.text(this.font, I18n.get("gui.universemod.current_system"), tx, ty, 0xFF7DE0A0);
            } else {
                double ly = UniverseMath.distanceLightYears(sys, UniverseMath.systemForPlanetSeed(worldSeed(), 0));
                g.text(this.font, UniverseMath.formatDistance(ly), tx, ty, 0xFFFFFFFF);
            }
        }
    }

    /** Clickable "[copy]" hit box next to the SEED label in the planet info panel. */
    private int[] seedCopyRect() {
        int w = 196, x = this.width - w - 12, y = 40;
        // SEED label sits on the third line of the panel (y+10, +12, +20 = y+42).
        return new int[]{x + w - 52, y + 42, 44, 10};
    }

    /** Copy the focused planet's seed to the system clipboard. */
    private void copyPlanetSeed() {
        if (focus == null || focus.bh() || selectedPlanet < 0) return;
        long pseed = UniverseMath.planetSeed(focus.seed(), selectedPlanet);
        if (minecraft != null) minecraft.keyboardHandler.setClipboard(Long.toString(pseed));
        seedCopiedMs = System.currentTimeMillis();
        playClick();
    }

    /** "Share beacon" button along the bottom of the planet info panel. */
    private int[] shareButtonRect() {
        int w = 196, x = this.width - w - 12, y = 40, h = 176;
        return new int[]{x + 8, y + h - 18, w - 16, 14};
    }

    /** Broadcast the focused planet to the whole server as a clickable beacon. */
    private void shareBeacon() {
        if (focus == null || focus.bh() || selectedPlanet < 0) return;
        long pseed = UniverseMath.planetSeed(focus.seed(), selectedPlanet);
        String name = NameGenerator.planetName(focus.seed(), selectedPlanet);
        ClientPlayNetworking.send(new ShareBeaconPacket(pseed, name));
        playClick();
    }

    private long galaxyLocalOf(long seed) {
        long block = UniverseMath.STARS_PER_GALAXY * UniverseMath.GALAXIES_PER_CLUSTER;
        return Long.divideUnsigned(Long.remainderUnsigned(seed, block), UniverseMath.STARS_PER_GALAXY);
    }

    private GalaxyView galaxyField(long galaxyId) {
        return galaxyCache.computeIfAbsent(galaxyId, id -> {
            long local = id - clusterIndex * UniverseMath.GALAXIES_PER_CLUSTER;
            return GalaxyView.galaxy(clusterIndex, local);
        });
    }

    private void easeZoom(double dt) {
        if (Math.abs(scale - targetScale) > 1e-6) {
            double k = 1.0 - Math.exp(-dt * 9.0);
            scale += (targetScale - scale) * k;
            if (Math.abs(scale - targetScale) / targetScale < 0.001) scale = targetScale;
        }
        if (anchoring && focus == null) {
            camX = focusWorldX - (focusScreenX - uWidth / 2.0) / scale;
            camY = focusWorldY - (focusScreenY - uHeight / 2.0) / scale;
        }
    }

    // ------------------------------------------------------------------
    // Cube (a Minecraft world) - drawn on the GUI layer (single object)
    // ------------------------------------------------------------------

    private void drawCube(GuiGraphicsExtractor g, double cx, double cy, double sizeHalf, int color, double alpha) {
        double w = sizeHalf;
        double th = sizeHalf * 0.55;
        double sh = sizeHalf * 1.15;
        double topY = cy - sh / 2.0;

        double[] P0 = {cx, topY - th};
        double[] P1 = {cx + w, topY};
        double[] P2 = {cx, topY + th};
        double[] P3 = {cx - w, topY};
        double[] P1d = {cx + w, topY + sh};
        double[] P2d = {cx, topY + th + sh};
        double[] P3d = {cx - w, topY + sh};

        int top = scaleColor(color, 1.15);
        int right = scaleColor(color, 0.82);
        int left = scaleColor(color, 0.58);

        fillQuad(g, P1, P2, P2d, P1d, argb(alpha, right));
        fillQuad(g, P3, P2, P2d, P3d, argb(alpha, left));
        fillQuad(g, P0, P1, P2, P3, argb(alpha, top));

        int grid = argb(alpha * 0.22, 0x101018);
        int edge = argb(alpha * 0.85, 0x0A0A12);
        int nn = 4;
        for (int i = 1; i < nn; i++) {
            double t = (double) i / nn;
            line(g, lerp(P0, P1, t), lerp(P3, P2, t), grid);
            line(g, lerp(P0, P3, t), lerp(P1, P2, t), grid);
            line(g, lerp(P1, P2, t), lerp(P1d, P2d, t), grid);
            line(g, lerp(P1, P1d, t), lerp(P2, P2d, t), grid);
            line(g, lerp(P3, P2, t), lerp(P3d, P2d, t), grid);
            line(g, lerp(P3, P3d, t), lerp(P2, P2d, t), grid);
        }
        line(g, P0, P1, edge); line(g, P1, P1d, edge); line(g, P1d, P2d, edge);
        line(g, P2d, P3d, edge); line(g, P3d, P3, edge); line(g, P3, P0, edge);
        line(g, P1, P2, edge); line(g, P3, P2, edge); line(g, P2, P2d, edge);
    }

    private static double[] lerp(double[] a, double[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t};
    }

    private void fillQuad(GuiGraphicsExtractor g, double[] a, double[] b, double[] c, double[] d, int argb) {
        fillTriangle(g, a[0], a[1], b[0], b[1], c[0], c[1], argb);
        fillTriangle(g, a[0], a[1], c[0], c[1], d[0], d[1], argb);
    }

    private void fillTriangle(GuiGraphicsExtractor g, double x0, double y0,
                              double x1, double y1, double x2, double y2, int argb) {
        if (y1 < y0) { double t; t = x0; x0 = x1; x1 = t; t = y0; y0 = y1; y1 = t; }
        if (y2 < y0) { double t; t = x0; x0 = x2; x2 = t; t = y0; y0 = y2; y2 = t; }
        if (y2 < y1) { double t; t = x1; x1 = x2; x2 = t; t = y1; y1 = y2; y2 = t; }
        int yStart = Math.max(0, (int) Math.floor(y0));
        int yEnd = Math.min(this.height, (int) Math.ceil(y2));
        for (int y = yStart; y < yEnd; y++) {
            double yc = y + 0.5;
            if (yc < y0 || yc > y2) continue;
            double xa = edgeX(y0, x0, y2, x2, yc);
            double xb = (yc < y1) ? edgeX(y0, x0, y1, x1, yc) : edgeX(y1, x1, y2, x2, yc);
            int xl = Math.max(0, (int) Math.round(Math.min(xa, xb)));
            int xr = Math.min(this.width, (int) Math.round(Math.max(xa, xb)));
            if (xr > xl) g.fill(xl, y, xr, y + 1, argb);
        }
    }

    private static double edgeX(double y0, double x0, double y1, double x1, double y) {
        if (y1 == y0) return x0;
        return x0 + (x1 - x0) * (y - y0) / (y1 - y0);
    }

    private void line(GuiGraphicsExtractor g, double[] a, double[] b, int argb) {
        int x0 = (int) Math.round(a[0]), y0 = (int) Math.round(a[1]);
        int x1 = (int) Math.round(b[0]), y1 = (int) Math.round(b[1]);
        int dx = Math.abs(x1 - x0), dy = -Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1, sy = y0 < y1 ? 1 : -1;
        int err = dx + dy, guard = 0;
        while (guard++ < 6000) {
            if (x0 >= 0 && x0 < this.width && y0 >= 0 && y0 < this.height) g.fill(x0, y0, x0 + 1, y0 + 1, argb);
            if (x0 == x1 && y0 == y1) break;
            int e2 = 2 * err;
            if (e2 >= dy) { err += dy; x0 += sx; }
            if (e2 <= dx) { err += dx; y0 += sy; }
        }
    }

    private void tooltip(GuiGraphicsExtractor g, int mouseX, int mouseY, String text) {
        int w = this.font.width(text);
        int x = mouseX + 10, y = mouseY - 12;
        g.fill(x - 3, y - 3, x + w + 3, y + 11, 0xE0000010);
        g.fill(x - 3, y - 3, x + w + 3, y - 2, 0xFF3A3A6A);
        g.fill(x - 3, y + 10, x + w + 3, y + 11, 0xFF3A3A6A);
        g.text(this.font, text, x, y, 0xFFFFFFFF);
    }

    private void drawDisc(GuiGraphicsExtractor g, double cx, double cy, double r, int argb) {
        if (r < 0.5) return;
        int ri = (int) Math.ceil(r);
        int top = Math.max(0, (int) (cy - ri));
        int bot = Math.min(this.height, (int) (cy + ri) + 1);
        for (int y = top; y < bot; y++) {
            double dy = y + 0.5 - cy;
            double h2 = r * r - dy * dy;
            if (h2 <= 0) continue;
            double half = Math.sqrt(h2);
            int x1 = Math.max(0, (int) Math.round(cx - half));
            int x2 = Math.min(this.width, (int) Math.round(cx + half));
            if (x2 <= x1) continue;
            g.fill(x1, y, x2, y + 1, argb);
        }
    }

    private static int lighten(int rgb) {
        int r = Math.min(255, ((rgb >> 16) & 0xFF) + 70);
        int gg = Math.min(255, ((rgb >> 8) & 0xFF) + 70);
        int b = Math.min(255, (rgb & 0xFF) + 70);
        return (r << 16) | (gg << 8) | b;
    }

    private static int argb(double a, int rgb) {
        int ai = Math.max(0, Math.min(255, (int) (a * 255)));
        return (ai << 24) | (rgb & 0xFFFFFF);
    }

    private static int scaleColor(int rgb, double f) {
        int r = Math.min(255, (int) (((rgb >> 16) & 0xFF) * f));
        int gg = Math.min(255, (int) (((rgb >> 8) & 0xFF) * f));
        int b = Math.min(255, (int) ((rgb & 0xFF) * f));
        return (r << 16) | (gg << 8) | b;
    }

    private static double smoothstep(double e0, double e1, double x) {
        double t = Math.max(0, Math.min(1, (x - e0) / (e1 - e0)));
        return t * t * (3 - 2 * t);
    }

    // ------------------------------------------------------------------
    // Picking
    // ------------------------------------------------------------------

    private StarPoint pickCluster(double mouseX, double mouseY) {
        double mx = toUnivX(mouseX);
        double my = toUnivY(mouseY);
        updateUniverseTrig();
        double cx = uWidth / 2, cy = uHeight / 2;
        double radius = Math.min(uWidth, uHeight) * 0.42 * uZoom;
        StarPoint best = null;
        double bestD = 8.0;
        double bestPersp = -1;
        for (StarPoint s : universeView.dots()) {
            double persp = up3persp(s.px(), s.py(), s.pz());
            double sx = cx + up3x(s.px(), s.py(), s.pz()) * radius;
            double sy = cy + up3y(s.px(), s.py(), s.pz()) * radius;
            double d = Math.hypot(sx - mx, sy - my);
            // Nearest to the cursor; when two overlap, prefer the front (nearer) one.
            if (d < bestD || (d < 8.0 && Math.abs(d - bestD) < 3.0 && persp > bestPersp)) {
                bestD = d; best = s; bestPersp = persp;
            }
        }
        return best;
    }

    private int pickStar(double mouseX, double mouseY) {
        double mx = toUnivX(mouseX);
        double my = toUnivY(mouseY);
        int best = -1;
        double bestD = Double.MAX_VALUE;
        for (int i = 0; i < rsCount; i++) {
            double d = Math.hypot(rsX[i] - mx, rsY[i] - my);
            if (d <= rsR[i] && d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** The galaxy dot nearest the cursor (within a click tolerance), or null. */
    private StarPoint pickGalaxy(double mouseX, double mouseY) {
        if (clusterGalaxies == null) return null;
        double mx = toUnivX(mouseX);
        double my = toUnivY(mouseY);
        StarPoint best = null;
        double bestD = 26.0;   // click tolerance in universe px
        for (StarPoint gx : clusterGalaxies.dots()) {
            double d = Math.hypot(w2sx(clX(gx), clY(gx)) - mx, w2sy(clX(gx), clY(gx)) - my);
            if (d < bestD) { bestD = d; best = gx; }
        }
        if (pinnedGalaxy != null) {
            double d = Math.hypot(w2sx(clX(pinnedGalaxy), clY(pinnedGalaxy)) - mx,
                    w2sy(clX(pinnedGalaxy), clY(pinnedGalaxy)) - my);
            if (d < bestD) best = pinnedGalaxy;
        }
        return best;
    }

    // ------------------------------------------------------------------
    // Input
    // ------------------------------------------------------------------

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        // Catch recent-search dropdown clicks before the search box loses focus.
        if (event.button() == 0 && searchDropdownVisible()) {
            int idx = searchDropdownIndexAt(event.x(), event.y());
            if (idx >= 0) {
                applySearchHistory(idx);
                return true;
            }
        }
        if (super.mouseClicked(event, doubleClick)) return true;
        // Quality slider grabs the click before the map's pan/rotate handling.
        if (tab == Tab.MAP && event.button() == 0 && inSliderArea(event.x(), event.y())) {
            draggingSlider = true;
            setSliderFromMouse(event.x());
            return true;
        }
        pressX = event.x();
        pressY = event.y();
        dragged = false;
        return true;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (draggingSlider) {
            setSliderFromMouse(event.x());
            return true;
        }
        if (tab != Tab.MAP) return true;   // no map manipulation off the map tab
        if (Math.hypot(event.x() - pressX, event.y() - pressY) > 4) dragged = true;
        // While inspecting a solar system, block all galaxy manipulation so a stray
        // drag can't pan/rotate away or release the focus and dump you back out.
        if (systemActive) return true;
        int b = event.button();
        if (b == 2) {
            if (clusterMode) {
                // Slow the 3D rotation the closer you are (the deeper the zoom), so
                // nearby galaxies don't whip across the screen. Full speed at the
                // cluster overview, progressively gentler as you zoom into a galaxy.
                double rotSpeed = Math.max(0.03, Math.min(1.0, (GAL_DETAIL_MIN / GALAXY_R) / scale));
                cluYaw += dragX * 0.008 * rotSpeed;
                cluPitch = Math.max(-1.4, Math.min(1.4, cluPitch + dragY * 0.008 * rotSpeed));
            } else {
                uYaw += dragX * 0.01;
                uPitch = Math.max(-1.4, Math.min(1.4, uPitch + dragY * 0.01));
            }
            return true;
        }
        if (b == 0 && clusterMode) {
            double scaleFactor = uWidth / (double) this.width;
            camX -= (dragX * scaleFactor) / scale;
            camY -= (dragY * scaleFactor) / scale;
            anchoring = false;
            focus = null;          // manual movement releases the orbit lock
            galaxyLocked = false;  // ...and the galaxy pivot lock
            selectedPlanet = -1;
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (draggingSlider) {
            draggingSlider = false;
            return super.mouseReleased(event);
        }
        if (!dragged && event.button() == 0) {
            // Top tabs take clicks first, in any view.
            if (inRect(event.x(), event.y(), tabRect(0))) { setTab(Tab.MAP); return super.mouseReleased(event); }
            if (inRect(event.x(), event.y(), tabRect(1))) { setTab(Tab.FAVORITES); return super.mouseReleased(event); }
            if (inRect(event.x(), event.y(), tabRect(2))) { setTab(Tab.PLAYERS); return super.mouseReleased(event); }

            // On the Favourites/Latest tab everything is handled by that screen.
            if (tab == Tab.FAVORITES) {
                favoritesClick(event.x(), event.y());
                return super.mouseReleased(event);
            }

            // On the Players tab, clicks hit the Join buttons.
            if (tab == Tab.PLAYERS) {
                playersClick(event.x(), event.y());
                return super.mouseReleased(event);
            }

            // Favourite toggle (star the focused planet / galaxy).
            if ((canFavPlanet() || canFavGalaxy()) && inRect(event.x(), event.y(), favToggleRect())) {
                if (canFavPlanet()) Favorites.togglePlanet(focus.seed(), selectedPlanet);
                else Favorites.toggleGalaxy(pinnedGalaxy.id());
                playClick();
                return super.mouseReleased(event);
            }

            // History button + panel take clicks next.
            int[] hb = historyButtonRect();
            if (inRect(event.x(), event.y(), hb[0], hb[1], hb[2], hb[3])) {
                historyOpen = !historyOpen;
                playClick();
                return super.mouseReleased(event);
            }
            if (historyOpen) {
                int hi = historyEntryAt(event.x(), event.y());
                if (hi >= 0) {
                    playClick();
                    goToVisited(VisitedPlanets.recent().get(hi));
                } else {
                    historyOpen = false;   // click elsewhere dismisses the panel
                }
                return super.mouseReleased(event);
            }
            if (clusterMode) {
                if (systemActive) {
                    // Copy-seed button in the planet info panel takes clicks first.
                    if (selectedPlanet >= 0 && focus != null && !focus.bh()
                            && inRect(event.x(), event.y(), seedCopyRect())) {
                        copyPlanetSeed();
                        return super.mouseReleased(event);
                    }
                    // Share-beacon button in the planet info panel.
                    if (selectedPlanet >= 0 && focus != null && !focus.bh()
                            && inRect(event.x(), event.y(), shareButtonRect())) {
                        shareBeacon();
                        return super.mouseReleased(event);
                    }
                    // Travel modal takes clicks first when it's showing.
                    if (travelModalVisible()) {
                        int[] b = travelModalButtons();
                        if (inRect(event.x(), event.y(), b[0], b[2], b[3], b[4])) { // Yes
                            confirmTravel();
                            return super.mouseReleased(event);
                        }
                        if (inRect(event.x(), event.y(), b[1], b[2], b[3], b[4])) { // No
                            selectedPlanet = -1;
                            return super.mouseReleased(event);
                        }
                    }
                    int p = pickPlanet(event.x(), event.y()); // -1 = clicked empty (deselect)
                    if (p >= 0 && focus != null && !focus.bh()) {
                        long now = System.currentTimeMillis();
                        if (p == lastPlanetClicked && now - lastPlanetClickMs < DOUBLE_CLICK_MS) {
                            // Double-click a planet: travel straight away, skip the confirm modal.
                            selectedPlanet = p;
                            lastPlanetClicked = -1;
                            confirmTravel();
                            return super.mouseReleased(event);
                        }
                        lastPlanetClicked = p;
                        lastPlanetClickMs = now;
                    }
                    selectedPlanet = p;
                } else {
                    int i = pickStar(event.x(), event.y());
                    if (i >= 0) {
                        focus = new FocusedPlanet(rsSeed[i], rsGx3[i], rsGy3[i], rsGz3[i],
                                rsLx[i], rsLy[i], rsFactor[i], rsColor[i],
                                UniverseMath.isStellarBlackHole(rsSeed[i]));
                        selectedPlanet = -1;
                        bhAlpha = 0;   // black-hole lens eases in from nothing
                    } else {
                        // No star hit: try to focus a galaxy so rotation pivots on it.
                        StarPoint g = pickGalaxy(event.x(), event.y());
                        if (g != null) {
                            pinnedGalaxy = g;
                            galaxyLocked = true;
                            focus = null;
                        } else {
                            focus = null;
                            galaxyLocked = false;   // clicked empty space: unlock
                        }
                    }
                }
            } else {
                StarPoint c = pickCluster(event.x(), event.y());
                if (c != null) enterCluster(c.id());
            }
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        // Favourites/Latest tab: scroll whichever list the cursor is over.
        if (tab == Tab.FAVORITES) {
            int[] lp = favLeftPanel(), rp = favRightPanel();
            double amt = -scrollY * FAV_ROW_H * 2.0;
            if (mouseX < lp[0] + lp[2]) {
                int count = (favSub == FavSub.GALAXIES ? Favorites.galaxies().size() : Favorites.planets().size());
                double maxS = Math.max(0, count * FAV_ROW_H - (lp[3] - 46));
                favScroll = Math.max(0, Math.min(maxS, favScroll + amt));
            } else {
                int count = VisitedPlanets.recent().size();
                double maxS = Math.max(0, count * FAV_ROW_H - (rp[3] - 30));
                latestScroll = Math.max(0, Math.min(maxS, latestScroll + amt));
            }
            return true;
        }
        // Players tab: scroll the roster list.
        if (tab == Tab.PLAYERS) {
            int[] p = playersPanel();
            int visible = p[3] - 26;
            double maxS = Math.max(0, ServerPlayers.get().size() * PLAYER_ROW_H - visible);
            playersScroll = Math.max(0, Math.min(maxS, playersScroll - scrollY * PLAYER_ROW_H * 2.0));
            return true;
        }
        // Solar-system view is a locked context, but scrolling out of it should
        // release the lock and zoom back to the galaxy (scrolling in stays put).
        if (systemActive) {
            if (scrollY < 0) exitSystemView();
            return true;
        }
        if (clusterMode) {
            // Zooming back in toward the star re-enables the solar-system view.
            if (scrollY > 0) systemSuppressed = false;
            double factor = Math.pow(1.25, scrollY);
            // Allow pulling back further within a cluster before dropping to the
            // supercluster screen - the cluster is spread out now, so a galaxy that
            // sits close to the camera needs more room to zoom out and frame it.
            double min = fitScale() * 0.15;
            double max = fitScale() * 5.0e8; // deep enough for planets to spread far apart
            double newTarget = Math.max(min, Math.min(max, targetScale * factor));
            if (scrollY < 0 && targetScale <= min * 1.02 && newTarget <= min * 1.02) {
                goToUniverse();
                return true;
            }
            if (focus == null) {
                if (scrollY > 0) {
                    // Zoom IN toward the cursor (camera converges as scale grows).
                    double umx = toUnivX(mouseX);
                    double umy = toUnivY(mouseY);
                    focusWorldX = s2wx(umx, umy);
                    focusWorldY = s2wy(umx, umy);
                    focusScreenX = umx;
                    focusScreenY = umy;
                    anchoring = true;
                } else {
                    // Zoom OUT around the current centre. Anchoring to the cursor
                    // here would divide the off-centre offset by an ever-smaller
                    // scale and fling the camera far away, so the galaxy would shoot
                    // off-screen and vanish. Pulling back around the centre keeps it.
                    anchoring = false;
                }
            }
            targetScale = newTarget;
        } else {
            uZoom = Math.max(0.6, Math.min(6.0, uZoom + scrollY * 0.35));
        }
        return true;
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // While typing in the search box, let it handle keys (Enter = travel).
        if (searchBox != null && searchBox.isFocused()) {
            int k = event.key();
            if (k == GLFW.GLFW_KEY_ENTER || k == GLFW.GLFW_KEY_KP_ENTER) {
                if (previewMatch != null) SearchHistory.record(searchBox.getValue());
                navigateTo(previewMatch);
                return true;
            }
            if (k == GLFW.GLFW_KEY_ESCAPE) {   // Escape just drops focus from the box
                searchBox.setFocused(false);
                setFocused(null);
                return true;
            }
            return super.keyPressed(event);
        }
        if (event.key() == GLFW.GLFW_KEY_BACKSPACE) {
            stepBack();   // Backspace walks up the hierarchy but never closes the map
            return true;
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            // Escape walks up the hierarchy one level; only closes at the very top.
            if (stepBack()) playClick();
            else onClose();
            return true;
        }
        return super.keyPressed(event);
    }

    /** Let Escape step back through the map instead of closing on the first press. */
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    /**
     * Walk one level up the map hierarchy:
     * planet -> star system -> galaxy -> cluster -> universe.
     * Returns false only when already at the top (the universe / supercluster
     * selection screen), so the caller can decide whether to close the map.
     */
    private boolean stepBack() {
        if (tab != Tab.MAP) { setTab(Tab.MAP); return true; }
        if (!clusterMode) return false;                 // universe view is the top

        if (selectedPlanet >= 0) { selectedPlanet = -1; return true; }

        // 1) In the solar-system / black-hole view: pull back toward the galaxy.
        if (focus != null && !systemSuppressed) { exitSystemView(); return true; }

        // 2) Orbit-locked on a star (system hidden): release it, frame the galaxy.
        if (focus != null) {
            focus = null;
            systemSuppressed = false;
            anchoring = false;
            zoomToGalaxyOverview();
            return true;
        }

        // 3) Zoomed into a galaxy: pull back to the whole cluster.
        double clusterScale = fitScale() * 0.6;
        if (galaxyLocked || pinnedGalaxy != null || targetScale > clusterScale * 2.0) {
            galaxyLocked = false;
            pinnedGalaxy = null;
            anchoring = false;
            zoomToClusterOverview();
            return true;
        }

        // 4) Cluster overview: back out to the universe.
        goToUniverse();
        return true;
    }

    /** Frame a single galaxy (used when stepping back out of a star system). */
    private void zoomToGalaxyOverview() {
        targetScale = 250.0 / GALAXY_R;
        if (pinnedGalaxy != null) { camX = clX(pinnedGalaxy); camY = clY(pinnedGalaxy); }
    }

    /** Frame the whole cluster (used when stepping back out of a galaxy). */
    private void zoomToClusterOverview() {
        camX = 0; camY = 0;
        targetScale = fitScale() * 0.6;
        systemSuppressed = false;
    }

    @Override
    public void onClose() {
        saveView();   // remember where we are so reopening resumes here
        // Clear our space ping for everyone else now that the map is closing.
        if (pingLevelSent != PingFocusPacket.LEVEL_NONE) {
            ClientPlayNetworking.send(new PingFocusPacket(PingFocusPacket.LEVEL_NONE, 0, -1));
            pingLevelSent = PingFocusPacket.LEVEL_NONE;
        }
        if (renderer != null) {
            renderer.close();
            renderer = null;
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ------------------------------------------------------------------
    // Records
    // ------------------------------------------------------------------

    private record FocusedPlanet(long seed, double gx3, double gy3, double gz3,
                                 double lx0, double ly0, double factor, int color, boolean bh) {}
}
