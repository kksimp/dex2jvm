// StackDepthCheck -- gate G7: reproduce HotSpot's GenerateOopMap operand-stack
// bookkeeping offline, so a stack-shape defect in an emitted Code attribute is
// named by class+method+bci instead of aborting the VM with a generic string.
//
// WHY THIS EXISTS: the converter once cleared every other check and its output
// still killed HotSpot at a GC safepoint with
//
//     Internal Error (generateOopMap.cpp:2165)
//     fatal error: Illegal class file encountered. Try running with -Xverify:all
//         in method a
//
// generateOopMap.cpp:2165 is GenerateOopMap::verify_error(), and it prints that
// SAME sentence for every condition it can detect. The underlying conditions are
// (OpenJDK, hotspot/share/oops/generateOopMap.cpp):
//
//     merge_state_into_bb()  -> "stack height conflict: %d vs. %d"
//     pop() / ppop_any()     -> "stack underflow"
//     push()                 -> "stack overflow"          (exceeds max_stack)
//     check_type()           -> "wrong type on stack (found: %c expected: %c)"
//     set_var() / get_var()  -> "variable write/read error: r%d"
//     do_astore()            -> "wrong type on stack"
//
// The first three are pure operand-stack DEPTH properties, and this file checks
// exactly those. It cannot see the type/variable conditions -- if this reports
// clean, the defect is a TYPE or LOCAL-VARIABLE error, which is itself a useful
// result because it redirects the search.
//
// Two facts make an offline depth checker sound:
//
//   1. GenerateOopMap runs its OWN abstract interpretation and never reads the
//      StackMapTable. So the defect is in the BYTECODE, not in the frames, and a
//      checker that ignores StackMapTable reproduces HotSpot faithfully.
//   2. Every dup/pop form is uniform in SLOT count regardless of which JVMS 6.5
//      operand form applies (dup2 is always pop-2-slots/push-4-slots whether it
//      duplicates two category-1 values or one category-2 value), so pure slot
//      arithmetic needs no type information. See the DUP note in the tables.
//
// Seeding matches GenerateOopMap::mark_reachable_code(), which marks bci 0 alive
// at depth 0 and marks EVERY exception handler alive with _stack_top = 1 (the
// pending throwable), unconditionally -- even handlers whose protected range is
// itself unreachable. Dead code is never interpreted, so this walker only visits
// bcis reachable from those seeds.
//
// Reporting per-bci rather than per-basic-block is equivalent but finer: depth is
// deterministic within a block, so a mid-block conflict implies a conflict at the
// block header, which is where HotSpot would raise it.
//
//   java -cp dex2jvm-verify.jar io.github.kksimp.dex2jvm.verify.StackDepthCheck <jar-or-dir> [<jar-or-dir>...]
//
// Companion gates: BranchTargetCheck (G6, branch/handler targets land on
// instruction boundaries) and VerifyCheck (G8, HotSpot's real split verifier).

