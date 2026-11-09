import org.objectweb.asm.*;
import org.objectweb.asm.commons.ClassRemapper;
import org.objectweb.asm.commons.SimpleRemapper;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

/**
 * Cross builds (Linux builder -> Darwin target): the image gets the builder's own (Linux) JDK
 * classes. For every JDK class whose bytecode differs between the Linux and Darwin builds, emit a
 * native-image substitution carrying the Darwin bytecode.
 *
 *  - original extends Object  -> whole-class substitution S_x (@TargetClass @Substitute, all Darwin members)
 *  - other superclass         -> method-level: every method present in both builds gets the Darwin
 *                                body via @Substitute
 *
 * The substitution classes live in an unnamed module, so Darwin bodies can't touch package-private /
 * protected JDK members directly. Every such class or member is reached through a generated alias
 * class A_x (@TargetClass x, @Alias members), and all references are remapped (x -> S_x / A_x).
 *
 * native-image force-runs a substitution class's <clinit> on the (Linux) builder, so the Darwin
 * initializer is moved to $pf_clinit and only invoked in the image at run time (whole-class mode;
 * originals must be --initialize-at-run-time). The builder runs with -XX:-BytecodeVerificationRemote:
 * these classes never execute there.
 *
 * Usage: GenJdkSubst <darwin-classes-dir> <linux-classes-dir> <darwin-only-dir> <class-list.txt> <out.jar> <exports-out.txt>
 */
public class GenJdkSubst {
    static final String TC = "Lcom/oracle/svm/core/annotate/TargetClass;";
    static final String SUB = "Lcom/oracle/svm/core/annotate/Substitute;";
    static final String ALIAS = "Lcom/oracle/svm/core/annotate/Alias;";
    static final String PKG = "dev/pufferfish/cross/jdk/";

    /** class metadata of the *target* JDK view (Linux classes + patched darwin-only classes) */
    record CInfo(String name, int access, String sup, String[] itfs,
                 Map<String, Integer> fields, Map<String, String> fieldDesc, Map<String, Integer> methods) { }

    static Path darwinDir, linuxDir, darwinOnlyDir;
    static final Map<String, Optional<CInfo>> db = new HashMap<>();

    static CInfo info(String n) {
        return db.computeIfAbsent(n, k -> {
            for (Path d : new Path[] { linuxDir, darwinOnlyDir }) {
                Path p = d.resolve(k + ".class");
                if (Files.isRegularFile(p)) {
                    try { return Optional.of(parse(Files.readAllBytes(p))); } catch (Exception e) { throw new RuntimeException(e); }
                }
            }
            return Optional.empty();
        }).orElse(null);
    }

