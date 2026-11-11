import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.*;
import java.nio.file.*;
import java.util.stream.*;
/** Verify <classes-dir>: ASM BasicVerifier (int vs reference, stack shapes) over every method of every baked class. A bad
 *  stand-in otherwise only shows up as a native-image parse error deep into the CI build. Exit 1 if any method fails. */
public class Verify {
    public static void main(String[] a) throws Exception {
        int n = 0, bad = 0;
        try (Stream<Path> s = Files.walk(Paths.get(a[0]))) {
            for (Path p : (Iterable<Path>) s.filter(x -> x.toString().endsWith(".class"))::iterator) {
                ClassNode c = new ClassNode();
                new ClassReader(Files.readAllBytes(p)).accept(c, ClassReader.SKIP_FRAMES);
                n++;
                for (MethodNode m : c.methods) {
                    if (m.instructions.size() == 0) continue;
                    try { new Analyzer<>(new BasicVerifier()).analyze(c.name, m); }
                    catch (AnalyzerException e) { bad++; System.out.println("BAD " + c.name + "." + m.name + m.desc + ": " + e.getMessage()); }
                }
            }
        }
        System.out.println("[verify] " + n + " classes, " + bad + " bad methods");
        if (bad > 0) System.exit(1);
    }
}
