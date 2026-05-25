package io.github.m3t4f1v3;

import com.mojang.logging.LogUtils;
import io.github.m3t4f1v3.network.SnowstormNetwork;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import org.slf4j.Logger;

@Mod(Snowstorm.MODID)
public class Snowstorm {
    public static final String MODID = "snowstorm";

    private static final Logger LOGGER = LogUtils.getLogger();

    public Snowstorm() {
        LOGGER.info("Snowstorm loaded");
        SnowstormNetwork.register();
        // Register ValkyrienSkies collision listeners (if VS is present)
        io.github.m3t4f1v3.util.VSCollisionEvents.register();
    }
}
