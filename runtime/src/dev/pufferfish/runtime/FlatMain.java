package dev.pufferfish.runtime;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.FormattedException;
import net.fabricmc.loader.impl.game.GameProvider;
import net.fabricmc.loader.impl.launch.FabricLauncherBase;
import net.fabricmc.loader.impl.util.log.Log;
import net.fabricmc.loader.impl.util.log.LogCategory;

/**
 * Fabric without Knot: no custom classloader, no Mixin, no transformers.
 * Every class is already in its final (baked) form on the plain classpath, so this is what
 * an AOT image (closed world, no class definition at runtime) needs.
 *
 * Classpath order: baked/classes : baked/res : fabric-loader + libs : minecraft.jar : pufferfish-runtime.jar
 * Pass -Dfabric.gameJarPath=<client jar> so the game provider doesn't mistake baked/classes for the game.
 */
public final class FlatMain extends FabricLauncherBase {
    private final Map<String, Object> properties = new HashMap<>();
    private final List<Path> classPath = new ArrayList<>();
    /** Mods that are not in the bake (dropped into mods/ later) are defined at runtime by this loader: Crema's
     *  interpreter on iOS, the JIT on a JVM. Parent-first, so baked classes always win. */
    static final class RuntimeModLoader extends java.net.URLClassLoader {
        static { registerAsParallelCapable(); }
        RuntimeModLoader(ClassLoader parent) { super("pufferfish-runtime-mods", new java.net.URL[0], parent); }
        void add(Path p) {
            try { addURL(p.toUri().toURL()); } catch (java.net.MalformedURLException e) { throw new RuntimeException(e); }
        }
    }
    private final RuntimeModLoader cl = new RuntimeModLoader(FlatMain.class.getClassLoader());
    private final Set<Path> added = new HashSet<>();
    private GameProvider provider;

    public static void main(String[] args) {
        try {
            FlatMain l = new FlatMain();
            l.init(args);
            l.provider.launch(l.cl);
        } catch (FormattedException e) {
            handleFormattedException(e);
        }
    }

    private void init(String[] args) {
        setProperties(properties);
        String cpProp = System.getProperty("java.class.path", "");
        for (String e : cpProp.split(File.pathSeparator)) {
            if (e.isEmpty()) continue;
            Path p = Paths.get(e);
            if (Files.exists(p)) classPath.add(p.toAbsolutePath().normalize());
        }

        provider = createGameProvider(args);
        Log.finishBuiltinConfig();
        Log.info(LogCategory.GAME_PROVIDER, "[pufferfish] flat launch: %s %s with Fabric Loader %s (no Knot, no Mixin)",
                provider.getGameName(), provider.getRawGameVersion(), FabricLoaderImpl.VERSION);
        Thread.currentThread().setContextClassLoader(cl);

        FabricLoaderImpl loader = FabricLoaderImpl.INSTANCE;
        loader.setGameProvider(provider);
        provider.initialize(this);
        loader.load();
        loader.freeze();
        loader.loadClassTweakers(); // parsed for API queries only; already applied at bake time
        provider.unlockClassPath(this);
        reportMods(loader);

        try {
            loader.invokeEntrypoints("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
        } catch (RuntimeException e) {
            throw FormattedException.ofLocalized("exception.initializerFailure", e);
        }
    }

    /** Baked mods come from res/pufferfish-baked-mods.txt (written by BakeMain). Everything else is a runtime mod. */
    private void reportMods(FabricLoaderImpl loader) {
        Set<String> baked = new HashSet<>();
        try (InputStream in = FlatMain.class.getClassLoader().getResourceAsStream("pufferfish-baked-mods.txt")) {
            if (in != null) for (String l : new String(in.readAllBytes()).split("\n")) if (!l.isBlank()) baked.add(l.trim());
        } catch (IOException ignored) { }
        int n = 0;
        for (net.fabricmc.loader.impl.ModContainerImpl m : loader.getModsInternal()) {
            String id = m.getMetadata().getId();
            if (baked.contains(id) || id.equals("minecraft") || id.equals("java") || id.equals("fabricloader")) continue;
            n++;
            Collection<String> mixins = m.getMetadata().getMixinConfigs(EnvType.CLIENT);
            if (mixins.isEmpty()) Log.info(LogCategory.GENERAL, "[pufferfish] runtime mod %s %s (loaded at runtime, interpreted)", id, m.getMetadata().getVersion().getFriendlyString());
            else Log.warn(LogCategory.GENERAL, "[pufferfish] runtime mod %s has mixins %s: NOT applied (runtime mixins not supported yet), the mod may misbehave", id, mixins);
        }
        Log.info(LogCategory.GENERAL, "[pufferfish] %d baked mods, %d runtime mods", baked.size(), n);
    }

    private GameProvider createGameProvider(String[] args) {
        List<String> failed = new ArrayList<>();
        for (GameProvider p : ServiceLoader.load(GameProvider.class, cl)) {
            if (!p.isEnabled()) continue;
            if (p.locateGame(this, args)) return p;
            failed.add(p.getGameName());
        }
        throw new RuntimeException("No game provider could locate the game: " + failed);
    }

    // --- FabricLauncher -------------------------------------------------------------------

    @Override public void addToClassPath(Path path, String... allowedPrefixes) {
        // baked mods' jars are added too: harmless, parent-first lookup finds the baked classes first
        Path p = path.toAbsolutePath().normalize();
        if (classPath.contains(p) || !added.add(p)) return;
        cl.add(p);
        Log.debug(LogCategory.GENERAL, "[pufferfish] runtime classpath += %s", p);
    }
    @Override public void setAllowedPrefixes(Path path, String... prefixes) { }
    @Override public void setValidParentClassPath(Collection<Path> paths) { }
    @Override public EnvType getEnvironmentType() { return EnvType.CLIENT; }

    @Override public boolean isClassLoaded(String name) {
        // Only used for diagnostics/early-load checks; resolving is safe in a flat world.
        return false;
    }

    @Override public Class<?> loadIntoTarget(String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl);
    }

    @Override public InputStream getResourceAsStream(String name) { return cl.getResourceAsStream(name); }
    @Override public ClassLoader getTargetClassLoader() { return cl; }

    @Override public byte[] getClassByteArray(String name, boolean runTransformers) throws IOException {
        try (InputStream in = cl.getResourceAsStream(name.replace('.', '/') + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    @Override public Manifest getManifest(Path origin) {
        try {
            if (Files.isDirectory(origin)) {
                Path m = origin.resolve(JarFile.MANIFEST_NAME);
                if (!Files.exists(m)) return null;
                try (InputStream in = Files.newInputStream(m)) { return new Manifest(in); }
            }
            try (JarFile jf = new JarFile(origin.toFile())) { return jf.getManifest(); }
        } catch (IOException e) {
            return null;
        }
    }

    @Override public String getEntrypoint() { return provider.getEntrypoint(); }
    @Override public List<Path> getClassPath() { return classPath; }
}
