import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.zip.*;

/**
 * HookGen <out.jar> <classpath> <mods-dir> [exclude-file]   (exclude: mixinClass#handler+desc per line, see prune.sh)
 *
 * Runtime mixins, bake half. For every @Inject handler in the given mods, emit a stand-in mixin method with the SAME
 * annotations (targets, @At, ordinal, slice, cancellable, locals) but require=0, whose body is:
 *
 *     Hook h = PfHooks.H[id]; if (h != null) h.call(this|null, new Object[]{ handler args... });
 *
 * Mixin then applies these at bake time exactly like it would apply the mod (its own @At resolution, CallbackInfo,
 * local capture), and the result is compiled into the image. On the phone, dev.pufferfish.runtime.mixin.RuntimeMixins
 * fills PfHooks.H[id] with the mod's real handler (loaded from the jar, interpreted). Empty slot = one array load + null check.
 *
 * Output: a Fabric mod jar "pufferfish-hooks" with pufferfish-hooks.mixins.json, the stand-in classes and
 * pufferfish-hooks.txt (id <TAB> modid <TAB> mixinClass <TAB> handlerName <TAB> handlerDesc), which the runtime reads.
 * Handlers whose targets or descriptor types aren't on <classpath> (e.g. mixins into another mod) are skipped.
 */
public class HookGen {
    static final String INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;";
    static final String MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;";
    static final String HOOKS = "dev/pufferfish/runtime/mixin/PfHooks";
    static final String HOOK = "dev/pufferfish/runtime/mixin/Hook";
    static final String PKG = "dev/pufferfish/hooks/";

    static ClassLoader cp;
    static final Map<String, byte[]> out = new LinkedHashMap<>();
    static final List<String> commonMixins = new ArrayList<>(), clientMixins = new ArrayList<>();
    static final StringBuilder table = new StringBuilder();
    static int nextId, skipped, mixinCount;
    static final Map<String, Integer> skipWhy = new TreeMap<>();

    static final Set<String> exclude = new HashSet<>();

