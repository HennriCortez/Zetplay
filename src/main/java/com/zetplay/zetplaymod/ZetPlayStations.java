package com.zetplay.zetplaymod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class ZetPlayStations {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final File FILE = new File("config/stations.json");
    private static final Map<String, String> STATIONS = new ConcurrentHashMap<>();

    public static void load() {
        if (!FILE.exists()) {
            // Pre-seed default stations
            STATIONS.put("easyrock", "https://azura.easyrock.com.ph/listen/easy_rock_manila/radio.mp3");
            save();
            return;
        }

        try (FileReader reader = new FileReader(FILE)) {
            Type type = new TypeToken<Map<String, String>>() {}.getType();
            Map<String, String> loaded = GSON.fromJson(reader, type);
            if (loaded != null) {
                STATIONS.clear();
                STATIONS.putAll(loaded);
            }
        } catch (IOException e) {
            ZetPlayMod.LOGGER.error("[ZetPlay] Failed to load stations.json", e);
        }
    }

    public static void save() {
        try {
            File parent = FILE.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();

            try (FileWriter writer = new FileWriter(FILE)) {
                GSON.toJson(STATIONS, writer);
            }
        } catch (IOException e) {
            ZetPlayMod.LOGGER.error("[ZetPlay] Failed to save stations.json", e);
        }
    }

    public static void registerStation(String name, String url) {
        STATIONS.put(name.toLowerCase(), url);
        save();
    }

    public static String getUrl(String name) {
        return STATIONS.get(name.toLowerCase());
    }

    public static Map<String, String> getAll() {
        return STATIONS;
    }
}