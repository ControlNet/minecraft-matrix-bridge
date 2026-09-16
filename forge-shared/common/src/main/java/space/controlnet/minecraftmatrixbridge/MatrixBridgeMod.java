package space.controlnet.minecraftmatrixbridge;

import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Mod(MatrixBridgeMod.MOD_ID)
public final class MatrixBridgeMod {
    public static final String MOD_ID = "minecraftmatrixbridge";
    public static final Logger LOGGER = LoggerFactory.getLogger(MatrixBridgeMod.class);

    public MatrixBridgeMod() {
        // Resolve our container explicitly; this API also supports older loaders
        // that do not provide constructor-injected loading contexts.
        var container = ForgeHooks.getModContainer();
        container.addConfig(new ModConfig(ModConfig.Type.COMMON, MatrixBridgeConfig.SPEC, container, MOD_ID + ".toml"));
        MinecraftForge.EVENT_BUS.register(new ForgeHooks());
        LOGGER.info("Minecraft Matrix Bridge loaded (server-only).");
    }
}
