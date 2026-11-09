package dev.pufferfish.cross;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

/**
 * Hand-written cross substitution: the image carries Linux's FileDispatcherImpl, whose natives
 * (init0, transferFrom0) don't exist in Darwin's libnio. Darwin has no sendfile-style transferFrom,
 * so report "unsupported" (IOStatus.UNSUPPORTED_CASE) and let FileChannelImpl fall back to read/write.
 */
@TargetClass(className = "sun.nio.ch.FileDispatcherImpl")
final class Target_sun_nio_ch_FileDispatcherImpl {
    @Substitute
    static void init0() { }

    @Substitute
    long transferFrom(java.io.FileDescriptor src, java.io.FileDescriptor dst, long position, long count, boolean append) {
        return -6; // IOStatus.UNSUPPORTED_CASE
    }
}
