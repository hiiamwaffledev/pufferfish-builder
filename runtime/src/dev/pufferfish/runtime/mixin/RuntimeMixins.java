package dev.pufferfish.runtime.mixin;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

/**
 * Runtime mixins for mods loaded after the bake (Tier 1: @Inject + @Shadow/@Unique/@Accessor/@Invoker/added interfaces).
 *
 * Minecraft is compiled into the image, so a runtime mod's mixins can't edit it. Instead:
 *  - @Inject: HookGen baked a stand-in injector with the same annotation into MC; we put the mod's handler in its slot.
 *  - a mixin class becomes a companion class: instance methods turn static with the target object as parameter 0 (so
 *    the bytecode's "this" slot already holds the target), @Shadow members are accessed on the target (reflectively
 *    when not public), fields the mixin adds live in PfFields side storage.
 *  - mod code using an interface a mixin adds to a target (or an @Accessor/@Invoker interface) gets its casts,
 *    instanceof and interface calls rewritten to a generated dispatcher (pf.dispatch.*).
 * Every injector kind (@Inject, @ModifyArg/Variable/Constant, MixinExtras wrappers, @Redirect, @Overwrite) runs through a
 * stand-in HookGen baked for it; handlers without one are reported and turn their mixin class off.
 */
public final class RuntimeMixins {
    static final String SHADOW = "Lorg/spongepowered/asm/mixin/Shadow;", UNIQUE = "Lorg/spongepowered/asm/mixin/Unique;",
            MIXIN = "Lorg/spongepowered/asm/mixin/Mixin;", INJECT = "Lorg/spongepowered/asm/mixin/injection/Inject;",
            ACCESSOR = "Lorg/spongepowered/asm/mixin/gen/Accessor;", INVOKER = "Lorg/spongepowered/asm/mixin/gen/Invoker;",
            OVERWRITE = "Lorg/spongepowered/asm/mixin/Overwrite;";
    static final String DISPATCH_PKG = "pf/dispatch/";

    /** One mixin class of a runtime mod. */
    static final class MixinInfo {
        final int id; final String mod, name; final String target; final List<String> targets; final boolean itf;
        final Set<String> shadowFields = new HashSet<>(), shadowMethods = new HashSet<>(); // name / name+desc
        final Map<String, Integer> addedFields = new LinkedHashMap<>();                     // name -> slot
        final Map<String, String> addedFieldDesc = new HashMap<>();
        final Set<String> ownMethods = new HashSet<>();                                    // name+desc (instance, non-shadow)
        final Set<String> declaredFields = new HashSet<>(), declaredMethods = new HashSet<>(); // everything else with owner = mixin is inherited
        final Map<String, String[]> accessors = new HashMap<>();                           // name+desc -> {kind, member}
        final List<String> interfaces = new ArrayList<>();
        boolean hasInit; int unsupported;   // injectors we cannot apply: then none of this mixin's hooks run (half a mixin misbehaves)
        MixinInfo(int id, String mod, String name, List<String> targets, boolean itf) {
            this.id = id; this.mod = mod; this.name = name; this.targets = targets; this.target = targets.get(0); this.itf = itf;
        }
    }

    private static ClassLoader loader;
    private static final Map<String, Integer> hookIds = new HashMap<>();         // mixinClass#name+desc -> id
    private static final Map<Integer, Integer> hookDrop = new HashMap<>();       // id -> trailing stand-in args the handler doesn't take
    private static final Map<String, MixinInfo> mixins = new HashMap<>();        // internal name -> info
    private static final List<MixinInfo> byId = new ArrayList<>();
    /** interface (added by a mixin, or an accessor interface) -> mixins implementing it on some target */
    private static final Map<String, List<MixinInfo>> dispatchItf = new HashMap<>();
    private static final List<String> report = new ArrayList<>();
    private static int hooked, unsupported, skipped, disabled;

    public static ClassLoader loader() { return loader; }
    public static int mixinCount() { return byId.size(); }
    public static Object[] newSlots(int mixin) { return new Object[byId.get(mixin).addedFields.size()]; }

    // ---- setup --------------------------------------------------------------------------------------------------

    /** mods: runtime mods as (modId, mixin config names, root paths). Call before any of their classes load. */
    public static void setup(ClassLoader cl, List<Object[]> mods) {
        loader = cl;
        try (InputStream in = RuntimeMixins.class.getClassLoader().getResourceAsStream("pufferfish-hooks.txt")) {
            if (in != null) for (String l : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String[] p = l.split("\t");
                if (p.length < 5) continue;
                int id = Integer.parseInt(p[0]);
                hookIds.put(p[2] + "#" + p[3] + p[4], id);
                if (p.length > 5 && !p[5].equals("0")) hookDrop.put(id, Integer.parseInt(p[5].trim()));
            }
        } catch (IOException ignored) { }
        for (Object[] m : mods) {
            String mod = (String) m[0];
            @SuppressWarnings("unchecked") Collection<String> configs = (Collection<String>) m[1];
            @SuppressWarnings("unchecked") List<Path> roots = (List<Path>) m[2];
            for (String cfg : configs) {
                String json = read(roots, cfg);
                if (json == null) { report.add(mod + ": mixin config " + cfg + " not found"); continue; }
                String pkg = str(json, "package");
                for (String side : new String[] { "mixins", "client" }) for (String n : list(json, side)) {
                    String internal = (pkg + "." + n).replace('.', '/');
                    byte[] b = readBytes(roots, internal + ".class");
                    if (b != null) analyse(mod, internal, b);
                }
            }
        }
    }

    /** resource lookup first (does not load runtime-mod classes early); AOT images may lack .class resources, so fall back to an uninitialised load */
    private static boolean exists(String internal) {
        if (loader.getResource(internal + ".class") != null) return true;
        try { Class.forName(internal.replace('/', '.'), false, loader); return true; } catch (Throwable t) { return false; }
    }

