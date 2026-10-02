package io.github.kksimp.dex2jvm;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Re-points app calls on {@code sun.misc.Unsafe} at the class named by
 * Options.unsafeRedirect (OPT-IN, off by default). It is for hosts that run
 * Android code on HotSpot, where an address that code derived from ART's
 * object layout points at unrelated memory: the redirect class sees every
 * such access first and decides what it means (for example, forward an access
 * the VM really owns and fail cleanly on one it does not), instead of letting
 * HotSpot fault.
 *
 * WHY. Android apps that poke ART's internals do it through sun.misc.Unsafe:
 * LSPosed's HiddenApiBypass reads {@code java.lang.Class.methods} at an offset
 * measured on a mirror class, treats the word it finds as the address of an
 * ArtMethod array and walks it. On HotSpot that word is two compressed oops of
 * an unrelated java.lang.Class field. The walk dereferences a garbage address,
 * HotSpot turns the fault into java.lang.InternalError ("a fault occurred in an
 * unsafe memory access operation"), and the library, which only catches
 * ReflectiveOperationException, lets it out. Aurora Store 4.8.4 calls that
 * library from Application.onCreate (for its Shizuku installer), so on a plain
 * HotSpot the InternalError aborts onCreate before the rest of the app's
 * initialisation runs (measured: its download helper was never initialised,
 * so every download stayed QUEUED forever).
 *
 * WHY HERE. The library is R8-renamed in every app that ships it (nk3/lk3 in
 * Aurora), so a replacement class keyed on its name never binds, and the
 * only stable boundary it crosses is the sun.misc.Unsafe call itself. The
 * converter is where that call is emitted, so this is a pure method-ref swap:
 * invokevirtual Unsafe.m(args) becomes invokestatic R.m(Unsafe, args), where R
 * is Options.unsafeRedirect, with the receiver as the first parameter. The
 * operand stack is identical, so nothing about frames, registers or the
 * move-result that follows changes.
 *
 * WHAT IS ROUTED: every instance accessor whose base is (Object, long) or a
 * raw (long) address -- get/put of each width, the Volatile and Ordered forms,
 * the compareAndSwap, getAndAdd and getAndSet families, copyMemory and
 * setMemory. The allocators (allocateMemory/reallocateMemory/freeMemory), objectFieldOffset,
 * arrayBaseOffset, park/unpark, allocateInstance and everything else stay on
 * the real Unsafe: they do not read memory the app has no business reading.
 *
 * The table is generated from `javap -p sun.misc.Unsafe` on JDK 21; a method
 * missing here simply keeps its direct call. Only the input dex is ever routed
 * -- the converter never sees framework classes that are not in it.
 */
public final class UnsafeRepoint {
    private UnsafeRepoint() {}

    public static final String OWNER  = "sun/misc/Unsafe";

    private static final Set<String> ROUTED = new HashSet<>(Arrays.asList(
        "compareAndSwapInt(Ljava/lang/Object;JII)Z",
        "compareAndSwapLong(Ljava/lang/Object;JJJ)Z",
        "compareAndSwapObject(Ljava/lang/Object;JLjava/lang/Object;Ljava/lang/Object;)Z",
        "copyMemory(JJJ)V",
        "copyMemory(Ljava/lang/Object;JLjava/lang/Object;JJ)V",
        "getAddress(J)J",
        "getAndAddInt(Ljava/lang/Object;JI)I",
        "getAndAddLong(Ljava/lang/Object;JJ)J",
        "getAndSetInt(Ljava/lang/Object;JI)I",
        "getAndSetLong(Ljava/lang/Object;JJ)J",
        "getAndSetObject(Ljava/lang/Object;JLjava/lang/Object;)Ljava/lang/Object;",
        "getBoolean(Ljava/lang/Object;J)Z",
        "getBooleanVolatile(Ljava/lang/Object;J)Z",
        "getByte(J)B",
        "getByte(Ljava/lang/Object;J)B",
        "getByteVolatile(Ljava/lang/Object;J)B",
        "getChar(J)C",
        "getChar(Ljava/lang/Object;J)C",
        "getCharVolatile(Ljava/lang/Object;J)C",
        "getDouble(J)D",
        "getDouble(Ljava/lang/Object;J)D",
        "getDoubleVolatile(Ljava/lang/Object;J)D",
        "getFloat(J)F",
        "getFloat(Ljava/lang/Object;J)F",
        "getFloatVolatile(Ljava/lang/Object;J)F",
        "getInt(J)I",
        "getInt(Ljava/lang/Object;J)I",
        "getIntVolatile(Ljava/lang/Object;J)I",
        "getLong(J)J",
        "getLong(Ljava/lang/Object;J)J",
        "getLongVolatile(Ljava/lang/Object;J)J",
        "getObject(Ljava/lang/Object;J)Ljava/lang/Object;",
        "getObjectVolatile(Ljava/lang/Object;J)Ljava/lang/Object;",
        "getShort(J)S",
        "getShort(Ljava/lang/Object;J)S",
        "getShortVolatile(Ljava/lang/Object;J)S",
        "putAddress(JJ)V",
        "putBoolean(Ljava/lang/Object;JZ)V",
        "putBooleanVolatile(Ljava/lang/Object;JZ)V",
        "putByte(JB)V",
        "putByte(Ljava/lang/Object;JB)V",
        "putByteVolatile(Ljava/lang/Object;JB)V",
        "putChar(JC)V",
        "putChar(Ljava/lang/Object;JC)V",
        "putCharVolatile(Ljava/lang/Object;JC)V",
        "putDouble(JD)V",
        "putDouble(Ljava/lang/Object;JD)V",
        "putDoubleVolatile(Ljava/lang/Object;JD)V",
        "putFloat(JF)V",
        "putFloat(Ljava/lang/Object;JF)V",
        "putFloatVolatile(Ljava/lang/Object;JF)V",
        "putInt(JI)V",
        "putInt(Ljava/lang/Object;JI)V",
        "putIntVolatile(Ljava/lang/Object;JI)V",
        "putLong(JJ)V",
        "putLong(Ljava/lang/Object;JJ)V",
        "putLongVolatile(Ljava/lang/Object;JJ)V",
        "putObject(Ljava/lang/Object;JLjava/lang/Object;)V",
        "putObjectVolatile(Ljava/lang/Object;JLjava/lang/Object;)V",
        "putOrderedInt(Ljava/lang/Object;JI)V",
        "putOrderedLong(Ljava/lang/Object;JJ)V",
        "putOrderedObject(Ljava/lang/Object;JLjava/lang/Object;)V",
        "putShort(JS)V",
        "putShort(Ljava/lang/Object;JS)V",
        "putShortVolatile(Ljava/lang/Object;JS)V",
        "setMemory(JJB)V",
        "setMemory(Ljava/lang/Object;JJB)V",
        ""));

    /**
     * The descriptor to emit for an invokestatic on Options.unsafeRedirect, or null
     * when this Unsafe method is not routed.
     */
    public static String repoint(String name, String desc) {
        if (Options.unsafeRedirect == null) return null;
        if (!ROUTED.contains(name + desc)) return null;
        return "(L" + OWNER + ";" + desc.substring(1);
    }
}
