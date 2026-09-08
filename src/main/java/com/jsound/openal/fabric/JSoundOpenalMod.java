package com.jsound.openal.fabric;

import com.jsound.openal.OpenALCore;

import net.fabricmc.api.ModInitializer;

/**
 * Fabric entrypoint. Fabric Loader's classloader does not scan
 * {@code META-INF/services}, so SPI discovery alone never registers the bridge
 * on a phone. This {@code onInitialize} hook forces the reflective fallback,
 * appending our {@code MixerProvider} to {@code AudioSystem}'s internal list.
 */
public final class JSoundOpenalMod implements ModInitializer {

    @Override
    public void onInitialize() {
        OpenALCore.registerFallback();
    }
}
