package dev.pufferfish.runtime.mixin;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * java.lang.reflect.Proxy for runtime mods. JDK proxy classes for interfaces loaded at runtime are spun as hidden
 * classes that Crema cannot dispatch interface calls on ("Erroneous dispatching for seed"). Runtime mod calls to
 * Proxy.newProxyInstance/isProxyClass/getInvocationHandler are redirected here (RuntimeMixins), and we generate an
 * ordinary class in the runtime mod loader instead: it extends {@link Base} and implements the interfaces, every
 * method calling handler.invoke(this, method, args).
 */
public final class PfProxy {
    private PfProxy() { }

    /** superclass of every generated proxy: holds the handler */
    public abstract static class Base {
        public final InvocationHandler pf$h;
        protected Base(InvocationHandler h) { this.pf$h = h; }
    }

    private static final ConcurrentHashMap<List<Class<?>>, Constructor<?>> CACHE = new ConcurrentHashMap<>();

    public static Object newProxyInstance(ClassLoader l, Class<?>[] itfs, InvocationHandler h) {
        for (Class<?> i : itfs) if (!Modifier.isPublic(i.getModifiers())) return Proxy.newProxyInstance(l, itfs, h);
        try {
            Constructor<?> c = CACHE.computeIfAbsent(List.of(itfs), k -> RuntimeMixins.proxyClass(itfs));
            return c.newInstance(h);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("[pufferfish] proxy for " + Arrays.toString(itfs), e); }
    }

    public static boolean isProxyClass(Class<?> c) { return Base.class.isAssignableFrom(c) || Proxy.isProxyClass(c); }

    public static InvocationHandler getInvocationHandler(Object o) {
        return o instanceof Base b ? b.pf$h : Proxy.getInvocationHandler(o);
    }

    /** methods a proxy for these interfaces implements: Object's equals/hashCode/toString, then every interface method */
    static List<Method> methods(Class<?>[] itfs) {
        java.util.LinkedHashMap<String, Method> m = new java.util.LinkedHashMap<>();
        try {
            for (String n : new String[] { "equals", "hashCode", "toString" }) {
                Method om = n.equals("equals") ? Object.class.getMethod(n, Object.class) : Object.class.getMethod(n);
                m.put(n + org.objectweb.asm.Type.getMethodDescriptor(om), om);
            }
        } catch (NoSuchMethodException e) { throw new IllegalStateException(e); }
        for (Class<?> i : itfs) for (Method x : i.getMethods()) {
            if (Modifier.isStatic(x.getModifiers())) continue;
            m.putIfAbsent(x.getName() + org.objectweb.asm.Type.getMethodDescriptor(x), x);
        }
        return List.copyOf(m.values());
    }
}
