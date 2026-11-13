package com.chestcount.mod.service;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.storage.JsonStorageService;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.storage.LevelResource;

/**
 * Resolve a identidade de origem a partir do save local ou do endereço normalizado.
 *
 * O alias configura contextos distintos; o nome visível do mundo não é a chave de sincronização.
 */
public final class WorldIdentityService {
    private static final java.util.Map<java.nio.file.Path,String> localIds=new java.util.HashMap<>();
    public static String name(Minecraft mc) {
        if(mc.hasSingleplayerServer()) return mc.getSingleplayerServer().getWorldData().getLevelName();
        return mc.getCurrentServer()==null?"Minecraft":mc.getCurrentServer().name;
    }
    public static String normalizeServer(String address) {
        return ServerAddressNormalizer.normalize(address);
    }
    public static String current(Minecraft mc, ModConfig config) {
        if (mc.level == null || mc.getConnection() == null) return null;
        String base;
        if (mc.hasSingleplayerServer()) {
            var path = mc.getSingleplayerServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            if(!localIds.containsKey(path)) {
                try { localIds.put(path,SaveWorldIdentity.load(path)); }
                catch(java.io.IOException e) {
                    localIds.put(path,null);
                    org.slf4j.LoggerFactory.getLogger("chestcount").error("World identity unavailable; repair chestcount-world-id.json and restart. Local data preserved.");
                }
            }
            base=localIds.get(path);
            if(base==null) return null;
        } else {
            if (mc.getCurrentServer() == null) return null;
            base = "server:"+normalizeServer(mc.getCurrentServer().ip);
        }
        return base+"|"+config.worldAliases.getOrDefault(base,"default");
    }
}
