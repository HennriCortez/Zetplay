package com.zetplay.zetplaymod;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ZetPlayMod implements ModInitializer {

    public static final String MOD_ID = "zetplay";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        LOGGER.info("[ZetPlay] Mod loaded.");

        // Load configuration file (creates config/zetplay.json if missing)
        ZetPlayConfig.load();

        ServerMessageEvents.CHAT_MESSAGE.register(new ChatCommandListener());
    }
}