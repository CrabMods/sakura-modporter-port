package com.modporter.validation;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(ExactJarValidationMod.MOD_ID)
public final class ExactJarValidationMod {
    public static final String MOD_ID = "exact_jar_validation";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ExactJarValidationMod() {
        LOGGER.info("[ExactJarValidation] Harness loaded");
    }
}

