package com.chestcount.mod.config;

import com.google.gson.*;
import com.chestcount.mod.storage.JsonStorageService;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

public final class ModConfig {
    public int formatVersion = 1;
    public Sync sync = new Sync();
    public Backup backup = new Backup();
    public static final class Backup {
        public boolean enabled=true, onlyWhenChanged=true;
        public int intervalMinutes=5, maxAutomaticBackupsPerWorld=10;
        public void validate() {
            if(intervalMinutes<1 || intervalMinutes>1440 || maxAutomaticBackupsPerWorld<1 || maxAutomaticBackupsPerWorld>1000) throw new IllegalArgumentException("Invalid backup configuration");
        }
    }
    public static final class Sync {
        public boolean enabled = true, automatic = true;
        public String endpoint = "http://127.0.0.1:8080/api/v1/sync";
        public int debounceSeconds=3, retryInitialSeconds=5, retryMaxSeconds=300;
        public int intervalSeconds = 30, connectTimeoutSeconds = 5, requestTimeoutSeconds = 10;
        public void validate() {
            com.chestcount.mod.integration.HttpSyncProvider.endpoint(endpoint);
            if(debounceSeconds<0 || debounceSeconds>300 || retryInitialSeconds<1 || retryMaxSeconds<retryInitialSeconds || retryMaxSeconds>86400) throw new IllegalArgumentException("Invalid retry settings");
            if (intervalSeconds < 1 || intervalSeconds > 86400 || connectTimeoutSeconds < 1 || connectTimeoutSeconds > 120 || requestTimeoutSeconds < 1 || requestTimeoutSeconds > 300)
                throw new IllegalArgumentException("Invalid sync timeouts");
        }
    }
    public void save(Path path) throws IOException {
        JsonStorageService.atomic(path,new GsonBuilder().setPrettyPrinting().create().toJson(this));
    }
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
            if (c.sync == null) c.sync = new Sync();
            if(c.sync.endpoint.equals("http://127.0.0.1:8080/api/sync")) c.sync.endpoint="http://127.0.0.1:8080/api/v1/sync";
            if(c.backup==null) c.backup=new Backup();
            c.backup.validate(); c.sync.validate();
            return c;
        } catch (RuntimeException e) { throw new IOException("Invalid config; preserved original",e); }
    }
}
