// AndroidSdk -- finds the android.jar an app was built against, so the CLI can
// use it as the class-hierarchy library without the user passing --classpath.
//
// WHY. ClassHierarchyOracle computes least-upper-bounds for stack-map frames.
// For a merge of two framework types (two Views, a Drawable and a ColorDrawable)
// it needs the framework's class hierarchy; without it the merge widens to
// java/lang/Object, which is sound but which HotSpot's split verifier can reject.
// The right library is the API surface the app was COMPILED against, which the
// app records in its own manifest.
//
// HOW.
//   1. Which API level: read the base apk's binary AndroidManifest.xml (AXML)
//      and take, in order, android:compileSdkVersion (on <manifest>, written by
//      aapt2 for apps built with compileSdk 28+), platformBuildVersionCode (the
//      older aapt spelling of the same fact), then android:targetSdkVersion.
//   2. Which SDK: --classpath wins; otherwise ANDROID_HOME, ANDROID_SDK_ROOT,
//      then the Android Studio default location for this OS.
//   3. Which platform: platforms/android-<N>/android.jar for exactly that level
//      if installed, else the nearest NEWER one (a superset of the API the app
//      used), else the newest older one. No level known (a bare .dex): newest.
//
// Nothing here is required for a correct conversion: if no SDK is found the
// converter runs exactly as it would with no --classpath, and Main says so.
//
// Binary XML layout: AOSP frameworks/base/libs/androidfw/include/androidfw/
// ResourceTypes.h (ResChunk_header, ResXMLTree_node, ResXMLTree_attrExt,
// ResXMLTree_attribute, Res_value).

package io.github.kksimp.dex2jvm;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

final class AndroidSdk {
    private AndroidSdk() {}

    /** android:targetSdkVersion (public android.R.attr). */
    static final int ATTR_TARGET_SDK = 0x01010270;
    /** android:compileSdkVersion. Not in the public R.attr stubs, so the id is
     *  taken from what aapt2 writes into real manifests (AOSP
     *  frameworks/base/core/res/res/values/public-final.xml). */
    static final int ATTR_COMPILE_SDK = 0x01010572;

    /** The chosen library jar, and why it was chosen (for the log line). */
    static final class Choice {
        final Path jar;
        final String reason;
        Choice(Path jar, String reason) { this.jar = jar; this.reason = reason; }
    }

    /** The android.jar to use for {@code input}, or null when no SDK platform
     *  is installed anywhere we know to look. */
    static Choice choose(Path input) {
        Path sdk = findSdk();
        if (sdk == null) return null;
        List<int[]> levels = new ArrayList<>();         // {apiLevel, index}
        List<Path> jars = new ArrayList<>();
        File[] dirs = sdk.resolve("platforms").toFile().listFiles();
        if (dirs == null) return null;
        for (File d : dirs) {
            String n = d.getName();
            if (!n.startsWith("android-")) continue;
            int api = leadingInt(n.substring("android-".length()));
            Path jar = d.toPath().resolve("android.jar");
            if (api <= 0 || !Files.isRegularFile(jar)) continue;
            // Prefer the plain platform over an extension level ("android-33-ext5").
            boolean plain = n.equals("android-" + api);
            int existing = -1;
            for (int i = 0; i < levels.size(); i++) if (levels.get(i)[0] == api) existing = i;
            if (existing >= 0) {
                if (plain) jars.set(levels.get(existing)[1], jar);
                continue;
            }
            levels.add(new int[] {api, jars.size()});
            jars.add(jar);
        }
        if (levels.isEmpty()) return null;

        int want = appApiLevel(input);
        int[] best = null;
        for (int[] l : levels) {
            if (want <= 0) {
                if (best == null || l[0] > best[0]) best = l;
            } else if (l[0] >= want) {
                if (best == null || best[0] < want || l[0] < best[0]) best = l;
            } else if (best == null || (best[0] < want && l[0] > best[0])) {
                best = l;
            }
        }
        String why = want <= 0 ? "newest installed platform; the input names no API level"
                : best[0] == want ? "the app was built against API " + want
                : "nearest installed platform to the app's API " + want;
        return new Choice(jars.get(best[1]), why);
    }

