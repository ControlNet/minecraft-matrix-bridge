package space.controlnet.minecraftmatrixbridge;

import com.mojang.logging.LogUtils;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import org.slf4j.Logger;

@Mod(MatrixBridgeMod.MOD_ID)
public final class MatrixBridgeMod {
    public static final String MOD_ID = "minecraftmatrixbridge";
    public static final Logger LOGGER = LogUtils.getLogger();

    public MatrixBridgeMod(ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.COMMON, MatrixBridgeConfig.SPEC, MOD_ID + ".toml");
        NeoForge.EVENT_BUS.register(new NeoForgeHooks());
        LOGGER.info("Minecraft Matrix Bridge loaded (server-only, NeoForge).");
    }
}

