package com.chestcount.mod.config;

import com.google.gson.*;
import com.chestcount.mod.storage.JsonStorageService;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class ModConfig {
    public int formatVersion = 1;
    public boolean alwaysShowOutlines = false;
    public int outlineRadius = 32;
    public int nearbyRadius = 32;
    /** Explicitly trusted plugin servers. Default multiplayer policy accepts only vanilla brand. */
    public Set<String> trustedServers = new HashSet<>();
    public Map<String,String> worldAliases = new HashMap<>();
    public static ModConfig load(Path path) throws IOException {
        var gson = new GsonBuilder().setPrettyPrinting().create();
        if (!Files.exists(path)) { var c = new ModConfig(); JsonStorageService.atomic(path,gson.toJson(c)); return c; }
        try {
            var c = gson.fromJson(Files.readString(path),ModConfig.class);
            if (c == null || c.formatVersion != 1 || c.outlineRadius < 1 || c.outlineRadius > 128 || c.nearbyRadius < 1 || c.nearbyRadius > 4096 || c.trustedServers == null || c.worldAliases == null) throw new IllegalArgumentException("Invalid config");
            return c;
        } catch (RuntimeException e) { throw new IOException("Invalid config; preserved original",e); }
    }
}
