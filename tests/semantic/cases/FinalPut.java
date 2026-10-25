// dexsem: no-r8
// dexsem: dex-final FinalPut$Table.TABLE FinalPut$Table.SEED FinalPut$Table.CALLS
// dexsem: dex-final FinalPut$Box.value FinalPut$Box.DEFAULT FinalPut$Box.MADE
//
// Semantic cover for DEX2JVM_UNFINAL_FOREIGN_WRITES. DEX code may write its
// own final fields from ANY method of the class: ART's verifier refuses a put
// to a final field only from another class. The JVM allows it only in the
// initializer (<clinit> for a static, <init> for an instance field), and
// HotSpot enforces that from class file version 53, which the nest these
// classes form raises them to (NestHost/NestMembers need 55). Obfuscators emit
// exactly this: a static init$0() that <clinit> calls to fill a private static
// final table. Converted as it is, the first such write is "IllegalAccessError:
// Update to static final field ... attempted from a different method (init$0)
// than the initializer method <clinit>", and the class never initializes.
//
// javac cannot compile such code, so the fields are declared non-final here
// (the oracle has to compile and run), and run.sh sets ACC_FINAL on them in the
// DEX (the dex-final lines above; tools/DexSetFinal.java). That turns the d8
// output into the shape an obfuscated app ships. The converted classes must
// then print exactly what the oracle prints; with
// DEX2JVM_UNFINAL_FOREIGN_WRITES=0 this case fails on the first write.
//
//   Table.TABLE   static, written only by init$0, which <clinit> calls
//   Table.SEED    static that <clinit> sets (d8 keeps the store there) and
//                 init$0 then reads and overwrites
//   Table.CALLS   static, written by a method called long after <clinit>
//   Box.value     instance, written by a private method the constructor
//                 calls, by a static factory, by an instance method, and by
//                 <clinit> (a putfield outside <init> is as illegal)
//   Box.DEFAULT   static written only by <clinit>: legal, keeps ACC_FINAL
//   Box.MADE      static written by <init>
//
// No r8: the marker names fields, and R8 renames, inlines and removes them.

public class FinalPut {

    static final class Table {
        private static byte[] TABLE;
        private static int SEED = 7;
        private static int CALLS;

        static {
            init$0();
        }

        static void init$0() {
            TABLE = new byte[] { 82, 42, -87, -21 };
            SEED = SEED * 3 + TABLE.length;
        }

        static int bump() {
            CALLS = CALLS + 1;
            return CALLS;
        }

        static String describe() {
            StringBuilder sb = new StringBuilder("table");
            for (byte x : TABLE) sb.append(' ').append(x);
            return sb.append(" seed ").append(SEED).append(" calls ").append(CALLS).toString();
        }
    }

    static final class Box {
        private int value;
        private static Box DEFAULT;
        private static int MADE;

        static {
            DEFAULT = new Box(1);
            DEFAULT.value = DEFAULT.value + 100;
        }

        Box(int v) {
            set(v);
            MADE = MADE + 1;
        }

        private void set(int v) {
            value = v * 2;
        }

        static Box make(int v) {
            Box b = new Box(0);
            b.value = v;
            return b;
        }

        void add(int d) {
            value += d;
        }

        int value() { return value; }

        static Box defaultBox() { return DEFAULT; }

        static int made() { return MADE; }
    }

    public static void main(String[] args) {
        System.out.println(Table.describe());
        System.out.println("bump " + Table.bump() + " " + Table.bump() + " " + Table.bump());
        System.out.println(Table.describe());

        Box a = new Box(5);
        Box b = Box.make(41);
        b.add(1);
        System.out.println("box " + a.value() + " " + b.value() + " default " + Box.defaultBox().value()
                + " made " + Box.made());
    }
}
