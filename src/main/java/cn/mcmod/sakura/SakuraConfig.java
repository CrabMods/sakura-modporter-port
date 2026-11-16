package cn.mcmod.sakura;

import net.neoforged.neoforge.common.ModConfigSpec;

public class SakuraConfig {
    public static ModConfigSpec COMMON_CONFIG;


    static {
        ModConfigSpec.Builder COMMON_BUILDER = new ModConfigSpec.Builder();
        COMMON_BUILDER.comment("General settings").push("general");

        COMMON_CONFIG = COMMON_BUILDER.build();
    }
}