package io.github.kksimp.dex2jvm.verify;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class StackDepthCheck {

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

    /** Marks an opcode whose slot arithmetic depends on a descriptor or an operand. */
    private static final int SPECIAL = -1;

    /** Operand-stack SLOTS consumed / produced, JVMS 6.5. long and double are 2. */
    private static final int[] POP = new int[256];
    private static final int[] PUSH = new int[256];
    static {
        for (int i = 0; i < 256; i++) { POP[i] = SPECIAL; PUSH[i] = SPECIAL; }

        set(0x00, 0, 0);                                 // nop
        set(0x01, 0, 1);                                 // aconst_null
        for (int i = 0x02; i <= 0x08; i++) set(i, 0, 1); // iconst_m1 .. iconst_5
        set(0x09, 0, 2); set(0x0a, 0, 2);                // lconst_0/1
        for (int i = 0x0b; i <= 0x0d; i++) set(i, 0, 1); // fconst_0/1/2
        set(0x0e, 0, 2); set(0x0f, 0, 2);                // dconst_0/1
        set(0x10, 0, 1);                                 // bipush
        set(0x11, 0, 1);                                 // sipush
        // ldc / ldc_w ALWAYS push one slot: GenerateOopMap::do_ldc() calls ppush1()
        // unconditionally, so an ldc naming a CONSTANT_Long/Double desynchronises the
        // stack instead of pushing 2. checkLdcWidth() below flags exactly that.
        set(0x12, 0, 1); set(0x13, 0, 1);                // ldc / ldc_w
        set(0x14, 0, 2);                                 // ldc2_w
        set(0x15, 0, 1); set(0x16, 0, 2); set(0x17, 0, 1);
        set(0x18, 0, 2); set(0x19, 0, 1);                // iload lload fload dload aload
        for (int i = 0x1a; i <= 0x1d; i++) set(i, 0, 1); // iload_n
        for (int i = 0x1e; i <= 0x21; i++) set(i, 0, 2); // lload_n
        for (int i = 0x22; i <= 0x25; i++) set(i, 0, 1); // fload_n
        for (int i = 0x26; i <= 0x29; i++) set(i, 0, 2); // dload_n
        for (int i = 0x2a; i <= 0x2d; i++) set(i, 0, 1); // aload_n

        set(0x2e, 2, 1);                                 // iaload  (arrayref,index -> value)
        set(0x2f, 2, 2);                                 // laload
        set(0x30, 2, 1);                                 // faload
        set(0x31, 2, 2);                                 // daload
        set(0x32, 2, 1);                                 // aaload
        set(0x33, 2, 1); set(0x34, 2, 1); set(0x35, 2, 1); // baload caload saload

        set(0x36, 1, 0); set(0x37, 2, 0); set(0x38, 1, 0);
        set(0x39, 2, 0); set(0x3a, 1, 0);                // istore lstore fstore dstore astore
        for (int i = 0x3b; i <= 0x3e; i++) set(i, 1, 0); // istore_n
        for (int i = 0x3f; i <= 0x42; i++) set(i, 2, 0); // lstore_n
        for (int i = 0x43; i <= 0x46; i++) set(i, 1, 0); // fstore_n
        for (int i = 0x47; i <= 0x4a; i++) set(i, 2, 0); // dstore_n
        for (int i = 0x4b; i <= 0x4e; i++) set(i, 1, 0); // astore_n

        set(0x4f, 3, 0);                                 // iastore (arrayref,index,value)
        set(0x50, 4, 0);                                 // lastore (value is 2 slots)
        set(0x51, 3, 0);                                 // fastore
        set(0x52, 4, 0);                                 // dastore
        set(0x53, 3, 0);                                 // aastore
        set(0x54, 3, 0); set(0x55, 3, 0); set(0x56, 3, 0); // bastore castore sastore

        // DUP/POP family. JVMS 6.5 gives each of these several operand FORMS
        // depending on whether the values are category 1 or category 2, but every
        // form of a given opcode moves the same NUMBER OF SLOTS, so slot arithmetic
        // is exact without type information. dup2 for instance is either
        // "value2,value1 -> value2,value1,value2,value1" (two category-1) or
        // "value -> value,value" (one category-2); both are pop 2 / push 4.
        set(0x57, 1, 0);                                 // pop
        set(0x58, 2, 0);                                 // pop2
        set(0x59, 1, 2);                                 // dup
        set(0x5a, 2, 3);                                 // dup_x1
        set(0x5b, 3, 4);                                 // dup_x2
        set(0x5c, 2, 4);                                 // dup2
        set(0x5d, 3, 5);                                 // dup2_x1
        set(0x5e, 4, 6);                                 // dup2_x2
        set(0x5f, 2, 2);                                 // swap

        set(0x60, 2, 1); set(0x61, 4, 2); set(0x62, 2, 1); set(0x63, 4, 2); // *add
        set(0x64, 2, 1); set(0x65, 4, 2); set(0x66, 2, 1); set(0x67, 4, 2); // *sub
        set(0x68, 2, 1); set(0x69, 4, 2); set(0x6a, 2, 1); set(0x6b, 4, 2); // *mul
        set(0x6c, 2, 1); set(0x6d, 4, 2); set(0x6e, 2, 1); set(0x6f, 4, 2); // *div
        set(0x70, 2, 1); set(0x71, 4, 2); set(0x72, 2, 1); set(0x73, 4, 2); // *rem
        set(0x74, 1, 1); set(0x75, 2, 2); set(0x76, 1, 1); set(0x77, 2, 2); // *neg
        // Shifts: the shift AMOUNT is always an int (1 slot), so lshl/lshr/lushr
        // pop 3 and push 2 (JVMS 6.5 lshl: "value1 (long), value2 (int) -> result").
        set(0x78, 2, 1); set(0x79, 3, 2);                // ishl  lshl
        set(0x7a, 2, 1); set(0x7b, 3, 2);                // ishr  lshr
        set(0x7c, 2, 1); set(0x7d, 3, 2);                // iushr lushr
        set(0x7e, 2, 1); set(0x7f, 4, 2);                // iand  land
        set(0x80, 2, 1); set(0x81, 4, 2);                // ior   lor
        set(0x82, 2, 1); set(0x83, 4, 2);                // ixor  lxor
        set(0x84, 0, 0);                                 // iinc (operates on a local)

        set(0x85, 1, 2); set(0x86, 1, 1); set(0x87, 1, 2); // i2l i2f i2d
        set(0x88, 2, 1); set(0x89, 2, 1); set(0x8a, 2, 2); // l2i l2f l2d
        set(0x8b, 1, 1); set(0x8c, 1, 2); set(0x8d, 1, 2); // f2i f2l f2d
        set(0x8e, 2, 1); set(0x8f, 2, 2); set(0x90, 2, 1); // d2i d2l d2f
        set(0x91, 1, 1); set(0x92, 1, 1); set(0x93, 1, 1); // i2b i2c i2s

        set(0x94, 4, 1);                                 // lcmp
        set(0x95, 2, 1); set(0x96, 2, 1);                // fcmpl fcmpg
        set(0x97, 4, 1); set(0x98, 4, 1);                // dcmpl dcmpg

        for (int i = 0x99; i <= 0x9e; i++) set(i, 1, 0); // ifeq .. ifle
        for (int i = 0x9f; i <= 0xa4; i++) set(i, 2, 0); // if_icmpeq .. if_icmple
        set(0xa5, 2, 0); set(0xa6, 2, 0);                // if_acmpeq / if_acmpne
        set(0xa7, 0, 0);                                 // goto
        set(0xa8, 0, 1);                                 // jsr  (pushes returnAddress)
        set(0xa9, 0, 0);                                 // ret
        set(0xaa, 1, 0);                                 // tableswitch
        set(0xab, 1, 0);                                 // lookupswitch
        set(0xac, 1, 0); set(0xad, 2, 0); set(0xae, 1, 0);
        set(0xaf, 2, 0); set(0xb0, 1, 0); set(0xb1, 0, 0); // *return

        // 0xb2..0xba (getstatic .. invokedynamic) stay SPECIAL: descriptor-driven.

        set(0xbb, 0, 1);                                 // new
        set(0xbc, 1, 1);                                 // newarray  (count -> arrayref)
        set(0xbd, 1, 1);                                 // anewarray
        set(0xbe, 1, 1);                                 // arraylength
        set(0xbf, 1, 0);                                 // athrow (block ends; no successor)
        set(0xc0, 1, 1);                                 // checkcast
        set(0xc1, 1, 1);                                 // instanceof
        set(0xc2, 1, 0); set(0xc3, 1, 0);                // monitorenter / monitorexit
        // 0xc4 wide and 0xc5 multianewarray stay SPECIAL.
        set(0xc6, 1, 0); set(0xc7, 1, 0);                // ifnull / ifnonnull
        set(0xc8, 0, 0);                                 // goto_w
        set(0xc9, 0, 1);                                 // jsr_w
    }

    private static void set(int op, int pop, int push) { POP[op] = pop; PUSH[op] = push; }

    // ---- counters --------------------------------------------------------------

    static int checked, methods, badMethods, skippedSubroutine;
    static final List<String> failures = new ArrayList<>();
    static final List<String> notes = new ArrayList<>();
    static final Map<String, Integer> byKind = new LinkedHashMap<>();
    static int maxShown = 60;

    public static void main(String[] args) throws Exception {
        List<String> paths = new ArrayList<>();
        for (String a : args) {
            if (a.startsWith("--show=")) maxShown = Integer.parseInt(a.substring(7));
            else paths.add(a);
        }
        for (String a : paths) scan(new File(a));
        System.out.println("classes checked : " + checked);
        System.out.println("methods checked : " + methods);
        System.out.println("methods skipped : " + skippedSubroutine + " (contain jsr/ret)");
        System.out.println("BAD METHODS     : " + badMethods);
        for (Map.Entry<String, Integer> e : byKind.entrySet()) {
            System.out.println("    " + e.getKey() + " : " + e.getValue());
        }
        for (String n : notes) System.out.println("    " + n);
        int shown = 0;
        for (String f : failures) {
            if (shown++ == maxShown) {
                System.out.println("    ... and " + (failures.size() - maxShown) + " more");
                break;
            }
            System.out.println("    " + f);
        }
        System.out.println(badMethods == 0 ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(badMethods == 0 ? 0 : 1);
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

    static void check(String where, byte[] cf) {
        try {
            new Parser(where, cf).run();
            checked++;
        } catch (Exception e) {
            report(where, "UNPARSABLE", String.valueOf(e));
        }
    }

    // ---- constant pool ---------------------------------------------------------

    /**
     * Only the slices the stack arithmetic needs: UTF8 text, the NameAndType each
     * ref points at, and the descriptor each NameAndType points at.
     */
    static final class Cp {
        int[] tag;
        String[] utf8;
        int[] nat;     // Fieldref/Methodref/InterfaceMethodref/Dynamic/InvokeDynamic -> NameAndType idx
        int[] desc;    // NameAndType -> descriptor UTF8 idx

        /** Descriptor behind a Fieldref/Methodref/InterfaceMethodref/InvokeDynamic index. */
        String descriptorOf(int idx) {
            if (idx <= 0 || idx >= tag.length) return null;
            int n = nat[idx];
            if (n <= 0 || n >= tag.length || tag[n] != 12) return null;
            int d = desc[n];
            if (d <= 0 || d >= tag.length) return null;
            return utf8[d];
        }

        int tagOf(int idx) { return (idx > 0 && idx < tag.length) ? tag[idx] : -1; }
    }

    private static final class Parser {
        final String where;
        final DataInputStream in;
        Cp cp;

        Parser(String where, byte[] cf) {
            this.where = where;
            this.in = new DataInputStream(new ByteArrayInputStream(cf));
        }

        void run() throws IOException {
            in.readInt();                       // magic
            in.readUnsignedShort();             // minor
            in.readUnsignedShort();             // major
            int n = in.readUnsignedShort();
            cp = new Cp();
            cp.tag = new int[n];
            cp.utf8 = new String[n];
            cp.nat = new int[n];
            cp.desc = new int[n];
            for (int i = 1; i < n; i++) {
                int tag = in.readUnsignedByte();
                cp.tag[i] = tag;
                switch (tag) {
                    case 1:                                     // Utf8
                        cp.utf8[i] = in.readUTF(); break;
                    case 7: case 8: case 16: case 19: case 20:  // Class/String/MethodType/Module/Package
                        in.skipBytes(2); break;
                    case 15:                                    // MethodHandle
                        in.skipBytes(3); break;
                    case 3: case 4:                             // Integer / Float
                        in.skipBytes(4); break;
                    case 9: case 10: case 11:                   // Field/Method/InterfaceMethodref
                        in.readUnsignedShort();                 //   class_index
                        cp.nat[i] = in.readUnsignedShort(); break;
                    case 17: case 18:                           // Dynamic / InvokeDynamic
                        in.readUnsignedShort();                 //   bootstrap_method_attr_index
                        cp.nat[i] = in.readUnsignedShort(); break;
                    case 12:                                    // NameAndType
                        in.readUnsignedShort();                 //   name_index
                        cp.desc[i] = in.readUnsignedShort(); break;
                    case 5: case 6:                             // Long / Double take two slots
                        in.skipBytes(8); i++; break;
                    default:
                        throw new IOException("bad cp tag " + tag + " at " + i);
                }
            }
            in.readUnsignedShort();             // access
            in.readUnsignedShort();             // this
            in.readUnsignedShort();             // super
            in.skipBytes(in.readUnsignedShort() * 2);   // interfaces
            skipMembers();                      // fields
            parseMethods();
        }

        void skipMembers() throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) { in.skipBytes(6); skipAttributes(); }
        }

        void skipAttributes() throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) { in.readUnsignedShort(); in.skipBytes(in.readInt()); }
        }

        void parseMethods() throws IOException {
            int n = in.readUnsignedShort();
            for (int i = 0; i < n; i++) {
                in.readUnsignedShort();                       // access
                String name = cp.utf8[in.readUnsignedShort()];
                String desc = cp.utf8[in.readUnsignedShort()];
                int na = in.readUnsignedShort();
                for (int a = 0; a < na; a++) {
                    int an = in.readUnsignedShort();
                    int len = in.readInt();
                    if (!"Code".equals(cp.utf8[an])) { in.skipBytes(len); continue; }
                    byte[] body = new byte[len];
                    in.readFully(body);
                    checkCode(where + "." + name + desc, body, cp);
                }
            }
        }
    }

    // ---- descriptors -----------------------------------------------------------

    /** Slots a field descriptor occupies. JVMS 2.6.2: long and double take two. */
    static int fieldSlots(String d) {
        if (d == null || d.isEmpty()) return -1;
        char c = d.charAt(0);
        if (c == 'J' || c == 'D') return 2;
        if (c == 'V') return 0;
        if (c == 'B' || c == 'C' || c == 'F' || c == 'I' || c == 'S' || c == 'Z'
            || c == 'L' || c == '[') return 1;
        return -1;
    }

    /** Total slots the ARGUMENTS of a method descriptor occupy. */
    static int argSlots(String d) {
        if (d == null || d.isEmpty() || d.charAt(0) != '(') return -1;
        int n = 0, i = 1;
        while (i < d.length() && d.charAt(i) != ')') {
            char c = d.charAt(i);
            if (c == '[') {
                while (i < d.length() && d.charAt(i) == '[') i++;
                if (i >= d.length()) return -1;
                if (d.charAt(i) == 'L') {
                    int s = d.indexOf(';', i);
                    if (s < 0) return -1;
                    i = s + 1;
                } else {
                    i++;
                }
                n += 1;                          // an array reference is always one slot
            } else if (c == 'L') {
                int s = d.indexOf(';', i);
                if (s < 0) return -1;
                i = s + 1;
                n += 1;
            } else if (c == 'J' || c == 'D') {
                i++; n += 2;
            } else if (c == 'B' || c == 'C' || c == 'F' || c == 'I' || c == 'S' || c == 'Z') {
                i++; n += 1;
            } else {
                return -1;
            }
        }
        return (i < d.length()) ? n : -1;        // must have found the ')'
    }

    /** Slots the RETURN type of a method descriptor occupies (0 for void). */
    static int retSlots(String d) {
        if (d == null) return -1;
        int p = d.indexOf(')');
        if (p < 0 || p + 1 >= d.length()) return -1;
        return fieldSlots(d.substring(p + 1));
    }

    // ---- the walk --------------------------------------------------------------

    /** Code attribute BODY: max_stack, max_locals, code_length, code[], exception_table, attrs. */
    static void checkCode(String who, byte[] body, Cp cp) {
        methods++;
        int p = 0;
        int maxStack = u16(body, p); p += 2;
        p += 2;                                             // max_locals
        int codeLen = i32(body, p); p += 4;
        if (codeLen <= 0 || p + codeLen > body.length) {
            report(who, "DECODE", "code_length " + codeLen + " does not fit the attribute");
            return;
        }
        final int cs = p;                                   // code[0] offset in body

        int[] len = new int[codeLen];                       // 0 = not an instruction start
        int[] pop = new int[codeLen];
        int[] push = new int[codeLen];
        boolean[] falls = new boolean[codeLen];             // control may fall to bci+len
        List<int[]> edges = new ArrayList<>();              // {fromBci, targetBci}
        int[] edgeStart = new int[codeLen + 1];             // filled after sorting by from

        List<String> pending = new ArrayList<>();           // non-fatal notes for this method

        // ---- pass 1: decode, compute per-instruction slot arithmetic ----------
        int bci = 0;
        while (bci < codeLen) {
            int op = body[cs + bci] & 0xff;
            int l, po, pu;
            boolean fall = true;

            if (op == 0xc4) {                               // wide
                if (bci + 1 >= codeLen) { report(who, "DECODE", "wide at end of code"); return; }
                int op2 = body[cs + bci + 1] & 0xff;
                l = (op2 == 0x84) ? 6 : 4;                  // wide iinc carries a 2-byte const
                switch (op2) {
                    case 0x15: case 0x17: case 0x19: po = 0; pu = 1; break;   // iload/fload/aload
                    case 0x16: case 0x18:             po = 0; pu = 2; break;   // lload/dload
                    case 0x36: case 0x38: case 0x3a: po = 1; pu = 0; break;   // istore/fstore/astore
                    case 0x37: case 0x39:             po = 2; pu = 0; break;   // lstore/dstore
                    case 0x84:                        po = 0; pu = 0; break;   // iinc
                    case 0xa9:                        po = 0; pu = 0; fall = false;
                        pending.add("wide ret at bci " + bci); break;
                    default:
                        report(who, "DECODE", "wide 0x" + Integer.toHexString(op2)
                                              + " at bci " + bci + " is not a widenable opcode");
                        return;
                }
            } else if (op == 0xaa) {                        // tableswitch
                int q = bci + 1;
                while ((q & 3) != 0) q++;
                if (q + 12 > codeLen) { report(who, "DECODE", "tableswitch header past end"); return; }
                edges.add(new int[]{ bci, bci + i32(body, cs + q) });
                int lo = i32(body, cs + q + 4), hi = i32(body, cs + q + 8);
                long count = (long) hi - lo + 1;
                if (count < 0 || count > codeLen) { report(who, "DECODE", "tableswitch count " + count); return; }
                for (int k = 0; k < count; k++) {
                    int off = q + 12 + k * 4;
                    if (off + 4 > codeLen) { report(who, "DECODE", "tableswitch entries past end"); return; }
                    edges.add(new int[]{ bci, bci + i32(body, cs + off) });
                }
                l = (q + 12 + (int) count * 4) - bci;
                po = 1; pu = 0; fall = false;
            } else if (op == 0xab) {                        // lookupswitch
                int q = bci + 1;
                while ((q & 3) != 0) q++;
                if (q + 8 > codeLen) { report(who, "DECODE", "lookupswitch header past end"); return; }
                edges.add(new int[]{ bci, bci + i32(body, cs + q) });
                int npairs = i32(body, cs + q + 4);
                if (npairs < 0 || npairs > codeLen) { report(who, "DECODE", "lookupswitch npairs " + npairs); return; }
                for (int k = 0; k < npairs; k++) {
                    int off = q + 8 + k * 8 + 4;
                    if (off + 4 > codeLen) { report(who, "DECODE", "lookupswitch pairs past end"); return; }
                    edges.add(new int[]{ bci, bci + i32(body, cs + off) });
                }
                l = (q + 8 + npairs * 8) - bci;
                po = 1; pu = 0; fall = false;
            } else {
                l = LEN[op];
                if (l == 0) {
                    report(who, "DECODE", "unknown opcode 0x" + Integer.toHexString(op)
                                          + " at bci " + bci);
                    return;
                }
                if (bci + l > codeLen) {
                    report(who, "DECODE", "opcode 0x" + Integer.toHexString(op) + " at bci " + bci
                                          + " runs past code_length " + codeLen);
                    return;
                }
                po = POP[op];
                pu = PUSH[op];

                if (po == SPECIAL) {
                    int idx = u16(body, cs + bci + 1);
                    switch (op) {
                        case 0xb2: case 0xb3: case 0xb4: case 0xb5: {   // get/put static/field
                            if (cp.tagOf(idx) != 9) {
                                report(who, "CPTAG", "bci " + bci + ": field opcode 0x"
                                        + Integer.toHexString(op) + " names cp[" + idx
                                        + "] tag " + cp.tagOf(idx) + " (expected 9 Fieldref)");
                                return;
                            }
                            int sz = fieldSlots(cp.descriptorOf(idx));
                            if (sz < 0) {
                                report(who, "CPTAG", "bci " + bci + ": unparsable field descriptor "
                                        + cp.descriptorOf(idx));
                                return;
                            }
                            // getstatic: -> value            putstatic: value ->
                            // getfield : objectref -> value  putfield : objectref, value ->
                            if (op == 0xb2)      { po = 0;      pu = sz; }
                            else if (op == 0xb3) { po = sz;     pu = 0;  }
                            else if (op == 0xb4) { po = 1;      pu = sz; }
                            else                 { po = 1 + sz; pu = 0;  }
                            break;
                        }
                        case 0xb6: case 0xb7: case 0xb8: case 0xb9: case 0xba: {
                            int want = (op == 0xb9) ? 11 : (op == 0xba) ? 18 : 10;
                            int got = cp.tagOf(idx);
                            // invokevirtual/special/static may name a Methodref (10) OR, for
                            // a default/private interface method, an InterfaceMethodref (11).
                            boolean ok = (got == want)
                                    || ((op == 0xb6 || op == 0xb7 || op == 0xb8) && got == 11);
                            if (!ok) {
                                report(who, "CPTAG", "bci " + bci + ": invoke opcode 0x"
                                        + Integer.toHexString(op) + " names cp[" + idx
                                        + "] tag " + got + " (expected " + want + ")");
                                return;
                            }
                            String md = cp.descriptorOf(idx);
                            int as = argSlots(md), rs = retSlots(md);
                            if (as < 0 || rs < 0) {
                                report(who, "CPTAG", "bci " + bci
                                        + ": unparsable method descriptor " + md);
                                return;
                            }
                            // Everything but invokestatic/invokedynamic also pops objectref.
                            int recv = (op == 0xb8 || op == 0xba) ? 0 : 1;
                            po = as + recv;
                            pu = rs;
                            if (op == 0xb9) {
                                // invokeinterface: indexbyte1,2 then count, then a zero byte.
                                // JVMS 6.5 requires count == argument slots + 1 (the receiver).
                                int cnt = body[cs + bci + 3] & 0xff;
                                int zero = body[cs + bci + 4] & 0xff;
                                if (cnt != as + 1 || zero != 0) {
                                    report(who, "INVOKEIFACE", "bci " + bci + ": count=" + cnt
                                            + " zero=" + zero + " but descriptor " + md
                                            + " needs count=" + (as + 1));
                                    return;
                                }
                            }
                            if (op == 0xba) {
                                // invokedynamic: indexbyte1,2 then two zero bytes.
                                int z1 = body[cs + bci + 3] & 0xff, z2 = body[cs + bci + 4] & 0xff;
                                if (z1 != 0 || z2 != 0) {
                                    report(who, "INVOKEDYN", "bci " + bci
                                            + ": trailing bytes not zero (" + z1 + "," + z2 + ")");
                                    return;
                                }
                            }
                            break;
                        }
                        case 0xc5: {                                    // multianewarray
                            int dims = body[cs + bci + 3] & 0xff;
                            if (dims < 1) {
                                report(who, "DECODE", "bci " + bci + ": multianewarray dimensions " + dims);
                                return;
                            }
                            po = dims; pu = 1;                          // dims counts, -> arrayref
                            break;
                        }
                        default:
                            report(who, "DECODE", "no slot rule for opcode 0x"
                                    + Integer.toHexString(op) + " at bci " + bci);
                            return;
                    }
                }

                // ldc width: GenerateOopMap pushes exactly one slot for ldc/ldc_w and
                // exactly two for ldc2_w, so naming the wrong constant kind silently
                // desynchronises the stack for the rest of the method.
                if (op == 0x12 || op == 0x13) {
                    int idx = (op == 0x12) ? (body[cs + bci + 1] & 0xff) : u16(body, cs + bci + 1);
                    int t = cp.tagOf(idx);
                    if (t == 5 || t == 6) {
                        report(who, "LDC_WIDTH", "bci " + bci + ": ldc"
                                + (op == 0x13 ? "_w" : "") + " names cp[" + idx + "] tag " + t
                                + " (Long/Double) but pushes only one slot; needs ldc2_w");
                        return;
                    }
                } else if (op == 0x14) {
                    int idx = u16(body, cs + bci + 1);
                    int t = cp.tagOf(idx);
                    if (t != 5 && t != 6 && t != 17) {
                        report(who, "LDC_WIDTH", "bci " + bci + ": ldc2_w names cp[" + idx
                                + "] tag " + t + " (not Long/Double/Dynamic) but pushes two slots");
                        return;
                    }
                }

                // Control-flow shape.
                if ((op >= 0x99 && op <= 0xa6) || op == 0xc6 || op == 0xc7) {
                    edges.add(new int[]{ bci, bci + i16(body, cs + bci + 1) });   // conditional
                } else if (op == 0xa7) {                                          // goto
                    edges.add(new int[]{ bci, bci + i16(body, cs + bci + 1) });
                    fall = false;
                } else if (op == 0xc8) {                                          // goto_w
                    edges.add(new int[]{ bci, bci + i32(body, cs + bci + 1) });
                    fall = false;
                } else if (op == 0xa8 || op == 0xc9) {                            // jsr / jsr_w
                    pending.add("jsr at bci " + bci);
                    fall = false;
                } else if (op == 0xa9) {                                          // ret
                    pending.add("ret at bci " + bci);
                    fall = false;
                } else if (op >= 0xac && op <= 0xb1) {                            // *return
                    fall = false;
                } else if (op == 0xbf) {                                          // athrow
                    fall = false;
                }
            }

            len[bci] = l; pop[bci] = po; push[bci] = pu; falls[bci] = fall;
            bci += l;
        }
        if (bci != codeLen) {
            report(who, "DECODE", "last instruction ends at " + bci
                                  + ", code_length " + codeLen);
            return;
        }

        // DEX has no subroutines, so our writer should never emit jsr/ret. If one
        // shows up the depth model would need the full JVMS 4.10.2.5 treatment, so
        // count the method as skipped rather than invent a false positive.
        if (!pending.isEmpty()) {
            skippedSubroutine++;
            note("SUBROUTINE", who + ": " + pending.get(0));
            return;
        }

        // Index the edges by source bci so the fixpoint does not rescan the list.
        edges.sort((a, b) -> Integer.compare(a[0], b[0]));
        int[] edgeTo = new int[edges.size()];
        {
            int e = 0;
            for (int b = 0; b <= codeLen; b++) {
                edgeStart[b] = e;
                while (e < edges.size() && edges.get(e)[0] == b) { edgeTo[e] = edges.get(e)[1]; e++; }
            }
        }

        // ---- pass 2: propagate depth ------------------------------------------
        final int UNSEEN = -1;
        int[] depth = new int[codeLen];
        java.util.Arrays.fill(depth, UNSEEN);
        ArrayDeque<Integer> work = new ArrayDeque<>();

        // Seed exactly as GenerateOopMap::mark_reachable_code() does.
        if (!seed(who, depth, work, 0, 0, codeLen, len, "entry")) return;

        int q = cs + codeLen;
        if (q + 2 > body.length) { report(who, "DECODE", "truncated before exception_table"); return; }
        int etLen = u16(body, q); q += 2;
        for (int i = 0; i < etLen; i++) {
            if (q + 8 > body.length) { report(who, "DECODE", "truncated exception_table"); return; }
            int h = u16(body, q + 4);
            q += 8;
            // GenerateOopMap sets _stack_top = 1 before merging into a handler: the
            // pending throwable is the only thing on the stack.
            if (!seed(who, depth, work, h, 1, codeLen, len, "handler")) return;
        }

        while (!work.isEmpty()) {
            int b = work.poll();
            int d = depth[b];
            if (d < pop[b]) {
                report(who, "UNDERFLOW", "bci " + b + " (op 0x"
                        + Integer.toHexString(body[cs + b] & 0xff) + ") pops " + pop[b]
                        + " slots but the stack holds " + d);
                return;
            }
            int after = d - pop[b] + push[b];
            if (after > maxStack) {
                report(who, "OVERFLOW", "bci " + b + " (op 0x"
                        + Integer.toHexString(body[cs + b] & 0xff) + ") leaves depth " + after
                        + " > max_stack " + maxStack);
                return;
            }
            if (falls[b]) {
                int nxt = b + len[b];
                if (nxt >= codeLen) {
                    report(who, "FALLOFF", "bci " + b + " (op 0x"
                            + Integer.toHexString(body[cs + b] & 0xff)
                            + ") falls through past the end of the code");
                    return;
                }
                if (!propagate(who, depth, work, nxt, after, codeLen, len, b)) return;
            }
            for (int e = edgeStart[b]; e < edgeStart[b + 1]; e++) {
                if (!propagate(who, depth, work, edgeTo[e], after, codeLen, len, b)) return;
            }
        }
    }

    /** Seed a root (entry / exception handler) at a fixed depth. */
    static boolean seed(String who, int[] depth, ArrayDeque<Integer> work,
                        int bci, int d, int codeLen, int[] len, String what) {
        if (bci < 0 || bci >= codeLen || len[bci] == 0) {
            report(who, "DECODE", what + " bci " + bci + " is not an instruction start");
            return false;
        }
        if (depth[bci] == -1) { depth[bci] = d; work.add(bci); return true; }
        if (depth[bci] != d) {
            report(who, "CONFLICT", "bci " + bci + " reached as " + what + " at depth " + d
                    + " but already had depth " + depth[bci]);
            return false;
        }
        return true;
    }

    /** Merge a computed depth into a successor. Mirrors merge_state_into_bb(). */
    static boolean propagate(String who, int[] depth, ArrayDeque<Integer> work,
                             int bci, int d, int codeLen, int[] len, int from) {
        if (bci < 0 || bci >= codeLen || len[bci] == 0) {
            report(who, "DECODE", "edge from bci " + from + " to " + bci
                    + " is not an instruction start");
            return false;
        }
        if (depth[bci] == -1) { depth[bci] = d; work.add(bci); return true; }
        if (depth[bci] != d) {
            // This is HotSpot's "stack height conflict: %d vs. %d".
            report(who, "CONFLICT", "bci " + bci + " is reachable at depth " + d
                    + " (from bci " + from + ") and at depth " + depth[bci]);
            return false;
        }
        return true;
    }

    static void report(String who, String kind, String msg) {
        badMethods++;
        byKind.merge(kind, 1, Integer::sum);
        failures.add("[" + kind + "] " + who + ": " + msg);
    }

    /** A non-fatal observation: counted by kind, not counted as a bad method. */
    static void note(String kind, String msg) {
        byKind.merge("note:" + kind, 1, Integer::sum);
        if (notes.size() < 5) notes.add("[note:" + kind + "] " + msg);
    }

    static int u16(byte[] b, int p) { return ((b[p] & 0xff) << 8) | (b[p + 1] & 0xff); }
    static int i16(byte[] b, int p) { return (short) u16(b, p); }
    static int i32(byte[] b, int p) {
        return ((b[p] & 0xff) << 24) | ((b[p + 1] & 0xff) << 16)
             | ((b[p + 2] & 0xff) << 8) | (b[p + 3] & 0xff);
    }
}
