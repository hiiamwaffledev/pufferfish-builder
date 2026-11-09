package dev.pufferfish.aot;

import java.util.function.BooleanSupplier;

/** Substitutions that touch Fabric only apply when Fabric Loader is on the image classpath. */
final class FabricPresent implements BooleanSupplier {
    @Override public boolean getAsBoolean() {
        try { Class.forName("net.fabricmc.loader.impl.util.UrlUtil", false, FabricPresent.class.getClassLoader()); return true; }
        catch (ClassNotFoundException e) { return false; }
    }
}
