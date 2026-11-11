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
                // interface (default) methods: LevelChunk.getSectionIndex comes from LevelHeightAccessor
                Method im = fromInterfaces(cls(owner), name, ps);
                if (im != null) { im.setAccessible(true); return im; }
                throw new NoSuchMethodError(owner + "." + name + desc);
            } catch (ClassNotFoundException e) { throw new NoClassDefFoundError(e.getMessage()); }
        });
    }

    private static Method fromInterfaces(Class<?> c, String name, Class<?>[] ps) {
        for (Class<?> k = c; k != null; k = k.getSuperclass())
            for (Class<?> i : k.getInterfaces()) {
                try { return i.getDeclaredMethod(name, ps); } catch (NoSuchMethodException ignored) { }
                Method m = fromInterfaces(i, name, ps);
                if (m != null) return m;
            }
        return null;
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
    private static final ConcurrentHashMap<String, Class<?>> CLASSES = new ConcurrentHashMap<>();
    /** instanceof for classes runtime-mod code can't name (package-private game classes) */
    public static boolean isInstance(Object o, String internal) {
        if (o == null) return false;
        Class<?> c = CLASSES.computeIfAbsent(internal, k -> { try { return cls(k); } catch (ClassNotFoundException e) { return Void.class; } });
        return c.isInstance(o);
    }
    public static boolean class$isAssignableFrom(Class<?> a, Class<?> b) { return a.isAssignableFrom(b) || RuntimeMixins.virtuallyImplements(a, b); }
    public static boolean class$isInstance(Class<?> a, Object o) { return a.isInstance(o) || (o != null && RuntimeMixins.virtuallyImplements(a, o.getClass())); }
    public static Object class$cast(Class<?> a, Object o) { return o == null || class$isInstance(a, o) ? o : a.cast(o); }
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
            // Java 25: setAccessible(true) allows writing non-static final fields of non-record, non-hidden classes.
            // static final (@Shadow @Final @Mutable static, e.g. Lithium nulling Mth.SIN): only Unsafe can. AOT code may have
            // folded the old value; that's the price of mutating a constant after the fact.
            if (!Modifier.isStatic(f.getModifiers())) { f.set(o, v); return; }
            sun.misc.Unsafe u = unsafe();
            Object base = u.staticFieldBase(f); long off = u.staticFieldOffset(f);
            Class<?> t = f.getType();
            if (!t.isPrimitive()) u.putObjectVolatile(base, off, v);
            else if (t == int.class) u.putIntVolatile(base, off, (Integer) v);
            else if (t == long.class) u.putLongVolatile(base, off, (Long) v);
            else if (t == boolean.class) u.putBooleanVolatile(base, off, (Boolean) v);
            else if (t == float.class) u.putFloatVolatile(base, off, (Float) v);
            else if (t == double.class) u.putDoubleVolatile(base, off, (Double) v);
            else if (t == byte.class) u.putByteVolatile(base, off, (Byte) v);
            else if (t == short.class) u.putShortVolatile(base, off, (Short) v);
            else u.putCharVolatile(base, off, (Character) v);
        }
        private static sun.misc.Unsafe unsafe() {
            try { Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe"); f.setAccessible(true); return (sun.misc.Unsafe) f.get(null); }
            catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
        }
    }

    /**
     * Lookup.unreflect for runtime mods. Crema's handle from unreflect(interface method) dies with "Erroneous dispatching
     * for seed" when invoked (any interface, JDK ones too); findVirtual for the same method dispatches fine.
     */
    public static java.lang.invoke.MethodHandle unreflect(java.lang.invoke.MethodHandles.Lookup l, Method m) throws IllegalAccessException {
        Class<?> d = m.getDeclaringClass();
        int mod = m.getModifiers();
        if (d.isInterface() && !java.lang.reflect.Modifier.isStatic(mod) && !java.lang.reflect.Modifier.isPrivate(mod)) {
            try {
                return l.findVirtual(d, m.getName(), java.lang.invoke.MethodType.methodType(m.getReturnType(), m.getParameterTypes()));
            } catch (NoSuchMethodException | IllegalAccessException e) { /* fall through: same access rules as unreflect */ }
        }
        return l.unreflect(m);
    }
}
