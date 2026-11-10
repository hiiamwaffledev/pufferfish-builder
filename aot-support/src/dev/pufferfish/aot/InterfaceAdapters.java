package dev.pufferfish.aot;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Known Fabric entrypoint interfaces get compiled-in lambdas; anything else falls back
 * to java.lang.reflect.Proxy (proxy interface lists must be registered at build time).
 */
public final class InterfaceAdapters {
    @SuppressWarnings("unchecked")
    public static <T> T adapt(Class<T> intf, MethodHandle target) {
        if (intf == ModInitializer.class) return (T) (ModInitializer) () -> call(target);
        if (intf == ClientModInitializer.class) return (T) (ClientModInitializer) () -> call(target);
        if (intf == DedicatedServerModInitializer.class) return (T) (DedicatedServerModInitializer) () -> call(target);
        if (intf == PreLaunchEntrypoint.class) return (T) (PreLaunchEntrypoint) () -> call(target);

        Method sam = null;
        for (Method m : intf.getMethods()) {
            if (Modifier.isAbstract(m.getModifiers())) { sam = m; break; }
        }
        final Method fsam = sam;
        return (T) Proxy.newProxyInstance(intf.getClassLoader(), new Class<?>[] { intf }, (proxy, m, args) -> {
            if (m.getDeclaringClass() == Object.class) {
                switch (m.getName()) {
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    case "toString": return "PufferfishProxy[" + intf.getName() + "]";
                }
            }
            if (fsam != null && m.getName().equals(fsam.getName())) {
                return args == null ? target.invokeWithArguments() : target.invokeWithArguments(args);
            }
            throw new UnsupportedOperationException(m.toString());
        });
    }

    private static void call(MethodHandle h) {
        try {
            h.invokeWithArguments();
        } catch (Throwable t) {
            // A mod the user dropped in (runtime mod loader) must not take the whole game down: log it and carry on
            // without that initializer. Baked mods (Fabric API) failing is a real bug → rethrow.
            String owner = runtimeOwner(h);
            if (owner == null) owner = runtimeFrame(t);
            if (owner != null) {
                System.err.println("[pufferfish] runtime mod initializer " + owner + " failed, continuing without it: " + t);
                t.printStackTrace();
                return;
            }
            if (t instanceof RuntimeException r) throw r;
            if (t instanceof Error e) throw e;
            throw new RuntimeException(t);
        }
    }

    /** first runtime-mod class in the failure's stack (frames carry their loader name) */
    private static String runtimeFrame(Throwable t) {
        for (Throwable x = t; x != null; x = x.getCause())
            for (StackTraceElement e : x.getStackTrace())
                if ("pufferfish-runtime-mods".equals(e.getClassLoaderName())) return e.getClassName();
        return null;
    }

    /** declaring class of the entrypoint if it was loaded by the runtime mod loader, else null */
    private static String runtimeOwner(MethodHandle h) {
        try {
            Class<?> c = java.lang.invoke.MethodHandles.lookup().revealDirect(h).getDeclaringClass();
            ClassLoader l = c.getClassLoader();
            return l != null && l.getName() != null && l.getName().equals("pufferfish-runtime-mods") ? c.getName() : null;
        } catch (Throwable e) { return null; }
    }
}
