package io.github.kksimp.dex2jvm;

import java.nio.charset.StandardCharsets;

/**
 * Modified UTF-8 (MUTF-8) codec, the string encoding used by both DEX
 * string_data_item and the JVM class file CONSTANT_Utf8_info.
 *
 * <p>Why this is not just {@code new String(bytes, UTF_8)}: MUTF-8 differs from
 * standard UTF-8 in two ways that matter for correctness, per
 * https://source.android.com/docs/core/runtime/dex-format ("MUTF-8 (Modified
 * UTF-8) Encoding"):
 *
 * <ol>
 *   <li>U+0000 is encoded as the two bytes {@code C0 80}, never as a bare
 *       {@code 00}. That is what lets a string be NUL-terminated in the file
 *       while still being able to contain an embedded NUL. Standard UTF-8
 *       decoders reject {@code C0 80} as an overlong form.</li>
 *   <li>Only one-, two- and three-byte sequences exist. A supplementary
 *       character (U+10000 and above) is encoded as its UTF-16 surrogate PAIR,
 *       i.e. two separate three-byte sequences. A standard UTF-8 decoder
 *       rejects encoded surrogates (CESU-8 style) outright.</li>
 * </ol>
 *
 * <p>Because MUTF-8 encodes UTF-16 code units one-for-one, decoding to a Java
 * String and re-encoding with {@link #encode(String)} round-trips EXACTLY. That
 * is load-bearing for the class file writer: a DEX string must be re-emitted
 * into the constant pool with {@link #encode}, NOT with
 * {@code String.getBytes(UTF_8)}, or any string containing U+0000 or a
 * supplementary character (emoji in a resource string, for one) comes out with
 * a different byte length than the JVM expects.
 *
 * <p>Decoding here is deliberately LENIENT. Obfuscators (and a few
 * hand-assembled DEX files in the wild) emit sequences that AOSP's strict
 * decoder rejects: real four-byte UTF-8 for supplementary characters, and
 * isolated bytes in 0x80..0xBF. Refusing to parse the whole APK over one junk
 * identifier is the wrong trade, so malformed input degrades to a best-effort
 * character rather than an exception. This mirrors enjarify's
 * error-tolerant decoder (enjarify/mutf8.py).
 *
 * <p>Reference implementation cross-checked against AOSP
 * {@code dx/src/com/android/dex/Mutf8.java}.
 */
public final class Mutf8 {

    private Mutf8() {}

    /**
     * Decodes the NUL-terminated MUTF-8 string that starts at {@code offset}.
     *
     * @param in         the buffer holding the encoded bytes
     * @param offset     index of the first encoded byte
     * @param utf16Size  the declared UTF-16 length from the string_data_item's
     *                   leading uleb128. Used only as a sizing hint; a
     *                   mismatched declaration does not fail the decode,
     *                   because obfuscated DEX files are known to lie here.
     * @return the decoded string (never null)
     */
    public static String decode(byte[] in, int offset, int utf16Size) {
        // Locate the terminator first. Two reasons: it bounds every subsequent
        // read (so a truncated multi-byte sequence cannot run off the end of
        // the string into the next one), and it lets us take the ASCII fast
        // path, which the overwhelming majority of DEX strings hit.
        int end = offset;
        boolean ascii = true;
        while (end < in.length) {
            int b = in[end] & 0xff;
            if (b == 0) {
                break;
            }
            if (b >= 0x80) {
                ascii = false;
            }
            end++;
        }
        int len = end - offset;
        if (ascii) {
            // ISO-8859-1 is a byte-for-char identity map, and for bytes below
            // 0x80 that is exactly ASCII. On a compact-strings JVM this is a
            // straight array copy with no decoder loop, which matters when a
            // single DEX carries 100k+ strings.
            return new String(in, offset, len, StandardCharsets.ISO_8859_1);
        }
        return decodeSlow(in, offset, end, utf16Size);
    }

    /** Convenience overload for callers with no utf16_size hint. */
    public static String decode(byte[] in, int offset) {
        return decode(in, offset, 16);
    }

