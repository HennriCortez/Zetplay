package com.zetplay.zetplaymod;

import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZetPlayMod implements ModInitializer {

    public static final String MOD_ID = "zetplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[ZetPlay] Mod loaded.");

        // 1. Load Configuration
        ZetPlayConfig.load();

        // 2. Initialize State Helper
        new ChatCommandListener();

        // 3. Register Native Commands (/play, /stream, /skip, etc.)
        ZetPlayCommands.register();
    }
}