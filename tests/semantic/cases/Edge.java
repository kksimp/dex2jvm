// Edge cases that stress the ENCODING rather than the language: wide local
// indices, big argument counts, deep expression nesting, and initialisation
// failures. These are where a converter's register->local mapping and its
// stack-depth accounting break.
import java.util.*;
public class Edge {
    public static void main(String[] a) {
        p("wide-locals", wideLocals());
        p("manyargs", many(1,2,3,4,5,6,7,8,9,10,11,12,13,14,15,16,17,18,19,20));
        p("manywide", manyWide(1L,2.0,3L,4.0,5L,6.0,7L,8.0));
        p("deepexpr", deep());
        p("deepstack", deepStack(1,2,3,4,5,6,7,8));
        p("enumbody", enumBodies());
        p("enumvalues", Arrays.toString(Op.values()) + "/" + Op.valueOf("ADD").ordinal()
                        + "/" + Op.ADD.name() + "/" + Op.ADD.compareTo(Op.MUL));
        p("clinit-fail", clinitFail());
        p("shadow", shadow());
        p("ifaceconst", I.K + "/" + I.NAME + "/" + I.ARR.length);
        p("surrogate", surrogate());
        p("nestedloopfin", nestedLoopFinally());
        p("longswitch", longSwitch(0) + longSwitch(1) + longSwitch(50) + longSwitch(99));
        p("condstack", condStack(3) + "/" + condStack(-3));
        p("arrstore-loop", arrStoreLoop());
        p("interface-generic", new GImpl().apply("z"));
    }

    // >4 locals forces wide-ish indexing; >255 forces the `wide` opcode prefix.
    static long wideLocals() {
        long v0=0,v1=1,v2=2,v3=3,v4=4,v5=5,v6=6,v7=7,v8=8,v9=9;
        long w0=10,w1=11,w2=12,w3=13,w4=14,w5=15,w6=16,w7=17,w8=18,w9=19;
        long x0=20,x1=21,x2=22,x3=23,x4=24,x5=25,x6=26,x7=27,x8=28,x9=29;
        long y0=30,y1=31,y2=32,y3=33,y4=34,y5=35,y6=36,y7=37,y8=38,y9=39;
        long z0=40,z1=41,z2=42,z3=43,z4=44,z5=45,z6=46,z7=47,z8=48,z9=49;
        double d0=0.5,d1=1.5,d2=2.5,d3=3.5,d4=4.5,d5=5.5,d6=6.5,d7=7.5;
        long s = v0+v1+v2+v3+v4+v5+v6+v7+v8+v9 + w0+w1+w2+w3+w4+w5+w6+w7+w8+w9
               + x0+x1+x2+x3+x4+x5+x6+x7+x8+x9 + y0+y1+y2+y3+y4+y5+y6+y7+y8+y9
               + z0+z1+z2+z3+z4+z5+z6+z7+z8+z9;
        return s + (long) (d0+d1+d2+d3+d4+d5+d6+d7);
    }
    static int many(int p1,int p2,int p3,int p4,int p5,int p6,int p7,int p8,int p9,int p10,
                    int p11,int p12,int p13,int p14,int p15,int p16,int p17,int p18,int p19,int p20) {
        return p1+p2*2+p3*3+p4*4+p5*5+p6*6+p7*7+p8*8+p9*9+p10*10
             + p11*11+p12*12+p13*13+p14*14+p15*15+p16*16+p17*17+p18*18+p19*19+p20*20;
    }
    // Wide args occupy TWO register words each; a converter that miscounts the
    // parameter offset reads the wrong register and returns garbage.
    static String manyWide(long a1,double b1,long a2,double b2,long a3,double b3,long a4,double b4) {
        return (a1+a2+a3+a4) + "/" + (b1+b2+b3+b4);
    }
    static int deep() {
        return ((((1+2)*(3+4))-((5+6)*(7-8)))*(((9+10)/(11-12))+((13*14)%(15+16))))
             + ((((17-18)*(19+20))+((21/22)-(23%24)))*(((25+26)*(27-28))+((29+30)/(31-32))));
    }
    static int deepStack(int a1,int a2,int a3,int a4,int a5,int a6,int a7,int a8) {
        return many(a1,a2,a3,a4,a5,a6,a7,a8,
                    many(1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1,1),
                    many(2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2,2),
                    a1,a2,a3,a4,a5,a6,a7,a8,
                    many(3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,3,3),
                    a1);
    }

    // Constant-specific class bodies compile to anonymous subclasses of the enum.
    enum Op {
        ADD { int apply(int x, int y) { return x + y; } },
        SUB { int apply(int x, int y) { return x - y; } },
        MUL { int apply(int x, int y) { return x * y; } };
        abstract int apply(int x, int y);
    }
    static String enumBodies() {
        StringBuilder s = new StringBuilder();
        for (Op o : Op.values()) s.append(o).append('=').append(o.apply(7, 3)).append(' ');
        return s.toString();
    }

    static class Boom { static final int V; static { V = 1; if (V == 1) throw new RuntimeException("boom"); } }
    static String clinitFail() {
        StringBuilder s = new StringBuilder();
        // First touch gives ExceptionInInitializerError, every later touch gives
        // NoClassDefFoundError. Both must survive translation.
        try { s.append(Boom.V); } catch (Throwable t) { s.append(t.getClass().getSimpleName()); }
        try { s.append('|').append(Boom.V); } catch (Throwable t) { s.append('|').append(t.getClass().getSimpleName()); }
        return s.toString();
    }

    static class P { int f = 1; static int sf = 10; int get() { return f; } }
    static class C extends P { int f = 2; static int sf = 20;
        int both() { return f + super.f + ((P) this).f + sf + P.sf + get(); } }
    static String shadow() { C c = new C(); return c.both() + "/" + c.f + "/" + ((P) c).f; }

    interface I { int K = 7; String NAME = "iface"; int[] ARR = { 1, 2, 3 }; }
    interface G<T> { String apply(T t); default String twice(T t) { return apply(t) + apply(t); } }
    static class GImpl implements G<String> { public String apply(String s) { return "[" + s + "]"; } }

    static String surrogate() {
        String s = "a😀b";              // one astral char as a surrogate pair
        return s.length() + "/" + s.codePointCount(0, s.length()) + "/"
             + Integer.toHexString(s.codePointAt(1)) + "/" + (int) s.charAt(1) + "/" + (int) s.charAt(2);
    }
    static String nestedLoopFinally() {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            try { for (int j = 0; j < 3; j++) {
                    try { if (j == 1) continue; if (i == 2) break; s.append(i).append(j); }
                    finally { s.append('f'); } } }
            finally { s.append('F'); }
        }
        return s.toString();
    }
    static int longSwitch(int i) {
        switch (i) { case 0: return 1; case 1: return 2; case 50: return 3; case 99: return 4; default: return 0; }
    }
    // A conditional whose two arms leave different types on the stack forces a
    // merge the frame writer must describe.
    static String condStack(int v) { Object o = v > 0 ? "pos" : Integer.valueOf(v); return o.getClass().getSimpleName() + ":" + o; }
    static String arrStoreLoop() {
        Object[] arr = new Object[6];
        for (int i = 0; i < 6; i++) arr[i] = (i % 2 == 0) ? Integer.valueOf(i) : ("s" + i);
        return Arrays.toString(arr);
    }
    static void p(String k, Object v) { System.out.println(k + "=" + v); }
}