    private static void analyse(String mod, String name, byte[] b) {
        ClassNode cn = node(b);
        AnnotationNode mx = ann(cn, MIXIN);
        List<String> targets = new ArrayList<>();
        if (mx != null && mx.values != null) for (int i = 0; i < mx.values.size(); i += 2) {
            Object v = mx.values.get(i + 1);
            if ("value".equals(mx.values.get(i)) && v instanceof List<?> l) for (Object t : l) targets.add(((Type) t).getInternalName());
            if ("targets".equals(mx.values.get(i)) && v instanceof List<?> l) for (Object t : l) targets.add(((String) t).replace('.', '/'));
        }
        targets.removeIf(t -> !exists(t));   // optional compat mixins (JEI, ...): Mixin skips missing targets too
        if (targets.isEmpty()) { skipped++; return; }
        MixinInfo mi = new MixinInfo(byId.size(), mod, name, targets, (cn.access & Opcodes.ACC_INTERFACE) != 0);
        byId.add(mi);
        mixins.put(name, mi);
        for (FieldNode f : cn.fields) mi.declaredFields.add(f.name);
        for (MethodNode m : cn.methods) mi.declaredMethods.add(m.name + m.desc);
        for (FieldNode f : cn.fields) {
            if (has(f.visibleAnnotations, f.invisibleAnnotations, SHADOW)) mi.shadowFields.add(f.name);
            else if ((f.access & Opcodes.ACC_STATIC) == 0) { mi.addedFields.put(f.name, mi.addedFields.size()); mi.addedFieldDesc.put(f.name, f.desc); }
        }
        for (MethodNode m : cn.methods) {
            if (m.name.equals("<init>")) { mi.hasInit = initHasCode(m); continue; }
            if (has(m.visibleAnnotations, m.invisibleAnnotations, SHADOW)) { mi.shadowMethods.add(m.name + m.desc); continue; }
            AnnotationNode acc = annOf(m, ACCESSOR), inv = annOf(m, INVOKER);
            if (acc != null || inv != null) {
                String member = annValue(acc != null ? acc : inv);
                if (member == null) member = inferMember(m.name, acc != null);
                mi.accessors.put(m.name + m.desc, new String[] { acc != null ? "accessor" : "invoker", member });
                continue;
            }
            if ((m.access & Opcodes.ACC_STATIC) == 0 && (m.access & Opcodes.ACC_ABSTRACT) == 0) mi.ownMethods.add(m.name + m.desc);
            String kind = injectorKind(m);
            if (kind == null) continue;
            // every injector kind runs through a baked stand-in (HookGen); none baked = this mod wasn't in the hook scan
            Integer id = hookIds.get(name + "#" + m.name + m.desc);
            if (id == null) { unsupported++; mi.unsupported++; report.add(mod + ": " + simple(name) + "." + m.name + " @" + kind + " has no baked hook (not in the hook set)"); }
        }
        if (!mi.itf) mi.interfaces.addAll(cn.interfaces);
        else {
            if (!mi.accessors.isEmpty()) mi.interfaces.add(name);   // accessor interface: dispatch calls on targets
            mi.interfaces.addAll(cn.interfaces);                     // interface mixin extending a mod interface (default methods)
        }
        for (String i : mi.interfaces) dispatchItf.computeIfAbsent(i, k -> new ArrayList<>()).add(mi);
    }

    /** Fill the hook slots. Loads companion classes, so call once setup() has seen every runtime mod. */
    public static void installHooks() {
        for (MixinInfo mi : byId) {
            if (mi.unsupported > 0) { disabled++; continue; }
            Class<?> comp;
            try { comp = Class.forName(mi.name.replace('/', '.'), true, loader); }
            catch (Throwable t) { report.add(mi.mod + ": " + simple(mi.name) + " failed to load: " + t); continue; }
            Method[] ms;
            try { ms = comp.getDeclaredMethods(); }
            catch (Throwable t) { report.add(mi.mod + ": " + simple(mi.name) + " skipped, references a missing class: " + t); continue; }
            // the hook table has the original handler descriptors; companion methods have erased ones
            Map<String, Integer> ids = new HashMap<>();
            String pre = mi.name + "#";
            for (var e : hookIds.entrySet()) if (e.getKey().startsWith(pre)) {
                String k = e.getKey().substring(pre.length()); int p = k.indexOf('(');
                ids.put(k.substring(0, p) + eraseDesc(k.substring(p), mi), e.getValue());
            }
            Set<Integer> unmatched = new HashSet<>(ids.values());
            for (Method m : ms) {
                boolean inst = !isStaticOrig(mi, m);
                String origDesc = origDesc(m, inst);
                Integer id = ids.get(m.getName() + origDesc);
                if (id == null) continue;
                m.setAccessible(true);
                int drop = hookDrop.getOrDefault(id, 0);
                PfHooks.H[id] = (self, args) -> {
                    int n = args.length - drop, o = inst ? 1 : 0;
                    Object[] a = new Object[n + o];
                    if (inst) a[0] = self;
                    System.arraycopy(args, 0, a, o, n);
                    try { return m.invoke(null, a); }
                    catch (java.lang.reflect.InvocationTargetException e) { throw PfRefl.sneaky(e.getCause()); }
                    catch (IllegalAccessException e) { throw new IllegalAccessError(e.getMessage()); }
                };
                hooked++;
                unmatched.remove(id);
            }
            for (var e : ids.entrySet()) if (unmatched.contains(e.getValue())) report.add(mi.mod + ": " + simple(mi.name) + "." + e.getKey() + " baked hook " + e.getValue() + " matches no companion method");
        }
    }

    public static List<String> report() {
        List<String> r = new ArrayList<>(report);
        r.add(0, mixins.size() + " runtime mixin classes (" + skipped + " skipped, target missing; " + disabled + " off, unsupported injectors), " + hooked + " injectors hooked, " + unsupported + " injectors unsupported");
        return r;
    }

    /** original handler descriptor: instance handlers got the target prepended as parameter 0 */
    private static String origDesc(Method m, boolean inst) {
        StringBuilder b = new StringBuilder("(");
        Class<?>[] ps = m.getParameterTypes();
        for (int i = inst ? 1 : 0; i < ps.length; i++) b.append(Type.getDescriptor(ps[i]));
        return b.append(')').append(Type.getDescriptor(m.getReturnType())).toString();
    }
    private static final Map<String, Set<String>> staticOrig = new HashMap<>();
    private static boolean isStaticOrig(MixinInfo mi, Method m) {
        return staticOrig.getOrDefault(mi.name, Set.of()).contains(m.getName() + Type.getMethodDescriptor(m));
    }
    /** type of "this" in a companion: the target, or Object for interface mixins and mixins with several targets */
    private static String companionSelf(MixinInfo mi) { return mi.itf || mi.targets.size() > 1 ? "java/lang/Object" : mi.target; }
    /** owner for instance @Shadow access: "*" = resolve on the object's runtime class (several targets) */
    private static String selfOwner(MixinInfo mi) { return mi.targets.size() > 1 ? "*" : mi.target; }

    // ---- class transformation -------------------------------------------------------------------------------------

