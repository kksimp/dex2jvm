// PrivAudit -- find every CROSS-CLASS reference to a PRIVATE (or otherwise
// inaccessible) member in a jar the converter EMITTED, i.e. every latent
// IllegalAccessError.
//
// WHY THIS GATE EXISTS AND WHY THE OTHERS CANNOT SEE THIS
// An IllegalAccessError from a bad symbolic reference is thrown at RESOLUTION
// time, which for an invoke/field instruction is the first time that
// instruction EXECUTES (JVMS 5.4.3: resolution is lazy). So:
//   - G6 (branch targets) and G7 (stack depth) are structural: blind to it.
//   - G8 (HotSpot's split verifier) verifies a class in isolation and does NOT
//     resolve constant-pool entries: blind to it.
//   - VerifyCheck's link step (getDeclaredMethods) links the class but still
//     does not resolve method refs inside method BODIES: blind to it.
//   - Turning bytecode verification off would not mask it either -- access
//     control is resolution, not verification -- which is why it surfaces as a
//     loud IllegalAccessError (often wrapped in an ExceptionInInitializerError)
//     instead of silent corruption.
// Measured 2026-07-29: Google Calculator 9.1 failed at startup with
//   "class cfo tried to access private method 'void dgo.<init>()'"
// and no other structural gate had anything to say about it.
//
// WHAT IT REPORTS
// One line per (referrer -> target.member) pair, tagged with the reason:
//   PRIVATE   target member is private and the referrer is a different class,
//             and neither class declares a nest that would make it legal
//             (JVMS 5.4.4 nestmate clause).
//   PACKAGE   member is package-private and the two classes are in different
//             run-time packages (same loader here, so package name decides).
//   PROTECTED member is protected, different package, and the referrer is not a
//             subclass of the declaring class.
// Everything is judged inside the jar only: a reference to a class that is not
// in the jar (an android.jar or JDK class, say) is skipped, because the
// member's flags live in that library, not in the jar, and whether the app's
// references to a library are legal is a property of the app and the library,
// which the converter does not change.
//
//   javac -d bin tools/verify/src/io/github/kksimp/dex2jvm/verify/PrivAudit.java \
//     && java -cp bin io.github.kksimp.dex2jvm.verify.PrivAudit out.jar

