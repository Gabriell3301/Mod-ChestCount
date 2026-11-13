package com.chestcount.mod.integration;
import com.google.gson.JsonObject;
import java.util.List;
/**
 * Define os objetos serializados do protocolo 1 entre o mod e a API.
 *
 * As revisões locais e do servidor têm funções diferentes; o resultado por alteração
 * é necessário mesmo quando o envelope HTTP termina com sucesso.
 */
public final class SyncProtocol {
    private SyncProtocol() {}
    public record Change(String changeId,String clientId,String worldId,String chestId,String dimension,long revision,String operation,Long baseServerRevision,JsonObject data,String createdAt,String status) {
        public Change status(String status,Long base) { return new Change(changeId,clientId,worldId,chestId,dimension,revision,operation,base,data,createdAt,status); }
    }
    public record Versions(String minecraftVersion, String fabricVersion, String modVersion) {
        public static Versions current() {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            return new Versions(net.minecraft.SharedConstants.getCurrentVersion().name(),
                    version(loader, "fabricloader"), version(loader, "chestcount"));
        }

        private static String version(net.fabricmc.loader.api.FabricLoader loader, String id) {
            return loader.getModContainer(id).map(mod -> mod.getMetadata().getVersion().getFriendlyString()).orElse(null);
        }
    }
    public record Request(int protocolVersion,String clientId,String worldId,boolean bootstrap,List<Change> changes,
                          String worldName,String minecraftVersion,String fabricVersion,String modVersion) {
        public Request(int protocolVersion, String clientId, String worldId, boolean bootstrap, List<Change> changes, String worldName) {
            this(protocolVersion, clientId, worldId, bootstrap, changes, worldName, null, null, null);
        }
        public Request(int protocolVersion,String clientId,String worldId,boolean bootstrap,List<Change> changes) {
            this(protocolVersion,clientId,worldId,bootstrap,changes,null);
        }
    }
    public record Result(String changeId,String chestId,long revision,String status,Long serverRevision,String reason) {}
    public record Response(boolean success,int protocolVersion,List<Result> results) {}
}
