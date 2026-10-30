package com.chestcount.mod.integration;
import com.google.gson.JsonObject;
import java.util.List;
public final class SyncProtocol {
    private SyncProtocol() {}
    public record Change(String changeId,String clientId,String worldId,String chestId,String dimension,long revision,String operation,Long baseServerRevision,JsonObject data,String createdAt,String status) {
        public Change status(String status,Long base) { return new Change(changeId,clientId,worldId,chestId,dimension,revision,operation,base,data,createdAt,status); }
    }
    public record Request(int protocolVersion,String clientId,String worldId,boolean bootstrap,List<Change> changes) {}
    public record Result(String changeId,String chestId,long revision,String status,Long serverRevision,String reason) {}
    public record Response(boolean success,int protocolVersion,List<Result> results) {}
}
