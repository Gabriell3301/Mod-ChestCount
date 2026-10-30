package com.chestcount.mod;
import com.chestcount.mod.integration.*;
import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.model.*;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class IncrementalSyncTest {
    @TempDir Path temp;
    HttpServer server;
    ModConfig.Sync config;
    AtomicInteger calls=new AtomicInteger(), httpCode=new AtomicInteger(200);
    AtomicReference<String> resultStatus=new AtomicReference<>("applied"),body=new AtomicReference<>();
    volatile CountDownLatch gate;
    volatile boolean malformed;
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/v1/sync",e -> {
            calls.incrementAndGet(); String raw=new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8); body.set(raw);
            try { if(gate!=null) gate.await(4,TimeUnit.SECONDS); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            var request=new Gson().fromJson(raw,SyncProtocol.Request.class);
            var results=request.changes().stream().map(c -> new SyncProtocol.Result(c.changeId(),c.chestId(),c.revision(),resultStatus.get(),c.baseServerRevision()+1,null)).toList();
            byte[] bytes=(malformed?"{}":new Gson().toJson(new SyncProtocol.Response(true,1,results))).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try { e.sendResponseHeaders(httpCode.get(),bytes.length); e.getResponseBody().write(bytes); } catch(java.io.IOException ignored) {} finally { e.close(); }
        }); server.start(); config=new ModConfig.Sync(); config.endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/sync"; config.debounceSeconds=0; config.retryInitialSeconds=1;
    }
    @AfterEach void cleanup() { if(gate!=null) gate.countDown(); server.stop(0); }
    ChestRecord chest(String world) { return new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity(world,"minecraft:overworld",List.of(new Position(1,64,2))),"Minerios",Instant.now().toString(),Instant.now().toString(),List.of(new ItemRecord(0,"minecraft:diamond",32)),false); }
    JsonStorageService.Document doc(ChestRecord... chests) { return new JsonStorageService.Document(1,chests.length==0?"local:test":chests[0].identity().world(),List.of(chests)); }
    IncrementalSyncQueue queue() { var q=new IncrementalSyncQueue(temp); q.configure(config); return q; }
    <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(6,TimeUnit.SECONDS); }
    @Test void stableClientAndPendingIdsAfterRestart() throws Exception {
        var chest=chest("local:test"); String client,id;
        try(var q=queue()) { q.update(doc(chest)); client=q.clientId(); id=q.pending("local:test").getFirst().changeId(); }
        try(var q=queue()) { q.update(doc(chest)); assertEquals(client,q.clientId()); assertEquals(id,q.pending("local:test").getFirst().changeId()); assertEquals(1,await(q.attempt("local:test",config,true))); assertFalse(q.status("local:test",config).pending()); }
    }
    @Test void incrementalDoesNotResendUnchangedChests() throws Exception {
        var a=chest("local:test"); var b=new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity("local:test","minecraft:the_nether",List.of(new Position(1,64,2))),"B",a.createdAt(),a.syncedAt(),a.items(),false);
        try(var q=queue()) { q.update(doc(a,b)); await(q.attempt("local:test",config,true)); q.update(doc(a.rename("New"),b)); assertEquals(1,await(q.attempt("local:test",config,true))); var request=new Gson().fromJson(body.get(),SyncProtocol.Request.class); assertEquals(1,request.changes().size()); assertEquals("rename",request.changes().getFirst().operation()); assertEquals(1L,request.changes().getFirst().baseServerRevision()); }
    }
    @Test void compactsOnlyUnsentAndPreservesFullPayload() throws Exception {
        var a=chest("local:test");
        try(var q=queue()) { q.update(doc(a)); var first=q.pending("local:test").getFirst(); q.update(doc(a.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",48))))); q.update(doc(a.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",48))).rename("New"))); assertEquals(1,q.pending("local:test").size()); var last=q.pending("local:test").getFirst(); assertEquals("upsert",last.operation()); assertTrue(last.revision()>first.revision()); assertEquals(48,last.data().getAsJsonArray("items").get(0).getAsJsonObject().get("count").getAsInt()); }
    }
    @Test void sentChangeFrozenWhileNewEditsWaitForCorrectBase() throws Exception {
        gate=new CountDownLatch(1); var a=chest("local:test");
        try(var q=queue()) { q.update(doc(a)); var flight=q.attempt("local:test",config,true); var frozen=q.pending("local:test").getFirst(); q.update(doc(a.rename("New"))); assertEquals(2,q.pending("local:test").size()); assertEquals(frozen,q.pending("local:test").getFirst()); assertSame(flight,q.attempt("local:test",config,true)); gate.countDown(); await(flight); assertTrue(q.status("local:test",config).pending()); await(q.attempt("local:test",config,true)); var sent=new Gson().fromJson(body.get(),SyncProtocol.Request.class).changes().getFirst(); assertEquals(1L,sent.baseServerRevision()); assertFalse(q.status("local:test",config).pending()); }
    }
    @Test void retryRepeatsSameChangeIdAndBodyAfterRestart() throws Exception {
        httpCode.set(500); var a=chest("local:test"); String sent;
        try(var q=queue()) { q.update(doc(a)); assertThrows(Exception.class,() -> await(q.attempt("local:test",config,true))); sent=body.get(); assertEquals("sent",q.pending("local:test").getFirst().status()); }
        httpCode.set(200); resultStatus.set("duplicate");
        try(var q=queue()) { await(q.attempt("local:test",config,true)); assertEquals(sent,body.get()); assertFalse(q.status("local:test",config).pending()); }
    }
    @Test void retryBackoffAndAutomaticRecovery() throws Exception {
        httpCode.set(500); var a=chest("local:test");
        try(var q=queue()) { q.update(doc(a)); assertThrows(Exception.class,() -> await(q.attempt("local:test",config,false))); await(q.attempt("local:test",config,false)); assertEquals(1,calls.get()); Thread.sleep(1100); httpCode.set(200); await(q.attempt("local:test",config,false)); assertEquals(2,calls.get()); }
    }
    @Test void conflictsAndRejectionsStopRetryingAndSurviveRestart() throws Exception {
        var a=chest("local:test"); resultStatus.set("conflict");
        try(var q=queue()) { q.update(doc(a)); assertEquals(0,await(q.attempt("local:test",config,true))); q.update(doc(a.rename("New"))); await(q.attempt("local:test",config,true)); assertEquals(1,calls.get()); assertEquals(1,q.status("local:test",config).conflicts()); }
        try(var q=queue()) { await(q.attempt("local:test",config,true)); assertEquals(1,calls.get()); assertFalse(q.status("local:test",config).diagnostics().isEmpty()); }
    }
    @Test void rejectedRemainsDiagnostic() throws Exception { resultStatus.set("rejected"); var a=chest("local:test"); try(var q=queue()) { q.update(doc(a)); await(q.attempt("local:test",config,true)); assertEquals("rejected",q.pending("local:test").getFirst().status()); await(q.attempt("local:test",config,true)); assertEquals(1,calls.get()); } }
    @Test void malformedSuccessCannotAcknowledgeAnything() throws Exception { malformed=true; var a=chest("local:test"); try(var q=queue()) { q.update(doc(a)); assertThrows(Exception.class,() -> await(q.attempt("local:test",config,true))); assertEquals(1,q.pending("local:test").size()); } }
    @Test void inventoryHoldSurvivesRestartAndAnotherChest() throws Exception {
        var a=chest("local:test");
        try(var q=queue()) { q.hold("local:test",a.id()); q.update(doc(a)); assertTrue(q.pending("local:test").isEmpty()); }
        try(var q=queue()) { q.release("local:test",UUID.randomUUID().toString()); await(q.attempt("local:test",config,true)); assertEquals(0,calls.get()); q.release("local:test",a.id()); await(q.attempt("local:test",config,true)); assertEquals(1,calls.get()); }
    }
    @Test void deleteAndStructureOperationsPreserveDependencies() throws Exception {
        var a=chest("local:test"); try(var q=queue()) { q.update(doc(a)); await(q.attempt("local:test",config,true)); q.update(doc(a.stale())); assertEquals("structure_changed",q.pending("local:test").getFirst().operation()); await(q.attempt("local:test",config,true)); q.update(doc()); assertEquals("delete",q.pending("local:test").getFirst().operation()); await(q.attempt("local:test",config,true)); assertEquals(2L,new Gson().fromJson(body.get(),SyncProtocol.Request.class).changes().getFirst().baseServerRevision()); }
    }
    @Test void timestampAloneDoesNotCreateChange() throws Exception { var a=chest("local:test"); try(var q=queue()) { q.update(doc(a)); await(q.attempt("local:test",config,true)); q.update(doc(a.snapshot(a.items()))); assertTrue(q.pending("local:test").isEmpty()); } }
    @Test void isolatedWorldsAndEndpoints() throws Exception { var a=chest("local:test"); var b=chest("local:another"); try(var q=queue()) { q.update(doc(a)); q.update(doc(b)); await(q.attempt("local:test",config,true)); assertEquals(1,q.pending("local:another").size()); config.endpoint+="/new"; q.configure(config); q.update(doc(a)); assertEquals(1,q.pending("local:test").size()); assertEquals(0L,q.pending("local:test").getFirst().baseServerRevision()==null?0:q.pending("local:test").getFirst().baseServerRevision()); } }
    @Test void automaticOffManualAvailable() throws Exception { var a=chest("local:test"); config.automatic=false; try(var q=queue()) { q.update(doc(a)); await(q.attempt("local:test",config,false)); assertEquals(0,calls.get()); await(q.attempt("local:test",config,true)); assertEquals(1,calls.get()); } }
    @Test void transientTimeoutRetainsSentChange() throws Exception { gate=new CountDownLatch(1); config.requestTimeoutSeconds=1; try(var q=queue()) { q.update(doc(chest("local:test"))); assertThrows(Exception.class,() -> await(q.attempt("local:test",config,true))); assertEquals("sent",q.pending("local:test").getFirst().status()); } }
    @Test void queueBackupRecoversDamagedPrimary() throws Exception {
        var a=chest("local:test"); try(var q=queue()) { q.update(doc(a)); q.hold("local:test",a.id()); }
        Path file; try(var paths=Files.walk(temp.resolve("incremental"))) { file=paths.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
        Files.writeString(file,"{bad"); try(var q=queue()) { assertEquals(1,q.pending("local:test").size()); } assertTrue(Files.readString(file).contains("formatVersion"));
    }
    @Test void http400IsPermanentAndNotRetried() throws Exception {
        httpCode.set(400); var a=chest("local:test"); try(var q=queue()) { q.update(doc(a)); assertEquals(0,await(q.attempt("local:test",config,true))); await(q.attempt("local:test",config,true)); assertEquals(1,calls.get()); assertEquals("rejected",q.pending("local:test").getFirst().status()); }
    }
    @Test void failedQueueWriteCanBeRetriedWithoutLosingChanges() throws Exception {
        var a=chest("local:test");
        try(var q=queue()) {
            q.update(doc(a)); Path primary;
            try(var paths=Files.walk(temp.resolve("incremental"))) { primary=paths.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow(); }
            Path backup=primary.resolveSibling(primary.getFileName()+".bak"); Files.createDirectory(backup); Files.writeString(backup.resolve("blocker"),"test");
            assertThrows(Exception.class,() -> q.update(doc(a.rename("New"))));
            Files.delete(backup.resolve("blocker")); Files.delete(backup);
            q.update(doc(a.rename("New")));
        }
        try(var q=queue()) { assertEquals("New",q.pending("local:test").getFirst().data().get("name").getAsString()); }
    }
}
