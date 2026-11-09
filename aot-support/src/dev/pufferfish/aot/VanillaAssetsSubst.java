package dev.pufferfish.aot;

import com.google.common.collect.ImmutableMap;
import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import com.oracle.svm.core.annotate.TargetElement;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import net.minecraft.server.packs.PackType;

/**
 * In an AOT image the client jar's resources aren't on a classpath. Point the vanilla
 * pack roots at a zip filesystem over the user's client jar on disk (-Dpufferfish.gameJar).
 */
@TargetClass(className = "net.minecraft.server.packs.VanillaPackResourcesBuilder")
final class VanillaAssetsSubst {
    @Substitute
    @TargetElement(name = "lambda$static$1")
    private static ImmutableMap<PackType, Path> rootsFromGameJar() {
        ImmutableMap.Builder<PackType, Path> b = ImmutableMap.builder();
        Path root = GameJar.root();
        for (PackType t : PackType.values()) b.put(t, root.resolve(t.getDirectory()));
        return b.build();
    }
}

final class GameJar {
    private static volatile Path root;
    static Path root() {
        if (root == null) {
            synchronized (GameJar.class) {
                if (root == null) {
                    String p = System.getProperty("pufferfish.gameJar");
                    if (p == null) throw new IllegalStateException("pufferfish.gameJar not set");
                    try {
                        FileSystem fs = FileSystems.newFileSystem(Path.of(p));
                        root = fs.getPath("/");
                    } catch (Exception e) { throw new RuntimeException("open " + p, e); }
                }
            }
        }
        return root;
    }
}
