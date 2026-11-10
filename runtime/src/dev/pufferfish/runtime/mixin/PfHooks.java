package dev.pufferfish.runtime.mixin;

/** Hook slots read by the stand-in injectors that HookGen bakes into Minecraft. Empty slot = no runtime mod handler. */
public final class PfHooks {
    public static final int MAX = 1 << 15;
    public static final Hook[] H = new Hook[MAX];
    private PfHooks() { }
}
