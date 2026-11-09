package dev.pufferfish.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.ModContainerImpl;
import net.fabricmc.loader.impl.launch.knot.Knot;

/**
 * Bake a Fabric instance into a closed world.
 *
 * Boots Knot exactly like a normal launch (mod discovery, class tweakers, Mixin bootstrap,
 * preLaunch), then pushes every class through Knot's full transform pipeline
 * (entrypoint patches + access wideners + Mixin) and writes the results:
 *
 *   out/classes/   every mod class (transformed), plus every classpath class whose bytes changed,
 *                  plus Mixin-generated synthetic classes
 *   out/res/       non-class resources from mod jars (so Class#getResource works on a flat classpath)
 *   out/bake.json  summary
 *
 * Usage: java -Dpufferfish.bake.out=DIR -cp <fabric cp>:pufferfish-runtime.jar dev.pufferfish.runtime.BakeMain <game args>
 */
public final class BakeMain {
    public static void main(String[] args) throws Throwable {
        Path out = Paths.get(System.getProperty("pufferfish.bake.out", "baked")).toAbsolutePath();
        long t0 = System.currentTimeMillis();

        Knot knot = new Knot(EnvType.CLIENT);
        knot.init(args);

        Object delegate = field(knot, "classLoader");
        Method post = delegate.getClass().getDeclaredMethod("getPostMixinClassByteArray", String.class, boolean.class);
        post.setAccessible(true);
        Method raw = delegate.getClass().getDeclaredMethod("getRawClassByteArray", String.class, boolean.class);
        raw.setAccessible(true);

        Path classesDir = out.resolve("classes");
        Path resDir = out.resolve("res");
        deleteTree(out);
        Files.createDirectories(classesDir);
        Files.createDirectories(resDir);

        Stats st = new Stats();
        Set<String> done = new HashSet<>();
        Map<String, StringBuilder> services = new TreeMap<>();
        List<String> modIds = new ArrayList<>();

        // 1) every mod (incl. nested jar-in-jar mods): all classes + resources
        for (ModContainerImpl mod : FabricLoaderImpl.INSTANCE.getModsInternal()) {
            String id = mod.getMetadata().getId();
            if (id.equals("java") || id.equals("fabricloader")) continue;
            boolean isGame = id.equals("minecraft");
            modIds.add(id + "@" + mod.getMetadata().getVersion().getFriendlyString());
            for (Path root : mod.getRootPaths()) {
                try (Stream<Path> s = Files.walk(root)) {
                    for (Path p : (Iterable<Path>) s::iterator) {
                        if (Files.isDirectory(p)) continue;
                        String rel = root.relativize(p).toString().replace('\\', '/');
                        if (rel.endsWith(".class")) {
                            String name = rel.substring(0, rel.length() - 6).replace('/', '.');
                            if (name.endsWith("module-info") || name.endsWith("package-info")) continue;
                            if (!done.add(name)) continue;
                            byte[] b = transform(post, raw, delegate, name, st);
                            if (b == null) continue;
                            if (isGame) {
                                byte[] r = (byte[]) raw.invoke(delegate, name, false);
                                if (r != null && Arrays.equals(r, b)) { st.unchanged++; continue; }
                            }
                            write(classesDir, rel, b);
                            st.written++;
                        } else if (!isGame) {
                            if (rel.startsWith("META-INF/services/")) {
                                services.computeIfAbsent(rel, k -> new StringBuilder())
                                        .append(Files.readString(p, StandardCharsets.UTF_8)).append('\n');
                            } else if (!rel.startsWith("META-INF/jars/")) {
                                Path d = resDir.resolve(rel);
                                if (!Files.exists(d)) { Files.createDirectories(d.getParent()); Files.copy(p, d); }
                            }
                        }
                    }
                }
            }
        }

        // 2) classpath libraries: Mixins may target library classes too (brigadier, authlib, ...)
        for (Path cp : knot.getClassPath()) {
            if (!cp.toString().endsWith(".jar") || !Files.isRegularFile(cp)) continue;
            try (ZipFile zf = new ZipFile(cp.toFile())) {
                for (ZipEntry e : Collections.list(zf.entries())) {
                    String rel = e.getName();
                    if (!rel.endsWith(".class") || rel.startsWith("META-INF/")) continue;
                    String name = rel.substring(0, rel.length() - 6).replace('/', '.');
                    if (name.endsWith("module-info") || name.endsWith("package-info")) continue;
                    if (!done.add(name)) continue;
                    byte[] b = transform(post, raw, delegate, name, st);
                    if (b == null) continue;
                    byte[] r;
                    r = (byte[]) raw.invoke(delegate, name, false); // same (multi-release-aware) source Knot used
                    if (Arrays.equals(r, b)) { st.unchanged++; continue; }
                    write(classesDir, rel, b);
                    st.written++;
                    st.libChanged.add(name);
                }
            }
        }

        // 3) Mixin synthetic classes (Args subclasses etc.)
        Object transformer = invoke(delegate, "getMixinTransformer");
        Object registry = field(transformer, "syntheticClassRegistry");
        @SuppressWarnings("unchecked")
        Map<String, Object> synth = (Map<String, Object>) field(registry, "classes");
        Class<?> envCls = Class.forName("org.spongepowered.asm.mixin.MixinEnvironment", true, transformer.getClass().getClassLoader());
        Object env = envCls.getMethod("getCurrentEnvironment").invoke(null);
        Method gen = transformer.getClass().getMethod("generateClass", envCls, String.class);
        gen.setAccessible(true);
        for (String internal : new ArrayList<>(synth.keySet())) {
            String name = internal.replace('/', '.');
            byte[] b = (byte[]) gen.invoke(transformer, env, name);
            if (b != null) { write(classesDir, internal.replace('.', '/') + ".class", b); st.synthetic++; }
        }

        // 3b) MixinExtras runtime-generated classes (LocalRefImpl etc.), defined via Lookup at apply time
        for (ClassLoader l : new ClassLoader[] { knot.getTargetClassLoader(), BakeMain.class.getClassLoader() }) {
            try {
                Class<?> cg = Class.forName("com.llamalad7.mixinextras.utils.ClassGenUtils", false, l);
                @SuppressWarnings("unchecked")
                Map<String, byte[]> defs = (Map<String, byte[]>) cg.getMethod("getDefinitions").invoke(null);
                for (Map.Entry<String, byte[]> e : defs.entrySet()) {
                    write(classesDir, e.getKey().replace('.', '/') + ".class", e.getValue());
                    st.synthetic++;
                }
                if (!defs.isEmpty()) break;
            } catch (ClassNotFoundException ignored) { }
        }

        // FlatMain reads this to tell baked mods from mods added later (runtime-loaded)
        StringBuilder ids = new StringBuilder();
        for (String m : modIds) ids.append(m, 0, m.indexOf('@')).append('\n');
        Files.writeString(resDir.resolve("pufferfish-baked-mods.txt"), ids);

        for (Map.Entry<String, StringBuilder> e : services.entrySet()) {
            Path d = resDir.resolve(e.getKey());
            Files.createDirectories(d.getParent());
            Files.writeString(d, e.getValue().toString());
        }

        long ms = System.currentTimeMillis() - t0;
        StringBuilder j = new StringBuilder("{\n");
        j.append("  \"mods\": ").append(jsonList(modIds)).append(",\n");
        j.append("  \"written\": ").append(st.written).append(",\n");
        j.append("  \"unchangedGameOrLib\": ").append(st.unchanged).append(",\n");
        j.append("  \"synthetic\": ").append(st.synthetic).append(",\n");
        j.append("  \"untransformable\": ").append(jsonList(st.failed)).append(",\n");
        j.append("  \"libChanged\": ").append(jsonList(st.libChanged)).append(",\n");
        j.append("  \"ms\": ").append(ms).append("\n}\n");
        Files.writeString(out.resolve("bake.json"), j);
        System.out.printf("[bake] %d mods, %d classes written (%d synthetic), %d untouched, %d raw-fallback, %d ms -> %s%n",
                modIds.size(), st.written, st.synthetic, st.unchanged, st.failed.size(), ms, out);
        System.exit(0);
    }

