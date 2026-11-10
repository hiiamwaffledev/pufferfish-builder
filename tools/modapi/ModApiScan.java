import org.objectweb.asm.*;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * ModApiScan <out-dir> <classpath> <mod.jar|dir>...
 * Collects every method, constructor and field that mod bytecode references outside the mod itself (Minecraft, Fabric
 * API, libraries, JDK), resolves each to its declaring class on <classpath>, and writes <out-dir>/reachability-metadata.json
 * registering them. In a Crema image, interpreted mod code can only call AOT methods that exist as standalone compiled
 * methods; analysis prunes/inlines everything vanilla doesn't call that way ("Unable to call AOT method"), so this list is
 * the API surface we keep alive for runtime-loaded mods.
 */
public class ModApiScan {
    record Ref(String owner, String name, String desc, boolean field) {}
    static final Set<Ref> refs = new HashSet<>();
    static final java.util.regex.Pattern SKIP = java.util.regex.Pattern.compile(
        // java.nio.file / nio.charset / net are not preserved whole (ios: they reach run-time-initialized Darwin JDK
        // objects), so the members mods use there are registered individually
        "(?!java/(nio/file|nio/charset|net)/)(java|javax|jdk|sun|com/sun|org/apache/logging|org/slf4j)/");
    static final Set<String> modClasses = new HashSet<>();
    static int jars, classes, mixinRemapped;
    /** mixin class -> its target classes. Runtime mixins run as companions that reach @Shadow / @Accessor / @Invoker
     *  members on the real target reflectively, so refs to a mixin's own class are registered on its targets. */
    static final Map<String, List<String>> mixinTargets = new HashMap<>();
    static final List<Ref> mixinRefs = new ArrayList<>();

