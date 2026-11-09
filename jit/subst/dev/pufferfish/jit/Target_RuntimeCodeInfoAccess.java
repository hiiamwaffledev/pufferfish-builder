package dev.pufferfish.jit;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import org.graalvm.nativeimage.c.function.CFunction;
import org.graalvm.nativeimage.c.function.CodePointer;
import org.graalvm.word.UnsignedWord;

/**
 * Sealed JIT for stock iOS: instead of mprotect(RX) (which iOS refuses to execute: unsigned page), hand finished
 * runtime-compiled code to pf_jit_seal (pf-jitseal.mm), which signs it on the device and maps the signed pages back at
 * the same address. Only used by builds that put this jar on the classpath (Ristretto experiments).
 */
@TargetClass(className = "com.oracle.svm.core.code.RuntimeCodeInfoAccess")
final class Target_RuntimeCodeInfoAccess {
    @Substitute
    public static void makeCodeMemoryExecutableReadOnly(CodePointer codeStart, UnsignedWord codeSize) {
        int r = PfJitNative.seal(codeStart, codeSize);
        if (r != 0) {
            throw new InternalError("pf_jit_seal failed: " + r);
        }
    }
}

final class PfJitNative {
    @CFunction("pf_jit_seal")
    static native int seal(CodePointer start, UnsignedWord size);
}
