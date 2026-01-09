package space.controlnet.minecraftmatrixbridge;

import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(MatrixBridgeMod.MOD_ID)
public final class MatrixBridgeMod {
    public static final String MOD_ID = "minecraftmatrixbridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MatrixBridgeMod() {
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, MatrixBridgeConfig.SPEC, MOD_ID + ".toml");
        MinecraftForge.EVENT_BUS.register(new ForgeHooks());
        LOGGER.info("Minecraft Matrix Bridge loaded (server-only).");
    }
}
