package com.chestcount.mod.service;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.storage.JsonStorageService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

public final class WorldIdentityService {
    public static String normalizeServer(String address) {
        return ServerAddressNormalizer.normalize(address);
    }
    public static String current(Minecraft mc, ModConfig config) {
        if (mc.level == null || mc.getConnection() == null) return null;
        String base;
        if (mc.hasSingleplayerServer()) {
            var path = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            base = "local:"+JsonStorageService.key(path.toString());
        } else {
            if (mc.getCurrentServer() == null) return null;
            base = "server:"+normalizeServer(mc.getCurrentServer().ip);
        }
        return base+"|"+config.worldAliases.getOrDefault(base,"default");
    }
}
