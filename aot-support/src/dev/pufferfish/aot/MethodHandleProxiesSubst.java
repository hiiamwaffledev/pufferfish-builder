package dev.pufferfish.aot;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.lang.invoke.MethodHandle;

/**
 * MethodHandleProxies spins a hidden class per call, which an AOT image can't do.
 * Fabric uses it for "Class::method" entrypoints. See {@link InterfaceAdapters}.
 */
@TargetClass(className = "java.lang.invoke.MethodHandleProxies", onlyWith = FabricPresent.class)
final class MethodHandleProxiesSubst {
    @Substitute
    public static <T> T asInterfaceInstance(Class<T> intf, MethodHandle target) {
        return InterfaceAdapters.adapt(intf, target);
    }
}
