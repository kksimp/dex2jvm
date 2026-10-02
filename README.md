# dex2jvm

**A DEX-to-JVM bytecode converter built to *run* the output, not just read it.**

dex2jvm converts the Dalvik bytecode inside an Android app (`classes*.dex`) into
standard Java class files that load and run on a stock HotSpot JVM **with
bytecode verification on**. It was built for [Mac.apk](https://github.com/kksimp/Mac.apk-Releases),
which runs Android apps on macOS, and is released here as a standalone tool.

## Why does this exist?

Android apps contain DEX bytecode, designed for Android's own runtime (ART,
formerly Dalvik). If you want to run Android app code inside a regular JVM,
converting DEX into something the JVM will actually verify and execute is much
harder than producing a jar that a decompiler can open.

```
classes.dex  -->  dex2jvm  -->  JVM class files  -->  HotSpot (verification on)
```

Tools like dex2jar and enjarify are mostly used for the decompiler case.
dex2jvm is built for the execution case: it targets the strict class-file rules
(version 52, full StackMapTable frames) and preserves the metadata real apps
depend on at run time.

## What it does that the older tools don't

- **Passes HotSpot's strict split verifier.** Output is class-file version 52,
  verified with stack-map frames. Frames are computed from real
  least-upper-bounds over the app's own classes plus a library classpath.
- **Methods over the JVM's 64 KB limit.** Dalvik has no method size limit; the
  JVM caps a method at 65,535 bytes. dex2jvm hoists straight-line regions into
  helper methods, or splits a method with complex control flow into a chain of
  methods. A method it cannot rescue becomes a stub that throws, and the rest of
  the class is kept.
- **R8-minified input.** Shipping apps are built by R8, which emits shapes a JVM
  rejects: constructor calls re-pointed at a superclass, `invoke-super` on an
  indirect interface, private access between classes whose nest metadata was
  stripped. Each is rewritten into its JVM-legal equivalent.
- **Modern bytecode:** `invoke-custom`, `invoke-polymorphic`,
  `const-method-handle`, `const-method-type`.
- **Metadata:** annotations, generic signatures, InnerClasses / EnclosingMethod,
  Exceptions, MethodParameters, AnnotationDefault, LineNumberTable and
  SourceFile. Reflection-driven libraries (Retrofit, Gson, Moshi, Kotlin
  reflection) work, and stack traces have file and line numbers.
- **JIT-friendly synchronized code.** Exception tables are shaped so HotSpot can
  prove monitors balanced and JIT-compile `synchronized` methods.
- **Split APKs:** `.apk`, `.apkm`, `.xapk`, `.apks` (bundletool), plus bare `.dex`.
- **Fast, parallel and deterministic:** up to 8 threads, with byte-identical
  output at any thread count. Pure Java, no dependencies.

## Usage

Requires JDK 21 or newer.

```bash
./build.sh
java -jar build/dex2jvm.jar app.apk app.jar
```

dex2jvm needs the Android API's class hierarchy to compute precise stack-map
frames for framework types (without it, AnkiDroid goes from 0 rejected classes
to 169). It picks one automatically:

1. **An installed Android SDK.** If `ANDROID_HOME`, `ANDROID_SDK_ROOT` or the
   Android Studio default location has a platform installed, dex2jvm reads the
   app's manifest and uses the `android.jar` of the API level it was compiled
   against (or the nearest installed one).
2. **Otherwise, a bundled index.** dex2jvm ships a 42 KB index of the Android
   API 36 class hierarchy, generated from AOSP's public API files, so it works
   with no SDK at all.

The first line of output says which one it used. `--classpath <android.jar>`
overrides the choice.

