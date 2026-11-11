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
    static final Map<String, Integer> skipWhy = new TreeMap<>(), kinds = new TreeMap<>();

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
        System.out.printf("[hookgen] %d hooks %s in %d stand-in mixins (%d common, %d client); %d handlers skipped %s%n",
                nextId, kinds, mixinCount, commonMixins.size(), clientMixins.size(), skipped, skipWhy);
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
            Spec sp;
            try { sp = spec(m, itf); } catch (RuntimeException e) { skip("generator error " + e.getClass().getSimpleName(), 1); continue; }
            if (sp == null) continue;
            if (sp.skip != null) { skip(sp.skip, 1); continue; }
            if (exclude.contains(mixinName + "#" + m.name + m.desc)) { skip("failed in a previous bake", 1); continue; }
            String why = descProblem(sp.desc);
            if (why != null) { skip(why, 1); continue; }
            MethodNode st;
            try { st = stub(m, sp, nextId); } catch (RuntimeException e) { skip("generator error " + e.getClass().getSimpleName(), 1); continue; }
            int id = nextId++;
            table.append(id).append('\t').append(modId).append('\t').append(mixinName).append('\t').append(m.name).append('\t').append(m.desc)
                 .append('\t').append(sp.drop).append('\n');
            kinds.merge(sp.kind, 1, Integer::sum);
            cn.methods.add(st);
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

    // ---- injector kinds ---------------------------------------------------------------------------------------------
    // Every stand-in keeps the mod's handler descriptor (so Mixin resolves @At/locals/sugar exactly as for the mod),
    // except @Redirect and @Overwrite: those become @WrapOperation / @WrapMethod with an Operation appended (drop = 1:
    // the runtime strips it before calling the mod's handler). The bake holds the hooks of every scanned mod at once;
    // two @Redirects (or @Overwrites) of the same thing would conflict, wrappers chain. With an empty slot each stand-in
    // does what the code did without the mod: return the value unchanged, call the original operation, or return true.

    static final String MI = "Lorg/spongepowered/asm/mixin/injection/", MX = "Lcom/llamalad7/mixinextras/injector/";
    static final String OPERATION = "com/llamalad7/mixinextras/injector/wrapoperation/Operation";
    static final String WRAP_OPERATION = MX + "wrapoperation/WrapOperation;", WRAP_METHOD = MX + "wrapmethod/WrapMethod;";
    static final String OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";

    enum Def { NONE, ARG, TRUE, OP, INSTANCEOF }

    static final class Spec {
        String kind, skip;                 // skip != null: not supported, reason
        AnnotationNode ann;                // stand-in's injector annotation
        AnnotationNode orig;               // the mod's annotation (replaced by ann)
        String desc;                       // stand-in descriptor
        int drop;                          // trailing stand-in args the mod's handler doesn't take
        Def def = Def.NONE; int defArg;    // empty-slot behaviour (ARG: return param defArg; OP: Operation is param defArg)
        boolean keepParamAnns = true;
        Spec(String kind) { this.kind = kind; }
        static Spec no(String kind, String why) { Spec s = new Spec(kind); s.skip = why; return s; }
    }

    static AnnotationNode injector(MethodNode m) {
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l) {
            if (a.desc.startsWith(MI) && !a.desc.contains("/callback/") && !a.desc.equals(MI + "Coerce;")) return a;
            if (a.desc.startsWith(MX) && !a.desc.contains("/sugar/")) return a;
            if (a.desc.equals(OVERWRITE)) return a;
        }
        return null;
    }

    static Spec spec(MethodNode m, boolean itf) {
        AnnotationNode a = injector(m);
        if (a == null) return null;
        String k = a.desc.substring(a.desc.lastIndexOf('/') + 1, a.desc.length() - 1);
        Type[] ps = Type.getArgumentTypes(m.desc);
        Type ret = Type.getReturnType(m.desc);
        Spec s = new Spec(k);
        s.orig = a; s.desc = m.desc; s.ann = copyRequire0(a);
        switch (k) {
            case "Inject", "ModifyArgs" -> { if (ret.getSort() != Type.VOID) return Spec.no(k, "non-void " + k); }
            case "ModifyVariable", "ModifyConstant", "ModifyExpressionValue", "ModifyReceiver", "ModifyReturnValue" -> {
                if (ps.length == 0 || ret.getSort() == Type.VOID) return Spec.no(k, "odd " + k + " shape");
                // @ModifyConstant on an instanceof class constant: (Object, Class) -> boolean replaces the instanceof itself
                if (k.equals("ModifyConstant") && ret.getSort() == Type.BOOLEAN && ps.length >= 2
                        && ps[0].getDescriptor().equals("Ljava/lang/Object;") && ps[1].getDescriptor().equals("Ljava/lang/Class;")) { s.def = Def.INSTANCEOF; break; }
                s.def = Def.ARG; s.defArg = 0;
            }
            case "ModifyArg" -> {
                if (ps.length == 0) return Spec.no(k, "odd ModifyArg shape");
                int i = 0;
                if (ps.length > 1) {   // handler takes all target args: the modified one is "index", else the only one of the return type
                    Object idx = val(a, "index");
                    if (idx instanceof Integer n && n < ps.length && ps[n].equals(ret)) i = n;
                    else {
                        i = -1;
                        for (int j = 0; j < ps.length; j++) if (ps[j].equals(ret)) { if (i >= 0) return Spec.no(k, "ambiguous ModifyArg"); i = j; }
                        if (i < 0) return Spec.no(k, "ambiguous ModifyArg");
                    }
                }
                s.def = Def.ARG; s.defArg = i;
            }
            case "WrapWithCondition" -> { if (ret.getSort() != Type.BOOLEAN) return Spec.no(k, "odd WrapWithCondition"); s.def = Def.TRUE; }
            case "WrapOperation", "WrapMethod" -> {
                int op = -1;
                for (int j = 0; j < ps.length; j++) if (ps[j].getSort() == Type.OBJECT && ps[j].getInternalName().equals(OPERATION)) { op = j; break; }
                if (op < 0) return Spec.no(k, "no Operation param");
                s.def = Def.OP; s.defArg = op;
            }
            case "Redirect" -> {
                String why = redirectProblem(a, m);
                if (why != null) return Spec.no(k, why);
                AnnotationNode w = new AnnotationNode(WRAP_OPERATION);
                w.values = new ArrayList<>();
                for (String key : new String[] { "method", "remap" }) { Object v = val(a, key); if (v != null) { w.values.add(key); w.values.add(v); } }
                Object at = val(a, "at"), slice = val(a, "slice");
                w.values.add("at"); w.values.add(at instanceof List<?> ? at : List.of(at));
                if (slice != null) { w.values.add("slice"); w.values.add(slice instanceof List<?> ? slice : List.of(slice)); }
                w.values.add("require"); w.values.add(0);
                s.ann = w; s.drop = 1; s.keepParamAnns = false;
                s.desc = withOperation(m.desc); s.def = Def.OP; s.defArg = ps.length;
            }
            case "Overwrite" -> {
                if (itf) return Spec.no(k, "Overwrite in interface mixin");
                if (m.name.equals("<init>") || m.name.equals("<clinit>")) return Spec.no(k, "odd Overwrite");
                AnnotationNode w = new AnnotationNode(WRAP_METHOD);
                w.values = new ArrayList<>(List.of("method", List.of(m.name + m.desc), "require", 0));
                s.ann = w; s.drop = 1; s.keepParamAnns = false;
                s.desc = withOperation(m.desc); s.def = Def.OP; s.defArg = ps.length;
            }
            default -> { return Spec.no(k, "kind " + k); }
        }
        if (s.def == Def.ARG && !returnable(ps[s.defArg], ret)) return Spec.no(k, "default value type mismatch");
        return s;
    }

    /** can the stand-in return parameter type t as ret unchanged (same primitive, or reference + checkcast) */
    static boolean returnable(Type t, Type ret) {
        boolean tr = t.getSort() >= Type.ARRAY, rr = ret.getSort() >= Type.ARRAY;
        return tr && rr || !tr && !rr && t.getSort() == ret.getSort();
    }

    static String withOperation(String desc) {
        int p = desc.indexOf(')');
        return desc.substring(0, p) + "L" + OPERATION + ";" + desc.substring(p);
    }

    /** a @Redirect WrapOperation can stand in for: INVOKE/FIELD/NEW, no array element access, no captured target args */
    static String redirectProblem(AnnotationNode a, MethodNode m) {
        Object atv = val(a, "at");
        if (atv instanceof List<?> l && l.size() == 1) atv = l.get(0);
        if (!(atv instanceof AnnotationNode at)) return "Redirect without @At";
        String v = String.valueOf(val(at, "value"));
        if (val(at, "args") instanceof List<?> args) for (Object x : args) if (String.valueOf(x).startsWith("array")) return "Redirect of array access";
        if (!v.equals("INVOKE") && !v.equals("FIELD") && !v.equals("NEW")) return "Redirect @At(" + v + ")";
        if (v.equals("INVOKE") && val(at, "target") instanceof String t) {   // handler = (receiver?, args...) [+ captured target args]
            Matcher x = Pattern.compile("L([^;]+);([^(]+)(\\(.*)").matcher(t);
            if (!x.matches()) return "Redirect target unparsed";
            Boolean st = isStatic(x.group(1), x.group(2), x.group(3));
            if (st == null) return "Redirect target not found";
            int want = Type.getArgumentTypes(x.group(3)).length + (st ? 0 : 1);
            if (Type.getArgumentTypes(m.desc).length != want) return "Redirect captures target args";
        }
        // a WrapOperation moves the field write into a bridge method: illegal for a final field (written in <init>)
        if (v.equals("FIELD") && Type.getReturnType(m.desc).getSort() == Type.VOID && val(at, "target") instanceof String t) {
            Matcher x = Pattern.compile("L([^;]+);([^:]+):(.*)").matcher(t);
            if (!x.matches()) return "Redirect target unparsed";
            Boolean fin = isFinalField(x.group(1), x.group(2));
            if (fin == null || fin) return "Redirect of final field write";
        }
        return null;
    }

    /** final-ness of field owner.name (superclasses searched); null = not found */
    static Boolean isFinalField(String owner, String name) {
        for (String c = owner; c != null; ) {
            try (InputStream in = cp.getResourceAsStream(c + ".class")) {
                if (in == null) return null;
                ClassNode n = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE);
                for (FieldNode f : n.fields) if (f.name.equals(name)) return (f.access & Opcodes.ACC_FINAL) != 0;
                c = n.superName;
            } catch (IOException e) { return null; }
        }
        return null;
    }

    /** static-ness of owner.name+desc, searching superclasses and interfaces on the bake classpath; null = not found */
    static Boolean isStatic(String owner, String name, String desc) {
        Deque<String> q = new ArrayDeque<>(List.of(owner)); Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (!seen.add(c)) continue;
            try (InputStream in = cp.getResourceAsStream(c + ".class")) {
                if (in == null) continue;
                ClassNode n = new ClassNode();
                new ClassReader(in.readAllBytes()).accept(n, ClassReader.SKIP_CODE);
                for (MethodNode mm : n.methods) if (mm.name.equals(name) && mm.desc.equals(desc)) return (mm.access & Opcodes.ACC_STATIC) != 0;
                if (n.superName != null) q.add(n.superName);
                q.addAll(n.interfaces);
            } catch (IOException e) { return null; }
        }
        return null;
    }

    static Object val(AnnotationNode a, String key) {
        if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if (key.equals(a.values.get(i))) return a.values.get(i + 1);
        return null;
    }

    /** same annotation, but never fail the bake when a target moved (require = 0) */
    static AnnotationNode copyRequire0(AnnotationNode inj) {
        AnnotationNode a = new AnnotationNode(inj.desc);
        a.values = new ArrayList<>();
        if (inj.values != null) for (int i = 0; i < inj.values.size(); i += 2) {
            String k = (String) inj.values.get(i);
            if (k.equals("require") || k.equals("expect") || k.equals("allow")) continue;
            a.values.add(k); a.values.add(inj.values.get(i + 1));
        }
        if (!inj.desc.equals(OVERWRITE)) { a.values.add("require"); a.values.add(0); }
        return a;
    }

    /**
     * Stand-in body:  Hook h = PfHooks.H[id]; if (h != null) return (R) h.call(this|null, new Object[]{ all args });
     *                 else <default>   (see Def)
     */
    static MethodNode stub(MethodNode src, Spec sp, int id) {
        boolean stat = (src.access & Opcodes.ACC_STATIC) != 0;
        MethodNode m = new MethodNode(Opcodes.ACC_PRIVATE | (stat ? Opcodes.ACC_STATIC : 0), "pf$hook" + id + "$" + src.name, sp.desc, null, null);
        m.visibleAnnotations = new ArrayList<>();
        m.invisibleAnnotations = new ArrayList<>();
        if (src.visibleAnnotations != null) for (AnnotationNode x : src.visibleAnnotations) if (x != sp.orig) m.visibleAnnotations.add(x);
        if (src.invisibleAnnotations != null) for (AnnotationNode x : src.invisibleAnnotations) if (x != sp.orig) m.invisibleAnnotations.add(x);
        boolean inv = src.invisibleAnnotations != null && src.invisibleAnnotations.contains(sp.orig);
        (inv ? m.invisibleAnnotations : m.visibleAnnotations).add(sp.ann);
        if (sp.keepParamAnns) {
            m.signature = src.signature;
            m.visibleParameterAnnotations = src.visibleParameterAnnotations;
            m.invisibleParameterAnnotations = src.invisibleParameterAnnotations;
            m.visibleAnnotableParameterCount = src.visibleAnnotableParameterCount;
            m.invisibleAnnotableParameterCount = src.invisibleAnnotableParameterCount;
        }
        InsnList c = m.instructions;
        Type[] args = Type.getArgumentTypes(sp.desc);
        Type ret = Type.getReturnType(sp.desc);
        int[] slot = new int[args.length];
        for (int i = 0, s = stat ? 0 : 1; i < args.length; s += args[i].getSize(), i++) slot[i] = s;
        LabelNode fallback = new LabelNode();
        c.add(new FieldInsnNode(Opcodes.GETSTATIC, HOOKS, "H", "[L" + HOOK + ";"));
        c.add(ldcInt(id));
        c.add(new InsnNode(Opcodes.AALOAD));
        c.add(new InsnNode(Opcodes.DUP));
        LabelNode call = new LabelNode();
        c.add(new JumpInsnNode(Opcodes.IFNONNULL, call));
        c.add(new InsnNode(Opcodes.POP));
        c.add(new JumpInsnNode(Opcodes.GOTO, fallback));
        c.add(call);
        c.add(stat ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, 0));
        c.add(objectArray(args, slot, args.length));
        c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, HOOK, "call", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;", true));
        returnObject(c, ret);
        c.add(fallback);
        switch (sp.def) {
            case NONE -> { if (ret.getSort() != Type.VOID) throw new IllegalStateException("no default for " + sp.kind); c.add(new InsnNode(Opcodes.RETURN)); }
            case TRUE -> { c.add(new InsnNode(Opcodes.ICONST_1)); c.add(new InsnNode(Opcodes.IRETURN)); }
            case INSTANCEOF -> {   // cls.isInstance(obj)
                c.add(new VarInsnNode(Opcodes.ALOAD, slot[1])); c.add(new VarInsnNode(Opcodes.ALOAD, slot[0]));
                c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "isInstance", "(Ljava/lang/Object;)Z", false));
                c.add(new InsnNode(Opcodes.IRETURN));
            }
            case ARG -> {
                Type t = args[sp.defArg];
                c.add(new VarInsnNode(t.getOpcode(Opcodes.ILOAD), slot[sp.defArg]));
                if (!t.equals(ret) && ret.getSort() >= Type.ARRAY) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ret.getInternalName()));
                c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            }
            case OP -> {   // original.call(args before the Operation)
                c.add(new VarInsnNode(Opcodes.ALOAD, slot[sp.defArg]));
                c.add(objectArray(args, slot, sp.defArg));
                c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, OPERATION, "call", "([Ljava/lang/Object;)Ljava/lang/Object;", true));
                returnObject(c, ret);
            }
        }
        return m;
    }

    static InsnList objectArray(Type[] args, int[] slot, int n) {
        InsnList c = new InsnList();
        c.add(ldcInt(n));
        c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < n; i++) {
            c.add(new InsnNode(Opcodes.DUP));
            c.add(ldcInt(i));
            c.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot[i]));
            box(c, args[i]);
            c.add(new InsnNode(Opcodes.AASTORE));
        }
        return c;
    }

    /** Object on the stack → return it as ret (void: drop it) */
    static void returnObject(InsnList c, Type ret) {
        switch (ret.getSort()) {
            case Type.VOID -> { c.add(new InsnNode(Opcodes.POP)); c.add(new InsnNode(Opcodes.RETURN)); return; }
            case Type.OBJECT, Type.ARRAY -> {
                if (!ret.getDescriptor().equals("Ljava/lang/Object;")) c.add(new TypeInsnNode(Opcodes.CHECKCAST, ret.getInternalName()));
            }
            default -> {
                String w = switch (ret.getSort()) {
                    case Type.BOOLEAN -> "java/lang/Boolean"; case Type.CHAR -> "java/lang/Character";
                    default -> "java/lang/Number";   // byte/short/int/long/float/double: any Number
                };
                c.add(new TypeInsnNode(Opcodes.CHECKCAST, w));
                String m = switch (ret.getSort()) {
                    case Type.BOOLEAN -> "booleanValue"; case Type.CHAR -> "charValue"; case Type.BYTE -> "byteValue";
                    case Type.SHORT -> "shortValue"; case Type.INT -> "intValue"; case Type.LONG -> "longValue";
                    case Type.FLOAT -> "floatValue"; default -> "doubleValue";
                };
                c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, w, m, "()" + ret.getDescriptor(), false));
            }
        }
        c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
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
        for (MethodNode m : c.methods) if (injector(m) != null) n++;
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
                + "  \"injectors\": { \"defaultRequire\": 0 },\n  \"mixinextras\": { \"minVersion\": \"0.5.0\" },\n"
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
