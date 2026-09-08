package com.jsound.openal.forge;

import com.jsound.openal.OpenALCore;
import net.minecraftforge.fml.common.Mod;

/**
 * Forge entry point (1.20.1). Forge uses {@code META-INF/mods.toml} + an
 * {@code @Mod}-annotated class rather than Fabric's {@code ModInitializer};
 * instantiating this class lets us run the reflective SPI fallback so
 * {@code AudioSystem} finds the bridge under Forge's classloader, which (like
 * Fabric's) does not scan {@code META-INF/services}.
 */
@Mod("jsoundopenal")
public class JSoundOpenalForgeMod {

    public JSoundOpenalForgeMod() {
        OpenALCore.registerFallback();
    }
}