    public static void main(String[] a) throws Exception {
        Path out = Paths.get(a[0]);
        List<URL> urls = new ArrayList<>();
        for (String s : a[1].split(File.pathSeparator)) if (!s.isEmpty()) urls.add(Paths.get(s).toUri().toURL());
        for (int i = 2; i < a.length; i++) {
            Path p = Paths.get(a[i]);
            if (Files.isDirectory(p)) try (var s = Files.list(p)) { for (Path j : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".jar"))::iterator) scanJar(Files.readAllBytes(j)); }
            else scanJar(Files.readAllBytes(p));
        }
        // Owners that exist only in the scanned mods (not on the game classpath) don't need registration; they fail to
        // resolve below. Don't drop by modClasses: Fabric API is both a scanned mod and baked into the image.
        // Registering a type makes reflective lookups on it exact: members we didn't list then throw
        // MissingReflectionRegistrationError instead of NoSuchMethodException, which breaks code that probes optional
        // methods (Fabric's Log4jLogHandler). Skip the JDK (its core packages are preserved whole) and logging libs.
        for (Ref r : List.copyOf(refs)) {
            List<String> ts = mixinTargets.get(r.owner);
            if (ts != null) for (String t : ts) { refs.add(new Ref(t, r.name, r.desc, r.field)); mixinRemapped++; }
        }
        for (Ref r : mixinRefs) for (String t : mixinTargets.getOrDefault(r.owner, List.of())) { refs.add(new Ref(t, r.name, r.desc, r.field)); mixinRemapped++; }
        refs.removeIf(r -> SKIP.matcher(r.owner).lookingAt());
        ClassLoader cl = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        Map<String, Map<String, Object>> types = new TreeMap<>();
        int ok = 0, missing = 0;
        Set<String> missingOwners = new TreeSet<>();
        for (Ref r : refs) {
            Class<?> c;
            try { c = Class.forName(r.owner.replace('/', '.'), false, cl); }
            catch (Throwable t) { missing++; missingOwners.add(r.owner); continue; }
            try {
                Object m = r.field ? findField(c, r.name) : findExec(c, r.name, r.desc, cl);
                if (m == null) { missing++; continue; }
                Class<?> decl = m instanceof Member mm ? mm.getDeclaringClass() : c;
                Map<String, Object> t = types.computeIfAbsent(decl.getName(), k -> new TreeMap<>());
                if (m instanceof Field f) {
                    @SuppressWarnings("unchecked") Set<String> fs = (Set<String>) t.computeIfAbsent("fields", k -> new TreeSet<String>());
                    fs.add(f.getName());
                } else {
                    Executable e = (Executable) m;
                    StringBuilder sb = new StringBuilder("{\"name\":\"").append(e instanceof Constructor ? "<init>" : e.getName()).append("\",\"parameterTypes\":[");
                    Class<?>[] ps = e.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) sb.append(i > 0 ? "," : "").append('"').append(ps[i].getTypeName()).append('"');
                    @SuppressWarnings("unchecked") Set<String> ms = (Set<String>) t.computeIfAbsent("methods", k -> new TreeSet<String>());
                    ms.add(sb.append("]}").toString());
                }
                ok++;
            } catch (Throwable t) { missing++; }
        }
        StringBuilder j = new StringBuilder("{\n  \"reflection\": [\n");
        boolean first = true;
        for (var e : types.entrySet()) {
            j.append(first ? "" : ",\n").append("    {\"type\":\"").append(e.getKey()).append('"');
            first = false;
            @SuppressWarnings("unchecked") Set<String> ms = (Set<String>) e.getValue().get("methods");
            @SuppressWarnings("unchecked") Set<String> fs = (Set<String>) e.getValue().get("fields");
            if (ms != null) j.append(",\"methods\":[").append(String.join(",", ms)).append(']');
            if (fs != null) { j.append(",\"fields\":["); int i = 0; for (String f : fs) j.append(i++ > 0 ? "," : "").append("{\"name\":\"").append(f).append("\"}"); j.append(']'); }
            j.append('}');
        }
        j.append("\n  ]\n}\n");
        Files.createDirectories(out);
        Files.writeString(out.resolve("reachability-metadata.json"), j);
        System.out.printf("[modapi] %d mixin classes, %d refs remapped onto mixin targets%n", mixinTargets.size(), mixinRemapped);
        System.out.printf("[modapi] %d jars, %d classes scanned; %d external refs → %d resolved in %d types, %d unresolved (%d unknown owners)%n",
                jars, classes, refs.size(), ok, types.size(), missing, missingOwners.size());
    }

    static void scanJar(byte[] bytes) throws IOException {
        jars++;
        try (var z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.endsWith(".jar")) scanJar(z.readAllBytes());
                else if (n.endsWith(".class") && !n.startsWith("META-INF/")) scanClass(z.readAllBytes());
            }
        }
    }

    static void scanClass(byte[] b) {
        classes++;
        ClassReader r;
        try { r = new ClassReader(b); } catch (Throwable t) { return; }
        modClasses.add(r.getClassName());
        mixin(r);
        r.accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int op, String owner, String n, String d, boolean itf) { add(owner, n, d, false); }
                    @Override public void visitFieldInsn(int op, String owner, String n, String d) { add(owner, n, d, true); }
                    @Override public void visitInvokeDynamicInsn(String n, String d, Handle bsm, Object... args) {
                        handle(bsm);
                        for (Object o : args) if (o instanceof Handle h) handle(h);
                    }
                    @Override public void visitLdcInsn(Object v) { if (v instanceof Handle h) handle(h); }
                };
            }
        }, ClassReader.SKIP_FRAMES);
    }

    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;", ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;",
            INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;";

    static void mixin(ClassReader r) {
        ClassNode cn = new ClassNode();
        try { r.accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES); } catch (Throwable t) { return; }
        AnnotationNode mx = find(cn.visibleAnnotations, cn.invisibleAnnotations, "Lorg/spongepowered/asm/mixin/Mixin;");
        if (mx == null || mx.values == null) return;
        List<String> ts = new ArrayList<>();
        for (int i = 0; i < mx.values.size(); i += 2) {
            Object v = mx.values.get(i + 1);
            if ("value".equals(mx.values.get(i)) && v instanceof List<?> l) for (Object t : l) if (t instanceof Type ty) ts.add(ty.getInternalName());
            if ("targets".equals(mx.values.get(i)) && v instanceof List<?> l) for (Object t : l) ts.add(String.valueOf(t).replace('.', '/'));
        }
        if (ts.isEmpty()) return;
        mixinTargets.put(cn.name, ts);
        for (FieldNode f : cn.fields) if (find(f.visibleAnnotations, f.invisibleAnnotations, SHADOW) != null)
            mixinRefs.add(new Ref(cn.name, strip(f.name), f.desc, true));
        for (MethodNode m : cn.methods) {
            if (find(m.visibleAnnotations, m.invisibleAnnotations, SHADOW) != null) { mixinRefs.add(new Ref(cn.name, strip(m.name), m.desc, false)); continue; }
            AnnotationNode acc = find(m.visibleAnnotations, m.invisibleAnnotations, ACCESSOR), inv = find(m.visibleAnnotations, m.invisibleAnnotations, INVOKER);
            if (acc == null && inv == null) continue;
            String member = value(acc != null ? acc : inv);
            if (member == null) member = infer(m.name, acc != null);
            if (acc != null) mixinRefs.add(new Ref(cn.name, member, "", true));
            else if (member.equals("<init>")) mixinRefs.add(new Ref(cn.name, "<init>", Type.getMethodDescriptor(Type.VOID_TYPE, Type.getArgumentTypes(m.desc)), false));
            else mixinRefs.add(new Ref(cn.name, member, m.desc, false));
        }
    }
    static String strip(String n) { return n.startsWith("shadow$") ? n.substring(7) : n; }
    static String value(AnnotationNode a) {
        if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if ("value".equals(a.values.get(i))) return String.valueOf(a.values.get(i + 1));
        return null;
    }
    static String infer(String n, boolean accessor) {
        for (String p : accessor ? new String[] { "get", "set", "is" } : new String[] { "call", "invoke", "new", "create" })
            if (n.startsWith(p) && n.length() > p.length()) { String r = n.substring(p.length()); return Character.toLowerCase(r.charAt(0)) + r.substring(1); }
        return n;
    }
    static AnnotationNode find(List<AnnotationNode> a, List<AnnotationNode> b, String desc) {
        for (List<AnnotationNode> l : Arrays.asList(a, b)) if (l != null) for (AnnotationNode x : l) if (x.desc.equals(desc)) return x;
        return null;
    }

    static void handle(Handle h) { add(h.getOwner(), h.getName(), h.getDesc(), h.getTag() <= Opcodes.H_PUTSTATIC); }

    static void add(String owner, String name, String desc, boolean field) {
        if (owner.startsWith("[")) return;  // array clone() etc.
        refs.add(new Ref(owner, name, desc, field));
    }

    static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            for (Field f : k.getDeclaredFields()) if (f.getName().equals(name)) return f;
            for (Class<?> i : k.getInterfaces()) { Field f = findField(i, name); if (f != null) return f; }
        }
        return null;
    }

    static Executable findExec(Class<?> c, String name, String desc, ClassLoader cl) throws Exception {
        Type[] at = Type.getArgumentTypes(desc);
        Class<?>[] ps = new Class<?>[at.length];
        for (int i = 0; i < at.length; i++) ps[i] = toClass(at[i], cl);
        if (name.equals("<init>")) return c.getDeclaredConstructor(ps);
        if (name.equals("<clinit>")) return null;
        Deque<Class<?>> q = new ArrayDeque<>(List.of(c));
        Set<Class<?>> seen = new HashSet<>();
        while (!q.isEmpty()) {
            Class<?> k = q.poll();
            if (!seen.add(k)) continue;
            try { return k.getDeclaredMethod(name, ps); } catch (NoSuchMethodException ignored) { }
            if (k.getSuperclass() != null) q.add(k.getSuperclass());
            q.addAll(List.of(k.getInterfaces()));
        }
        return null;
    }

    static Class<?> toClass(Type t, ClassLoader cl) throws ClassNotFoundException {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> boolean.class; case Type.BYTE -> byte.class; case Type.CHAR -> char.class;
            case Type.SHORT -> short.class; case Type.INT -> int.class; case Type.LONG -> long.class;
            case Type.FLOAT -> float.class; case Type.DOUBLE -> double.class; case Type.VOID -> void.class;
            default -> Class.forName(t.getSort() == Type.ARRAY ? t.getDescriptor().replace('/', '.') : t.getClassName(), false, cl);
        };
    }
}