    static CInfo parse(byte[] b) {
        Map<String, Integer> f = new HashMap<>(), m = new HashMap<>();
        Map<String, String> fd = new HashMap<>();
        Object[] h = new Object[4];
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int a, String n, String s, String su, String[] i) { h[0] = n; h[1] = a; h[2] = su; h[3] = i; }
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object val) { f.put(n, a); fd.put(n, d); return null; }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) { m.put(n + d, a); return null; }
        }, ClassReader.SKIP_CODE);
        return new CInfo((String) h[0], (int) h[1], (String) h[2], (String[]) h[3], f, fd, m);
    }

    // ---- state shared between passes ----
    static Set<String> list = new LinkedHashSet<>();
    static Map<String, Boolean> wholeClassOf = new HashMap<>();
    static Map<String, String> rename = new HashMap<>();
    /** declaring class -> alias members ("F name desc acc" / "M name desc acc") */
    static Map<String, Set<String>> aliasMembers = new TreeMap<>();
    static Set<String> aliasTypes = new TreeSet<>();
    static Set<String> exportPkgs = new TreeSet<>();
    /**
     * Whole-class substitutions: svm binds a substitution field to the original field of the same *name*. When the
     * Darwin field has a different type (DefaultFileSystemProvider.INSTANCE: MacOSX vs Linux provider) that binds to
     * a deleted Linux field, so such fields get a distinct name.
     */
    static String fieldName(String owner, String name, String desc) {
        if (!list.contains(owner) || !wholeClassOf.get(owner)) return name;
        CInfo lc = info(owner);
        String ld = lc == null ? null : lc.fieldDesc().get(name);
        return ld != null && !ld.equals(desc) ? name + "$darwin" : name;
    }

    /** method-level classes: name+desc of methods carrying a Darwin @Substitute body */
    static Map<String, Set<String>> substituted = new HashMap<>();

    static boolean isPublic(int acc) { return (acc & Opcodes.ACC_PUBLIC) != 0; }

    /** a type the generated code mentions: alias it unless it's public (or one of ours) */
    static void noteType(String t) {
        if (t == null) return;
        while (t.startsWith("[")) t = t.substring(1);
        if (t.startsWith("L") && t.endsWith(";")) t = t.substring(1, t.length() - 1);
        if (t.length() == 1) return; // primitive
        if (list.contains(t)) return;
        CInfo c = info(t);
        if (c == null) return; // other module / unknown: assume accessible
        int slash = t.lastIndexOf('/');
        if (slash > 0) exportPkgs.add(t.substring(0, slash).replace('/', '.'));
        if (!isPublic(c.access())) aliasTypes.add(t);
    }

    static void noteDesc(String desc) {
        if (desc == null) return;
        Type ty = desc.startsWith("(") ? Type.getMethodType(desc) : Type.getType(desc);
        if (ty.getSort() == Type.METHOD) {
            noteType(ty.getReturnType().getDescriptor());
            for (Type a : ty.getArgumentTypes()) noteType(a.getDescriptor());
        } else noteType(ty.getDescriptor());
    }

    /** find the class declaring a member as the JVM would resolve it (target JDK view) */
    static String resolveDecl(String owner, String name, String desc, boolean field) {
        Deque<String> q = new ArrayDeque<>(List.of(owner));
        Set<String> seen = new HashSet<>();
        while (!q.isEmpty()) {
            String c = q.poll();
            if (c == null || !seen.add(c)) continue;
            CInfo ci = info(c);
            if (ci == null) return null;
            if (field ? ci.fields().containsKey(name) : ci.methods().containsKey(name + desc)) return c;
            if (ci.sup() != null) q.add(ci.sup());
            if (ci.itfs() != null) q.addAll(Arrays.asList(ci.itfs()));
        }
        return null;
    }

    static int memberAccess(String decl, String name, String desc, boolean field) {
        CInfo ci = info(decl);
        Integer a = field ? ci.fields().get(name) : ci.methods().get(name + desc);
        return a == null ? 0 : a;
    }

    /** returns the owner the instruction should name after aliasing (pre-rename) */
    static String noteMember(String owner, String name, String desc, boolean field) {
        noteDesc(desc);
        if (owner.startsWith("[")) { noteType(owner); return owner; }
        if (list.contains(owner) && wholeClassOf.get(owner)) return owner; // S_ has every member
        String decl = resolveDecl(owner, name, desc, field);
        if (decl == null) { noteType(owner); return owner; }
        CInfo dc = info(decl);
        int ma = memberAccess(decl, name, desc, field);
        boolean ok = !list.contains(decl) && isPublic(dc.access()) && isPublic(ma);
        if (ok) { noteType(owner); return owner; }
        if (list.contains(decl) && wholeClassOf.get(decl)) return decl;
        if (!field && list.contains(decl) && substituted.get(decl).contains(name + desc)) return decl;
        aliasMembers.computeIfAbsent(decl, k -> new TreeSet<>())
                .add((field ? "F " : "M ") + name + " " + desc + " " + ma);
        if (!list.contains(decl)) aliasTypes.add(decl);
        return decl;
    }

    /** method visitor that routes member owners through noteMember (both passes use it) */
    static class RefVisitor extends MethodVisitor {
        RefVisitor(MethodVisitor mv) { super(Opcodes.ASM9, mv); }
        @Override public void visitFieldInsn(int op, String owner, String name, String desc) {
            String o = noteMember(owner, name, desc, true);
            super.visitFieldInsn(op, o, fieldName(o, name, desc), desc);
        }
        @Override public void visitMethodInsn(int op, String owner, String name, String desc, boolean itf) {
            String o = name.equals("<init>") ? owner : noteMember(owner, name, desc, false);
            if (name.equals("<init>")) {
                // constructors: alias when not reachable
                noteDesc(desc);
                if (!list.contains(owner) || !wholeClassOf.get(owner)) {
                    CInfo c = info(owner);
                    if (c != null && (list.contains(owner) || !isPublic(c.access()) || !isPublic(memberAccess(owner, name, desc, false)))) {
                        aliasMembers.computeIfAbsent(owner, k -> new TreeSet<>()).add("M <init> " + desc + " " + memberAccess(owner, name, desc, false));
                        if (!list.contains(owner)) aliasTypes.add(owner);
                    }
                }
            }
            CInfo oc = info(o);
            boolean isItf = oc != null ? (oc.access() & Opcodes.ACC_INTERFACE) != 0 : itf;
            super.visitMethodInsn(op, o, name, desc, isItf);
        }
        @Override public void visitTypeInsn(int op, String t) { noteType(t); super.visitTypeInsn(op, t); }
        @Override public void visitMultiANewArrayInsn(String d, int dims) { noteType(d); super.visitMultiANewArrayInsn(d, dims); }
        @Override public void visitTryCatchBlock(Label s, Label e, Label h, String t) { noteType(t); super.visitTryCatchBlock(s, e, h, t); }
        @Override public void visitLdcInsn(Object v) {
            if (v instanceof Type t) noteDesc(t.getDescriptor());
            super.visitLdcInsn(v);
        }
        @Override public void visitInvokeDynamicInsn(String name, String desc, Handle bsm, Object... args) {
            noteDesc(desc);
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Handle hd && !hd.getName().equals("<init>")) {
                    boolean f = hd.getTag() <= Opcodes.H_PUTSTATIC;
                    String o = noteMember(hd.getOwner(), hd.getName(), hd.getDesc(), f);
                    CInfo oc = info(o);
                    args[i] = new Handle(hd.getTag(), o, hd.getName(), hd.getDesc(), oc != null ? (oc.access() & Opcodes.ACC_INTERFACE) != 0 : hd.isInterface());
                } else if (args[i] instanceof Type t) noteDesc(t.getDescriptor());
            }
            super.visitInvokeDynamicInsn(name, desc, bsm, args);
        }
        @Override public void visitLocalVariable(String n, String d, String s, Label a, Label b, int i) { }
        @Override public void visitFrame(int type, int nl, Object[] l, int ns, Object[] s) {
            for (Object[] arr : new Object[][] { l, s }) if (arr != null) for (Object o : arr) if (o instanceof String t) noteType(t);
            super.visitFrame(type, nl, l, ns, s);
        }
    }

    /** superclass, methods (name+desc -> normalized code, constant-pool independent), fields signature */
    static Info2 info2(byte[] b) {
        Map<String, String> m = new HashMap<>();
        StringBuilder fields = new StringBuilder();
        String[] sup = new String[1];
        new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public void visit(int v, int a, String n, String s, String su, String[] i) { sup[0] = su; }
            @Override public FieldVisitor visitField(int a, String n, String d, String s, Object val) {
                fields.append(a).append(' ').append(n).append(' ').append(d).append('=').append(val).append(';');
                return null;
            }
            @Override public MethodVisitor visitMethod(int a, String n, String d, String s, String[] e) {
                StringBuilder sb = new StringBuilder().append(a).append('|');
                m.put(n + d, null);
                return new MethodVisitor(Opcodes.ASM9) {
                    final Map<Label, Integer> labels = new IdentityHashMap<>();
                    String l(Label x) { return "L" + labels.computeIfAbsent(x, k -> labels.size()); }
                    @Override public void visitInsn(int op) { sb.append(op).append(';'); }
                    @Override public void visitIntInsn(int op, int v) { sb.append(op).append(' ').append(v).append(';'); }
                    @Override public void visitVarInsn(int op, int v) { sb.append(op).append(' ').append(v).append(';'); }
                    @Override public void visitTypeInsn(int op, String t) { sb.append(op).append(' ').append(t).append(';'); }
                    @Override public void visitFieldInsn(int op, String o, String nm, String dd) { sb.append(op).append(' ').append(o).append('.').append(nm).append(dd).append(';'); }
                    @Override public void visitMethodInsn(int op, String o, String nm, String dd, boolean i) { sb.append(op).append(' ').append(o).append('.').append(nm).append(dd).append(';'); }
                    @Override public void visitInvokeDynamicInsn(String nm, String dd, Handle h, Object... args) { sb.append("indy ").append(nm).append(dd).append(h).append(Arrays.toString(args)).append(';'); }
                    @Override public void visitJumpInsn(int op, Label x) { sb.append(op).append(' ').append(l(x)).append(';'); }
                    @Override public void visitLabel(Label x) { sb.append(l(x)).append(':'); }
                    @Override public void visitLdcInsn(Object v) { sb.append("ldc ").append(v.getClass().getSimpleName()).append(' ').append(v).append(';'); }
                    @Override public void visitIincInsn(int v, int inc) { sb.append("iinc ").append(v).append(' ').append(inc).append(';'); }
                    @Override public void visitTableSwitchInsn(int lo, int hi, Label dflt, Label... ls) { sb.append("tsw ").append(lo).append(' ').append(hi).append(' ').append(l(dflt)); for (Label x : ls) sb.append(' ').append(l(x)); sb.append(';'); }
                    @Override public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] ls) { sb.append("lsw ").append(l(dflt)).append(Arrays.toString(keys)); for (Label x : ls) sb.append(' ').append(l(x)); sb.append(';'); }
                    @Override public void visitMultiANewArrayInsn(String dd, int dims) { sb.append("mana ").append(dd).append(dims).append(';'); }
                    @Override public void visitTryCatchBlock(Label st, Label en, Label h, String t) { sb.append("try ").append(l(st)).append(l(en)).append(l(h)).append(t).append(';'); }
                    @Override public void visitEnd() { m.put(n + d, sb.toString()); }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return new Info2(sup[0], m, fields.toString());
    }
    record Info2(String sup, Map<String, String> code, String fields) {
        Set<String> methods() { return code.keySet(); }
    }

    /** emit (or just scan, with a throwaway writer) one substitution class */
    static byte[] emit(String n, byte[] db, Info2 li, boolean pass2) {
        boolean wholeClass = wholeClassOf.get(n);
        ClassWriter out = new ClassWriter(0);
        ClassVisitor sink = pass2 ? new ClassRemapper(out, new SimpleRemapper(rename)) : out;
        new ClassReader(db).accept(new ClassVisitor(Opcodes.ASM9, sink) {
            boolean hadClinit;

            @Override public void visit(int v, int acc, String name, String sig, String sup, String[] itf) {
                int nacc = (acc & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED | Opcodes.ACC_ABSTRACT
                        | Opcodes.ACC_ENUM | Opcodes.ACC_INTERFACE | Opcodes.ACC_ANNOTATION))
                        | Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL;
                if (wholeClass && itf != null) for (String i : itf) noteType(i);
                // svm: annotated class must be final and extend Object
                super.visit(v, nacc, n, null, "java/lang/Object", wholeClass ? itf : null);
                AnnotationVisitor tc = super.visitAnnotation(TC, true);
                tc.visit("className", n.replace('/', '.'));
                tc.visitEnd();
                if (wholeClass) super.visitAnnotation(SUB, true).visitEnd();
            }
            @Override public void visitNestHost(String h) { }
            @Override public void visitNestMember(String m) { }
            @Override public void visitOuterClass(String o, String nm, String d) { }
            @Override public void visitInnerClass(String nm, String outer, String inner, int acc) { }
            @Override public void visitPermittedSubclass(String s) { }
            @Override public void visitSource(String s, String d) { }
            @Override public RecordComponentVisitor visitRecordComponent(String nm, String d, String s) { return null; }
            @Override public AnnotationVisitor visitAnnotation(String d, boolean vis) { return null; }

            @Override public FieldVisitor visitField(int acc, String name, String desc, String sig, Object value) {
                if (!wholeClass) return null; // method-level: original fields stay (reached via @Alias)
                name = fieldName(n, name, desc);
                noteDesc(desc);
                acc &= ~Opcodes.ACC_PRIVATE; // nestmates are gone: inner S_ classes reach these as package members
                if ((acc & Opcodes.ACC_STATIC) != 0 && value == null) acc &= ~Opcodes.ACC_FINAL; // set from $pf_clinit
                return super.visitField(acc, name, desc, null, value);
            }

            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                noteDesc(desc);
                if (name.equals("<clinit>")) {
                    if (!wholeClass) return null; // original initializer runs (identical fields)
                    hadClinit = true;
                    return new RefVisitor(super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC, "$pf_clinit", desc, null, ex));
                }
                if (wholeClass) {
                    int a = acc & ~Opcodes.ACC_ABSTRACT;
                    if (!name.equals("<init>")) a &= ~Opcodes.ACC_PRIVATE;
                    // svm: in a whole-class substitution only @Substitute methods replace originals (same
                    // name+desc); unannotated ones are plain helpers and every unmatched original is deleted
                    MethodVisitor mv = super.visitMethod(a, name, desc, null, ex);
                    if (li.methods().contains(name + desc)) mv.visitAnnotation(SUB, true).visitEnd();
                    return new RefVisitor(mv);
                }
                // method-level: only non-constructor methods both builds have; keep natives/abstracts as originals
                if (!substituted.get(n).contains(name + desc)) return null;
                MethodVisitor mv = super.visitMethod(acc, name, desc, null, ex);
                mv.visitAnnotation(SUB, true).visitEnd();
                return new RefVisitor(mv);
            }

            @Override public void visitEnd() {
                if (!wholeClass && pass2) emitAliases(cv, n, true);
                if (hadClinit) {
                    MethodVisitor mv = super.visitMethod(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
                    mv.visitCode();
                    Label skip = new Label();
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, "org/graalvm/nativeimage/ImageInfo", "inImageBuildtimeCode", "()Z", false);
                    mv.visitJumpInsn(Opcodes.IFNE, skip);
                    mv.visitMethodInsn(Opcodes.INVOKESTATIC, n, "$pf_clinit", "()V", false);
                    mv.visitLabel(skip);
                    mv.visitFrame(Opcodes.F_SAME, 0, null, 0, null);
                    mv.visitInsn(Opcodes.RETURN);
                    mv.visitMaxs(1, 0);
                    mv.visitEnd();
                }
                super.visitEnd();
            }
        }, 0);
        return out.toByteArray();
    }

    /** @Alias declarations for the members of target class `t` that generated code touches */
    static void emitAliases(ClassVisitor cv, String t, boolean intoSubst) {
        boolean itf = (info(t).access() & Opcodes.ACC_INTERFACE) != 0;
        for (String m : aliasMembers.getOrDefault(t, Set.of())) {
            String[] p = m.split(" ");
            String name = p[1], desc = p[2];
            int acc = Integer.parseInt(p[3]);
            boolean st = (acc & Opcodes.ACC_STATIC) != 0;
            if (p[0].equals("F")) {
                int a = Opcodes.ACC_PUBLIC | (st ? Opcodes.ACC_STATIC : 0);
                FieldVisitor fv = cv.visitField(a, name, desc, null, null);
                fv.visitAnnotation(ALIAS, true).visitEnd();
                fv.visitEnd();
            } else if (name.equals("<init>")) {
                MethodVisitor mv = cv.visitMethod(Opcodes.ACC_PUBLIC, name, desc, null, null);
                mv.visitAnnotation(ALIAS, true).visitEnd();
                mv.visitCode();
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitMethodInsn(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false);
                mv.visitInsn(Opcodes.RETURN);
                mv.visitMaxs(1, Type.getArgumentsAndReturnSizes(desc) >> 2);
                mv.visitEnd();
            } else {
                int a = Opcodes.ACC_PUBLIC | (st ? Opcodes.ACC_STATIC : 0) | (itf && !st ? Opcodes.ACC_ABSTRACT : Opcodes.ACC_NATIVE);
                if (itf && st) a = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_NATIVE;
                MethodVisitor mv = cv.visitMethod(a, name, desc, null, null);
                mv.visitAnnotation(ALIAS, true).visitEnd();
                mv.visitEnd();
            }
        }
    }

    static byte[] aliasClass(String t) {
        CInfo c = info(t);
        boolean itf = (c.access() & Opcodes.ACC_INTERFACE) != 0;
        ClassWriter out = new ClassWriter(0);
        ClassVisitor cv = new ClassRemapper(out, new SimpleRemapper(rename));
        int acc = Opcodes.ACC_PUBLIC | (itf ? Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT : Opcodes.ACC_FINAL | Opcodes.ACC_SUPER);
        cv.visit(Opcodes.V17, acc, t, null, "java/lang/Object", null);
        AnnotationVisitor tc = cv.visitAnnotation(TC, true);
        tc.visit("className", t.replace('/', '.'));
        tc.visitEnd();
        emitAliases(cv, t, false);
        cv.visitEnd();
        return out.toByteArray();
    }

    public static void main(String[] a) throws Exception {
        darwinDir = Paths.get(a[0]); linuxDir = Paths.get(a[1]); darwinOnlyDir = Paths.get(a[2]);
        for (String l : Files.readAllLines(Paths.get(a[3]))) {
            l = l.trim();
            if (l.isEmpty() || l.startsWith("#")) continue;
            list.add(l.endsWith(".class") ? l.substring(0, l.length() - 6) : l);
        }
        Map<String, byte[]> dbytes = new HashMap<>();
        Map<String, Info2> linfo = new HashMap<>();
        for (String n : list) {
            byte[] db = Files.readAllBytes(darwinDir.resolve(n + ".class"));
            Info2 di = info2(db), li = info2(Files.readAllBytes(linuxDir.resolve(n + ".class")));
            dbytes.put(n, db); linfo.put(n, li);
            // method-level suffices when only bodies of shared methods differ; otherwise replace the whole class
            Set<String> subs = new TreeSet<>();
            boolean needWhole = !di.fields().equals(li.fields()) || !li.methods().containsAll(di.methods());
            for (Map.Entry<String, String> e : di.code().entrySet()) {
                String lc = li.code().get(e.getKey());
                if (lc == null || lc.equals(e.getValue())) continue;
                if (e.getKey().startsWith("<")) needWhole = true; else subs.add(e.getKey());
            }
            boolean objSuper = "java/lang/Object".equals(di.sup()) && "java/lang/Object".equals(li.sup());
            if (needWhole && !objSuper) System.out.println("WARN " + n + ": needs whole-class but has superclass " + di.sup() + "; method-level best effort");
            wholeClassOf.put(n, needWhole && objSuper);
            System.out.println((needWhole && objSuper ? "whole  " : "method ") + n + (needWhole && objSuper ? "" : " " + subs));
            substituted.put(n, subs);
            rename.put(n, PKG + "S_" + n.replace('/', '_').replace('$', '_'));
        }
        // pass 1: discover what needs aliasing (iterate to a fixpoint: alias descriptors add types)
        for (String n : list) emit(n, dbytes.get(n), linfo.get(n), false);
        for (Map.Entry<String, Set<String>> e : aliasMembers.entrySet()) for (String m : e.getValue()) noteDesc(m.split(" ")[2]);
        for (String t : aliasTypes) rename.put(t, PKG + "A_" + t.replace('/', '_').replace('$', '_'));

        int whole = 0, ml = 0;
        try (ZipOutputStream zo = new ZipOutputStream(Files.newOutputStream(Paths.get(a[4])))) {
            for (String n : list) {
                if (wholeClassOf.get(n)) whole++; else ml++;
                zo.putNextEntry(new ZipEntry(rename.get(n) + ".class"));
                zo.write(emit(n, dbytes.get(n), linfo.get(n), true));
                zo.closeEntry();
            }
            for (String t : aliasTypes) {
                zo.putNextEntry(new ZipEntry(rename.get(t) + ".class"));
                zo.write(aliasClass(t));
                zo.closeEntry();
            }
        }
        StringBuilder ex = new StringBuilder();
        for (String p : exportPkgs) ex.append("-J--add-exports=java.base/").append(p).append("=ALL-UNNAMED\n");
        Files.writeString(Paths.get(a[5]), ex);
        System.out.println("generated " + whole + " whole-class + " + ml + " method-level substitutions, "
                + aliasTypes.size() + " alias classes, " + exportPkgs.size() + " exports -> " + a[4]);
    }
}
