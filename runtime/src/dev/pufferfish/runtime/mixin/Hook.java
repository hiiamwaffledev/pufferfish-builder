package dev.pufferfish.runtime.mixin;

/** A runtime mod's injector handler plugged into a baked hook slot (see HookGen / PfHooks). */
public interface Hook {
    void call(Object self, Object[] args);
}
