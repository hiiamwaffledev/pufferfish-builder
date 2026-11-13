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
        /** Hot runtime: mixin-runtime classes resolve to the hot jar (child-first there), not the baked copies. */
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (Rt.hot != null && Rt.hotName(name.replace('.', '/'))) return Rt.hot.loadClass(name);
            return super.loadClass(name, resolve);
        }
        /** Every runtime mod class goes through RuntimeMixins (mixin companions, added-interface/accessor call sites). */
        @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
            String internal = name.replace('.', '/');
            if (internal.startsWith("pf/proxy/") || internal.contains("PfHook$")) {
                byte[] d = Rt.generated(internal);
                if (d != null) return defineClass(name, d, 0, d.length);
            }
            if (internal.startsWith("pf/dispatch/")) {
                byte[] d = Rt.dispatcher(internal.substring("pf/dispatch/".length()));
                if (d != null) return defineClass(name, d, 0, d.length);
            }
            java.net.URL u = findResource(internal + ".class");
            if (u == null) throw new ClassNotFoundException(name);
            byte[] b;
            try (InputStream in = u.openStream()) { b = in.readAllBytes(); } catch (IOException e) { throw new ClassNotFoundException(name, e); }
            try { b = Rt.transform(internal, b); }
            catch (Throwable t) { Log.warn(LogCategory.GENERAL, "[pufferfish] transform failed for %s: %s", name, t); }
            return defineClass(name, b, 0, b.length);
        }
    }
    private final RuntimeModLoader cl = new RuntimeModLoader(FlatMain.class.getClassLoader());
    private final Set<Path> added = new HashSet<>();
    private final Set<String> bakedIds = new HashSet<>();
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
        setupRuntimeMixins(loader);

        try {
            loader.invokeEntrypoints("preLaunch", PreLaunchEntrypoint.class, PreLaunchEntrypoint::onPreLaunch);
        } catch (RuntimeException e) {
            throw FormattedException.ofLocalized("exception.initializerFailure", e);
        }
    }

    /** Baked mods come from res/pufferfish-baked-mods.txt (written by BakeMain). Everything else is a runtime mod. */
    private void reportMods(FabricLoaderImpl loader) {
        Set<String> baked = bakedIds;
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
            else Log.info(LogCategory.GENERAL, "[pufferfish] runtime mod %s %s with mixins %s (runtime mixins)", id, m.getMetadata().getVersion().getFriendlyString(), mixins);
        }
        Log.info(LogCategory.GENERAL, "[pufferfish] %d baked mods, %d runtime mods", baked.size(), n);
    }

    /** Runtime mods' mixins: companions + hook slots (dev.pufferfish.runtime.mixin). Before any mod class loads. */
    private void setupRuntimeMixins(FabricLoaderImpl loader) {
        List<Object[]> mods = new ArrayList<>();
        for (net.fabricmc.loader.impl.ModContainerImpl m : loader.getModsInternal()) {
            if (bakedIds.contains(m.getMetadata().getId())) continue;
            Collection<String> cfgs = m.getMetadata().getMixinConfigs(EnvType.CLIENT);
            // access widener: the AOT game keeps its private members private; RuntimeMixins routes widened accesses via reflection
            String aw = m.getMetadata() instanceof net.fabricmc.loader.impl.metadata.LoaderModMetadata lm ? lm.getClassTweaker() : null;
            if (!cfgs.isEmpty() || aw != null) mods.add(new Object[] { m.getMetadata().getId(), cfgs, m.getRootPaths(), aw });
        }
        if (mods.isEmpty()) return;
        long t0 = System.nanoTime();
        Rt.init(loader.getGameDir());
        Rt.setup(cl, mods);
        Rt.installHooks();
        List<String> rep = Rt.report();
        Log.info(LogCategory.GENERAL, "[pufferfish] runtime mixins: %s (%d ms)", rep.get(0), (System.nanoTime() - t0) / 1_000_000);
        for (String l : rep.subList(1, rep.size())) Log.warn(LogCategory.GENERAL, "[pufferfish]   %s", l);
    }

    /**
     * The mixin runtime (RuntimeMixins, PfRefl, ...) is baked into the image, but a newer copy can be dropped into the game
     * dir as pufferfish-hot.jar (or -Dpf.hot=<jar>; -Dpf.hot=off ignores it). Its classes are then defined at runtime
     * (Crema/JIT) and used instead of the baked ones, so runtime fixes need no image rebuild. Hook and PfHooks stay baked:
     * the baked hook stand-ins call them.
     */
    static final class Rt {
        static final String PKG = "dev/pufferfish/runtime/mixin/";
        static volatile HotLoader hot;
        static java.lang.reflect.Method mGen, mDisp, mTr, mSetup, mInstall, mReport;

        static boolean hotName(String internal) {
            return internal.startsWith(PKG) && !internal.equals(PKG + "Hook") && !internal.equals(PKG + "PfHooks");
        }
        static final class HotLoader extends java.net.URLClassLoader {
            static { registerAsParallelCapable(); }
            HotLoader(java.net.URL u, ClassLoader parent) { super("pufferfish-hot", new java.net.URL[] { u }, parent); }
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!hotName(name.replace('.', '/'))) return super.loadClass(name, resolve);
                synchronized (getClassLoadingLock(name)) {
                    Class<?> c = findLoadedClass(name);
                    return c != null ? c : findClass(name);
                }
            }
        }
        static void init(Path gameDir) {
            String prop = System.getProperty("pf.hot", "");
            if (prop.equals("off")) return;
            Path jar = prop.isEmpty() ? gameDir.resolve("pufferfish-hot.jar") : Paths.get(prop);
            if (!Files.isRegularFile(jar)) return;
            try {
                HotLoader l = new HotLoader(jar.toUri().toURL(), FlatMain.class.getClassLoader());
                Class<?> c = l.loadClass("dev.pufferfish.runtime.mixin.RuntimeMixins");
                mGen = c.getMethod("generated", String.class);
                mDisp = c.getMethod("dispatcher", String.class);
                mTr = c.getMethod("transform", String.class, byte[].class);
                mSetup = c.getMethod("setup", ClassLoader.class, List.class);
                mInstall = c.getMethod("installHooks");
                mReport = c.getMethod("report");
                hot = l;
                Log.info(LogCategory.GENERAL, "[pufferfish] hot runtime: %s (%d bytes, %s)", jar, Files.size(jar), Files.getLastModifiedTime(jar));
            } catch (Throwable t) {
                Log.warn(LogCategory.GENERAL, "[pufferfish] hot runtime %s unusable, using baked: %s", jar, t);
            }
        }
        private static Object call(java.lang.reflect.Method m, Object... a) {
            try { return m.invoke(null, a); }
            catch (java.lang.reflect.InvocationTargetException e) {
                Throwable c = e.getCause();
                if (c instanceof RuntimeException r) throw r;
                if (c instanceof Error r) throw r;
                throw new RuntimeException(c);
            } catch (IllegalAccessException e) { throw new RuntimeException(e); }
        }
        static byte[] generated(String i) { return hot == null ? dev.pufferfish.runtime.mixin.RuntimeMixins.generated(i) : (byte[]) call(mGen, i); }
        static byte[] dispatcher(String i) { return hot == null ? dev.pufferfish.runtime.mixin.RuntimeMixins.dispatcher(i) : (byte[]) call(mDisp, i); }
        static byte[] transform(String i, byte[] b) { return hot == null ? dev.pufferfish.runtime.mixin.RuntimeMixins.transform(i, b) : (byte[]) call(mTr, i, b); }
        static void setup(ClassLoader cl, List<Object[]> mods) { if (hot == null) dev.pufferfish.runtime.mixin.RuntimeMixins.setup(cl, mods); else call(mSetup, cl, mods); }
        static void installHooks() { if (hot == null) dev.pufferfish.runtime.mixin.RuntimeMixins.installHooks(); else call(mInstall); }
        @SuppressWarnings("unchecked")
        static List<String> report() { return hot == null ? dev.pufferfish.runtime.mixin.RuntimeMixins.report() : (List<String>) call(mReport); }
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
