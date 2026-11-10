package dev.pufferfish.runtime.mixin;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Side storage for fields a mixin adds to a target (@Unique / new fields): the target class is compiled into the image
 * and can't grow fields, so each object gets a slot array keyed by identity (weak: dies with the object).
 * Slots of one mixin class are initialised on first access by the mixin's field initialisers (pf$init).
 */
public final class PfFields {
    private PfFields() { }

    static final class Key extends WeakReference<Object> {
        final int hash;
        Key(Object o, ReferenceQueue<Object> q) { super(o, q); hash = System.identityHashCode(o); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object x) { return x instanceof Key k && k.get() == get() && get() != null; }
    }

    private static final ReferenceQueue<Object> Q = new ReferenceQueue<>();
    private static final Map<Object, Object[][]> MAP = new HashMap<>();

    /** slots for (object, mixin) — mixin id from RuntimeMixins; size = number of added fields of that mixin */
    public static synchronized Object[] slots(Object o, int mixin) {
        for (Object k; (k = Q.poll()) != null; ) MAP.remove(k);
        Object[][] all = MAP.get(new ProbeKey(o));
        if (all == null) { all = new Object[RuntimeMixins.mixinCount()][]; MAP.put(new Key(o, Q), all); }
        Object[] s = all[mixin];
        if (s == null) {
            s = all[mixin] = RuntimeMixins.newSlots(mixin);
            RuntimeMixins.initFields(mixin, o);
        }
        return s;
    }

    /** Probe that compares equal to the Key of the same object (HashMap calls probe.equals(key)). */
    static final class ProbeKey {
        final Object o; final int hash;
        ProbeKey(Object o) { this.o = o; hash = System.identityHashCode(o); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object x) { return x instanceof Key k && k.get() == o; }
    }

    public static Object get(Object o, int mixin, int idx) { return slots(o, mixin)[idx]; }
    public static void set(Object o, Object v, int mixin, int idx) { slots(o, mixin)[idx] = v; }
}
