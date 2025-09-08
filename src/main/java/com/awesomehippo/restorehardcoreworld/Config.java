package com.awesomehippo.restorehardcoreworld;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    public static ForgeConfigSpec CLIENT_CONFIG;
    public static ForgeConfigSpec.BooleanValue KEEP_HARDCORE;
    public static ForgeConfigSpec.BooleanValue KEEP_INVENTORY;

    public static void init() {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Restore Hardcore World Configuration");

        KEEP_HARDCORE = builder
                .comment("Keep the world as hardcore after restore (true) or convert to survival (false)")
                .define("keepHardcore", true);

        KEEP_INVENTORY = builder
                .comment("Keep the player's inventory/experience after restore (true) or drop it (false)")
                .define("keepInventory", false);

        CLIENT_CONFIG = builder.build();
    }
}