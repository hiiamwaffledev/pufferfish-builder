package dev.pufferfish.runtime.mixin;

/** A runtime mod's injector handler plugged into a baked hook slot (see HookGen / PfHooks). Returns the handler's result (null for void). */
public interface Hook {
    Object call(Object self, Object[] args);
}