    private static String decodeSlow(byte[] in, int offset, int end, int utf16Size) {
        // Every output char consumes at least one input byte, so the byte
        // count is a safe upper bound on the char count. (The four-byte
        // leniency case emits two chars from four bytes, still under bound.)
        char[] out = new char[Math.max(end - offset, Math.max(utf16Size, 1))];
        int p = offset;
        int s = 0;
        while (p < end) {
            int a = in[p++] & 0xff;
            if (a < 0x80) {
                out[s++] = (char) a;
            } else if ((a & 0xe0) == 0xc0) {
                // Two-byte form. Covers U+0080..U+07FF plus the C0 80 spelling
                // of U+0000, which falls out of the arithmetic for free.
                if (p >= end || !isCont(in[p])) {
                    out[s++] = (char) a;
                    continue;
                }
                int b = in[p++] & 0x3f;
                out[s++] = (char) (((a & 0x1f) << 6) | b);
            } else if ((a & 0xf0) == 0xe0) {
                // Three-byte form: U+0800..U+FFFF, including lone surrogates,
                // which is how MUTF-8 spells supplementary characters.
                if (p + 1 >= end || !isCont(in[p]) || !isCont(in[p + 1])) {
                    out[s++] = (char) a;
                    continue;
                }
                int b = in[p++] & 0x3f;
                int c = in[p++] & 0x3f;
                out[s++] = (char) (((a & 0x0f) << 12) | (b << 6) | c);
            } else if ((a & 0xf8) == 0xf0) {
                // NOT legal MUTF-8: a real four-byte UTF-8 sequence. Some
                // obfuscators emit standard UTF-8 anyway. Decode it and split
                // into the surrogate pair a conforming file would have used,
                // so the resulting String is the same either way.
                if (p + 2 >= end || !isCont(in[p]) || !isCont(in[p + 1]) || !isCont(in[p + 2])) {
                    out[s++] = (char) a;
                    continue;
                }
                int cp = ((a & 0x07) << 18)
                        | ((in[p++] & 0x3f) << 12)
                        | ((in[p++] & 0x3f) << 6)
                        | (in[p++] & 0x3f);
                if (cp > 0xffff && cp <= 0x10ffff) {
                    cp -= 0x10000;
                    out[s++] = (char) (0xd800 + (cp >> 10));
                    out[s++] = (char) (0xdc00 + (cp & 0x3ff));
                } else {
                    out[s++] = (char) cp;
                }
            } else {
                // Stray continuation byte or 0xF8..0xFF. Pass it through as a
                // Latin-1 char so the identifier stays distinguishable from
                // its neighbours instead of collapsing to U+FFFD.
                out[s++] = (char) a;
            }
        }
        return new String(out, 0, s);
    }

    private static boolean isCont(byte b) {
        return (b & 0xc0) == 0x80;
    }

    /** Number of bytes {@link #encode(String)} would produce for {@code s}. */
    public static int encodedLength(String s) {
        int n = s.length();
        int result = 0;
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch != 0 && ch <= 0x7f) {
                result++;
            } else if (ch <= 0x7ff) {
                result += 2;
            } else {
                result += 3;
            }
        }
        return result;
    }

    /**
     * Writes the MUTF-8 form of {@code s} into {@code dst} at {@code offset}.
     *
     * @return the offset just past the last byte written
     */
    public static int encode(byte[] dst, int offset, String s) {
        int n = s.length();
        for (int i = 0; i < n; i++) {
            char ch = s.charAt(i);
            if (ch != 0 && ch <= 0x7f) {
                dst[offset++] = (byte) ch;
            } else if (ch <= 0x7ff) {
                // The ch == 0 case lands here and produces C0 80, which is the
                // whole point of "modified" UTF-8.
                dst[offset++] = (byte) (0xc0 | (0x1f & (ch >> 6)));
                dst[offset++] = (byte) (0x80 | (0x3f & ch));
            } else {
                // Surrogates are encoded individually, which is what makes a
                // supplementary character six bytes rather than four.
                dst[offset++] = (byte) (0xe0 | (0x0f & (ch >> 12)));
                dst[offset++] = (byte) (0x80 | (0x3f & (ch >> 6)));
                dst[offset++] = (byte) (0x80 | (0x3f & ch));
            }
        }
        return offset;
    }

    /** Returns the MUTF-8 bytes for {@code s} (no NUL terminator appended). */
    public static byte[] encode(String s) {
        byte[] result = new byte[encodedLength(s)];
        encode(result, 0, s);
        return result;
    }
}
