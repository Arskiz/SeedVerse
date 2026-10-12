package arsqz.mod.universe;

import java.util.HashMap;
import java.util.Map;

/**
 * A reversible, pronounceable syllable codec: {@code name <-> value} is an exact
 * bijection. This is what lets the search bar turn a typed name straight back
 * into universe coordinates (so you can jump anywhere by name).
 *
 * <p>Each syllable is an onset (consonant token) + a nucleus (vowel token) and
 * encodes one base-{@link #BASE} digit. Names are therefore a clean alternation
 * of consonant-run / vowel-run, which decodes unambiguously by taking maximal
 * runs. A fixed number of syllables encodes a fixed number of bits:
 * <pre>
 *   2 syllables -> up to 65,536      (clusters)
 *   5 syllables -> up to ~1.1e12     (global galaxy index)
 *   8 syllables -> full 2^64         (system seed)
 * </pre>
 */
public final class NameCodec {

    private NameCodec() {}

    private static final String[] ONSET = {
            "b", "c", "d", "f", "g", "h", "j", "k", "l", "m", "n", "p", "r", "s",
            "t", "v", "w", "z", "br", "dr", "fr", "gr", "kr", "pr", "tr", "st",
            "sp", "sk", "th", "sh", "ch", "vr"
    }; // 32
    private static final String[] NUCLEUS = {
            "a", "e", "i", "o", "u", "ae", "io", "ou"
    }; // 8

    public static final int BASE = ONSET.length * NUCLEUS.length; // 256

    private static final Map<String, Integer> ONSET_IX = new HashMap<>();
    private static final Map<String, Integer> NUCLEUS_IX = new HashMap<>();
    static {
        for (int i = 0; i < ONSET.length; i++) ONSET_IX.put(ONSET[i], i);
        for (int i = 0; i < NUCLEUS.length; i++) NUCLEUS_IX.put(NUCLEUS[i], i);
    }

    private static boolean isVowel(char c) {
        return c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u';
    }

    // Invertible bit-scramble so adjacent values produce totally different names
    // (odd-multiply and xor are exact bijections mod 2^bits, so decode reverses it).
    private static final long MULT1 = 0x9E3779B97F4A7C15L;
    private static final long MULT2 = 0xD1B54A32D192ED03L;
    private static final long XORK = 0xC2B2AE3D27D4EB4FL;

    private static long maskOf(int bits) {
        return bits >= 64 ? -1L : (1L << bits) - 1L;
    }

    private static long modInverse(long k) { // inverse of odd k, mod 2^64
        long x = k;
        for (int i = 0; i < 6; i++) x = x * (2 - k * x);
        return x;
    }

    private static long scramble(long v, int bits) {
        long m = maskOf(bits);
        v &= m;
        v = (v * MULT1) & m;
        v ^= XORK & m;
        v = (v * MULT2) & m;
        return v & m;
    }

    private static long unscramble(long v, int bits) {
        long m = maskOf(bits);
        v &= m;
        v = (v * modInverse(MULT2)) & m;
        v ^= XORK & m;
        v = (v * modInverse(MULT1)) & m;
        return v & m;
    }

    /** Encode {@code value} (treated as unsigned) into {@code syllables} syllables. */
    public static String encode(long value, int syllables) {
        String[] parts = new String[syllables];
        long v = scramble(value, syllables * 8);
        for (int i = syllables - 1; i >= 0; i--) {
            int digit = (int) Long.remainderUnsigned(v, BASE);
            v = Long.divideUnsigned(v, BASE);
            parts[i] = ONSET[digit / NUCLEUS.length] + NUCLEUS[digit % NUCLEUS.length];
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) sb.append(p);
        sb.setCharAt(0, Character.toUpperCase(sb.charAt(0)));
        return sb.toString();
    }

    /**
     * Decode a codeword back into its value, or {@code null} if it is not a
     * valid codeword of exactly {@code syllables} syllables.
     */
    public static Long decode(String name, int syllables) {
        if (name == null) return null;
        String s = name.trim().toLowerCase();
        if (s.isEmpty()) return null;
        int idx = 0;
        long value = 0;
        for (int k = 0; k < syllables; k++) {
            int c0 = idx;
            while (idx < s.length() && !isVowel(s.charAt(idx)) && Character.isLetter(s.charAt(idx))) idx++;
            String onset = s.substring(c0, idx);
            int v0 = idx;
            while (idx < s.length() && isVowel(s.charAt(idx))) idx++;
            String nucleus = s.substring(v0, idx);
            Integer oi = ONSET_IX.get(onset);
            Integer ni = NUCLEUS_IX.get(nucleus);
            if (oi == null || ni == null) return null;
            value = value * BASE + (oi * NUCLEUS.length + ni);
        }
        if (idx != s.length()) return null; // leftover characters -> invalid
        return unscramble(value, syllables * 8);
    }
}