    /** Called by the runtime mod class loader for every class it defines. */
    public static byte[] transform(String internal, byte[] b) {
        ClassNode cn = node(b);
        MixinInfo mi = mixins.get(internal);
        if (mi != null) companion(cn, mi);
        boolean changed = mi != null;
        for (MethodNode m : cn.methods) changed |= rewriteCalls(m, mi);
        changed |= erase(cn, mi);
        for (MethodNode m : cn.methods) changed |= unavailable(m);
        if (!changed) return b;
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String x, String y) {
                try { return super.getCommonSuperClass(x, y); } catch (Throwable t) { return "java/lang/Object"; }
            }
            @Override protected ClassLoader getClassLoader() { return loader != null ? loader : RuntimeMixins.class.getClassLoader(); }
        };
        cn.accept(w);
        return w.toByteArray();
    }

    private static void companion(ClassNode cn, MixinInfo mi) {
        cn.invisibleAnnotations = null; cn.visibleAnnotations = null;
        if (!mi.itf) { cn.superName = "java/lang/Object"; cn.interfaces = new ArrayList<>(); }
        cn.fields.removeIf(f -> (f.access & Opcodes.ACC_STATIC) == 0 || mi.shadowFields.contains(f.name));
        Set<String> stat = new HashSet<>();
        List<MethodNode> keep = new ArrayList<>();
        for (MethodNode m : cn.methods) {
            if (mi.shadowMethods.contains(m.name + m.desc)) continue;
            if (mi.accessors.containsKey(m.name + m.desc)) {
                if ((m.access & Opcodes.ACC_STATIC) != 0) { accessorBody(m, mi, true); keep.add(m); }
                continue;   // instance accessors are served by the dispatcher
            }
            if (m.name.equals("<init>")) {
                if (!mi.itf && mi.hasInit) keep.add(initToStatic(m, mi));
                continue;
            }
            if ((m.access & Opcodes.ACC_STATIC) != 0) { stat.add(m.name + eraseDesc(m.desc, mi)); keep.add(m); continue; }
            if ((m.access & Opcodes.ACC_ABSTRACT) != 0) { if (mi.itf) keep.add(m); continue; }
            // instance → static with the target in slot 0
            m.access = (m.access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) | Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
            m.desc = "(L" + companionSelf(mi) + ";" + m.desc.substring(1);
            m.signature = null;
            m.visibleAnnotations = null; m.invisibleAnnotations = null;
            m.visibleParameterAnnotations = null; m.invisibleParameterAnnotations = null;
            if (m.localVariables != null) m.localVariables.clear();
            keep.add(m);
        }
        staticOrig.put(mi.name, stat);
        cn.methods = keep;
        if (mi.itf) cn.access |= Opcodes.ACC_PUBLIC;
    }

    /** <init> of a mixin = field initialisers for the target; becomes static pf$init(target) run on first slot access. */
    private static MethodNode initToStatic(MethodNode init, MixinInfo mi) {
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pf$init", "(L" + companionSelf(mi) + ";)V", null, null);
        boolean afterSuper = false;
        for (AbstractInsnNode n : init.instructions.toArray()) {
            if (!afterSuper) {
                if (n instanceof MethodInsnNode mn && mn.getOpcode() == Opcodes.INVOKESPECIAL && mn.name.equals("<init>")) afterSuper = true;
                continue;
            }
            init.instructions.remove(n);
            m.instructions.add(n);
        }
        m.tryCatchBlocks = new ArrayList<>();
        return m;
    }

    public static void initFields(int mixin, Object target) {
        MixinInfo mi = byId.get(mixin);
        if (!mi.hasInit) return;
        try {
            Class<?> c = Class.forName(mi.name.replace('/', '.'), true, loader);
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals("pf$init")) { m.setAccessible(true); m.invoke(null, target); }
        } catch (java.lang.reflect.InvocationTargetException e) { throw PfRefl.sneaky(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    /** static @Accessor/@Invoker on an interface mixin: real body via reflection */
    private static void accessorBody(MethodNode m, MixinInfo mi, boolean stat) {
        String[] a = mi.accessors.get(m.name + m.desc);
        m.access &= ~Opcodes.ACC_ABSTRACT;
        m.instructions = accessorCode(a, mi.target, stat ? -1 : 0, m.desc, stat);
        m.tryCatchBlocks = new ArrayList<>(); m.localVariables = null;
    }

    /** body for an accessor/invoker; selfSlot = slot of the target object (-1 for static) */
    private static InsnList accessorCode(String[] a, String owner, int selfSlot, String desc, boolean stat) {
        InsnList c = new InsnList();
        Type[] args = Type.getArgumentTypes(desc); Type ret = Type.getReturnType(desc);
        int slot = stat ? 0 : 1;
        if (a[0].equals("accessor")) {
            if (ret.getSort() == Type.VOID) {     // setter
                c.add(selfSlot < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, selfSlot));
                c.add(new VarInsnNode(args[0].getOpcode(Opcodes.ILOAD), slot)); box(c, args[0]);
                c.add(new LdcInsnNode(owner)); c.add(new LdcInsnNode(a[1]));
                c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "set", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false));
                c.add(new InsnNode(Opcodes.RETURN));
            } else {
                c.add(selfSlot < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, selfSlot));
                c.add(new LdcInsnNode(owner)); c.add(new LdcInsnNode(a[1]));
                c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "get", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
                unbox(c, ret); c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            }
        } else if (a[1].equals("<init>")) {      // @Invoker("<init>") = constructor
            c.add(argArray(args, slot));
            c.add(new LdcInsnNode(owner)); c.add(new LdcInsnNode("(" + descArgs(desc) + ")V"));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "construct", "([Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
            unbox(c, ret); c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
        } else {
            c.add(selfSlot < 0 ? new InsnNode(Opcodes.ACONST_NULL) : new VarInsnNode(Opcodes.ALOAD, selfSlot));
            c.add(argArray(args, slot));
            c.add(new LdcInsnNode(owner)); c.add(new LdcInsnNode(a[1])); c.add(new LdcInsnNode("(" + descArgs(desc) + ")" + ret.getDescriptor()));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
            if (ret.getSort() == Type.VOID) { c.add(new InsnNode(Opcodes.POP)); c.add(new InsnNode(Opcodes.RETURN)); }
            else { unbox(c, ret); c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN))); }
        }
        return c;
    }

    /** Rewrites member accesses in a method. mi != null → this is a companion (mixin) method. */
    /** Rewrites member accesses in a method. mi != null → this is a companion (mixin) method. Accesses to ANY mixin's
     *  members are rewritten, also from its inner/anonymous classes (MixinPlayer$1 calling MixinPlayer.this.x()). */
    private static boolean rewriteCalls(MethodNode m, MixinInfo mi) {
        boolean changed = false;
        for (AbstractInsnNode n : m.instructions.toArray()) {
            if (n instanceof FieldInsnNode f && mixins.containsKey(f.owner)) {
                MixinInfo fm = mixins.get(f.owner);
                boolean stat = f.getOpcode() == Opcodes.GETSTATIC || f.getOpcode() == Opcodes.PUTSTATIC;
                if (fm.shadowFields.contains(f.name)) { replace(m, f, shadowField(f, stat ? fm.target : selfOwner(fm), stat)); changed = true; }
                else if (!stat && fm.addedFields.containsKey(f.name)) { replace(m, f, addedField(f, fm)); changed = true; }
                else if (!fm.declaredFields.contains(f.name)) { replace(m, f, shadowField(f, stat ? fm.target : selfOwner(fm), stat)); changed = true; } // inherited from the mixin's superclass
            } else if (n instanceof MethodInsnNode c) {
                MixinInfo own = mixins.get(c.owner);
                boolean done = false;
                if (own != null && c.getOpcode() != Opcodes.INVOKESTATIC && !c.name.equals("<init>")) {
                    if (own.shadowMethods.contains(c.name + c.desc)) { replace(m, c, shadowCall(c, selfOwner(own))); done = true; }
                    else if (own.ownMethods.contains(c.name + c.desc)) {
                        m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, own.name, c.name, "(L" + companionSelf(own) + ";" + c.desc.substring(1), own.itf));
                        done = true;
                    } else if (!own.declaredMethods.contains(c.name + c.desc) && !own.itf) {
                        replace(m, c, shadowCall(c, selfOwner(own))); done = true;   // inherited from the mixin's superclass
                    }
                } else if (own != null && c.getOpcode() == Opcodes.INVOKESTATIC && own.shadowMethods.contains(c.name + c.desc)) {
                    replace(m, c, staticShadowCall(c, own.target)); done = true;   // @Shadow private static (Architectury MixinNaturalSpawner)
                } else if (own != null && c.getOpcode() == Opcodes.INVOKESTATIC && !own.declaredMethods.contains(c.name + c.desc) && !own.itf) {
                    m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, own.target, c.name, c.desc, false)); done = true;
                } else if (mi != null && c.getOpcode() == Opcodes.INVOKESPECIAL && !c.name.equals("<init>") && !c.owner.equals(mi.name)) {
                    // super.foo() from a mixin extending the target's superclass: best effort, virtual call
                    m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKEVIRTUAL, c.owner, c.name, c.desc, false)); done = true;
                }
                if (!done && dispatchItf.containsKey(c.owner) && c.getOpcode() == Opcodes.INVOKEINTERFACE) {
                    m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCH_PKG + c.owner, c.name, "(Ljava/lang/Object;" + c.desc.substring(1), false));
                    done = true;
                }
                changed |= done;
            } else if (n instanceof TypeInsnNode t && dispatchItf.containsKey(t.desc)) {
                if (t.getOpcode() == Opcodes.CHECKCAST) { m.instructions.remove(t); changed = true; }
                else if (t.getOpcode() == Opcodes.INSTANCEOF) {
                    m.instructions.set(t, new MethodInsnNode(Opcodes.INVOKESTATIC, DISPATCH_PKG + t.desc, "pf$instanceof", "(Ljava/lang/Object;)Z", false));
                    changed = true;
                }
            }
        }
        if (mi != null && m.name.equals("pf$init")) changed = true;
        return changed;
    }

    // ---- JDK parts that don't exist on iOS -------------------------------------------------------------------------
    // The image never contains AWT/Swing/ImageIO/sound/print/beans/sql/RMI/scripting/compiler APIs, and an interpreted
    // call to a method the image lacks is a fatal VM error. Such calls in runtime mods throw instead, so mods that probe
    // optional features (clipboard images, update dialogs, ...) and catch the failure keep working. Same on the desktop
    // AOT build, so PC tests match the phone.
    static final java.util.regex.Pattern UNAVAILABLE = java.util.regex.Pattern.compile(
        "(java/awt|javax/swing|javax/imageio|javax/sound|javax/print|java/beans|java/applet|java/sql|javax/sql|java/rmi"
        + "|javax/script|javax/tools|javax/naming|javax/lang|javax/annotation/processing|java/util/prefs|java/lang/foreign)/.*");

    private static boolean unavailable(MethodNode m) {
        boolean ch = false;
        for (AbstractInsnNode n : m.instructions.toArray()) {
            // java.lang.reflect.Proxy → PfProxy (Crema can't dispatch on JDK proxies of runtime-loaded interfaces)
            if (n instanceof MethodInsnNode c && c.owner.equals("java/lang/reflect/Proxy")
                    && (c.name.equals("newProxyInstance") || c.name.equals("isProxyClass") || c.name.equals("getInvocationHandler"))) {
                c.owner = "dev/pufferfish/runtime/mixin/PfProxy"; ch = true; continue;
            }
            // Lookup.unreflect(interface method) → PfRefl.unreflect (Crema's unreflected interface handles fail to dispatch)
            if (n instanceof MethodInsnNode c && c.getOpcode() == Opcodes.INVOKEVIRTUAL && c.owner.equals("java/lang/invoke/MethodHandles$Lookup")
                    && c.name.equals("unreflect") && c.desc.equals("(Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;")) {
                m.instructions.set(c, new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "unreflect",
                        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/reflect/Method;)Ljava/lang/invoke/MethodHandle;", false));
                ch = true; continue;
            }
            String owner = n instanceof MethodInsnNode c ? c.owner : n instanceof FieldInsnNode f ? f.owner : null;
            if (owner == null || !UNAVAILABLE.matcher(owner).matches()) continue;
            String what = owner.replace('/', '.') + "." + (n instanceof MethodInsnNode c ? c.name : ((FieldInsnNode) n).name);
            InsnList t = new InsnList();
            t.add(new TypeInsnNode(Opcodes.NEW, "java/lang/UnsupportedOperationException")); t.add(new InsnNode(Opcodes.DUP));
            t.add(new LdcInsnNode("[pufferfish] " + what + " is not available on this platform"));
            t.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/UnsupportedOperationException", "<init>", "(Ljava/lang/String;)V", false));
            t.add(new InsnNode(Opcodes.ATHROW));
            replace(m, n, t);
            ch = true;
        }
        return ch;
    }

    // ---- generated classes (proxies) -------------------------------------------------------------------------------
    private static final Map<String, byte[]> generated = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.atomic.AtomicInteger proxyN = new java.util.concurrent.atomic.AtomicInteger();
    /** bytes for a class the runtime mod loader is asked for (pf/proxy/...), once */
    public static byte[] generated(String internal) { return generated.remove(internal); }

    /** pf/proxy/$ProxyN extends PfProxy.Base implements itfs: each method → pf$h.invoke(this, pf$m[i], args) */
    static java.lang.reflect.Constructor<?> proxyClass(Class<?>[] itfs) {
        String name = "pf/proxy/$Proxy" + proxyN.incrementAndGet(), base = "dev/pufferfish/runtime/mixin/PfProxy$Base";
        List<Method> ms = PfProxy.methods(itfs);
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21; cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL | Opcodes.ACC_SUPER; cn.name = name; cn.superName = base;
        for (Class<?> i : itfs) cn.interfaces.add(Type.getInternalName(i));
        cn.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pf$m", "[Ljava/lang/reflect/Method;", null, null));
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "(Ljava/lang/reflect/InvocationHandler;)V", null, null);
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, base, "<init>", "(Ljava/lang/reflect/InvocationHandler;)V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        cn.methods.add(init);
        for (int i = 0; i < ms.size(); i++) {
            Method x = ms.get(i);
            String desc = Type.getMethodDescriptor(x);
            MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL, x.getName(), desc, null, null);
            InsnList c = m.instructions;
            c.add(new VarInsnNode(Opcodes.ALOAD, 0)); c.add(new FieldInsnNode(Opcodes.GETFIELD, base, "pf$h", "Ljava/lang/reflect/InvocationHandler;"));
            c.add(new VarInsnNode(Opcodes.ALOAD, 0));
            c.add(new FieldInsnNode(Opcodes.GETSTATIC, name, "pf$m", "[Ljava/lang/reflect/Method;")); c.add(ldcInt(i)); c.add(new InsnNode(Opcodes.AALOAD));
            Type[] args = Type.getArgumentTypes(desc);
            if (args.length == 0) c.add(new InsnNode(Opcodes.ACONST_NULL)); else c.add(argArray(args, 1));
            c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/lang/reflect/InvocationHandler", "invoke",
                    "(Ljava/lang/Object;Ljava/lang/reflect/Method;[Ljava/lang/Object;)Ljava/lang/Object;", true));
            Type ret = Type.getReturnType(desc);
            if (ret.getSort() == Type.VOID) { c.add(new InsnNode(Opcodes.POP)); c.add(new InsnNode(Opcodes.RETURN)); }
            else { unbox(c, ret); c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN))); }
            cn.methods.add(m);
        }
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String x, String y) { return "java/lang/Object"; }
        };
        cn.accept(w);
        generated.put(name, w.toByteArray());
        try {
            Class<?> k = Class.forName(name.replace('/', '.'), true, loader);
            k.getField("pf$m").set(null, ms.toArray(new Method[0]));
            return k.getConstructor(java.lang.reflect.InvocationHandler.class);
        } catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }

    // ---- type erasure ---------------------------------------------------------------------------------------------
    // Targets never really implement mixin-added interfaces (Cullable, accessor interfaces), so any cast to one fails —
    // including casts the JVM inserts itself (LambdaMetafactory adapting Object → Cullable, array stores). Runtime mod
    // code therefore sees those interfaces as Object in every descriptor; calls go through pf.dispatch.*. Inside a
    // companion the mixin's own type becomes its target ("this" is the target object), and method handles to the
    // mixin's former instance methods point at their static versions.

    /** erasure map for descriptors: dispatch interfaces → Object; in a companion also mixin → target */
    private static String eraseDesc(String d, MixinInfo mi) {
        if (d == null || d.indexOf('L') < 0) return d;
        StringBuilder b = new StringBuilder(d.length());
        int i = 0;
        while (i < d.length()) {
            char ch = d.charAt(i);
            if (ch != 'L') { b.append(ch); i++; continue; }
            int e = d.indexOf(';', i);
            if (e < 0) { b.append(d, i, d.length()); break; }
            String n = d.substring(i + 1, e);
            b.append('L').append(eraseName(n, mi)).append(';');
            i = e + 1;
        }
        return b.toString();
    }
    private static String eraseName(String n, MixinInfo mi) {
        if (dispatchItf.containsKey(n)) return "java/lang/Object";
        MixinInfo x = mixins.get(n);
        return x != null ? companionSelf(x) : n;   // a mixin type anywhere (incl. its inner classes' this$0) = its target
    }
    private static boolean mentionsErased(String d, MixinInfo mi) { return d != null && !eraseDesc(d, mi).equals(d); }

    private static boolean erase(ClassNode cn, MixinInfo mi) {
        if (dispatchItf.isEmpty() && mixins.isEmpty()) return false;
        boolean ch = false;
        for (FieldNode f : cn.fields) if (mentionsErased(f.desc, mi)) { f.desc = eraseDesc(f.desc, mi); f.signature = null; ch = true; }
        for (MethodNode m : cn.methods) {
            if (mentionsErased(m.desc, mi)) { m.desc = eraseDesc(m.desc, mi); m.signature = null; ch = true; }
            if (m.localVariables != null && !m.localVariables.isEmpty() && (mi != null || ch)) m.localVariables.clear();
            for (AbstractInsnNode n : m.instructions.toArray()) {
                if (n instanceof MethodInsnNode c) {
                    if (mentionsErased(c.desc, mi)) { c.desc = eraseDesc(c.desc, mi); ch = true; }
                } else if (n instanceof FieldInsnNode f) {
                    if (mentionsErased(f.desc, mi)) { f.desc = eraseDesc(f.desc, mi); ch = true; }
                } else if (n instanceof TypeInsnNode t) {
                    String nd = t.desc.startsWith("[") ? eraseDesc(t.desc, mi)
                            : (t.getOpcode() == Opcodes.ANEWARRAY || (mixins.containsKey(t.desc) && t.getOpcode() != Opcodes.NEW)) ? eraseName(t.desc, mi) : t.desc;
                    if (!nd.equals(t.desc)) { t.desc = nd; ch = true; }
                } else if (n instanceof MultiANewArrayInsnNode a) {
                    if (mentionsErased(a.desc, mi)) { a.desc = eraseDesc(a.desc, mi); ch = true; }
                } else if (n instanceof InvokeDynamicInsnNode indy) {
                    if (mentionsErased(indy.desc, mi)) { indy.desc = eraseDesc(indy.desc, mi); ch = true; }
                    for (int i = 0; i < indy.bsmArgs.length; i++) {
                        Object o = indy.bsmArgs[i], r = o;
                        if (o instanceof Type ty && ty.getSort() == Type.METHOD) r = Type.getMethodType(eraseDesc(ty.getDescriptor(), mi));
                        else if (o instanceof Handle h) r = eraseHandle(h, mi);
                        if (!r.equals(o)) { indy.bsmArgs[i] = r; ch = true; }
                    }
                } else if (n instanceof LdcInsnNode l) {
                    if (l.cst instanceof Type ty && ty.getSort() == Type.METHOD && mentionsErased(ty.getDescriptor(), mi)) { l.cst = Type.getMethodType(eraseDesc(ty.getDescriptor(), mi)); ch = true; }
                    else if (l.cst instanceof Handle h) { Object r = eraseHandle(h, mi); if (!r.equals(h)) { l.cst = r; ch = true; } }
                }
            }
        }
        return ch;
    }

    private static Handle eraseHandle(Handle h, MixinInfo mi) {
        MixinInfo own = mixins.get(h.getOwner());
        int tag = h.getTag();
        if (own != null && (tag == Opcodes.H_INVOKEVIRTUAL || tag == Opcodes.H_INVOKESPECIAL || tag == Opcodes.H_INVOKEINTERFACE)
                && own.ownMethods.contains(h.getName() + h.getDesc()))
            return new Handle(Opcodes.H_INVOKESTATIC, own.name, h.getName(),
                    eraseDesc("(L" + companionSelf(own) + ";" + h.getDesc().substring(1), mi), own.itf);
        // method reference to a dispatch interface method (accessor::getFoo) → the static dispatcher
        if (dispatchItf.containsKey(h.getOwner()) && (tag == Opcodes.H_INVOKEINTERFACE || tag == Opcodes.H_INVOKEVIRTUAL))
            return new Handle(Opcodes.H_INVOKESTATIC, DISPATCH_PKG + h.getOwner(), h.getName(),
                    eraseDesc("(Ljava/lang/Object;" + h.getDesc().substring(1), mi), false);
        String d = eraseDesc(h.getDesc(), mi);
        return d.equals(h.getDesc()) ? h : new Handle(tag, h.getOwner(), h.getName(), d, h.isInterface());
    }

    private static void replace(MethodNode m, AbstractInsnNode at, InsnList with) { m.instructions.insert(at, with); m.instructions.remove(at); }

    private static boolean accessible(String owner, String name, boolean field) {
        try {
            Class<?> c = Class.forName(owner.replace('/', '.'), false, loader);
            if (!java.lang.reflect.Modifier.isPublic(c.getModifiers())) return false;
            for (Class<?> k = c; k != null; k = k.getSuperclass()) {
                if (field) { for (var f : k.getDeclaredFields()) if (f.getName().equals(name)) return java.lang.reflect.Modifier.isPublic(f.getModifiers()); }
                else for (var mm : k.getDeclaredMethods()) if (mm.getName().equals(name)) return java.lang.reflect.Modifier.isPublic(mm.getModifiers());
            }
        } catch (Throwable ignored) { }
        return false;
    }

    private static InsnList shadowField(FieldInsnNode f, String target, boolean stat) {
        InsnList c = new InsnList();
        boolean get = f.getOpcode() == Opcodes.GETFIELD || f.getOpcode() == Opcodes.GETSTATIC;
        if (!target.equals("*") && accessible(target, f.name, true)) { c.add(new FieldInsnNode(f.getOpcode(), target, f.name, f.desc)); return c; }
        Type t = Type.getType(f.desc);
        if (get) {
            if (stat) c.add(new InsnNode(Opcodes.ACONST_NULL));
            c.add(new LdcInsnNode(target)); c.add(new LdcInsnNode(f.name));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "get", "(Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
            unbox(c, t);
        } else {
            // stack: [obj] value → box value; for static push null first and swap
            box(c, t);
            if (stat) { c.add(new InsnNode(Opcodes.ACONST_NULL)); c.add(new InsnNode(Opcodes.SWAP)); }
            c.add(new LdcInsnNode(target)); c.add(new LdcInsnNode(f.name));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "set", "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;)V", false));
        }
        return c;
    }

    private static InsnList addedField(FieldInsnNode f, MixinInfo mi) {
        InsnList c = new InsnList();
        Type t = Type.getType(f.desc);
        int idx = mi.addedFields.get(f.name);
        if (f.getOpcode() == Opcodes.GETFIELD) {
            c.add(ldcInt(mi.id)); c.add(ldcInt(idx));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfFields", "get", "(Ljava/lang/Object;II)Ljava/lang/Object;", false));
            unboxOrDefault(c, t);
        } else {
            box(c, t);
            c.add(ldcInt(mi.id)); c.add(ldcInt(idx));
            c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfFields", "set", "(Ljava/lang/Object;Ljava/lang/Object;II)V", false));
        }
        return c;
    }

    /** static @Shadow call: invokestatic on the target, or PfRefl.invoke(null, args, ...) when it isn't public */
    private static InsnList staticShadowCall(MethodInsnNode c, String target) {
        InsnList r = new InsnList();
        if (accessible(target, c.name, false)) {
            boolean itf = isInterface(target);
            r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, target, c.name, c.desc, itf));
            return r;
        }
        Type[] args = Type.getArgumentTypes(c.desc); Type ret = Type.getReturnType(c.desc);
        r.add(packArgs(args));                                  // args... → arr
        r.add(new InsnNode(Opcodes.ACONST_NULL)); r.add(new InsnNode(Opcodes.SWAP));   // → null, arr
        r.add(new LdcInsnNode(target)); r.add(new LdcInsnNode(c.name)); r.add(new LdcInsnNode(c.desc));
        r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
        if (ret.getSort() == Type.VOID) r.add(new InsnNode(Opcodes.POP)); else unbox(r, ret);
        return r;
    }

    /** stack: args... → Object[] (boxed), leaving whatever is below the args untouched */
    private static InsnList packArgs(Type[] args) {
        InsnList r = new InsnList();
        r.add(ldcInt(args.length)); r.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = args.length - 1; i >= 0; i--) {
            if (args[i].getSize() == 2) { r.add(new InsnNode(Opcodes.DUP_X2)); r.add(new InsnNode(Opcodes.POP)); }
            else r.add(new InsnNode(Opcodes.SWAP));
            box(r, args[i]);
            r.add(new InsnNode(Opcodes.SWAP)); r.add(new InsnNode(Opcodes.DUP_X1)); r.add(new InsnNode(Opcodes.SWAP));
            r.add(ldcInt(i)); r.add(new InsnNode(Opcodes.SWAP)); r.add(new InsnNode(Opcodes.AASTORE));
        }
        return r;
    }

    private static InsnList shadowCall(MethodInsnNode c, String target) {
        InsnList r = new InsnList();
        boolean targetItf = !target.equals("*") && isInterface(target);
        if (!target.equals("*") && accessible(target, c.name, false)) {
            r.add(new MethodInsnNode(targetItf ? Opcodes.INVOKEINTERFACE : Opcodes.INVOKEVIRTUAL, target, c.name, c.desc, targetItf));
            return r;
        }
        // stack: obj, args... → pack args into Object[] then PfRefl.invoke(obj, args, owner, name, desc)
        Type[] args = Type.getArgumentTypes(c.desc); Type ret = Type.getReturnType(c.desc);
        r.add(ldcInt(args.length)); r.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = args.length - 1; i >= 0; i--) {
            // array below value: [.. v arr] → [.. arr v] → box → store at i (via dup_x and swap dance for wide types)
            if (args[i].getSize() == 2) { r.add(new InsnNode(Opcodes.DUP_X2)); r.add(new InsnNode(Opcodes.POP)); }
            else r.add(new InsnNode(Opcodes.SWAP));
            box(r, args[i]);
            r.add(new InsnNode(Opcodes.SWAP)); r.add(new InsnNode(Opcodes.DUP_X1)); r.add(new InsnNode(Opcodes.SWAP));
            r.add(ldcInt(i)); r.add(new InsnNode(Opcodes.SWAP)); r.add(new InsnNode(Opcodes.AASTORE));
        }
        r.add(new LdcInsnNode(target)); r.add(new LdcInsnNode(c.name)); r.add(new LdcInsnNode(c.desc));
        r.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "dev/pufferfish/runtime/mixin/PfRefl", "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Object;", false));
        if (ret.getSort() == Type.VOID) r.add(new InsnNode(Opcodes.POP)); else unbox(r, ret);
        return r;
    }

    private static boolean isInterface(String internal) {
        try { return Class.forName(internal.replace('/', '.'), false, loader).isInterface(); } catch (Throwable t) { return false; }
    }

    // ---- dispatchers ----------------------------------------------------------------------------------------------

    /** pf.dispatch.<itf>: static per-method dispatch for interfaces implemented through mixins. */
    public static byte[] dispatcher(String itf) {
        List<MixinInfo> impls = dispatchItf.get(itf);
        if (impls == null) return null;
        ClassNode itfNode = null;
        try (InputStream in = loader.getResourceAsStream(itf + ".class")) { if (in != null) itfNode = node(in.readAllBytes()); }
        catch (IOException ignored) { }
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V21; cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL; cn.name = DISPATCH_PKG + itf; cn.superName = "java/lang/Object";
        // instanceof
        MethodNode io = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, "pf$instanceof", "(Ljava/lang/Object;)Z", null, null);
        for (MixinInfo mi : impls) for (String t : mi.targets) {
            LabelNode no = new LabelNode();
            io.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); io.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, t));
            io.instructions.add(new JumpInsnNode(Opcodes.IFEQ, no)); io.instructions.add(new InsnNode(Opcodes.ICONST_1));
            io.instructions.add(new InsnNode(Opcodes.IRETURN)); io.instructions.add(no);
        }
        if (itfNode != null && !impls.get(0).itf) {
            io.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0)); io.instructions.add(new TypeInsnNode(Opcodes.INSTANCEOF, itf));
            io.instructions.add(new InsnNode(Opcodes.IRETURN));
        } else { io.instructions.add(new InsnNode(Opcodes.ICONST_0)); io.instructions.add(new InsnNode(Opcodes.IRETURN)); }
        cn.methods.add(io);
        // one static method per interface method (incl. inherited ones mod code may call through this interface)
        Set<String> done = new HashSet<>();
        for (ClassNode k = itfNode; k != null; k = null) for (MethodNode im : k.methods) {
            if ((im.access & Opcodes.ACC_STATIC) != 0 || im.name.startsWith("<") || !done.add(im.name + im.desc)) continue;
            cn.methods.add(dispatchMethod(itf, im, impls));
        }
        erase(cn, null);   // same descriptors as the (erased) companions and runtime mod classes it is called from
        ClassWriter w = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override protected String getCommonSuperClass(String x, String y) { return "java/lang/Object"; }
        };
        cn.accept(w);
        return w.toByteArray();
    }

    private static MethodNode dispatchMethod(String itf, MethodNode im, List<MixinInfo> impls) {
        String desc = "(Ljava/lang/Object;" + im.desc.substring(1);
        MethodNode m = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC, im.name, desc, null, null);
        Type[] args = Type.getArgumentTypes(im.desc); Type ret = Type.getReturnType(im.desc);
        InsnList c = m.instructions;
        for (MixinInfo mi : impls) {
            String[] acc = mi.accessors.get(im.name + im.desc);
            boolean implemented = acc != null || mi.ownMethods.contains(im.name + im.desc);
            if (!implemented) continue;
            for (String t : mi.targets) {
                LabelNode next = new LabelNode();
                c.add(new VarInsnNode(Opcodes.ALOAD, 0)); c.add(new TypeInsnNode(Opcodes.INSTANCEOF, t)); c.add(new JumpInsnNode(Opcodes.IFEQ, next));
                if (acc != null) {
                    InsnList body = accessorCode(acc, t, 0, im.desc, false);
                    c.add(body);
                } else {
                    c.add(new VarInsnNode(Opcodes.ALOAD, 0)); c.add(new TypeInsnNode(Opcodes.CHECKCAST, companionSelf(mi)));
                    int slot = 1; for (Type a : args) { c.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot)); slot += a.getSize(); }
                    c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, mi.name, im.name, "(L" + companionSelf(mi) + ";" + im.desc.substring(1), mi.itf));
                    c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
                }
                c.add(next);
            }
        }
        // a real implementor (mod's own class implementing the interface)
        boolean mixinItf = mixins.containsKey(itf);
        if (!mixinItf) {
            LabelNode next = new LabelNode();
            c.add(new VarInsnNode(Opcodes.ALOAD, 0)); c.add(new TypeInsnNode(Opcodes.INSTANCEOF, itf)); c.add(new JumpInsnNode(Opcodes.IFEQ, next));
            c.add(new VarInsnNode(Opcodes.ALOAD, 0)); c.add(new TypeInsnNode(Opcodes.CHECKCAST, itf));
            int slot = 1; for (Type a : args) { c.add(new VarInsnNode(a.getOpcode(Opcodes.ILOAD), slot)); slot += a.getSize(); }
            c.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, itf, im.name, im.desc, true));
            c.add(new InsnNode(ret.getOpcode(Opcodes.IRETURN)));
            c.add(next);
        }
        if ((im.access & Opcodes.ACC_ABSTRACT) == 0 && im.instructions.size() > 0) {
            // default method, no mixin overrides it for this object: run its body with "this" = the target object
            rewriteCalls(im, null);   // only the copied body: the dispatch code above must keep its real instanceof/invokeinterface
            c.add(im.instructions);
            if (im.tryCatchBlocks != null) m.tryCatchBlocks.addAll(im.tryCatchBlocks);
        } else {
            c.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IncompatibleClassChangeError")); c.add(new InsnNode(Opcodes.DUP));
            c.add(new LdcInsnNode("[pufferfish] no mixin implements " + itf + "." + im.name + " for this object"));
            c.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IncompatibleClassChangeError", "<init>", "(Ljava/lang/String;)V", false));
            c.add(new InsnNode(Opcodes.ATHROW));
        }
        return m;
    }

    // ---- small helpers --------------------------------------------------------------------------------------------

    static ClassNode node(byte[] b) { ClassNode cn = new ClassNode(); new ClassReader(b).accept(cn, ClassReader.SKIP_FRAMES); return cn; }

    static AnnotationNode ann(ClassNode c, String d) {
        for (List<AnnotationNode> l : List.of(Optional.ofNullable(c.invisibleAnnotations).orElse(List.of()), Optional.ofNullable(c.visibleAnnotations).orElse(List.of())))
            for (AnnotationNode a : l) if (a.desc.equals(d)) return a;
        return null;
    }
    static AnnotationNode annOf(MethodNode m, String d) {
        for (List<AnnotationNode> l : List.of(Optional.ofNullable(m.invisibleAnnotations).orElse(List.of()), Optional.ofNullable(m.visibleAnnotations).orElse(List.of())))
            for (AnnotationNode a : l) if (a.desc.equals(d)) return a;
        return null;
    }
    static boolean has(List<AnnotationNode> a, List<AnnotationNode> b, String d) {
        for (List<AnnotationNode> l : Arrays.asList(a, b)) if (l != null) for (AnnotationNode x : l) if (x.desc.equals(d)) return true;
        return false;
    }
    static String annValue(AnnotationNode a) {
        if (a.values != null) for (int i = 0; i < a.values.size(); i += 2) if ("value".equals(a.values.get(i))) return String.valueOf(a.values.get(i + 1));
        return null;
    }
    static String inferMember(String method, boolean accessor) {
        String n = method.replaceFirst("^(get|set|is|call|invoke|create|new)", "");
        if (n.isEmpty()) return method;
        if (!accessor && (method.startsWith("create") || method.startsWith("new"))) return "<init>";
        return Character.toLowerCase(n.charAt(0)) + n.substring(1);
    }
    static String injectorKind(MethodNode m) {
        for (List<AnnotationNode> l : Arrays.asList(m.visibleAnnotations, m.invisibleAnnotations)) if (l != null) for (AnnotationNode a : l) {
            if (a.desc.startsWith("Lorg/spongepowered/asm/mixin/injection/") && !a.desc.contains("/callback/")) return simpleDesc(a.desc);
            if (a.desc.startsWith("Lcom/llamalad7/mixinextras/injector/")) return simpleDesc(a.desc);
            if (a.desc.equals(OVERWRITE)) return "Overwrite";
        }
        return null;
    }
    static boolean initHasCode(MethodNode init) {
        boolean afterSuper = false;
        for (AbstractInsnNode n : init.instructions) {
            if (!afterSuper) { if (n instanceof MethodInsnNode mn && mn.name.equals("<init>")) afterSuper = true; continue; }
            if (n.getOpcode() >= 0 && n.getOpcode() != Opcodes.RETURN) return true;
        }
        return false;
    }
    static String simpleDesc(String d) { return d.substring(d.lastIndexOf('/') + 1, d.length() - 1); }
    static String simple(String internal) { return internal.substring(internal.lastIndexOf('/') + 1); }
    static String descArgs(String desc) { return desc.substring(1, desc.indexOf(')')); }

    static InsnList argArray(Type[] args, int slot) {
        InsnList c = new InsnList();
        c.add(ldcInt(args.length)); c.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < args.length; i++) {
            c.add(new InsnNode(Opcodes.DUP)); c.add(ldcInt(i));
            c.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), slot)); box(c, args[i]);
            c.add(new InsnNode(Opcodes.AASTORE)); slot += args[i].getSize();
        }
        return c;
    }
    static void box(InsnList c, Type t) {
        String w = wrapper(t);
        if (w != null) c.add(new MethodInsnNode(Opcodes.INVOKESTATIC, w, "valueOf", "(" + t.getDescriptor() + ")L" + w + ";", false));
    }
    static void unbox(InsnList c, Type t) {
        String w = wrapper(t);
        if (t.getSort() == Type.VOID) return;
        if (w == null) { c.add(new TypeInsnNode(Opcodes.CHECKCAST, t.getSort() == Type.ARRAY ? t.getDescriptor() : t.getInternalName())); return; }
        c.add(new TypeInsnNode(Opcodes.CHECKCAST, w));
        c.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, w, t.getClassName() + "Value", "()" + t.getDescriptor(), false));
    }
    /** added-field read: null slot = Java default value for primitives */
    static void unboxOrDefault(InsnList c, Type t) {
        String w = wrapper(t);
        if (w == null) { unbox(c, t); return; }
        LabelNode notNull = new LabelNode(), end = new LabelNode();
        c.add(new InsnNode(Opcodes.DUP)); c.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        c.add(new InsnNode(Opcodes.POP));
        switch (t.getSort()) {
            case Type.LONG -> c.add(new InsnNode(Opcodes.LCONST_0));
            case Type.FLOAT -> c.add(new InsnNode(Opcodes.FCONST_0));
            case Type.DOUBLE -> c.add(new InsnNode(Opcodes.DCONST_0));
            default -> c.add(new InsnNode(Opcodes.ICONST_0));
        }
        c.add(new JumpInsnNode(Opcodes.GOTO, end));
        c.add(notNull); unbox(c, t); c.add(end);
    }
    static String wrapper(Type t) {
        return switch (t.getSort()) {
            case Type.BOOLEAN -> "java/lang/Boolean"; case Type.BYTE -> "java/lang/Byte"; case Type.CHAR -> "java/lang/Character";
            case Type.SHORT -> "java/lang/Short"; case Type.INT -> "java/lang/Integer"; case Type.LONG -> "java/lang/Long";
            case Type.FLOAT -> "java/lang/Float"; case Type.DOUBLE -> "java/lang/Double"; default -> null;
        };
    }
    static AbstractInsnNode ldcInt(int v) {
        if (v >= -1 && v <= 5) return new InsnNode(Opcodes.ICONST_0 + v);
        if (v >= Byte.MIN_VALUE && v <= Byte.MAX_VALUE) return new IntInsnNode(Opcodes.BIPUSH, v);
        if (v >= Short.MIN_VALUE && v <= Short.MAX_VALUE) return new IntInsnNode(Opcodes.SIPUSH, v);
        return new LdcInsnNode(v);
    }

    static String read(List<Path> roots, String rel) {
        byte[] b = readBytes(roots, rel);
        return b == null ? null : new String(b, StandardCharsets.UTF_8);
    }
    static byte[] readBytes(List<Path> roots, String rel) {
        for (Path r : roots) { Path p = r.resolve(rel); if (Files.exists(p)) try { return Files.readAllBytes(p); } catch (IOException ignored) { } }
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
}
