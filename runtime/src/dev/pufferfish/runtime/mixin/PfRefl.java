package dev.pufferfish.runtime.mixin;

import java.lang.reflect.*;
import java.util.concurrent.ConcurrentHashMap;

/** Reflective access for mixin members the interpreter may not touch directly (private/protected @Shadow, accessors). */
public final class PfRefl {
    private static final ConcurrentHashMap<String, Field> FIELDS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Method> METHODS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Constructor<?>> CTORS = new ConcurrentHashMap<>();
    private PfRefl() { }

    public static Field field(String owner, String name) {
        return FIELDS.computeIfAbsent(owner + "." + name, k -> {
            try {
                for (Class<?> c = cls(owner); c != null; c = c.getSuperclass()) {
                    try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) { }
                }
                throw new NoSuchFieldError(owner + "." + name);
            } catch (ClassNotFoundException e) { throw new NoClassDefFoundError(owner); }
        });
    }

    public static Method method(String owner, String name, String desc) {
        return METHODS.computeIfAbsent(owner + "." + name + desc, k -> {
            try {
                Class<?>[] ps = params(desc);
                for (Class<?> c = cls(owner); c != null; c = c.getSuperclass()) {
                    try { Method m = c.getDeclaredMethod(name, ps); m.setAccessible(true); return m; } catch (NoSuchMethodException ignored) { }
                }
                throw new NoSuchMethodError(owner + "." + name + desc);
            } catch (ClassNotFoundException e) { throw new NoClassDefFoundError(e.getMessage()); }
        });
    }

    public static Constructor<?> ctor(String owner, String desc) {
        return CTORS.computeIfAbsent(owner + desc, k -> {
            try { Constructor<?> c = cls(owner).getDeclaredConstructor(params(desc)); c.setAccessible(true); return c; }
            catch (Exception e) { throw new NoSuchMethodError(owner + ".<init>" + desc); }
        });
    }

    /** owner "*": a mixin with several targets — resolve on the object's runtime class */
    static String own(Object o, String owner) { return owner.equals("*") ? o.getClass().getName().replace('.', '/') : owner; }

    // field get/set, boxed; the rewriter adds the unbox/checkcast
    public static Object get(Object o, String owner, String name) {
        try { return field(own(o, owner), name).get(o); } catch (IllegalAccessException e) { throw new IllegalAccessError(e.getMessage()); }
    }
    public static void set(Object o, Object v, String owner, String name) {
        try {
            Field f = field(own(o, owner), name);
            if (Modifier.isFinal(f.getModifiers())) FinalSetter.set(f, o, v); else f.set(o, v);
        } catch (IllegalAccessException e) { throw new IllegalAccessError(e.getMessage()); }
    }
    public static Object invoke(Object o, Object[] args, String owner, String name, String desc) {
        try { return method(own(o, owner), name, desc).invoke(o, args); }
        catch (InvocationTargetException e) { throw sneaky(e.getCause()); }
        catch (IllegalAccessException e) { throw new IllegalAccessError(e.getMessage()); }
    }
    public static Object construct(Object[] args, String owner, String desc) {
        try { return ctor(owner, desc).newInstance(args); }
        catch (InvocationTargetException e) { throw sneaky(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalAccessError(e.getMessage()); }
    }

    static Class<?> cls(String internal) throws ClassNotFoundException {
        return Class.forName(internal.replace('/', '.'), false, RuntimeMixins.loader());
    }

    static Class<?>[] params(String desc) throws ClassNotFoundException {
        org.objectweb.asm.Type[] ts = org.objectweb.asm.Type.getArgumentTypes(desc);
        Class<?>[] r = new Class<?>[ts.length];
        for (int i = 0; i < ts.length; i++) r[i] = toClass(ts[i]);
        return r;
    }

    static Class<?> toClass(org.objectweb.asm.Type t) throws ClassNotFoundException {
        return switch (t.getSort()) {
            case org.objectweb.asm.Type.BOOLEAN -> boolean.class; case org.objectweb.asm.Type.BYTE -> byte.class;
            case org.objectweb.asm.Type.CHAR -> char.class; case org.objectweb.asm.Type.SHORT -> short.class;
            case org.objectweb.asm.Type.INT -> int.class; case org.objectweb.asm.Type.LONG -> long.class;
            case org.objectweb.asm.Type.FLOAT -> float.class; case org.objectweb.asm.Type.DOUBLE -> double.class;
            case org.objectweb.asm.Type.VOID -> void.class;
            case org.objectweb.asm.Type.ARRAY -> Class.forName(t.getDescriptor().replace('/', '.'), false, RuntimeMixins.loader());
            default -> cls(t.getInternalName());
        };
    }

    @SuppressWarnings("unchecked")
    static <T extends Throwable> RuntimeException sneaky(Throwable t) throws T { throw (T) t; }

    /** @Mutable @Final shadow fields: plain reflection refuses final instance fields → go through Unsafe-free path. */
    static final class FinalSetter {
        static void set(Field f, Object o, Object v) throws IllegalAccessException {
            // Java 25: setAccessible(true) allows writing non-static final fields of non-record, non-hidden classes
            f.set(o, v);
        }
    }
}
