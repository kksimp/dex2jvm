// ApiIndexLoader -- serves the Android SDK's class hierarchy from a small index
// bundled in the jar, so a conversion is precise even with no Android SDK
// installed.
//
// WHAT IT REPLACES. ClassHierarchyOracle and SuperInterfacePlan read library
// classes through a ClassLoader, as class-file HEADERS only: access flags,
// superclass and direct interfaces (never fields, methods or code). An
// android.jar answers those questions, but it is a 60 MB SDK download under
// Google's SDK license. Those few facts per class are all the converter needs,
// and AOSP publishes them under Apache 2.0 in its api/current.txt signature
// files. tools/api-index/gen.py turns those files into android-api.idx.gz
// (about 5,000 classes in ~40 KB); this loader turns each index line back into
// a minimal class file on request, so the oracle code runs unchanged.
//
// Measured against the real android.jar for API 34: the index built from
// AOSP's android14-release files covered 4,332 of its 4,342 non-java classes
// (the 10 missing are annotation types and two hidden-but-stubbed classes),
// with zero disagreements on superclass, interfaces or the interface flag.
//
// java.* and javax.* are not in the index: the parent (platform) loader serves
// them from the running JDK, and parent-first delegation means the JDK always
// wins for any name both could answer.

package io.github.kksimp.dex2jvm;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

final class ApiIndexLoader extends ClassLoader {

    static final String RESOURCE = "android-api.idx.gz";

    /** internal name -> the index line's fields: {flags, super, iface...}. */
    private final Map<String, String[]> entries;
    /** "API 36", from the index header, for the log line. */
    final String description;

    private ApiIndexLoader(Map<String, String[]> entries, String description) {
        super(ClassLoader.getPlatformClassLoader());
        this.entries = entries;
        this.description = description;
    }

    /** The bundled index, or null if it is missing from the jar. */
    static ApiIndexLoader bundled() {
        try (InputStream raw = ApiIndexLoader.class.getResourceAsStream(RESOURCE)) {
            if (raw == null) return null;
            Map<String, String[]> map = new HashMap<>();
            String desc = "bundled Android API index";
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(raw), StandardCharsets.UTF_8))) {
                for (String line; (line = r.readLine()) != null; ) {
                    if (line.startsWith("#")) {
                        // "# dex2jvm Android API hierarchy index: API 36, ..."
                        java.util.regex.Matcher m =
                                java.util.regex.Pattern.compile("index: API (\\d+)").matcher(line);
                        if (m.find()) desc = "bundled Android API " + m.group(1) + " index";
                        continue;
                    }
                    String[] f = line.split(" ");
                    if (f.length < 3) continue;
                    String[] v = new String[f.length - 1];
                    v[0] = f[0];
                    System.arraycopy(f, 2, v, 1, f.length - 2);
                    map.put(f[1], v);
                }
            }
            return new ApiIndexLoader(map, desc);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    int size() { return entries.size(); }

    @Override
    protected URL findResource(String name) {
        if (!name.endsWith(".class")) return null;
        String internal = name.substring(0, name.length() - ".class".length());
        String[] e = entries.get(internal);
        if (e == null) return null;
        byte[] bytes = classFile(internal, e);
        try {
            return new URL("dex2jvm-api", null, -1, "/" + name, new URLStreamHandler() {
                @Override protected URLConnection openConnection(URL u) {
                    return new URLConnection(u) {
                        @Override public void connect() {}
                        @Override public InputStream getInputStream() {
                            return new ByteArrayInputStream(bytes);
                        }
                    };
                }
            });
        } catch (java.net.MalformedURLException ex) {
            return null;
        }
    }

    /**
     * A class file holding only a header: magic, version 52, a constant pool of
     * Utf8 + Class entries for this class, its superclass and its interfaces,
     * the access flags, and empty field / method / attribute tables (JVMS 4.1).
     */
    private static byte[] classFile(String name, String[] e) {
        int flags = Integer.parseInt(e[0], 16);
        String sup = e[1].equals("-") ? null : e[1];
        int ifaces = e.length - 2;
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(64 + 32 * ifaces);
            DataOutputStream out = new DataOutputStream(bos);
            out.writeInt(0xCAFEBABE);
            out.writeShort(0);
            out.writeShort(52);
            int classes = 1 + (sup != null ? 1 : 0) + ifaces;
            out.writeShort(1 + 2 * classes);              // constant_pool_count
            int next = 1;
            int thisIdx = next;
            next = writeClass(out, name, next);
            int superIdx = 0;
            if (sup != null) { superIdx = next; next = writeClass(out, sup, next); }
            int[] ifaceIdx = new int[ifaces];
            for (int i = 0; i < ifaces; i++) { ifaceIdx[i] = next; next = writeClass(out, e[2 + i], next); }
            out.writeShort(flags);
            out.writeShort(thisIdx + 1);                  // the Class entry follows its Utf8
            out.writeShort(superIdx == 0 ? 0 : superIdx + 1);
            out.writeShort(ifaces);
            for (int i : ifaceIdx) out.writeShort(i + 1);
            out.writeShort(0);                            // fields
            out.writeShort(0);                            // methods
            out.writeShort(0);                            // attributes
            out.flush();
            return bos.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException(ex);          // a byte array cannot fail
        }
    }

    /** Writes CONSTANT_Utf8 then CONSTANT_Class at {@code at}, at+1; returns at+2. */
    private static int writeClass(DataOutputStream out, String internal, int at) throws IOException {
        out.writeByte(1);
        out.writeUTF(internal);                           // modified UTF-8, as JVMS 4.4.7 requires
        out.writeByte(7);
        out.writeShort(at);
        return at + 2;
    }
}
