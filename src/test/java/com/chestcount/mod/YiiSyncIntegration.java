package com.chestcount.mod;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.integration.*;
import com.chestcount.mod.model.*;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Explicit integration against the real Yii2 test entrypoint and isolated MySQL database. */
public final class YiiSyncIntegration {
    private static <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(20,TimeUnit.SECONDS); }
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        var fixture=JsonParser.parseString(Files.readString(Path.of(args[0]))).getAsJsonObject();
        var config=new ModConfig.Sync(); config.endpoint=fixture.get("endpoint").getAsString(); config.debounceSeconds=0;
        Path root=Path.of(args[0]).getParent().resolve("java-device-integration-"+UUID.randomUUID());
        DeviceCredentials.initialize(root);
        var provider=new HttpSyncProvider(config);
        var input=new JsonObject(); input.addProperty("code",fixture.get("code").getAsString()); input.addProperty("label","Real Java integration");
        var response=JsonParser.parseString(await(provider.deviceRequest("link/exchange",input.toString(),null))).getAsJsonObject();
        var credential=new DeviceCredentials.Credential(response.get("token").getAsString(),response.get("deviceId").getAsString(),response.get("expiresAt").getAsString(),false);
        DeviceCredentials.save(config.endpoint,credential);
        check(DeviceCredentials.load(config.endpoint).equals(credential),"Credential restart persistence");
        check(JsonParser.parseString(await(provider.deviceRequest("device/status","{}",credential.token()))).getAsJsonObject().get("connected").getAsBoolean(),"Device status");
        String world=fixture.get("worldId").getAsString(),now=Instant.now().toString();
        List<ChestRecord> records=new ArrayList<>();
        for(int i=0;i<4;i++) {
            var blocks=new ArrayList<Position>(); blocks.add(new Position(i*10,64,0)); if(i==1) blocks.add(new Position(i*10+1,64,0));
            records.add(new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity(world,"minecraft:overworld",blocks),"Chest "+i,now,i==3?null:now,
                i<2?List.of(new ItemRecord(0,"minecraft:diamond",32),new ItemRecord(i==1?53:26,"minecraft:oak_log",12)):List.of(),false));
        }
        try(var queue=new IncrementalSyncQueue(root)) {
            queue.configure(config); queue.worldName(world,"Mundo Java real");
            // The first request intentionally registers a world without chests.
            queue.update(new JsonStorageService.Document(1,world,List.of())); check(await(queue.attempt(world,config,true))==0,"Empty world bootstrap");
            queue.update(new JsonStorageService.Document(1,world,records)); check(await(queue.attempt(world,config,true))==4,"Four actual Java records accepted");
            queue.update(new JsonStorageService.Document(1,world,records)); check(await(queue.attempt(world,config,true))==0,"No duplicates");
            var old=records.getFirst(); records.set(0,old.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",48))));
            queue.update(new JsonStorageService.Document(1,world,records)); check(await(queue.attempt(world,config,true))==1,"Quantity change");
            records.set(0,records.getFirst().stale()); queue.update(new JsonStorageService.Document(1,world,records)); check(await(queue.attempt(world,config,true))==1,"Structure change");
            var remote=JsonParser.parseString(await(provider.readChest(world,records.get(3).id()))).getAsJsonObject();
            check(remote.has("syncedAt") && remote.get("syncedAt").isJsonNull(),"Explicit null survives Gson, Yii and MySQL");
        }
        // Retain one device for browser revocation tests; this second device proves mod-side disconnect.
        await(provider.deviceRequest("device/disconnect","{}",credential.token()));
        try { await(provider.deviceRequest("device/status","{}",credential.token())); throw new AssertionError("Revoked token accepted"); }
        catch(ExecutionException e) { check(e.getCause() instanceof HttpSyncProvider.HttpStatusException status && status.status==401,"Revocation HTTP 401"); }
        DeviceCredentials.clear(config.endpoint);
        System.out.println("PASS real Java linking, persistence, empty world, simple/double/empty/unobserved, quantity/structure, idempotence, null, disconnect and revocation.");
    }
}
