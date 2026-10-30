package com.chestcount.mod;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.integration.*;
import com.chestcount.mod.model.*;
import com.chestcount.mod.storage.*;
import com.google.gson.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Explicit live integration runner. Only stops Python children started by this runner. */
public final class RealSyncIntegration {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final List<String> checks=new ArrayList<>();
    private static final Path SERVER=Path.of(System.getenv().getOrDefault("CHESTCOUNT_SERVER_PROJECT","C:/ChestCount-Server")).toAbsolutePath();
    private static final Path OUTPUT=Path.of("build/real-integration").toAbsolutePath();
    private static Process process;
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private static void check(boolean result,String label) { if(!result) throw new AssertionError(label); checks.add(label); System.out.println("PASS "+label); }
    private static JsonObject get(String route) throws Exception {
        var response=HTTP.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:8080"+route)).timeout(Duration.ofSeconds(3)).GET().build(),HttpResponse.BodyHandlers.ofString());
        if(response.statusCode()!=200) throw new java.io.IOException("GET HTTP "+response.statusCode()); return JsonParser.parseString(response.body()).getAsJsonObject();
    }
    private static void start() throws Exception {
        Files.createDirectories(OUTPUT);
        process=new ProcessBuilder("python",SERVER.resolve("server.py").toString(),"--host","127.0.0.1","--port","8080")
            .directory(SERVER.toFile()).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(OUTPUT.resolve("python.log").toFile())).start();
        for(int i=0;i<60;i++) {
            if(!process.isAlive()) throw new java.io.IOException("Owned Chest Count server exited; see python.log");
            try { if(get("/api/v1/health").get("success").getAsBoolean()) { check(true,"Health after start PID "+process.pid()); return; } } catch(Exception ignored) {}
            Thread.sleep(100);
        } throw new java.io.IOException("Server startup timeout");
    }
    private static void stop() throws Exception {
        if(process!=null && process.isAlive()) {
            // This Process handle was created only by start() with the exact absolute server.py path.
            // Windows may omit ProcessHandle.Info.commandLine(); never enumerate or kill other PIDs.
            process.destroy(); if(!process.waitFor(10,TimeUnit.SECONDS)) throw new IllegalStateException("Owned server did not stop");
            checks.add("Stopped owned Chest Count server PID "+process.pid());
        }
    }
    private static <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(15,TimeUnit.SECONDS); }
    private static ChestRecord chest(String world) { return new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity(world,"minecraft:overworld",List.of(new Position(100,64,200))),"Integration test",Instant.now().toString(),Instant.now().toString(),List.of(new ItemRecord(0,"minecraft:diamond",32)),false); }
    private static JsonStorageService.Document doc(String world,ChestRecord... records) { return new JsonStorageService.Document(1,world,List.of(records)); }
    private static Map<Path,String> preserved() throws Exception {
        var result=new HashMap<Path,String>(); Path data=SERVER.resolve("data");
        if(Files.isDirectory(data)) try(var files=Files.walk(data)) { for(var file:files.filter(f -> f.toString().endsWith(".json")).toList()) result.put(file,JsonStorageService.key(Files.readString(file))); }
        return result;
    }
    public static void main(String[] args) throws Exception {
        // A foreign listener is never stopped or reused silently.
        try(var socket=new java.net.ServerSocket()) { socket.bind(new InetSocketAddress("127.0.0.1",8080)); }
        var previous=preserved(); String world="integration-test:incremental:"+UUID.randomUUID();
        Path local=OUTPUT.resolve(UUID.randomUUID().toString()); Files.createDirectories(local);
        var config=new ModConfig.Sync(); config.debounceSeconds=0; config.retryInitialSeconds=1; config.requestTimeoutSeconds=2;
        var a=chest(world); String route="/api/v1/worlds/"+java.net.URLEncoder.encode(world,java.nio.charset.StandardCharsets.UTF_8);
        try {
            start();
            try(var queue=new IncrementalSyncQueue(local)) {
                queue.configure(config); queue.update(doc(world,a)); var first=queue.pending(world).getFirst().status("sent",0L);
                check(await(queue.attempt(world,config,true))==1,"Java outbox creates chest with 32 diamonds");
                a=a.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",48))); queue.update(doc(world,a));
                check(await(queue.attempt(world,config,true))==1,"Java outbox updates same chest to 48 diamonds");
                var rows=get(route+"/chests").getAsJsonArray("chests"); check(rows.size()==1 && rows.get(0).getAsJsonObject().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt()==48,"SQLite consolidates one chest instead of request files");
                a=a.rename("Renamed integration chest"); queue.update(doc(world,a)); check(queue.pending(world).getFirst().operation().equals("rename"),"Rename uses incremental operation"); await(queue.attempt(world,config,true));
                check(get(route+"/chests/"+a.id()).get("name").getAsString().equals(a.name()),"Rename persisted without replacing inventory");
                String duplicate=GSON.toJson(new SyncProtocol.Request(1,queue.clientId(),world,true,List.of(first)));
                var duplicateResponse=GSON.fromJson(await(new HttpSyncProvider(config).exchange(duplicate)),SyncProtocol.Response.class);
                check(duplicateResponse.results().getFirst().status().equals("duplicate"),"Repeated original change returns duplicate");
                check(get(route+"/chests/"+a.id()).get("serverRevision").getAsLong()==3,"Duplicate does not increment server revision");
                queue.update(doc(world)); check(queue.pending(world).getFirst().operation().equals("delete"),"Removal queues delete"); await(queue.attempt(world,config,true));
                check(get(route+"/chests/"+a.id()).get("deleted").getAsBoolean(),"Logical delete retains tombstone");
                stop(); var b=chest(world); queue.update(doc(world,b));
                try { await(queue.attempt(world,config,false)); throw new AssertionError("Expected offline failure"); } catch(ExecutionException expected) { check(queue.status(world,config).pending(),"Offline request retained durably"); }
                Files.writeString(local.resolve("restart-document.json"),GSON.toJson(doc(world,b)));
            }
            start(); Thread.sleep(1200);
            try(var recovered=new IncrementalSyncQueue(local)) {
                recovered.configure(config); var current=GSON.fromJson(Files.readString(local.resolve("restart-document.json")),JsonStorageService.Document.class); recovered.update(current);
                check(await(recovered.attempt(world,config,false))==1,"Restart recovers queue and automatic retry");
                check(!recovered.status(world,config).pending(),"Only confirmed changes removed from queue");
                var storage=new JsonStorageService(local); storage.save(world,current.chests());
                var archives=new WorldArchiveService(local); Path manual=archives.backup(current),export=archives.export(current);
                var backupConfig=new ModConfig.Backup(); archives.automatic(current,backupConfig,0); check(archives.automatic(current,backupConfig,300000).isPresent(),"Full backup after simulated five-minute interval");
                Files.createDirectories(local.resolve("imports")); Files.copy(export,local.resolve("imports/import.json")); var preview=archives.preview("import.json",current);
                check(archives.apply("import.json",current,preview.fingerprint(),storage).equals(current),"Export/import validation, confirmation and pre-import backup");
                check(Files.exists(manual),"Manual backup survives automatic retention");
                Files.writeString(OUTPUT.resolve("server-export.json"),GSON.toJson(get(route+"/export")));
                check(get(route+"/export").getAsJsonArray("chests").size()==1,"Server export excludes tombstones from active chest list");
                var events=get(route+"/changes?since=0&limit=2"); check(events.get("hasMore").getAsBoolean(),"Global change cursor is paginated");
            }
            stop(); start(); check(get(route+"/chests/"+a.id()).get("deleted").getAsBoolean(),"Tombstone persists after second Python restart");
            check(previous.equals(preserved()),"All pre-existing server JSONs unchanged");
            var report=new JsonObject(); report.addProperty("worldId",world); report.addProperty("success",true); report.add("checks",GSON.toJsonTree(checks)); report.addProperty("localArtifacts",local.toString());
            Files.writeString(OUTPUT.resolve("report.json"),GSON.toJson(report));
        } finally { stop(); HTTP.shutdown(); }
    }
}
