package com.awesomehippo.restorehardcoreworld;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;

@Mod(RestoreHardcoreWorld.MOD_ID)
public class RestoreHardcoreWorld {
    public static final String MOD_ID = "restorehardcoreworld";

    public RestoreHardcoreWorld() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            FMLJavaModLoadingContext.get().getModEventBus().addListener(this::onClientSetup);
            Config.init();
            ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, Config.CLIENT_CONFIG);
        } else {
            // TODO: log it runs only on client, but I'll probably do it with my future lib
        }
    }

    private void onClientSetup(FMLClientSetupEvent event) {
        MinecraftForge.EVENT_BUS.register(new ClientEvents());
    }
}