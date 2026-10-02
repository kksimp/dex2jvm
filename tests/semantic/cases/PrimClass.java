// const-class on a PRIMITIVE type.
//
// Dalvik bytecode spec, const-class (1c 21c): "In the case where the indicated
// type is primitive, this will store a reference to the primitive type's
// degenerate class." Older dx/d8 builds emitted exactly that for a
// multi-dimensional primitive array (`new boolean[r][c]` -> const-class Z +
// Array.newInstance(Class, int[])); the d8 this harness runs spells it
// sget Boolean.TYPE instead, so this case pins the BEHAVIOUR only: it CANNOT
// produce `const-class Z` and therefore passes on the pre-fix converter too (NO
// positive control). The evidence for the fix is a real-input check: the
// converted classes of 1010! Klooni (a real `const-class Z` input) must contain
// no `ldc` of a class named "Z" (the pre-fix converter emitted exactly that).
// The converter translated const-class to `ldc <class>` unconditionally, and a JVM class constant cannot name a
// primitive, so HotSpot resolved a class literally named "Z" and threw
// NoClassDefFoundError: Z. Measured on 1010! Klooni: Piece.<init> allocates
// boolean[][], so the converted constructor threw at run time and the game's
// Play button never opened the game screen.
//
// Every primitive component kind, 2-D and 3-D, plus reading back the element
// class names (which must be the primitive names, not wrapper names).
public class PrimClass {
    public static void main(String[] a) {
        int r = a.length + 2, c = a.length + 3;
        boolean[][] z = new boolean[r][c]; z[1][2] = true;
        byte[][] b = new byte[r][c];       b[1][2] = 7;
        short[][] s = new short[r][c];     s[1][2] = 300;
        char[][] ch = new char[r][c];      ch[1][2] = 'q';
        int[][] i = new int[r][c];         i[1][2] = 42;
        long[][] l = new long[r][c];       l[1][2] = 1L << 40;
        float[][] f = new float[r][c];     f[1][2] = 1.5f;
        double[][] d = new double[r][c];   d[1][2] = 2.25;
        int[][][] i3 = new int[r][c][r + 2]; i3[1][2][3] = 9;
        System.out.println(z.length + " " + z[0].length + " " + z[1][2] + " " + z[0][0]);
        System.out.println(b[1][2] + " " + s[1][2] + " " + ch[1][2] + " " + i[1][2]);
        System.out.println(l[1][2] + " " + f[1][2] + " " + d[1][2] + " " + i3[1][2][3]);
        System.out.println(z.getClass().getName() + " " + z.getClass().getComponentType().getName()
            + " " + d.getClass().getComponentType().getComponentType().getName()
            + " " + i3.getClass().getName());
    }
}
