# How dex2jvm works

dex2jvm turns Dalvik bytecode (the `classes*.dex` inside an APK) into JVM class
files that load and run on a stock HotSpot JVM with bytecode verification on
(nearly all of them: see "What is left" below).
This page walks the pipeline; each class's own header comment has the full
reasoning and the citations (JVMS, AOSP/ART, enjarify).

## The pipeline

```
 .apk / .apkm / .xapk / .apks / .dex
        |
        |  Main.readDexes            container layout, ART's multidex order
        v
 DexFile  ->  DexClass / DexMethod / DexField / DexCode
        |     (header, id tables, class_data, code_item, try/catch, debug_info,
        |      annotations; member tables parsed lazily)
        v
 DexConverter.Session                 one class table across every dex
        |   ClassHierarchyOracle      superclass / interface answers
        |   NestIndex, NestPlan       nest reconstruction across dex files
        |   InitRepointPlan           undo R8 constructor re-pointing
        |   SuperInterfacePlan        legal invokespecial targets
        v
 per method:
   InstructionDecoder -> Instruction           all 26 Dalvik formats
   ControlFlowGraph   -> BasicBlock            incl. per-instruction exception edges
   TypeInference      -> RegisterState/DexType what each register holds, everywhere
   Translator         -> CodeWriter            register machine -> stack machine
        |   MethodOutliner / MethodSplitter    methods over the 64 KB JVM limit
        |   MonitorCover                       JIT-compilable synchronized code
        v
 StackMapWriter, ConstantPool, AnnotationWriter, ClassFileWriter
        |
        v
  out.jar  (class files only; fixed timestamps, byte-reproducible)
```

## The hard parts, and where they live

**Typing untyped registers** (`TypeInference`, `DexType`, `RegisterState`).
Dalvik registers are untyped and reused: `v3` can hold an int on one path and a
reference on another, and `const/4 v0, 0` is both integer zero and `null`. The
JVM types every load and store. A fixpoint over the control-flow graph infers
the type of every register at every instruction, and one Dalvik register maps to
up to five JVM local slots (one per kind), so a register reused at different
types never confuses the verifier. Getting this wrong does not fail loudly; it
silently emits `iload` where `fload` belonged.

**Precise stack-map frames** (`StackMapWriter`, `ClassHierarchyOracle`).
Class files from version 50 on are checked by HotSpot's split verifier, which
reads the StackMapTable. Merging two reference types to `java/lang/Object` is
sound but too imprecise for the verifier, so the converter computes real
least-upper-bounds from the app's own classes plus a library hierarchy. The CLI
picks that library automatically (`AndroidSdk`): the installed SDK platform the
app was compiled against, else a bundled index of the Android API 36 hierarchy
(`ApiIndexLoader`, built by `tools/api-index` from AOSP's Apache-2.0
`api/current.txt` files). The index holds only flags, superclass and interfaces
per class, which is all the oracle reads; against API 34's real `android.jar` it
covered 4,332 of 4,342 classes with no disagreements. This is the main reason
converted output loads with verification on.

**Methods over 64 KB** (`MethodOutliner`, `MethodSplitter`).
Dalvik has no method size limit; the JVM caps `code_length` at 65,535 bytes.
The outliner hoists straight-line regions into synthetic static methods. The
splitter cuts a method with dense control flow into a forward-only chain of
methods entered through a dispatch switch, with live values carried across each
cut. It never cuts inside a try range or between a method's first and last
monitor operation, so a method that is one big try block or one big lock
cannot be split. Both refuse (loudly) rather than emit code they
cannot prove correct; a method neither can rescue becomes a stub that throws,
and the rest of its class is kept.

**R8-shaped input** (`InitRepointPlan`, `SuperInterfacePlan`, `NestPlan`).
Shipping apps are minified by R8, which produces bytecode a JVM rejects:
constructor calls re-pointed at a superclass constructor, `invoke-super` naming
an indirect interface, private access between classes whose nest attributes
were stripped. Each plan rewrites one such shape into its JVM-legal equivalent.

**Synchronized code the JIT can compile** (`MonitorCover`).
HotSpot only JIT-compiles a method with `monitorenter` if it can prove the
monitors balanced. Translated exception tables can defeat that proof, leaving
the method interpreted forever. The converter reshapes catch-all coverage the
way javac does for a `synchronized` block.

**Metadata** (`AnnotationWriter`, `DexCode`, `ClassFileWriter`).
Annotations, generic signatures, InnerClasses / EnclosingMethod, Exceptions,
MethodParameters, AnnotationDefault, LineNumberTable and SourceFile are all
emitted, so reflection-driven libraries (Retrofit, Gson, Moshi, Kotlin
reflection) work and stack traces carry file and line numbers.

**Modern bytecode.** `invoke-custom`, `invoke-polymorphic`,
`const-method-handle` and `const-method-type` are translated.

## Determinism

A bulk conversion runs on up to `min(cores, 8)` threads. Results are drained in
input order on the calling thread, and nothing in the output depends on
scheduling, so the jar is byte-identical at any thread count (`--threads 1`
and the default produce the same bytes).

## Options that change the output

See `java -jar dex2jvm.jar --help` and `Options.java`. Two are for embedders
that run Android code on a JVM and are off by default:

- `--redirect-unsafe <class>` routes `sun.misc.Unsafe` calls to static methods
  on your class, for hosts whose Unsafe semantics (field offsets in particular)
  differ from ART's.
- `--redirect-exit <class>` routes `System.exit` / `Runtime.exit` /
  `Runtime.halt`, for hosts where the app exiting must not exit the JVM.

Environment variables named `DEX2JVM_*` in the source fall into four groups:
the `Options` settings above (`DEX2JVM_THREADS`, `DEX2JVM_REDIRECT_*`,
`DEX2JVM_SYNTHETIC_PREFIX`); A/B switches that turn one transform off with `=0`
(each defaults to the correct behaviour, so a regression can be bisected to one
transform in one run); `*_DEBUG` switches that print diagnostics; and test knobs
(`*_FORCE`, `*_LIMIT`, `DEX2JVM_SPLIT_NO_LIVENESS`) that push a transform onto
inputs it would not normally touch. None of them is needed for normal use.

## What is left

A few classes in real apps still fail the strict verifier (about ten across a
62-app test corpus at last measurement, several of them invalid code in the
app itself). A method whose stack-map frames cannot be computed precisely is
emitted without a StackMapTable on purpose, so it fails verification loudly
rather than verifying with wrong frames. A JVM running with verification off
loads both.

## Verification

- `tools/verify/BranchTargetCheck` (G6): every branch, switch and handler target
  is an instruction start.
- `tools/verify/StackDepthCheck` (G7): operand-stack depth agrees with HotSpot's
  GenerateOopMap, which runs even with verification off and aborts the VM on a
  bad method.
- `tools/verify/VerifyCheck` (G8): HotSpot's real split verifier, class by class.
- `tools/verify/PrivAudit`: no latent cross-class private access.
- `tests/semantic/run.sh`: runs javac's output and dex2jvm's output of the same
  programs (via d8, d8 without desugaring, and R8) and diffs what they print.

The structural gates prove the output is well-formed; only the semantic suite
checks it computes the same thing, and only for its own cases. For an arbitrary
APK there is no source to compare against, so a translation that verifies but
computes a wrong value would not be caught by any gate here. Every case added to
`tests/semantic/cases` so far has found a real bug, which is the best argument
for adding more.