    public static void main(String[] a) throws Exception {
        if (a.length > 3 && Files.exists(Paths.get(a[3]))) exclude.addAll(Files.readAllLines(Paths.get(a[3])));
        List<URL> urls = new ArrayList<>();
        for (String s : a[1].split(File.pathSeparator)) if (!s.isEmpty()) urls.add(Paths.get(s).toUri().toURL());
        cp = new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
        try (var s = Files.list(Paths.get(a[2]))) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".jar")).sorted()::iterator) scanMod(Files.readAllBytes(p));
        }
        writeJar(Paths.get(a[0]));
        System.out.printf("[hookgen] %d hooks in %d stand-in mixins (%d common, %d client); %d handlers skipped %s%n",
                nextId, mixinCount, commonMixins.size(), clientMixins.size(), skipped, skipWhy);
    }

    // ---- mod / config discovery -------------------------------------------------------------------------------

    static void scanMod(byte[] jar) throws IOException {
        Map<String, byte[]> files = new HashMap<>();
        try (var z = new ZipInputStream(new ByteArrayInputStream(jar))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (!e.isDirectory()) files.put(e.getName(), z.readAllBytes());
        }
        byte[] fmj = files.get("fabric.mod.json");
        if (fmj != null) {
            String json = new String(fmj, StandardCharsets.UTF_8);
            String modId = str(json, "id");
            for (String[] cfg : mixinConfigs(json)) {
                byte[] c = files.get(cfg[0]);
                if (c != null) scanConfig(modId, new String(c, StandardCharsets.UTF_8), cfg[1], files);
            }
        }
        for (var e : files.entrySet()) if (e.getKey().startsWith("META-INF/jars/") && e.getKey().endsWith(".jar")) scanMod(e.getValue());
    }

    /** fabric.mod.json "mixins": ["a.json", {"config": "b.json", "environment": "client"}] → [file, env] */
    static List<String[]> mixinConfigs(String json) {
        List<String[]> r = new ArrayList<>();
        Matcher m = Pattern.compile("\"mixins\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (!m.find()) return r;
        String body = m.group(1);
        Matcher o = Pattern.compile("\\{[^}]*\\}|\"([^\"]+)\"").matcher(body);
        while (o.find()) {
            if (o.group(1) != null) r.add(new String[] { o.group(1), "*" });
            else { String obj = o.group(); r.add(new String[] { str(obj, "config"), Optional.ofNullable(str(obj, "environment")).orElse("*") }); }
        }
        return r;
    }

    static void scanConfig(String modId, String json, String env, Map<String, byte[]> files) {
        String pkg = str(json, "package");
        if (pkg == null) return;
        for (String side : new String[] { "mixins", "client", "server" }) {
            if (side.equals("server") || (side.equals("mixins") && env.equals("server"))) continue;
            boolean client = side.equals("client") || env.equals("client");
            for (String name : list(json, side)) {
                String internal = (pkg + "." + name).replace('.', '/');
                byte[] b = files.get(internal + ".class");
                if (b != null) genFor(modId, internal, b, client);
            }
        }
    }

    // ---- stand-in generation ----------------------------------------------------------------------------------

    static void genFor(String modId, String mixinName, byte[] bytes, boolean client) {
        ClassNode src = new ClassNode();
        new ClassReader(bytes).accept(src, ClassReader.SKIP_FRAMES);
        AnnotationNode mixinAnn = find(src.invisibleAnnotations, MIXIN);
        if (mixinAnn == null) mixinAnn = find(src.visibleAnnotations, MIXIN);
        if (mixinAnn == null) return;
        List<String> targets = mixinTargets(mixinAnn);
        if (targets.isEmpty() || !targets.stream().allMatch(HookGen::onClasspath)) { skip("target not on classpath", countInjects(src)); return; }
        boolean itf = (src.access & Opcodes.ACC_INTERFACE) != 0;

        ClassNode cn = new ClassNode();
        String name = PKG + modId.replaceAll("[^A-Za-z0-9_]", "_") + "/" + mixinName.substring(mixinName.lastIndexOf('/') + 1) + "$" + (mixinCount);
        cn.version = Opcodes.V21;
        cn.access = Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : 0);
        cn.name = name;
        cn.superName = "java/lang/Object";
        cn.invisibleAnnotations = new ArrayList<>(List.of(mixinAnn));
        int made = 0;
        for (MethodNode m : src.methods) {
            AnnotationNode inj = find(m.visibleAnnotations, INJECT);
            if (inj == null) inj = find(m.invisibleAnnotations, INJECT);
            if (inj == null) continue;
            if (exclude.contains(mixinName + "#" + m.name + m.desc)) { skip("failed in a previous bake", 1); continue; }
            String why = descProblem(m.desc);
            if (why != null) { skip(why, 1); continue; }
            int id = nextId++;
            table.append(id).append('\t').append(modId).append('\t').append(mixinName).append('\t').append(m.name).append('\t').append(m.desc).append('\n');
            cn.methods.add(stub(m, inj, id, itf));
            made++;
        }
        if (made == 0) return;
        mixinCount++;
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String x, String y) { return "java/lang/Object"; }
        };
        cn.accept(w);
        out.put(name + ".class", w.toByteArray());
        (client ? clientMixins : commonMixins).add(name.substring(PKG.length()).replace('/', '.'));
    }

    static MethodNode stub(MethodNode src, AnnotationNode inj, int id, boolean itf) {
        boolean stat = (src.access & Opcodes.ACC_STATIC) != 0;
        int access = (itf ? Opcodes.ACC_PRIVATE : Opcodes.ACC_PRIVATE) | (stat ? Opcodes.ACC_STATIC : 0);
        MethodNode m = new MethodNode(access, "pf$hook" + id + "$" + src.name, src.desc, src.signature, null);
        // same @Inject, but never fail the bake when a target moved (require = 0); keep @Slice/@At/locals/cancellable
        AnnotationNode a = new AnnotationNode(inj.desc);
        a.values = new ArrayList<>();
        if (inj.values != null) for (int i = 0; i < inj.values.size(); i += 2) {
            String k = (String) inj.values.get(i);
            if (k.equals("require") || k.equals("expect") || k.equals("allow")) continue;
            a.values.add(k); a.values.add(inj.values.get(i + 1));
        }
        a.values.add("require"); a.values.add(0);
        m.visibleAnnotations = new ArrayList<>();
        m.invisibleAnnotations = new ArrayList<>();
        if (src.visibleAnnotations != null) for (AnnotationNode x : src.visibleAnnotations) m.visibleAnnotations.add(x == inj ? a : x);
        if (src.invisibleAnnotations != null) for (AnnotationNode x : src.invisibleAnnotations) m.invisibleAnnotations.add(x == inj ? a : x);
        m.visibleParameterAnnotations = src.visibleParameterAnnotations;
        m.invisibleParameterAnnotations = src.invisibleParameterAnnotations;
        m.visibleAnnotableParameterCount = src.visibleAnnotableParameterCount;
        m.invisibleAnnotableParameterCount = src.invisibleAnnotableParameterCount;
        InsnList c = m.instructions;
        Type[] args = Type.getArgumentTypes(src.desc);
        LabelNode done = new LabelNode();
        c.add(new FieldInsnNode(Opcodes.GETSTATIC, HOOKS, "H", "[L" + HOOK + ";"));
        c.add(ldcInt(id));
        c.add(new InsnNode(Opcodes.AALOAD));
        c.add(new InsnNode(Opcodes.DUP));
        LabelNode call = new LabelNode();
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, call));
        c.add(new InsnNode(Opcodes.POP));
        c.add(new JumpInsnNode(Opcodes.GOTO, done));
        c.add(call);
        c.add(stat ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(ldcInt(args.length));
        c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int slot = stat ? 0 : 1;
        for (int i = 0; i < args.length; i++) {
            c.add(new InsnNode(Opcodes.DUP));
            c.add(ldcInt(i));
            c.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot));
            box(c, args[i]);
            c.add(new InsnNode(Opcodes.AASTORE));
            slot += args[i].getSize();
        }
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOOK, "call", "(Ljava/lang/Object;[Ljava/lang/Object;)V", true));
        c.add(done);
        Type ret = Type.getReturnType(src.desc);
        if (ret.getSort() != Type.VOID) { skip("non-void injector", 1); }
        c.add(new InsnNode(Opcodes.RETURN));
        return m;
    }

    static void box(InsnList c, Type t) {
        String w = switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte"; case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short"; case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float"; case Type.DOUBLE -> "java/lang/Double"; default -> null;
        };
        if (w != null) c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, w, "valueOf", "(" + t.getDescriptor() + ")L" + w + ";", false));
    }

    static AbstractInsnNode ldcInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static String descProblem(String desc) {
        for (Type t : Type.getArgumentTypes(desc)) {
            Type e = t.getSort() == Type.ARRAY ? t.getElementType() : t;
            if (e.getSort() == Type.OBJECT && !onClasspath(e.getInternalName())) return "handler arg type not on classpath";
        }
        return null;
    }

    static final Map<String, Boolean> cpCache = new HashMap<>();
    static boolean onClasspath(String internal) {
        return cpCache.computeIfAbsent(internal, k -> cp.getResource(k + ".class") != null);
    }

    static List<String> mixinTargets(AnnotationNode a) {
        List<String> r = new ArrayList<>();
        if (a.values == null) return r;
        for (int i = 0; i < a.values.size(); i += 2) {
            Object v = a.values.get(i + 1);
            if ("value".equals(a.values.get(i)) && v instanceof List<?> l) for (Object t : l) r.add(((Type) t).getInternalName());
            if ("targets".equals(a.values.get(i)) && v instanceof List<?> l) for (Object t : l) r.add(((String) t).replace('.', '/'));
        }
        return r;
    }

    static int countInjects(ClassNode c) {
        int n = 0;
        for (MethodNode m : c.methods) if (find(m.visibleAnnotations, INJECT) != null || find(m.invisibleAnnotations, INJECT) != null) n++;
        return n;
    }

    static void skip(String why, int n) { if (n > 0) { skipped += n; skipWhy.merge(why, n, Integer::sum); } }

    static AnnotationNode find(List<AnnotationNode> l, String desc) {
        if (l != null) for (AnnotationNode a : l) if (a.desc.equals(desc)) return a;
        return null;
    }

    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static List<String> list(String json, String key) {
        List<String> r = new ArrayList<>();
        Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\\[(.*?)\\]", Pattern.DOTALL).matcher(json);
        if (m.find()) { Matcher s = Pattern.compile("\"([^\"]+)\"").matcher(m.group(1)); while (s.find()) r.add(s.group(1)); }
        return r;
    }

    static void writeJar(Path p) throws IOException {
        String cfg = "{\n  \"required\": false,\n  \"package\": \"dev.pufferfish.hooks\",\n  \"compatibilityLevel\": \"JAVA_21\",\n"
                + "  \"injectors\": { \"defaultRequire\": 0 },\n"
                + "  \"mixins\": " + jsonList(commonMixins) + ",\n  \"client\": " + jsonList(clientMixins) + "\n}\n";
        String fmj = "{\n  \"schemaVersion\": 1,\n  \"id\": \"pufferfish-hooks\",\n  \"version\": \"1.0.0\",\n"
                + "  \"name\": \"Pufferfish runtime mixin hooks\",\n  \"mixins\": [\"pufferfish-hooks.mixins.json\"]\n}\n";
        try (var z = new ZipOutputStream(Files.newOutputStream(p))) {
            put(z, "fabric.mod.json", fmj.getBytes(StandardCharsets.UTF_8));
            put(z, "pufferfish-hooks.mixins.json", cfg.getBytes(StandardCharsets.UTF_8));
            put(z, "pufferfish-hooks.txt", table.toString().getBytes(StandardCharsets.UTF_8));
            for (var e : out.entrySet()) put(z, e.getKey(), e.getValue());
        }
    }

    static void put(ZipOutputStream z, String n, byte[] b) throws IOException { z.putNextEntry(new ZipEntry(n)); z.write(b); z.closeEntry(); }
    static String jsonList(List<String> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) b.append(i > 0 ? ", " : "").append('"').append(l.get(i)).append('"');
        return b.append(']').toString();
    }
}