| option | |
|---|---|
| `--classpath <path>` | jars/dirs the class-hierarchy oracle reads library types from (header only, nothing is loaded); overrides the automatic choice above |
| `--no-library` | use no library hierarchy at all (less precise; for comparison) |
| `--threads <n>` | worker threads (default `min(cores, 8)`) |
| `--synthetic-prefix <s>` | prefix for synthetic members (default `dex2jvm`) |
| `--redirect-unsafe <cls>` | route `sun.misc.Unsafe` calls to static methods on `<cls>` (for hosts whose Unsafe semantics differ from ART's) |
| `--redirect-exit <cls>` | route `System.exit` / `Runtime.exit` / `Runtime.halt` to `<cls>` |

Exit codes: `0` jar written, `1` bad input, `2` usage, `3` the input has no DEX
(a valid `hasCode="false"` app), `4` the input parsed but conversion failed (for
example out of memory: try a larger `-Xmx`).

A class that fails to translate is reported on stderr and left out, and the
rest of the app still converts. Large apps may need more heap
(`java -Xmx4g -jar ...`).

From Java, `DexConverter.open(dexBytes...)` returns a session that converts
everything or one class at a time (`classBytes(name)`), so it can also back a
class loader that converts on demand.

## Benchmarks

Measured 2026-10-02 on an Apple Silicon Mac, JDK 21, against
[enjarify](https://github.com/google/enjarify) at its latest commit (`f2db056`,
CPython 3) and [dex2jar](https://github.com/pxb1988/dex2jar) v2.4. Each tool
converted the same APK; every output jar was then judged by the same gates
(`bench/compare.sh` reproduces it). "Rejected" is the number of classes
HotSpot's verifier refuses to load (G8, with `android.jar` as the library
classpath; lower is better). Times are wall clock, including JVM or Python
startup.

| app | classes | dex2jvm rejected | enjarify rejected | dex2jar rejected | dex2jvm s | enjarify s | dex2jar s |
|---|---:|---:|---:|---:|---:|---:|---:|
| Flappy Bird | 1,136 | **0** | 0 | 0 | **0.6** | 5.3 | 1.3 |
| F-Droid | 19,701 | **0** | 1 | 1,750 | **2.4** | 39.9 | 9.6 |
| Simon Tatham's Puzzles | 3,978 | **0** | 1,235 | 1,101 | **1.1** | 12.9 | 3.9 |
| Mindustry | 6,018 | **0** | 0 | 0 | **1.4** | 20.6 | 4.9 |
| Geometry Dash Lite | 22,982 | **0** | 3 (-9 classes) | 3 | **3.0** | 47.6 | 11.0 |
| Hill Climb Racing 1.43 | 17,058 | **0** | 0 | 0 | **2.1** | 34.6 | 7.9 |
| Wikipedia | 12,788 | **0** | 4,206 | 3,655 | **2.5** | 38.8 | 13.1 |
| Google Calculator | 3,620 | **0** | 692 | 378 | **1.0** | 10.3 | 3.2 |
| AnkiDroid | 9,549 | **0** | 3,657 | 3,394 | **2.1** | 28.5 | 8.0 |
| Telegram | 41,073 | **1** | 167 (-1 classes) | 787 | **5.7** | 101.0 | 45.3 |
| **total** | | **1** | 9,961 | 11,068 | **22** | 340 | 108 |

All three tools pass G6 and G7 on every app. Overall: dex2jvm had **1** class
rejected where enjarify had 9,961 and dex2jar 11,068, and it
was **15x faster than enjarify** and
**5x faster than dex2jar**. enjarify
silently dropped classes on two apps (Geometry Dash Lite, Telegram).

A note on fairness: the tools do not face the same verifier. enjarify emits
class-file version 49, which HotSpot checks with the old, more lenient
type-inferencing verifier (no stack maps needed). dex2jar emits version 50,
which may fall back to that verifier on failure. dex2jvm emits version 52, which
always gets the strict stack-map verifier with no fallback, so its numbers are
measured against the hardest check of the three. The verifier is not the whole
story either: a class can verify and still be translated wrongly, which is what
the semantic suite below is for.

## Verification

- `tools/verify` holds the structural gates: **G6** BranchTargetCheck (branch and
  handler targets are instruction starts), **G7** StackDepthCheck (stack depth
  agrees with HotSpot's GenerateOopMap), **G8** VerifyCheck (HotSpot's real
  verifier, class by class) and **PrivAudit** (no latent cross-class private
  access).
- `tests/semantic/run.sh` is the semantic suite. It compiles each case with
  javac, runs it as the reference, then builds it into DEX three ways (d8, d8
  without desugaring, R8), converts it back with dex2jvm, runs it with
  verification on, and diffs the output. Needs the Android SDK command-line
  tools (`ANDROID_HOME`).

```bash
ANDROID_HOME=~/Library/Android/sdk tests/semantic/run.sh
```

## Limitations

- **Correctness on arbitrary apps cannot be fully proven.** The structural gates
  show the output is well-formed, and the semantic suite shows the converted
  code computes the same result as javac's on its cases. For an arbitrary APK
  there is no source to compare against, so a translation that verifies but
  computes a wrong value would not be caught by any gate here. The method
  splitter in particular relies on a liveness analysis that no gate can check
  end to end. Bug reports with a reproducing APK are very welcome.
- A small number of classes in real apps still fail the strict verifier. Some
  of those are invalid code in the apps themselves.
- CompactDex (`.cdex`) and `.aab` bundles are not supported (convert an `.aab`
  with `bundletool build-apks` first).
- Resources are not converted. The output jar holds class files only.

## How it works

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md). Each class also has a header
comment explaining what it does and why, with JVMS and AOSP citations.

## Credits and license

Apache License 2.0. See [LICENSE](LICENSE) and [NOTICE](NOTICE).

dex2jvm's design owes a lot to [enjarify](https://github.com/google/enjarify)
(Google, Apache 2.0), in particular its register type-inference model. dex2jvm is
an independent Java implementation.

Developed with heavy use of AI coding assistants (Claude). Every change is held
to the verification gates and semantic tests in this repo, which is how the
output is checked rather than trusted.
