package dev.pufferfish.runtime.mixin;

import java.nio.ByteBuffer;

/** Stand-in for java.lang.foreign.MemorySegment in runtime mod code: the iOS image is built without the FFM API
 *  (-H:-ForeignAPISupport). Covers raw native-address use (Sodium ChunkMeshBufferBuilder: ofAddress → reinterpret →
 *  asSlice → asByteBuffer / address); RuntimeMixins only rewrites classes that use nothing else. */
public final class PfSegment {
    private final long address, size;
    private PfSegment(long address, long size) { this.address = address; this.size = size; }

    public static PfSegment ofAddress(long address) { return new PfSegment(address, 0); }
    public long address() { return address; }
    public long byteSize() { return size; }
    public PfSegment reinterpret(long newSize) { return new PfSegment(address, newSize); }
    public PfSegment asSlice(long offset) { return asSlice(offset, size - offset); }
    public PfSegment asSlice(long offset, long newSize) {
        if (offset < 0 || newSize < 0 || offset + newSize > size) throw new IndexOutOfBoundsException("slice " + offset + "+" + newSize + " of " + size);
        return new PfSegment(address + offset, newSize);
    }
    public ByteBuffer asByteBuffer() {
        if (size > Integer.MAX_VALUE) throw new UnsupportedOperationException("segment too large for a ByteBuffer: " + size);
        return org.lwjgl.system.MemoryUtil.memByteBuffer(address, (int) size);
    }
}
