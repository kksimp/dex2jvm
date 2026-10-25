// DexSetFinal -- set ACC_FINAL on named fields of a .dex file, in place.
//
//     java DexSetFinal classes.dex 'Outer$Inner.field' ...
//
// For semantic cases whose DEX must hold something javac cannot write: a final
// field that a method other than its initializer writes, which ART accepts and
// obfuscators emit (see cases/FinalPut.java). The case declares the field
// non-final, so javac and the oracle are happy, and this turns the d8 output
// into the shape an obfuscated app ships.
//
// Only the field's access_flags change (dex format: class_data_item,
// encoded_field.access_flags, a uleb128). Setting bit 0x10 never changes the
// encoded length: a value below 0x80 stays below 0x80, and a longer one keeps
// its top bits. So no offset in the file moves, and only the header's SHA-1
// signature and Adler-32 checksum are recomputed (dex format: header_item).
// Every named field must be found exactly once, or the file is left as it was.

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Adler32;

public final class DexSetFinal {
    private static final int ACC_FINAL = 0x10;

    private static byte[] d;
    private static ByteBuffer b;
    private static int pos;

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: DexSetFinal <classes.dex> <Class.field>...");
            System.exit(2);
        }
        Path file = Paths.get(args[0]);
        d = Files.readAllBytes(file);
        b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        // "LOuter$Inner;" + "." + "field" -> times seen
        Map<String, Integer> want = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i++) {
            int dot = args[i].lastIndexOf('.');
            want.put("L" + args[i].substring(0, dot).replace('.', '/') + ";." + args[i].substring(dot + 1), 0);
        }

        int fieldIdsOff = b.getInt(84);
        int classDefsSize = b.getInt(96), classDefsOff = b.getInt(100);
        for (int c = 0; c < classDefsSize; c++) {
            int classDataOff = b.getInt(classDefsOff + 32 * c + 24);
            if (classDataOff == 0) continue;
            pos = classDataOff;
            int statics = uleb(), instances = uleb();
            uleb();
            uleb();
            for (int list = 0; list < 2; list++) {
                int fieldIdx = 0;
                for (int k = 0, n = list == 0 ? statics : instances; k < n; k++) {
                    fieldIdx += uleb();
                    int flagsAt = pos;
                    int flags = uleb();
                    int len = pos - flagsAt;
                    int f = fieldIdsOff + 8 * fieldIdx;
                    String key = type(b.getShort(f) & 0xFFFF) + "." + string(b.getInt(f + 4));
                    Integer seen = want.get(key);
                    if (seen == null) continue;
                    want.put(key, seen + 1);
                    int set = flags | ACC_FINAL;
                    if (uleb(set, flagsAt) != len) throw new IllegalStateException("uleb length changed: " + key);
                    System.out.printf("%s: access 0x%x -> 0x%x%n", key, flags, set);
                }
            }
        }
        for (Map.Entry<String, Integer> e : want.entrySet()) {
            if (e.getValue() != 1) {
                System.err.println("DexSetFinal: " + e.getKey() + " found " + e.getValue() + " times; "
                        + args[0] + " left unchanged");
                System.exit(1);
            }
        }
        // header_item: signature = SHA-1 of everything after it (offset 32 on),
        // checksum = Adler-32 of everything after the checksum (offset 12 on).
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        sha1.update(d, 32, d.length - 32);
        System.arraycopy(sha1.digest(), 0, d, 12, 20);
        Adler32 adler = new Adler32();
        adler.update(d, 12, d.length - 12);
        b.putInt(8, (int) adler.getValue());
        Files.write(file, d);
    }

    private static int uleb() {
        int v = 0, shift = 0, x;
        do {
            x = d[pos++] & 0xFF;
            v |= (x & 0x7F) << shift;
            shift += 7;
        } while ((x & 0x80) != 0);
        return v;
    }

    /** Writes v as uleb128 at {@code at}; returns its length. */
    private static int uleb(int v, int at) {
        int p = at;
        do {
            int x = v & 0x7F;
            v >>>= 7;
            d[p++] = (byte) (v != 0 ? x | 0x80 : x);
        } while (v != 0);
        return p - at;
    }

    private static String type(int typeIdx) {
        return string(b.getInt(b.getInt(68) + 4 * typeIdx));
    }

    /** A string_id's MUTF-8 data; the names used here are ASCII. */
    private static String string(int stringIdx) {
        int save = pos;
        pos = b.getInt(b.getInt(60) + 4 * stringIdx);
        uleb();                                   // utf16_size
        int start = pos;
        while (d[pos] != 0) pos++;
        String s = new String(d, start, pos - start, StandardCharsets.UTF_8);
        pos = save;
        return s;
    }
}