    static final class Stats {
        int written, unchanged, synthetic;
        List<String> failed = new ArrayList<>();
        List<String> libChanged = new ArrayList<>();
    }

    /** Full Knot pipeline; Mixin classes themselves refuse to transform -> keep raw bytes. */
    static byte[] transform(Method post, Method raw, Object delegate, String name, Stats st) throws Exception {
        try {
            return (byte[]) post.invoke(delegate, name, false);
        } catch (java.lang.reflect.InvocationTargetException e) {
            st.failed.add(name);
            return (byte[]) raw.invoke(delegate, name, false);
        }
    }

    static void write(Path dir, String rel, byte[] b) throws IOException {
        Path p = dir.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.write(p, b);
    }

    static Object field(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f.get(o); }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }

    static Object invoke(Object o, String name) throws Exception {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try { Method m = c.getDeclaredMethod(name); m.setAccessible(true); return m.invoke(o); }
            catch (NoSuchMethodException ignored) { }
        }
        throw new NoSuchMethodException(name);
    }

    static String jsonList(List<String> l) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < l.size(); i++) {
            if (i > 0) b.append(", ");
            b.append('"').append(l.get(i).replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        }
        return b.append(']').toString();
    }

    static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (Stream<Path> s = Files.walk(p)) {
            s.sorted(Comparator.reverseOrder()).forEach(x -> { try { Files.delete(x); } catch (IOException e) { throw new RuntimeException(e); } });
        }
    }
}
