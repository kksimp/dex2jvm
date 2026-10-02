// BranchTargetCheck -- gate G6: every branch, switch and exception-handler
// target in an emitted Code attribute must land on a real instruction boundary.
//
// WHY THIS EXISTS: the converter once passed every other check -- 22 APKs,
// 333,250 classes, 0 defineClass rejections, 0 regressions against
// jdk.internal.classfile.impl.verifier.VerifierImpl -- and the output still
// killed HotSpot inside a GC safepoint on three real apps with
//
//     Internal Error (generateOopMap.cpp:2165)
//     fatal error: Illegal class file encountered
//
// That is HotSpot's INTERPRETER oop-map builder, not its verifier. It runs even
// with bytecode verification switched off, and it rejects a method whose branch
// target is not the start of an instruction. Nothing was checking that, so the
// defect reached a running app.
//
// This check is deliberately independent of the converter's writer: it re-decodes the bytes
// we emitted, using the JVMS 6.5 instruction lengths, and asks the same question
// GenerateOopMap asks. A bug in CodeWriter's own bookkeeping therefore cannot
// hide it.
//
//   java -cp dex2jvm-verify.jar io.github.kksimp.dex2jvm.verify.BranchTargetCheck <jar-or-dir> [<jar-or-dir>...]

package io.github.kksimp.dex2jvm.verify;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class BranchTargetCheck {

    /** JVMS 6.5 instruction lengths, 0 where the length is computed. */
    private static final int[] LEN = new int[256];
    static {
        for (int i = 0x00; i <= 0x0f; i++) LEN[i] = 1;
        LEN[0x10] = 2; LEN[0x11] = 3; LEN[0x12] = 2; LEN[0x13] = 3; LEN[0x14] = 3;
        for (int i = 0x15; i <= 0x19; i++) LEN[i] = 2;
        for (int i = 0x1a; i <= 0x35; i++) LEN[i] = 1;
        for (int i = 0x36; i <= 0x3a; i++) LEN[i] = 2;
        for (int i = 0x3b; i <= 0x83; i++) LEN[i] = 1;
        LEN[0x84] = 3;                                   // iinc
        for (int i = 0x85; i <= 0x98; i++) LEN[i] = 1;
        for (int i = 0x99; i <= 0xa8; i++) LEN[i] = 3;   // if* / goto / jsr
        LEN[0xa9] = 2;                                   // ret
        LEN[0xaa] = 0; LEN[0xab] = 0;                    // tableswitch / lookupswitch
        for (int i = 0xac; i <= 0xb1; i++) LEN[i] = 1;
        for (int i = 0xb2; i <= 0xb8; i++) LEN[i] = 3;
        LEN[0xb9] = 5; LEN[0xba] = 5; LEN[0xbb] = 3; LEN[0xbc] = 2; LEN[0xbd] = 3;
        LEN[0xbe] = 1; LEN[0xbf] = 1; LEN[0xc0] = 3; LEN[0xc1] = 3;
        LEN[0xc2] = 1; LEN[0xc3] = 1;
        LEN[0xc4] = 0;                                   // wide
        LEN[0xc5] = 4; LEN[0xc6] = 3; LEN[0xc7] = 3; LEN[0xc8] = 5; LEN[0xc9] = 5;
    }

    static int checked, methods, bad;
    static final List<String> failures = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        for (String a : args) scan(new File(a));
        System.out.println("classes checked : " + checked);
        System.out.println("methods checked : " + methods);
        System.out.println("BAD METHODS     : " + bad);
        int shown = 0;
        for (String f : failures) {
            if (shown++ == 40) {
                System.out.println("    ... and " + (failures.size() - 40) + " more");
                break;
            }
            System.out.println("    " + f);
        }
        System.out.println(bad == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(bad == 0 ? 0 : 1);
    }

    static void scan(File f) throws Exception {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) scan(k);
            return;
        }
        if (f.getName().endsWith(".class")) {
            check(f.getPath(), Files.readAllBytes(f.toPath()));
            return;
        }
        if (f.getName().endsWith(".jar")) {
            try (ZipFile z = new ZipFile(f)) {
                for (ZipEntry e : Collections.list(z.entries())) {
                    if (!e.getName().endsWith(".class")) continue;
                    try (InputStream is = z.getInputStream(e)) {
                        check(e.getName(), is.readAllBytes());
                    }
                }
            }
        }
    }

    // ---- class file walk (only as much as is needed to reach Code) ----------

    static void check(String where, byte[] cf) {
        try {
            new Parser(where, cf).run();
            checked++;
        } catch (Exception e) {
            failures.add(where + ": UNPARSABLE " + e);
            bad++;
        }
    }

    private static final class Parser {
        final String where;
        final DataInputStream in;
        final byte[] cf;
        String[] utf8;

        Parser(String where, byte[] cf) {
            this.where = where;
            this.cf = cf;
            this.in = new DataInputStream(new ByteArrayInputStream(cf));
        }

        void run() throws IOException {
            in.readInt();                       // magic
            in.readUnsignedShort();             // minor
            in.readUnsignedShort();             // major
            int cpCount = in.readUnsignedShort();
            utf8 = new String[cpCount];
            for (int i = 1; i < cpCount; i++) {
                int tag = in.readUnsignedByte();
                switch (tag) {
                    case 1: utf8[i] = in.readUTF(); break;
                    case 7: case 8: case 16: case 19: case 20: in.skipBytes(2); break;
                    case 15: in.skipBytes(3); break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                        in.skipBytes(4); break;
                    case 5: case 6: in.skipBytes(8); i++; break;   // long/double take 2 slots
                    default: throw new IOException("bad cp tag " + tag + " at " + i);
                }
            }
            in.readUnsignedShort();             // access
            in.readUnsignedShort();             // this
            in.readUnsignedShort();             // super
            int ifaces = in.readUnsignedShort();
            in.skipBytes(ifaces * 2);
            skipMembers();                      // fields
            parseMethods();
        }

        void skipMembers() throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.skipBytes(6);
                skipAttributes();
            }
        }

        void skipAttributes() throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.readUnsignedShort();
                int len = in.readInt();
                in.skipBytes(len);
            }
        }

        void parseMethods() throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.readUnsignedShort();                       // access
                int nameIdx = in.readUnsignedShort();
                int descIdx = in.readUnsignedShort();
                String name = utf8[nameIdx], desc = utf8[descIdx];
                int na = in.readUnsignedShort();
                for (int a = 0; a < na; a++) {
                    int an = in.readUnsignedShort();
                    int len = in.readInt();
                    if (!"Code".equals(utf8[an])) { in.skipBytes(len); continue; }
                    byte[] body = new byte[len];
                    in.readFully(body);
                    checkCode(where + "." + name + desc, body);
                }
            }
        }
    }

    /** Code attribute BODY: max_stack, max_locals, code_length, code[], exception_table, attrs. */
    static void checkCode(String who, byte[] body) {
        methods++;
        int p = 0;
        p += 4;                                             // max_stack, max_locals
        int codeLen = i32(body, p); p += 4;
        if (codeLen <= 0 || p + codeLen > body.length) {
            fail(who, "code_length " + codeLen + " does not fit the attribute");
            return;
        }
        int codeStart = p;
        boolean[] boundary = new boolean[codeLen + 1];
        List<int[]> targets = new ArrayList<>();            // {fromBci, targetBci}

        // Pass 1: walk instructions, recording boundaries and branch targets.
        int bci = 0;
        while (bci < codeLen) {
            boundary[bci] = true;
            int op = body[codeStart + bci] & 0xff;
            int len;
            if (op == 0xc4) {                               // wide
                if (bci + 1 >= codeLen) { fail(who, "wide at end of code"); return; }
                int op2 = body[codeStart + bci + 1] & 0xff;
                len = (op2 == 0x84) ? 6 : 4;                // wide iinc is 6
            } else if (op == 0xaa) {                        // tableswitch
                int q = bci + 1;
                while ((q & 3) != 0) q++;                   // pad to 4-byte boundary
                if (q + 12 > codeLen) { fail(who, "tableswitch header past end"); return; }
                targets.add(new int[]{ bci, bci + i32(body, codeStart + q) });
                int low = i32(body, codeStart + q + 4);
                int high = i32(body, codeStart + q + 8);
                long count = (long) high - low + 1;
                if (count < 0 || count > codeLen) { fail(who, "tableswitch count " + count); return; }
                for (int k = 0; k < count; k++) {
                    int off = q + 12 + k * 4;
                    if (off + 4 > codeLen) { fail(who, "tableswitch entries past end"); return; }
                    targets.add(new int[]{ bci, bci + i32(body, codeStart + off) });
                }
                len = (q + 12 + (int) count * 4) - bci;
            } else if (op == 0xab) {                        // lookupswitch
                int q = bci + 1;
                while ((q & 3) != 0) q++;
                if (q + 8 > codeLen) { fail(who, "lookupswitch header past end"); return; }
                targets.add(new int[]{ bci, bci + i32(body, codeStart + q) });
                int npairs = i32(body, codeStart + q + 4);
                if (npairs < 0 || npairs > codeLen) { fail(who, "lookupswitch npairs " + npairs); return; }
                for (int k = 0; k < npairs; k++) {
                    int off = q + 8 + k * 8 + 4;
                    if (off + 4 > codeLen) { fail(who, "lookupswitch pairs past end"); return; }
                    targets.add(new int[]{ bci, bci + i32(body, codeStart + off) });
                }
                len = (q + 8 + npairs * 8) - bci;
            } else {
                len = LEN[op];
                if (len == 0) { fail(who, "unknown opcode 0x" + Integer.toHexString(op)
                                        + " at bci " + bci); return; }
                if ((op >= 0x99 && op <= 0xa8) || op == 0xc6 || op == 0xc7) {
                    targets.add(new int[]{ bci, bci + i16(body, codeStart + bci + 1) });
                } else if (op == 0xc8 || op == 0xc9) {
                    targets.add(new int[]{ bci, bci + i32(body, codeStart + bci + 1) });
                }
            }
            bci += len;
        }
        if (bci != codeLen) {
            fail(who, "last instruction runs past code_length (ended at " + bci
                      + ", code_length " + codeLen + ")");
            return;
        }
        boundary[codeLen] = true;   // valid only as an exception-range END

        for (int[] t : targets) {
            if (t[1] < 0 || t[1] >= codeLen || !boundary[t[1]]) {
                fail(who, "branch at bci " + t[0] + " targets " + t[1]
                          + " which is not an instruction start (code_length " + codeLen + ")");
                return;
            }
        }

        // Exception table: start_pc/handler_pc must be instruction starts,
        // end_pc may equal code_length (JVMS 4.7.3).
        int q = codeStart + codeLen;
        if (q + 2 > body.length) { fail(who, "truncated before exception_table"); return; }
        int etLen = u16(body, q); q += 2;
        for (int i = 0; i < etLen; i++) {
            if (q + 8 > body.length) { fail(who, "truncated exception_table"); return; }
            int s = u16(body, q), e = u16(body, q + 2), h = u16(body, q + 4);
            q += 8;
            if (s >= codeLen || !boundary[s]) { fail(who, "handler start_pc " + s + " invalid"); return; }
            if (e > codeLen || !boundary[e]) { fail(who, "handler end_pc " + e + " invalid"); return; }
            if (h >= codeLen || !boundary[h]) { fail(who, "handler_pc " + h + " invalid"); return; }
            if (s >= e) { fail(who, "handler range [" + s + "," + e + ") is empty"); return; }
        }
    }

    static void fail(String who, String msg) {
        bad++;
        failures.add(who + ": " + msg);
    }

    static int u16(byte[] b, int p) { return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); }
    static int i16(byte[] b, int p) { return (short) u16(b, p); }
    static int i32(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16)
             | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }
}
