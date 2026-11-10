import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * MixinSurvey <mods-dir>: which Mixin features do real mods use? Counts injector/member annotations, @At targets and
 * class-level tricks (added interfaces, @Unique fields), and per mod lists the features it needs. Drives what the
 * runtime mixin adapter must support first.
 */
public class MixinSurvey {
    static final Map<String, Integer> ann = new TreeMap<>(), at = new TreeMap<>(), cls = new TreeMap<>();
    static final Map<String, Set<String>> perMod = new TreeMap<>();
    static int mixinClasses;

    public static void main(String[] a) throws Exception {
        try (var s = Files.list(Paths.get(a[0]))) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".jar")).sorted()::iterator) {
                String mod = p.getFileName().toString().replace(".jar", "");
                Set<String> f = new TreeSet<>();
                scanJar(Files.readAllBytes(p), f);
                perMod.put(mod, f);
            }
        }
        System.out.println("== " + mixinClasses + " mixin classes in " + perMod.size() + " mods");
        System.out.println("\n== member annotations (count)"); dump(ann);
        System.out.println("\n== @At values (injectors)"); dump(at);
        System.out.println("\n== class-level"); dump(cls);
        // feature tiers
        Set<String> tier1 = Set.of("Inject", "Shadow", "Accessor", "Invoker", "Unique-method", "Final", "Mutable",
                "Overwrite-none", "Mixin-interface", "at:HEAD", "at:RETURN", "at:TAIL", "at:INVOKE", "at:FIELD", "at:NEW",
                "at:CONSTANT", "at:INVOKE_ASSIGN", "at:JUMP", "at:STORE", "at:LOAD", "Unique-field", "adds-interface");
        int none = 0, t1 = 0; List<String> blockers = new ArrayList<>();
        Map<String, Integer> blockCount = new TreeMap<>();
        for (var e : perMod.entrySet()) {
            Set<String> f = e.getValue();
            if (f.isEmpty()) { none++; continue; }
            Set<String> extra = new TreeSet<>(f); extra.removeAll(tier1);
            if (extra.isEmpty()) t1++;
            else for (String x : extra) blockCount.merge(x, 1, Integer::sum);
        }
        System.out.printf("%n== mods: %d without mixins, %d need only {Inject + Shadow/Accessor/Invoker/Unique + adds-interface}, %d need more%n",
                none, t1, perMod.size() - none - t1);
        System.out.println("== what the remaining mods additionally need (mods per feature)"); dump(blockCount);
        if (a.length > 1) perMod.forEach((m, f) -> System.out.println("  " + m + ": " + f));
    }

    static void dump(Map<String, Integer> m) {
        m.entrySet().stream().sorted((x, y) -> y.getValue() - x.getValue())
            .forEach(e -> System.out.printf("  %-34s %6d%n", e.getKey(), e.getValue()));
    }

    static void scanJar(byte[] bytes, Set<String> f) throws IOException {
        try (var z = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String n = e.getName();
                if (n.endsWith(".jar")) scanJar(z.readAllBytes(), f);
                else if (n.endsWith(".class")) scanClass(z.readAllBytes(), f);
            }
        }
    }

    static String simple(String desc) { String s = desc.substring(desc.lastIndexOf('/') + 1); return s.endsWith(";") ? s.substring(0, s.length() - 1) : s; }

    static void scanClass(byte[] b, Set<String> f) {
        ClassNode cn = new ClassNode();
        try { new ClassReader(b).accept(cn, ClassReader.SKIP_CODE | ClassReader.SKIP_FRAMES); } catch (Throwable t) { return; }
        if (all(cn.visibleAnnotations, cn.invisibleAnnotations).stream().noneMatch(x -> x.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;"))) return;
        mixinClasses++;
        boolean itf = (cn.access & Opcodes.ACC_INTERFACE) != 0;
        if (itf) { cls.merge("interface mixin (accessors)", 1, Integer::sum); f.add("Mixin-interface"); }
        if (!itf && cn.interfaces != null && !cn.interfaces.isEmpty()) { cls.merge("adds interfaces to target", 1, Integer::sum); f.add("adds-interface"); }
        if (!itf && cn.superName != null && !cn.superName.equals("java/lang/Object")) cls.merge("extends a class (shadow super)", 1, Integer::sum);
        for (FieldNode fn : cn.fields) {
            List<AnnotationNode> fa = all(fn.visibleAnnotations, fn.invisibleAnnotations);
            boolean shadow = has(fa, "Shadow");
            if (!shadow && (fn.access & Opcodes.ACC_SYNTHETIC) == 0) { cls.merge("new field (@Unique or plain)", 1, Integer::sum); f.add("Unique-field"); }
            for (String x : names(fa)) { ann.merge("field @" + x, 1, Integer::sum); f.add(x); }
        }
        for (MethodNode mn : cn.methods) {
            List<AnnotationNode> ma = all(mn.visibleAnnotations, mn.invisibleAnnotations);
            List<String> as = names(ma);
            if (as.isEmpty() && !mn.name.startsWith("<") && (mn.access & Opcodes.ACC_SYNTHETIC) == 0 && !itf) { ann.merge("(plain method → merged/new)", 1, Integer::sum); f.add("Unique-method"); }
            for (String x : as) { ann.merge("@" + x, 1, Integer::sum); f.add(x.equals("Overwrite") ? "Overwrite" : x); }
            for (AnnotationNode an : ma) collectAt(an, f);
        }
    }

    static void collectAt(Object o, Set<String> f) {
        if (o instanceof AnnotationNode an) {
            if (an.desc.endsWith("/At;") && an.values != null) {
                for (int i = 0; i < an.values.size(); i += 2) if ("value".equals(an.values.get(i))) {
                    String v = String.valueOf(an.values.get(i + 1)); at.merge(v, 1, Integer::sum); f.add("at:" + v);
                }
            }
            if (an.values != null) for (Object v : an.values) collectAt(v, f);
        } else if (o instanceof List<?> l) for (Object v : l) collectAt(v, f);
    }

    static List<AnnotationNode> all(List<AnnotationNode> a, List<AnnotationNode> b) {
        List<AnnotationNode> r = new ArrayList<>(); if (a != null) r.addAll(a); if (b != null) r.addAll(b); return r;
    }
    static boolean has(List<AnnotationNode> l, String n) { return names(l).contains(n); }
    static List<String> names(List<AnnotationNode> l) {
        List<String> r = new ArrayList<>();
        if (l != null) for (AnnotationNode x : l) if (x.desc.startsWith("Lorg/spongepowered/") || x.desc.startsWith("Lcom/llamalad7/mixinextras/")) r.add(simple(x.desc));
        return r;
    }
}
