package dev.pufferfish.cross;

import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;
import java.nio.channels.spi.SelectorProvider;

/**
 * Hand-written cross substitution: SVM requires SelectorProvider$Holder to be initialized at build time, so on the
 * Linux builder its INSTANCE is Linux's EPollSelectorProvider, and on iOS every Selector.open() (java.net.http, netty
 * NIO, spark) hits Linux-only natives. Here provider() creates the image's DefaultSelectorProvider (the Darwin one,
 * KQueue, via GenJdkSubst) lazily at run time instead of reading the build-time holder.
 */
@TargetClass(SelectorProvider.class)
final class Target_java_nio_channels_spi_SelectorProvider {
    @Substitute
    public static SelectorProvider provider() {
        SelectorProvider p = PfSelectorProvider.instance;
        if (p == null) {
            synchronized (PfSelectorProvider.class) {
                p = PfSelectorProvider.instance;
                if (p == null) PfSelectorProvider.instance = p = sun.nio.ch.DefaultSelectorProvider.get();
            }
        }
        return p;
    }
}

final class PfSelectorProvider {
    static volatile SelectorProvider instance;   // no <clinit>: safe whether initialized at build or run time
}
