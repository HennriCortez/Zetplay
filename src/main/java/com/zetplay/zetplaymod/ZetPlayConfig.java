package com.zetplay.zetplaymod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class ZetPlayConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance().getConfigDir().resolve("zetplay.json");

    // ── ACRCloud Credentials (Fill these in config/zetplay.json) ───────────
    public String acrHost = "identify-ap-southeast-1.acrcloud.com";
    public String acrAccessKey = "";
    public String acrAccessSecret = "";

    // ── Audio Settings ─────────────────────────────────────────────────────
    public int sampleRate = 48000;
    public int frameSamples = 960;
    public int downloadTimeoutSeconds = 180;

    // ── Singleton Instance ─────────────────────────────────────────────────
    private static ZetPlayConfig instance;

    public static ZetPlayConfig get() {
        if (instance == null) {
            load();
        }
        return instance;
    }

    public static void load() {
        if (Files.exists(CONFIG_PATH)) {
            try (FileReader reader = new FileReader(CONFIG_PATH.toFile())) {
                instance = GSON.fromJson(reader, ZetPlayConfig.class);
                if (instance == null) {
                    instance = new ZetPlayConfig();
                }
            } catch (Exception e) {
                ZetPlayMod.LOGGER.error("[ZetPlay] Failed to load config, using defaults", e);
                instance = new ZetPlayConfig();
            }
        } else {
            instance = new ZetPlayConfig();
            save();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (FileWriter writer = new FileWriter(CONFIG_PATH.toFile())) {
                GSON.toJson(instance, writer);
            }
        } catch (IOException e) {
            ZetPlayMod.LOGGER.error("[ZetPlay] Failed to save config", e);
        }
    }
}