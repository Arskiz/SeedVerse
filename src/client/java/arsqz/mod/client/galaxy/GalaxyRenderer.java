package arsqz.mod.client.galaxy;

import arsqz.mod.TwoSixTwo_Arskiz;

import com.mojang.blaze3d.GpuFormat;
import com.mojang.blaze3d.opengl.GlTextureView;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.textures.GpuTextureView;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;

/**
 * GPU renderer for the map:
 * <ul>
 *   <li>Pass 1: draw the scene (star points, sun/planet triangles, edge lines)
 *       into an off-screen target.</li>
 *   <li>Pass 2 (optional): a full-screen black-hole shader samples pass 1 and
 *       applies gravitational lensing, an event horizon, a photon ring and a
 *       Doppler-boosted accretion disk for each black hole in view.</li>
 * </ul>
 * The screen then blits the final target once.
 */
public final class GalaxyRenderer implements AutoCloseable {

    public static final int PT_STRIDE = 7;    // x,y,size,r,g,b,a
    public static final int SOLID_STRIDE = 6; // x,y,r,g,b,a
    public static final int MAX_BH = 32;

    private static final int GL_PROGRAM_POINT_SIZE = 0x8642;

    private static final String PT_VERT = """
            #version 150 core
            in vec2 aPos; in float aSize; in vec4 aColor;
            uniform vec2 uRes; out vec4 vColor;
            void main() {
                gl_Position = vec4(aPos.x/uRes.x*2.0-1.0, aPos.y/uRes.y*2.0-1.0, 0.0, 1.0);
                gl_PointSize = aSize; vColor = aColor;
            }
            """;
    private static final String PT_FRAG = """
            #version 150 core
            in vec4 vColor; out vec4 fragColor;
            void main() {
                float d = length(gl_PointCoord*2.0-1.0);
                if (d > 1.0) discard;
                float core = smoothstep(1.0, 0.45, d);
                float halo = 0.18*(1.0-d)*(1.0-d);
                fragColor = vec4(vColor.rgb, vColor.a*clamp(core+halo,0.0,1.0));
            }
            """;
    private static final String SOLID_VERT = """
            #version 150 core
            in vec2 aPos; in vec4 aColor;
            uniform vec2 uRes; out vec4 vColor;
            void main() {
                gl_Position = vec4(aPos.x/uRes.x*2.0-1.0, aPos.y/uRes.y*2.0-1.0, 0.0, 1.0);
                vColor = aColor;
            }
            """;
    private static final String SOLID_FRAG = """
            #version 150 core
            in vec4 vColor; out vec4 fragColor;
            void main() { fragColor = vColor; }
            """;

