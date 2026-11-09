package dev.pufferfish.aot;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipFile;

/**
 * In an AOT image every class's code source is the executable itself. Fabric uses code
 * sources to find its own jar (builtin "fabricloader" mod) and the game jar, so emulate
 * the JVM: the code source of a class is the first -Djava.class.path entry containing it.
 */
@TargetClass(className = "net.fabricmc.loader.impl.util.UrlUtil", onlyWith = FabricPresent.class)
final class UrlUtilSubst {
    @Substitute
    public static Path getCodeSource(Class<?> cls) {
        return ClassPathIndex.find(cls.getName().replace('.', '/') + ".class");
    }
}

final class ClassPathIndex {
    private static volatile List<Object[]> entries; // {Path, ZipFile|null}
    private static final ConcurrentHashMap<String, Path> cache = new ConcurrentHashMap<>();

    static Path find(String res) {
        Path hit = cache.get(res);
        if (hit != null) return hit;
        for (Object[] e : entries()) {
            Path p = (Path) e[0];
            boolean found = e[1] != null ? ((ZipFile) e[1]).getEntry(res) != null : Files.exists(p.resolve(res));
            if (found) { cache.put(res, p); return p; }
        }
        return null;
    }

    private static List<Object[]> entries() {
        List<Object[]> l = entries;
        if (l != null) return l;
        synchronized (ClassPathIndex.class) {
            if (entries != null) return entries;
            l = new ArrayList<>();
            for (String s : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
                if (s.isEmpty()) continue;
                Path p = Paths.get(s).toAbsolutePath().normalize();
                if (Files.isDirectory(p)) l.add(new Object[] { p, null });
                else if (Files.isRegularFile(p)) {
                    try { l.add(new Object[] { p, new ZipFile(p.toFile()) }); } catch (IOException ignored) { }
                }
            }
            entries = l;
            return l;
        }
    }
}
