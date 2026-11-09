import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.zip.*;

/**
 * svm's UnixFileSystemAccessor (injected accessor for UnixPath.fs) reaches DefaultFileSystemProvider.theFileSystem()
 * through an @Alias. Aliases always parse the *original* (Linux) bytecode, bypassing our Darwin whole-class
 * substitution of DefaultFileSystemProvider. Rewrite the call to a normal invokestatic so it resolves to the
 * substituted method.
 * Usage: PatchFsAccessor <svm.jar> <out-dir>
 *   builder flags: -J--patch-module=org.graalvm.nativeimage.builder=<out-dir>
 *                  -J--add-exports=java.base/sun.nio.fs=org.graalvm.nativeimage.builder
 */
public class PatchFsAccessor {
    static final String CLS = "com/oracle/svm/core/jdk/runtimeinit/UnixFileSystemAccessor";
    static final String ALIAS = "com/oracle/svm/core/jdk/runtimeinit/Target_sun_nio_fs_DefaultFileSystemProvider_RunTime";

    public static void main(String[] a) throws Exception {
        byte[] in;
        try (ZipFile z = new ZipFile(a[0])) { in = z.getInputStream(z.getEntry(CLS + ".class")).readAllBytes(); }
        int[] n = {0};
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                return new MethodVisitor(Opcodes.ASM9, super.visitMethod(acc, name, desc, sig, ex)) {
                    @Override public void visitMethodInsn(int op, String owner, String nm, String d, boolean itf) {
                        if (owner.equals(ALIAS) && nm.equals("theFileSystem")) { owner = "sun/nio/fs/DefaultFileSystemProvider"; n[0]++; }
                        super.visitMethodInsn(op, owner, nm, d, itf);
                    }
                };
            }
        }, 0);
        if (n[0] == 0) throw new IllegalStateException("theFileSystem alias call not found");
        Path p = Paths.get(a[1], CLS + ".class");
        Files.createDirectories(p.getParent());
        Files.write(p, cw.toByteArray());
        System.out.println("patched " + n[0] + " call(s) in " + p);
    }
}