    // Full-screen triangle; vUv is 0..1 in the target's own texel space.
    private static final String BH_VERT = """
            #version 150 core
            out vec2 vUv;
            void main() {
                vec2 p = vec2(gl_VertexID==1 ? 3.0 : -1.0, gl_VertexID==2 ? 3.0 : -1.0);
                vUv = vec2(gl_VertexID==1 ? 2.0 : 0.0, gl_VertexID==2 ? 2.0 : 0.0);
                gl_Position = vec4(p, 0.0, 1.0);
            }
            """;
    // Per-black-hole variant ids (must match NameGenerator.BH_*).
    //   0 Kerr, 1 Quasar/AGN, 2 Dormant, 3 Micro/primordial
    private static final String BH_FRAG = """
            #version 150 core
            in vec2 vUv; out vec4 fragColor;
            uniform sampler2D uScene;
            uniform vec2 uRes;
            uniform float uTime;
            uniform int uCount;
            uniform vec3 uBHPos[32];    // x, y (px), Rs (px)
            uniform vec3 uBHCol[32];    // base / disk colour
            uniform int uBHType[32];    // 0 Kerr, 1 Quasar, 2 Dormant, 3 Micro
            uniform vec4 uBHParam[32];  // x spin, y jet, z diskInner(Rs), w diskOuter(Rs)

            float hash(vec2 p){ return fract(sin(dot(p, vec2(127.1,311.7)))*43758.5453); }
            float vnoise(vec2 p){
                vec2 i = floor(p), f = fract(p);
                float a = hash(i), b = hash(i+vec2(1.0,0.0));
                float c = hash(i+vec2(0.0,1.0)), d = hash(i+vec2(1.0,1.0));
                vec2 u = f*f*(3.0-2.0*f);
                return mix(mix(a,b,u.x), mix(c,d,u.x), u.y);
            }

            // Micro holes wobble; returns the (possibly modulated) horizon radius.
            float effRs(int i, float Rs){
                if (uBHType[i]==3) return Rs * (1.0 + 0.15*sin(uTime*7.0 + float(i)));
                return Rs;
            }

            void main() {
                vec2 px = vUv * uRes;

                // --- 1) Background gravitational lensing -------------------------
                // Bend the starfield radially with an inverse-square deflection,
                // but confine it to the NEAR field (fade out by ~5 Rs). Otherwise
                // the deflection pushes the sparse background away over a huge
                // radius and carves that big artificial empty disc. Confining it
                // keeps distant stars in place and leaves only a tight shadow with
                // a lensed ring hugging the hole.
                vec2 warp = px;
                for (int i = 0; i < uCount; i++) {
                    vec2 d = px - uBHPos[i].xy;
                    float Rs = effRs(i, uBHPos[i].z);
                    float r = length(d) + 1e-3;
                    float rr = r / Rs;
                    float falloff = smoothstep(5.0, 2.2, rr);     // 1 near the hole, 0 by ~5 Rs
                    float defl = (Rs*Rs) / (r + 0.5*Rs) * falloff;
                    if (uBHType[i]==2) defl *= 1.5;               // dormant: distortion is all you see
                    defl = min(defl, r*0.98);
                    warp -= normalize(d) * defl;
                }
                vec3 col = texture(uScene, clamp(warp/uRes, 0.0, 1.0)).rgb;

                // --- 2) Per black hole features ----------------------------------
                for (int i = 0; i < uCount; i++) {
                    vec2 d = px - uBHPos[i].xy;
                    int type = uBHType[i];
                    float Rs = effRs(i, uBHPos[i].z);
                    float spin = uBHParam[i].x;
                    float jet = uBHParam[i].y;
                    float diskIn = uBHParam[i].z;
                    float diskOut = uBHParam[i].w;
                    vec3 base = uBHCol[i];

                    float r = length(d);
                    vec2 dd = d / Rs;
                    float rr = length(dd);
                    float ang = atan(dd.y, dd.x);
                    float sgn = spin < 0.0 ? -1.0 : 1.0;

                    // Shadow: darken the lensed background only within the photon-
                    // capture radius (~2.6 Rs; tighter for tiny micro holes). The
                    // soft edge plus the confined lensing above means stars sit
                    // naturally close around the hole instead of in a big empty disc.
                    float shadowR = (type==3) ? 1.5 : 2.6;
                    col *= smoothstep(shadowR - 0.30, shadowR + 0.05, rr);

                    // Photon ring at the shadow edge. Brightness varies around the
                    // ring via Doppler beaming (approaching side brighter), disk-plane
                    // concentration and a little turbulence, so it reads as a real
                    // asymmetric ring instead of a flat, perfectly uniform circle.
                    float ringW = (type==2) ? 0.34 : 0.22;
                    float ringGauss = exp(-pow((rr - shadowR)/ringW, 2.0));
                    float turb = 0.82 + 0.32 * vnoise(vec2(ang*3.0, uTime*0.25));
                    float ringBright;
                    if (type==2) {
                        ringBright = 0.7 * turb;                                  // dormant: soft, near-even
                    } else {
                        float dopRing = 0.5 + 0.5 * (-cos(ang)) * sgn;            // approaching side brighter
                        float planeRing = 0.28 + 0.72 * pow(abs(cos(ang)), 0.6); // brighter along disk plane
                        ringBright = (0.25 + 1.6*dopRing) * planeRing * turb * 1.25;
                    }
                    col += vec3(1.0,0.92,0.75) * ringGauss * ringBright;

                    // Accretion disk (everything except dormant holes).
                    if (type != 2) {
                        float er = length(vec2(dd.x, dd.y/0.28));         // squashed disk plane
                        float drag = spin * 0.6 / max(rr, 0.4);           // frame-dragging swirl
                        float innerA = diskIn * (1.0 - 0.22*spin*cos(ang)); // skewed inner edge
                        float disk = smoothstep(innerA, innerA+0.4, er)
                                   * (1.0 - smoothstep(diskOut-1.2, diskOut, er));
                        // The event horizon hides the far half of the disk that
                        // passes behind it (the near half still crosses in front),
                        // so the disk doesn't appear to shine through the shadow.
                        if (dd.y < 0.0) disk *= smoothstep(shadowR - 0.10, shadowR + 0.35, rr);
                        float swirl = 0.62 + 0.38*sin((ang+drag)*2.0 - uTime*(1.4+abs(spin)*2.2) + er*1.4);
                        // Doppler beaming: the side turning toward us is brighter & bluer.
                        float vdir = clamp(-dd.x/max(er,0.001), -1.0, 1.0) * sgn;
                        float beam = (type==0) ? 1.1 : 0.7;              // Kerr beams hardest
                        float doppler = max(0.0, 1.0 + beam*vdir);
                        vec3 diskCol = base;
                        diskCol = mix(diskCol, vec3(0.75,0.86,1.0), clamp(vdir,0.0,1.0)*0.8);
                        diskCol = mix(diskCol, vec3(1.0,0.45,0.25), clamp(-vdir,0.0,1.0)*0.7);
                        col += diskCol * disk * swirl * doppler * 1.3;

                        // Quasar/AGN: blinding core bloom + twin relativistic jets.
                        if (type==1) {
                            float core = exp(-pow((rr-2.6)/0.6, 2.0));
                            col += base * core * 4.0;                    // HDR-ish core
                            float axial = abs(dd.x);
                            float along = abs(dd.y);
                            float width = 0.18 + 0.03*along;
                            float jetShape = exp(-pow(axial/width, 2.0));
                            jetShape *= smoothstep(1.0, 3.0, along)
                                      * (1.0 - smoothstep(diskOut*3.0, diskOut*7.0, along));
                            float n = vnoise(vec2(dd.x*3.0, along*1.5 - uTime*3.0));
                            vec3 jetCol = mix(base, vec3(0.7,0.85,1.0), 0.6);
                            col += jetCol * jet * jetShape * (0.5 + 0.7*n);
                        }
                    }

                    // Micro/primordial: pulsing Hawking-radiation rim on the horizon.
                    if (type==3) {
                        float rim = exp(-pow((rr-1.1)/0.22, 2.0));
                        float pulse = 0.55 + 0.45*sin(uTime*9.0 + float(i)*1.7);
                        vec3 hawk = mix(vec3(0.6,0.85,1.0), vec3(1.0,1.0,1.0), pulse);
                        col += hawk * rim * (0.8 + 0.6*pulse);
                    }
                }
                fragColor = vec4(col, 1.0);
            }
            """;

