import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.zip.*;

/** Rewrite ObjectFile.getHostOS() to return a fixed HostOS (cross builds: Linux host, Darwin target). */
public class PatchHostOS {
    public static void main(String[] a) throws Exception {
        String jar = a[0], outDir = a[1], os = a.length > 2 ? a[2] : "MAC_OS_X";
        byte[] in;
        try (ZipFile z = new ZipFile(jar)) { in = z.getInputStream(z.getEntry("com/oracle/objectfile/ObjectFile.class")).readAllBytes(); }
        ClassReader cr = new ClassReader(in);
        ClassWriter cw = new ClassWriter(cr, 0);
        cr.accept(new ClassVisitor(Opcodes.ASM9, cw) {
            @Override public MethodVisitor visitMethod(int acc, String name, String desc, String sig, String[] ex) {
                MethodVisitor mv = super.visitMethod(acc, name, desc, sig, ex);
                if (!name.equals("getHostOS")) return mv;
                mv.visitCode();
                mv.visitFieldInsn(Opcodes.GETSTATIC, "com/oracle/objectfile/ObjectFile$HostOS", os, "Lcom/oracle/objectfile/ObjectFile$HostOS;");
                mv.visitInsn(Opcodes.ARETURN);
                mv.visitMaxs(1, 0);
                mv.visitEnd();
                return null; // drop original body
            }
        }, 0);
        Path p = Paths.get(outDir, "com/oracle/objectfile/ObjectFile.class");
        Files.createDirectories(p.getParent());
        Files.write(p, cw.toByteArray());
        System.out.println("patched getHostOS -> " + os + " in " + p);
    }
}