    static Path findSdk() {
        List<String> candidates = new ArrayList<>();
        for (String env : new String[] {"ANDROID_HOME", "ANDROID_SDK_ROOT"}) {
            String v = System.getenv(env);
            if (v != null && !v.isEmpty()) candidates.add(v);
        }
        String home = System.getProperty("user.home");
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("mac")) candidates.add(home + "/Library/Android/sdk");
        else if (os.contains("win")) {
            String local = System.getenv("LOCALAPPDATA");
            if (local != null) candidates.add(local + "\\Android\\Sdk");
        } else candidates.add(home + "/Android/Sdk");
        for (String c : candidates) {
            Path p = Paths.get(c);
            if (Files.isDirectory(p.resolve("platforms"))) return p;
        }
        return null;
    }

    /** The API level the app was built against, or 0 when unknown. */
    static int appApiLevel(Path input) {
        try {
            byte[] manifest = baseManifest(input);
            return manifest == null ? 0 : apiLevelFromManifest(manifest);
        } catch (IOException | RuntimeException e) {
            return 0;                      // advisory only: never fail a convert
        }
    }

    /** The base apk's AndroidManifest.xml bytes, or null (bare .dex, or no manifest). */
    private static byte[] baseManifest(Path input) throws IOException {
        try (InputStream is = Files.newInputStream(input)) {
            byte[] head = is.readNBytes(4);
            if (head.length == 4 && head[0] == 'd' && head[1] == 'e' && head[2] == 'x') return null;
        }
        try (ZipFile zip = new ZipFile(input.toFile())) {
            ZipEntry m = zip.getEntry("AndroidManifest.xml");
            if (m != null) {
                try (InputStream is = zip.getInputStream(m)) { return is.readAllBytes(); }
            }
            // A bundle: the base apk comes first in the same order Main merges in.
            List<ZipEntry> apks = Main.selectBundleApks(zip);
            if (apks.isEmpty()) return null;
            byte[] nested;
            try (InputStream is = zip.getInputStream(apks.get(0))) { nested = is.readAllBytes(); }
            try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(nested))) {
                for (ZipEntry e; (e = zis.getNextEntry()) != null; ) {
                    if (e.getName().equals("AndroidManifest.xml")) return zis.readAllBytes();
                }
            }
        }
        return null;
    }

    /** compileSdkVersion, else platformBuildVersionCode, else targetSdkVersion; 0 if none. */
    static int apiLevelFromManifest(byte[] axml) {
        ByteBuffer b = ByteBuffer.wrap(axml).order(ByteOrder.LITTLE_ENDIAN);
        if (b.remaining() < 8 || (b.getShort(0) & 0xffff) != 0x0003) return 0;   // RES_XML_TYPE
        List<String> strings = null;
        int[] resMap = new int[0];
        int compile = 0, platformBuild = 0, target = 0;
        int pos = b.getShort(2) & 0xffff;                                       // file header size
        while (pos + 8 <= axml.length) {
            int type = b.getShort(pos) & 0xffff;
            int headerSize = b.getShort(pos + 2) & 0xffff;
            int size = b.getInt(pos + 4);
            if (size < 8 || pos + size > axml.length) break;
            if (type == 0x0001) {                                               // RES_STRING_POOL_TYPE
                strings = stringPool(b, pos);
            } else if (type == 0x0180) {                                        // RES_XML_RESOURCE_MAP_TYPE
                int n = (size - headerSize) / 4;
                resMap = new int[n];
                for (int i = 0; i < n; i++) resMap[i] = b.getInt(pos + headerSize + 4 * i);
            } else if (type == 0x0102 && strings != null) {                     // RES_XML_START_ELEMENT_TYPE
                int ext = pos + headerSize;
                int attrStart = b.getShort(ext + 8) & 0xffff;
                int attrSize = b.getShort(ext + 10) & 0xffff;
                int attrCount = b.getShort(ext + 12) & 0xffff;
                for (int i = 0; i < attrCount; i++) {
                    int a = ext + attrStart + i * attrSize;
                    int nameIdx = b.getInt(a + 4);
                    int rawIdx = b.getInt(a + 8);
                    int dataType = b.get(a + 15) & 0xff;
                    int data = b.getInt(a + 16);
                    int value = intValue(strings, rawIdx, dataType, data);
                    // An API level is 1..99; anything else is some other number
                    // (one real app writes a version code into platformBuildVersionCode).
                    if (value <= 0 || value > 99) continue;
                    int id = nameIdx >= 0 && nameIdx < resMap.length ? resMap[nameIdx] : 0;
                    String name = nameIdx >= 0 && nameIdx < strings.size() ? strings.get(nameIdx) : "";
                    if (id == ATTR_COMPILE_SDK) compile = value;
                    else if (id == ATTR_TARGET_SDK) target = value;
                    else if (id == 0 && name.equals("platformBuildVersionCode")) platformBuild = value;
                }
            }
            pos += size;
        }
        return compile > 0 ? compile : platformBuild > 0 ? platformBuild : target;
    }

    /** An attribute's integer value: a typed int, or a decimal string ("34"). */
    private static int intValue(List<String> strings, int rawIdx, int dataType, int data) {
        if (dataType == 0x10 || dataType == 0x11) return data;              // TYPE_INT_DEC / INT_HEX
        if (dataType == 0x03 && rawIdx >= 0 && rawIdx < strings.size()) {   // TYPE_STRING
            return leadingInt(strings.get(rawIdx));
        }
        return 0;
    }

    private static List<String> stringPool(ByteBuffer b, int pos) {
        int headerSize = b.getShort(pos + 2) & 0xffff;
        int count = b.getInt(pos + 8);
        int flags = b.getInt(pos + 16);
        int stringsStart = b.getInt(pos + 20);
        boolean utf8 = (flags & 0x100) != 0;                                // UTF8_FLAG
        List<String> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int off = pos + stringsStart + b.getInt(pos + headerSize + 4 * i);
            try {
                if (utf8) {
                    int p = off;
                    p += (b.get(p) & 0x80) != 0 ? 2 : 1;                     // UTF-16 length, skipped
                    int len = b.get(p) & 0xff;
                    if ((len & 0x80) != 0) { len = ((len & 0x7f) << 8) | (b.get(p + 1) & 0xff); p += 2; }
                    else p += 1;
                    byte[] bytes = new byte[len];
                    b.get(p, bytes);
                    out.add(new String(bytes, StandardCharsets.UTF_8));
                } else {
                    int len = b.getShort(off) & 0xffff;
                    int p = off + 2;
                    if ((len & 0x8000) != 0) { len = ((len & 0x7fff) << 16) | (b.getShort(p) & 0xffff); p += 2; }
                    char[] cs = new char[len];
                    for (int k = 0; k < len; k++) cs[k] = b.getChar(p + 2 * k);
                    out.add(new String(cs));
                }
            } catch (IndexOutOfBoundsException e) {
                out.add("");
            }
        }
        return out;
    }

    private static int leadingInt(String s) {
        int n = 0, i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i)) && i < 4) n = n * 10 + (s.charAt(i++) - '0');
        return i == 0 ? 0 : n;
    }
}