    private boolean initialized = false, failed = false;

    private int ptProgram, ptVao, ptVbo, ptURes;
    private int solidProgram, solidVao, solidVbo, solidURes;
    private int bhProgram, bhVao, bhUScene, bhURes, bhUTime, bhUCount, bhUPos, bhUCol, bhUType, bhUParam;
    private int fbo;
    private FloatBuffer ptBuf, solidBuf;

    private TextureTarget targetA, targetB;
    private int curW = -1, curH = -1;

    private void ensureInit() {
        if (initialized || failed) return;
        try {
            ptProgram = link(PT_VERT, PT_FRAG, new String[]{"aPos", "aSize", "aColor"});
            ptURes = GL20.glGetUniformLocation(ptProgram, "uRes");
            ptVao = GL30.glGenVertexArrays();
            ptVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(ptVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ptVbo);
            int ps = PT_STRIDE * Float.BYTES;
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, ps, 0L);
            GL20.glVertexAttribPointer(1, 1, GL11.GL_FLOAT, false, ps, 2L * Float.BYTES);
            GL20.glVertexAttribPointer(2, 4, GL11.GL_FLOAT, false, ps, 3L * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);
            GL20.glEnableVertexAttribArray(2);

            solidProgram = link(SOLID_VERT, SOLID_FRAG, new String[]{"aPos", "aColor"});
            solidURes = GL20.glGetUniformLocation(solidProgram, "uRes");
            solidVao = GL30.glGenVertexArrays();
            solidVbo = GL15.glGenBuffers();
            GL30.glBindVertexArray(solidVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, solidVbo);
            int ss = SOLID_STRIDE * Float.BYTES;
            GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, ss, 0L);
            GL20.glVertexAttribPointer(1, 4, GL11.GL_FLOAT, false, ss, 2L * Float.BYTES);
            GL20.glEnableVertexAttribArray(0);
            GL20.glEnableVertexAttribArray(1);

            bhProgram = link(BH_VERT, BH_FRAG, new String[]{});
            bhUScene = GL20.glGetUniformLocation(bhProgram, "uScene");
            bhURes = GL20.glGetUniformLocation(bhProgram, "uRes");
            bhUTime = GL20.glGetUniformLocation(bhProgram, "uTime");
            bhUCount = GL20.glGetUniformLocation(bhProgram, "uCount");
            bhUPos = GL20.glGetUniformLocation(bhProgram, "uBHPos");
            bhUCol = GL20.glGetUniformLocation(bhProgram, "uBHCol");
            bhUType = GL20.glGetUniformLocation(bhProgram, "uBHType");
            bhUParam = GL20.glGetUniformLocation(bhProgram, "uBHParam");
            bhVao = GL30.glGenVertexArrays();