package io.github.kksimp.dex2jvm.verify;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public final class PrivAudit {

    static final int ACC_PUBLIC = 0x0001, ACC_PRIVATE = 0x0002, ACC_PROTECTED = 0x0004;

    /** One emitted class, reduced to what access control needs. */
    static final class Cls {
        String name, superName, nestHost;
        final Set<String> nestMembers = new HashSet<>();
        /** "name desc" -> flags for methods, "name" -> flags for fields. */
        final Map<String, Integer> members = new HashMap<>();
        /** (owner, name, desc) triples this class's constant pool references. */
        final List<String[]> refs = new ArrayList<>();
    }

    public static void main(String[] args) throws Exception {
        Map<String, Cls> all = new HashMap<>();
        try (ZipFile z = new ZipFile(args[0])) {
            for (java.util.Enumeration<? extends ZipEntry> e = z.entries(); e.hasMoreElements(); ) {
                ZipEntry en = e.nextElement();
                if (!en.getName().endsWith(".class")) continue;
                try (InputStream in = z.getInputStream(en)) {
                    Cls c = parse(new DataInputStream(in));
                    if (c != null) all.put(c.name, c);
                }
            }
        }
        Set<String> out = new TreeSet<>();
        int priv = 0, pkg = 0, prot = 0;
        for (Cls c : all.values()) {
            for (String[] r : c.refs) {
                Cls owner = all.get(r[0]);
                if (owner == null) continue;             // not ours to judge
                if (owner.name.equals(c.name)) continue; // same class: always legal
                // Walk up for an inherited member: the reference resolves to the
                // first declaration found (JVMS 5.4.3.3/5.4.3.2).
                Cls decl = owner;
                Integer flags = null;
                for (int guard = 0; guard < 64 && decl != null; guard++) {
                    flags = decl.members.get(r[1] + (r[2] == null ? "" : " " + r[2]));
                    if (flags != null) break;
                    decl = decl.superName == null ? null : all.get(decl.superName);
                }
                if (flags == null || decl == null) continue;   // inherited from outside the jar
                if ((flags & ACC_PUBLIC) != 0) continue;
                String tag;
                if ((flags & ACC_PRIVATE) != 0) {
                    if (sameNest(c, decl, all)) continue;
                    tag = "PRIVATE"; priv++;
                } else if ((flags & ACC_PROTECTED) != 0) {
                    if (samePackage(c.name, decl.name) || isSubclass(c, decl.name, all)) continue;
                    tag = "PROTECTED"; prot++;
                } else {
                    if (samePackage(c.name, decl.name)) continue;
                    tag = "PACKAGE"; pkg++;
                }
                out.add(tag + "  " + c.name + " -> " + decl.name + "." + r[1]
                        + (r[2] == null ? "" : r[2]));
            }
        }
        System.out.println("classes=" + all.size() + " private=" + priv
                           + " package=" + pkg + " protected=" + prot);
        for (String s : out) System.out.println("  " + s);
        System.out.println("RESULT: " + (out.isEmpty() ? "PASS" : "FAIL (" + out.size() + " sites)"));
    }

    /** JVMS 5.4.4 nestmate clause: both must agree, and share a run-time package. */
    static boolean sameNest(Cls from, Cls to, Map<String, Cls> all) {
        String h1 = from.nestHost != null ? from.nestHost : from.name;
        String h2 = to.nestHost != null ? to.nestHost : to.name;
        if (!h1.equals(h2)) return false;
        Cls host = all.get(h1);
        if (host == null) return false;
        if (!samePackage(host.name, from.name) || !samePackage(host.name, to.name)) return false;
        return (from.name.equals(h1) || host.nestMembers.contains(from.name))
            && (to.name.equals(h1)   || host.nestMembers.contains(to.name));
    }

    static boolean samePackage(String a, String b) {
        int i = a.lastIndexOf('/'), j = b.lastIndexOf('/');
        return (i < 0 ? "" : a.substring(0, i)).equals(j < 0 ? "" : b.substring(0, j));
    }

    static boolean isSubclass(Cls c, String maybeSuper, Map<String, Cls> all) {
        Cls cur = c;
        for (int guard = 0; guard < 64 && cur != null; guard++) {
            if (cur.name.equals(maybeSuper)) return true;
            cur = cur.superName == null ? null : all.get(cur.superName);
        }
        return false;
    }

    // ---- a minimum-effort class-file reader ------------------------------
    // Only the constant pool, this_class, super_class, the member tables' flags
    // and the two nest attributes are needed, so nothing else is decoded.

    static Cls parse(DataInputStream in) throws IOException {
        if (in.readInt() != 0xCAFEBABE) return null;
        in.readUnsignedShort(); in.readUnsignedShort();          // minor, major
        int cpCount = in.readUnsignedShort();
        String[] utf8 = new String[cpCount];
        int[][] entries = new int[cpCount][];                    // {tag, a, b}
        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1 -> { utf8[i] = in.readUTF(); entries[i] = new int[]{ tag, 0, 0 }; }
                case 7, 8, 16, 19, 20 -> entries[i] = new int[]{ tag, in.readUnsignedShort(), 0 };
                case 15 -> { in.readUnsignedByte(); entries[i] = new int[]{ tag, in.readUnsignedShort(), 0 }; }
                case 9, 10, 11, 12, 17, 18 -> entries[i] =
                        new int[]{ tag, in.readUnsignedShort(), in.readUnsignedShort() };
                case 3, 4 -> { in.readInt();  entries[i] = new int[]{ tag, 0, 0 }; }
                case 5, 6 -> { in.readLong(); entries[i] = new int[]{ tag, 0, 0 }; i++; }
                default -> throw new IOException("bad cp tag " + tag);
            }
        }
        Cls c = new Cls();
        in.readUnsignedShort();                                  // access_flags
        c.name = className(utf8, entries, in.readUnsignedShort());
        c.superName = className(utf8, entries, in.readUnsignedShort());
        int ifaces = in.readUnsignedShort();
        in.skipBytes(ifaces * 2);
        for (int pass = 0; pass < 2; pass++) {                   // fields, then methods
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                int flags = in.readUnsignedShort();
                String nm = utf8[in.readUnsignedShort()];
                String desc = utf8[in.readUnsignedShort()];
                c.members.put(pass == 0 ? nm : nm + " " + desc, flags);
                skipAttributes(in, in.readUnsignedShort());
            }
        }
        int nattr = in.readUnsignedShort();
        for (int i = 0; i < nattr; i++) {
            String nm = utf8[in.readUnsignedShort()];
            int len = in.readInt();
            if ("NestHost".equals(nm) && len >= 2) {
                c.nestHost = className(utf8, entries, in.readUnsignedShort());
                in.skipBytes(len - 2);
            } else if ("NestMembers".equals(nm) && len >= 2) {
                int cnt = in.readUnsignedShort();
                for (int k = 0; k < cnt; k++) c.nestMembers.add(className(utf8, entries, in.readUnsignedShort()));
                in.skipBytes(len - 2 - cnt * 2);
            } else {
                in.skipBytes(len);
            }
        }
        // Fieldref(9) / Methodref(10) / InterfaceMethodref(11) -> (owner, name, desc)
        for (int i = 1; i < cpCount; i++) {
            int[] e = entries[i];
            if (e == null || (e[0] != 9 && e[0] != 10 && e[0] != 11)) continue;
            String owner = className(utf8, entries, e[1]);
            int[] nat = entries[e[2]];
            if (owner == null || nat == null || nat[0] != 12) continue;
            String nm = utf8[nat[1]], desc = utf8[nat[2]];
            c.refs.add(new String[]{ owner, nm, e[0] == 9 ? null : desc });
        }
        return c;
    }

    static String className(String[] utf8, int[][] entries, int idx) {
        if (idx == 0 || entries[idx] == null || entries[idx][0] != 7) return null;
        return utf8[entries[idx][1]];
    }

    static void skipAttributes(DataInputStream in, int n) throws IOException {
        for (int i = 0; i < n; i++) { in.readUnsignedShort(); in.skipBytes(in.readInt()); }
    }
}
