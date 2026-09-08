package com.jsound.openal;

import javax.sound.sampled.Mixer;
import javax.sound.sampled.spi.MixerProvider;

/**
 * SPI entry point registered via {@code META-INF/services}. Its static
 * initializer also attempts a reflective fallback registration, in case the
 * environment's classloader did not scan the services file.
 */
public final class JSoundMixerProvider extends MixerProvider {

    static {
        OpenALCore.registerFallback();
    }

    @Override
    public Mixer getMixer(Mixer.Info info) {
        if (info == null) {
            return new JSoundMixer();
        }
        if (info.equals(JSoundMixer.INFO)) {
            return new JSoundMixer();
        }
        throw new IllegalArgumentException("Unknown mixer: " + info);
    }

    @Override
    public Mixer.Info[] getMixerInfo() {
        return new Mixer.Info[]{JSoundMixer.INFO};
    }
}