            GL30.glBindVertexArray(0);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0);

            fbo = GL30.glGenFramebuffers();
            ptBuf = MemoryUtil.memAllocFloat(8192 * PT_STRIDE);
            solidBuf = MemoryUtil.memAllocFloat(4096 * SOLID_STRIDE);

            initialized = true;
            TwoSixTwo_Arskiz.LOGGER.info("GalaxyRenderer initialized (points + solid + black-hole passes).");
        } catch (Throwable t) {
            failed = true;
            TwoSixTwo_Arskiz.LOGGER.error("GalaxyRenderer init failed.", t);
        }
    }

    private static int link(String vert, String frag, String[] attribs) {
        int vs = compile(GL20.GL_VERTEX_SHADER, vert);
        int fs = compile(GL20.GL_FRAGMENT_SHADER, frag);
        int p = GL20.glCreateProgram();
        GL20.glAttachShader(p, vs);
        GL20.glAttachShader(p, fs);
        for (int i = 0; i < attribs.length; i++) GL20.glBindAttribLocation(p, i, attribs[i]);
        GL20.glLinkProgram(p);
        if (GL20.glGetProgrami(p, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Link failed: " + GL20.glGetProgramInfoLog(p));
        }
        GL20.glDeleteShader(vs);
        GL20.glDeleteShader(fs);
        return p;
    }

    private static int compile(int type, String src) {
        int id = GL20.glCreateShader(type);
        GL20.glShaderSource(id, src);
        GL20.glCompileShader(id);
        if (GL20.glGetShaderi(id, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            throw new IllegalStateException("Shader compile failed: " + GL20.glGetShaderInfoLog(id));
        }
        return id;
    }

    private void ensureTargets(int w, int h) {
        if (targetA != null && curW == w && curH == h) return;
        if (targetA == null) targetA = new TextureTarget("universemod_scene", w, h, false, GpuFormat.RGBA8_UNORM);
        else targetA.resize(w, h);
        if (targetB == null) targetB = new TextureTarget("universemod_bh", w, h, false, GpuFormat.RGBA8_UNORM);
        else targetB.resize(w, h);
        curW = w;
        curH = h;
    }

    public boolean isUsable() { return !failed; }

    public GpuTextureView render(int w, int h,
                                 float[] pts, int ptCount,
                                 float[] tris, int triCount,
                                 float[] lines, int lineCount,
                                 float[] bhPos, float[] bhCol, int[] bhType, float[] bhParam,
                                 int bhCount, float time,
                                 float bgR, float bgG, float bgB) {
        ensureInit();
        if (failed) return null;
        try {
            ensureTargets(w, h);

            int prevFbo = GL11.glGetInteger(GL30.GL_FRAMEBUFFER_BINDING);
            int prevProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
            int prevVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
            int prevArray = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int[] vp = new int[4];
            GL11.glGetIntegerv(GL11.GL_VIEWPORT, vp);
            boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
            boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            boolean scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            boolean pps = GL11.glIsEnabled(GL_PROGRAM_POINT_SIZE);
            int sRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            int dRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            int sA = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            int dA = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL11.glViewport(0, 0, w, h);
            GL11.glDisable(GL11.GL_DEPTH_TEST);
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
            GL11.glEnable(GL11.GL_BLEND);

            // --- Pass 1: scene into targetA ---
            attach(targetA);
            GL11.glClearColor(bgR, bgG, bgB, 1.0f);
            GL11.glClear(GL11.GL_COLOR_BUFFER_BIT);

            GL11.glEnable(GL_PROGRAM_POINT_SIZE);
            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE, GL11.GL_ONE, GL11.GL_ONE);
            GL20.glUseProgram(ptProgram);
            GL20.glUniform2f(ptURes, w, h);
            GL30.glBindVertexArray(ptVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, ptVbo);
            ptBuf = upload(ptBuf, pts, ptCount);
            GL11.glDrawArrays(GL11.GL_POINTS, 0, ptCount / PT_STRIDE);

            GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                    GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
            GL20.glUseProgram(solidProgram);
            GL20.glUniform2f(solidURes, w, h);
            GL30.glBindVertexArray(solidVao);
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, solidVbo);
            if (triCount > 0) {
                solidBuf = upload(solidBuf, tris, triCount);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, triCount / SOLID_STRIDE);
            }
            if (lineCount > 0) {
                solidBuf = upload(solidBuf, lines, lineCount);
                GL11.glDrawArrays(GL11.GL_LINES, 0, lineCount / SOLID_STRIDE);
            }

            GpuTextureView result = targetA.getColorTextureView();

            // --- Pass 2: black-hole lensing/disk into targetB ---
            int prevTex0 = -1;
            if (bhCount > 0) {
                attach(targetB);
                GL11.glDisable(GL11.GL_BLEND);
                GL13.glActiveTexture(GL13.GL_TEXTURE0);
                // Remember what was bound on unit 0 so we can put it back; otherwise
                // our scene texture stays bound and Minecraft's font atlas (which it
                // assumes is still here) samples wrong -> garbled/missing GUI text.
                prevTex0 = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
                int sceneTex = ((GlTextureView) targetA.getColorTextureView()).glId();
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, sceneTex);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL13.GL_CLAMP_TO_EDGE);
                GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL13.GL_CLAMP_TO_EDGE);
                GL20.glUseProgram(bhProgram);
                GL20.glUniform1i(bhUScene, 0);
                GL20.glUniform2f(bhURes, w, h);
                GL20.glUniform1f(bhUTime, time);
                GL20.glUniform1i(bhUCount, Math.min(bhCount, MAX_BH));
                GL20.glUniform3fv(bhUPos, bhPos);
                GL20.glUniform3fv(bhUCol, bhCol);
                GL20.glUniform1iv(bhUType, bhType);
                GL20.glUniform4fv(bhUParam, bhParam);
                GL30.glBindVertexArray(bhVao);
                GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
                result = targetB.getColorTextureView();
            }

            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, prevArray);
            GL30.glBindVertexArray(prevVao);
            GL20.glUseProgram(prevProgram);
            // Unit 0 is still active here (set in the BH pass); put its texture back
            // before switching the active unit, so the restore lands on the right unit.
            if (prevTex0 != -1) GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTex0);
            GL13.glActiveTexture(prevActive);
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, prevFbo);
            GL11.glViewport(vp[0], vp[1], vp[2], vp[3]);
            GL14.glBlendFuncSeparate(sRgb, dRgb, sA, dA);
            setEnabled(GL11.GL_BLEND, blend);
            setEnabled(GL11.GL_DEPTH_TEST, depth);
            setEnabled(GL11.GL_SCISSOR_TEST, scissor);
            setEnabled(GL_PROGRAM_POINT_SIZE, pps);

            return result;
        } catch (Throwable t) {
            failed = true;
            TwoSixTwo_Arskiz.LOGGER.error("GalaxyRenderer render failed; disabling.", t);
            return null;
        }
    }

    private void attach(TextureTarget target) {
        int texId = ((GlTextureView) target.getColorTextureView()).glId();
        GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texId, 0);
    }

    private static FloatBuffer upload(FloatBuffer buf, float[] data, int count) {
        if (count > buf.capacity()) {
            int cap = Integer.highestOneBit(count - 1) * 2;
            buf = MemoryUtil.memRealloc(buf, cap);
        }
        buf.clear();
        buf.put(data, 0, count);
        buf.flip();
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, buf, GL15.GL_DYNAMIC_DRAW);
        return buf;
    }

    private static void setEnabled(int cap, boolean on) {
        if (on) GL11.glEnable(cap); else GL11.glDisable(cap);
    }

    @Override
    public void close() {
        try {
            if (ptProgram != 0) GL20.glDeleteProgram(ptProgram);
            if (solidProgram != 0) GL20.glDeleteProgram(solidProgram);
            if (bhProgram != 0) GL20.glDeleteProgram(bhProgram);
            if (ptVbo != 0) GL15.glDeleteBuffers(ptVbo);
            if (solidVbo != 0) GL15.glDeleteBuffers(solidVbo);
            if (ptVao != 0) GL30.glDeleteVertexArrays(ptVao);
            if (solidVao != 0) GL30.glDeleteVertexArrays(solidVao);
            if (bhVao != 0) GL30.glDeleteVertexArrays(bhVao);
            if (fbo != 0) GL30.glDeleteFramebuffers(fbo);
            if (ptBuf != null) MemoryUtil.memFree(ptBuf);
            if (solidBuf != null) MemoryUtil.memFree(solidBuf);
            if (targetA != null) targetA.destroyBuffers();
            if (targetB != null) targetB.destroyBuffers();
        } catch (Throwable ignored) {
        }
        ptProgram = solidProgram = bhProgram = ptVao = solidVao = bhVao = ptVbo = solidVbo = fbo = 0;
        ptBuf = solidBuf = null;
        targetA = targetB = null;
        initialized = false;
    }
}
