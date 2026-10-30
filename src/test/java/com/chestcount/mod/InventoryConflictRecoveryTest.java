package com.chestcount.mod;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.integration.*;
import com.chestcount.mod.model.*;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.*;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP requests against a revision-aware temporary server; no Minecraft inventory writes. */
class InventoryConflictRecoveryTest {
    static final String WORLD="local:inventory-recovery";
    static final Gson GSON=new Gson();
    @TempDir Path root;
    HttpServer server;
    ExecutorService httpWorker;
    ModConfig.Sync config;
    JsonStorageService storage;
    final Map<String,ChestRecord> remote=new ConcurrentHashMap<>();
    final Map<String,Long> revisions=new ConcurrentHashMap<>();
    final Set<String> deleted=ConcurrentHashMap.newKeySet();
    final Map<String,SyncProtocol.Result> receipts=new ConcurrentHashMap<>();
    final List<SyncProtocol.Change> posts=new CopyOnWriteArrayList<>();
    final AtomicInteger gets=new AtomicInteger(), getCode=new AtomicInteger(200);
    volatile CountDownLatch getGate;
    CountDownLatch entered=new CountDownLatch(1);
    volatile boolean malformed;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        httpWorker=Executors.newCachedThreadPool(r -> { var t=new Thread(r,"recovery test HTTP"); t.setDaemon(true); return t; }); server.setExecutor(httpWorker);
        server.createContext("/api/v1/sync",e -> {
            var request=GSON.fromJson(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),SyncProtocol.Request.class);
            var results=new ArrayList<SyncProtocol.Result>();
            synchronized(remote) {
                for(var c:request.changes()) {
                    posts.add(c); var prior=receipts.get(c.changeId());
                    if(prior!=null) { results.add(new SyncProtocol.Result(c.changeId(),c.chestId(),c.revision(),"duplicate",prior.serverRevision(),null)); continue; }
                    long rev=revisions.getOrDefault(c.chestId(),0L);
                    if(c.baseServerRevision()!=rev || deleted.contains(c.chestId())) {
                        results.add(new SyncProtocol.Result(c.changeId(),c.chestId(),c.revision(),"conflict",rev,null)); continue;
                    }
                    switch(c.operation()) {
                        case "upsert" -> remote.put(c.chestId(),GSON.fromJson(c.data(),ChestRecord.class));
                        case "rename" -> remote.put(c.chestId(),remote.get(c.chestId()).rename(c.data().get("name").getAsString()));
                        case "delete" -> deleted.add(c.chestId());
                        case "structure_changed" -> remote.put(c.chestId(),remote.get(c.chestId()).stale());
                        default -> throw new AssertionError(c.operation());
                    }
                    revisions.put(c.chestId(),rev+1);
                    var result=new SyncProtocol.Result(c.changeId(),c.chestId(),c.revision(),"applied",rev+1,null); receipts.put(c.changeId(),result); results.add(result);
                }
            }
            reply(e,200,GSON.toJson(new SyncProtocol.Response(true,1,results)));
        });
        server.createContext("/api/v1/worlds/",e -> {
            gets.incrementAndGet(); String path=e.getRequestURI().getPath(); String id=path.substring(path.lastIndexOf('/')+1);
            String body;
            synchronized(remote) {
                var value=remote.get(id); var json=value==null?new JsonObject():GSON.toJsonTree(value).getAsJsonObject();
                json.addProperty("deleted",deleted.contains(id)); json.addProperty("serverRevision",revisions.getOrDefault(id,0L)); body=malformed?"{}":GSON.toJson(json);
            }
            entered.countDown();
            try { if(getGate!=null) getGate.await(5,TimeUnit.SECONDS); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); }
            reply(e,getCode.get(),body);
        });
        server.start(); config=new ModConfig.Sync(); config.endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/sync"; config.debounceSeconds=0;
        storage=new JsonStorageService(root);
    }
    static void reply(HttpExchange e,int code,String text) throws java.io.IOException {
        byte[] bytes=text.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { e.sendResponseHeaders(code,bytes.length); e.getResponseBody().write(bytes); } catch(java.io.IOException ignored) {} finally { e.close(); }
    }
    @AfterEach void stop() { if(getGate!=null) getGate.countDown(); server.stop(0); httpWorker.shutdownNow(); }
    IncrementalSyncQueue queue() { var q=new IncrementalSyncQueue(root); q.configure(config); return q; }
    ChestRecord chest() { return new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity(WORLD,"minecraft:overworld",List.of(new Position(1,64,2))),"Minerios",Instant.now().toString(),Instant.now().toString(),List.of(new ItemRecord(0,"minecraft:diamond",32)),false); }
    ChestRecord inventory(ChestRecord chest,int count) { return chest.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",count))); }
    void persist(IncrementalSyncQueue q,ChestRecord... records) throws Exception { storage.save(WORLD,List.of(records)); q.update(new JsonStorageService.Document(1,WORLD,storage.load(WORLD))); }
    <T> T await(CompletionStage<T> stage) throws Exception { return stage.toCompletableFuture().get(7,TimeUnit.SECONDS); }
    ChestRecord conflict(IncrementalSyncQueue q) throws Exception {
        var a=chest(); persist(q,a); assertEquals(1,await(q.attempt(WORLD,config,true)));
        remote.put(a.id(),inventory(a,7)); revisions.put(a.id(),2L);
        a=inventory(a,48); persist(q,a); assertEquals(0,await(q.attempt(WORLD,config,true))); assertEquals(1,q.status(WORLD,config).conflicts()); return a;
    }
    CompletionStage<Boolean> finalize(IncrementalSyncQueue q,ChestRecord fresh) throws Exception {
        persist(q,fresh); q.release(WORLD,fresh.id()); return q.recoverInventory(WORLD,fresh);
    }
    @Test void reopenConfirmedSnapshotRebasesWithNewIdAndClearsOnlyAfterAcceptance() throws Exception {
        try(var q=queue()) {
            var a=conflict(q); String oldId=q.pending(WORLD).getFirst().changeId();
            q.opened(WORLD,a.id()); var fresh=inventory(a,96); assertTrue(await(finalize(q,fresh)));
            assertEquals(fresh,storage.load(WORLD).getFirst());
            var replacement=q.pending(WORLD).getLast(); assertNotEquals(oldId,replacement.changeId()); assertEquals(2L,replacement.baseServerRevision()); assertEquals(1,q.status(WORLD,config).conflicts());
            assertEquals(1,await(q.attempt(WORLD,config,true))); assertEquals(96,remote.get(a.id()).total()); assertFalse(q.status(WORLD,config).pending()); assertEquals(0,q.status(WORLD,config).conflicts()); assertTrue(q.status(WORLD,config).diagnostics().isEmpty());
        }
    }
    @Test void otherChestsKeepSyncingAndConflictDoesNotLoop() throws Exception {
        try(var q=queue()) {
            var a=conflict(q); int before=posts.size(); var b=chest();
            // Separate identity, same world.
            b=new ChestRecord(b.id(),new ChestIdentity(WORLD,"minecraft:the_nether",b.identity().blocks()),b.name(),b.createdAt(),b.syncedAt(),b.items(),false);
            persist(q,a,b); assertEquals(1,await(q.attempt(WORLD,config,true))); assertEquals(before+1,posts.size()); assertEquals(b.id(),posts.getLast().chestId());
            for(int i=0;i<4;i++) await(q.attempt(WORLD,config,true)); assertEquals(before+1,posts.size()); assertEquals(0,gets.get()); assertEquals(1,q.status(WORLD,config).conflicts());
        }
    }
    @Test void ordinaryReleaseOrUnpersistedSnapshotCannotTriggerRecovery() throws Exception {
        try(var q=queue()) {
            var a=conflict(q); var fresh=inventory(a,96); q.hold(WORLD,a.id()); assertFalse(await(finalize(q,fresh))); assertEquals(0,gets.get());
            q.opened(WORLD,a.id()); q.release(WORLD,a.id()); assertFalse(await(q.recoverInventory(WORLD,inventory(a,100)))); assertEquals(0,gets.get());
        }
    }
    @Test void lateAuthoritativeConfirmationDuringLookupUsesNewestPersistedSnapshot() throws Exception {
        getGate=new CountDownLatch(1);
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); var first=finalize(q,inventory(a,64)); assertTrue(entered.await(3,TimeUnit.SECONDS));
            var latest=inventory(a,96); assertSame(first,finalize(q,latest)); getGate.countDown(); assertTrue(await(first)); assertEquals(1,gets.get());
            await(q.attempt(WORLD,config,true)); assertEquals(96,remote.get(a.id()).total());
        }
    }
    @Test void anotherConflictRequiresAnotherOpeningAndDoesNotOverwriteAnything() throws Exception {
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); var fresh=inventory(a,96); assertTrue(await(finalize(q,fresh)));
            String replacement=q.pending(WORLD).getLast().changeId(); remote.put(a.id(),inventory(a,11)); revisions.put(a.id(),3L);
            assertEquals(0,await(q.attempt(WORLD,config,true))); int count=posts.size();
            assertTrue(await(q.recoverInventory(WORLD,fresh))); // Same completed lookup, not another request.
            await(q.attempt(WORLD,config,true)); assertEquals(count,posts.size()); assertEquals(1,gets.get()); assertEquals(11,remote.get(a.id()).total()); assertTrue(q.status(WORLD,config).pending());
            q.opened(WORLD,a.id()); assertTrue(await(finalize(q,inventory(a,100)))); assertNotEquals(replacement,q.pending(WORLD).getLast().changeId()); await(q.attempt(WORLD,config,true)); assertEquals(100,remote.get(a.id()).total()); assertEquals(0,q.status(WORLD,config).conflicts());
        }
    }
    void protectedRemote(String field) throws Exception {
        try(var q=queue()) {
            var a=conflict(q);
            switch(field) {
                case "name" -> remote.put(a.id(),remote.get(a.id()).rename("Remote name"));
                case "deleted" -> deleted.add(a.id());
                case "structure" -> remote.put(a.id(),remote.get(a.id()).stale());
                case "identity" -> remote.put(a.id(),new ChestRecord(a.id(),new ChestIdentity("local:foreign","minecraft:overworld",a.identity().blocks()),a.name(),a.createdAt(),a.syncedAt(),a.items(),false));
                default -> throw new AssertionError();
            }
            q.opened(WORLD,a.id()); int count=posts.size(); assertFalse(await(finalize(q,inventory(a,96)))); await(q.attempt(WORLD,config,true)); assertEquals(count,posts.size()); assertEquals(1,q.status(WORLD,config).conflicts());
        }
    }
    @Test void remoteRenameIsNeverOverwritten() throws Exception { protectedRemote("name"); }
    @Test void tombstoneIsNeverResurrected() throws Exception { protectedRemote("deleted"); }
    @Test void remoteStructuralFlagIsNeverCleared() throws Exception { protectedRemote("structure"); }
    @Test void foreignIdentityIsNeverAccepted() throws Exception { protectedRemote("identity"); }
    @Test void localRenameIsNotRecoveredAsInventory() throws Exception {
        try(var q=queue()) { var a=conflict(q); q.opened(WORLD,a.id()); assertFalse(await(finalize(q,inventory(a,96).rename("Local new name")))); assertEquals(0,gets.get()); assertEquals("Minerios",remote.get(a.id()).name()); }
    }
    @Test void localStructureIsNotRecoveredAsInventory() throws Exception {
        try(var q=queue()) { var a=conflict(q); q.opened(WORLD,a.id()); assertFalse(await(finalize(q,inventory(a,96).stale()))); assertEquals(0,gets.get()); }
    }
    @Test void renameDeleteAndStructureConflictsRequireExplicitResolution() throws Exception {
        for(String op:List.of("rename","delete","structure_changed")) {
            // Each iteration uses a fresh local world directory to keep queue histories separate.
            try(var q=new IncrementalSyncQueue(root.resolve(op))) {
                q.configure(config); var a=chest(); q.update(new JsonStorageService.Document(1,WORLD,List.of(a))); await(q.attempt(WORLD,config,true)); revisions.put(a.id(),2L);
                var changed=op.equals("rename")?a.rename("New"):a.stale();
                q.update(new JsonStorageService.Document(1,WORLD,op.equals("delete")?List.of():List.of(changed))); await(q.attempt(WORLD,config,true));
                assertEquals(op,q.pending(WORLD).getFirst().operation()); q.opened(WORLD,a.id()); q.update(new JsonStorageService.Document(1,WORLD,List.of(a))); q.release(WORLD,a.id()); assertFalse(await(q.recoverInventory(WORLD,a)));
            }
        } assertEquals(0,gets.get());
    }
    @Test void lookupFailureIsOneAttemptPerOpeningAndConflictSurvives() throws Exception {
        getCode.set(500);
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); var fresh=inventory(a,96); assertFalse(await(finalize(q,fresh)));
            for(int i=0;i<3;i++) { assertFalse(await(q.recoverInventory(WORLD,fresh))); await(q.attempt(WORLD,config,true)); }
            assertEquals(1,gets.get()); assertEquals(1,q.status(WORLD,config).conflicts());
            getCode.set(200); q.opened(WORLD,a.id()); assertTrue(await(finalize(q,inventory(fresh,96)))); await(q.attempt(WORLD,config,true)); assertEquals(0,q.status(WORLD,config).conflicts());
        }
    }
    @Test void malformedLookupCannotRebase() throws Exception { malformed=true; try(var q=queue()) { var a=conflict(q); q.opened(WORLD,a.id()); assertFalse(await(finalize(q,inventory(a,96)))); assertEquals(1,q.pending(WORLD).size()); } }
    @Test void timeoutDoesNotFreezeOtherChests() throws Exception {
        getGate=new CountDownLatch(1); config.requestTimeoutSeconds=1;
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); var lookup=finalize(q,inventory(a,96)); assertTrue(entered.await(3,TimeUnit.SECONDS));
            var b=chest(); b=new ChestRecord(b.id(),new ChestIdentity(WORLD,"minecraft:the_nether",b.identity().blocks()),b.name(),b.createdAt(),b.syncedAt(),b.items(),false);
            persist(q,inventory(a,96),b); assertEquals(1,await(q.attempt(WORLD,config,true))); assertFalse(await(lookup)); assertEquals(1,q.status(WORLD,config).conflicts());
        }
    }
    @Test void preparedRecoverySurvivesRestartAndClearsOldConflictOnAck() throws Exception {
        ChestRecord fresh;
        try(var q=queue()) { var a=conflict(q); q.opened(WORLD,a.id()); fresh=inventory(a,96); assertTrue(await(finalize(q,fresh))); }
        try(var q=queue()) { persist(q,fresh); assertEquals(1,await(q.attempt(WORLD,config,true))); assertEquals(0,q.status(WORLD,config).conflicts()); assertTrue(q.status(WORLD,config).diagnostics().isEmpty()); } assertEquals(1,gets.get());
    }
    @Test void serverRenameAfterLookupCausesConflictInsteadOfOverwrite() throws Exception {
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); assertTrue(await(finalize(q,inventory(a,96)))); remote.put(a.id(),remote.get(a.id()).rename("New remote name")); revisions.put(a.id(),3L);
            assertEquals(0,await(q.attempt(WORLD,config,true))); assertEquals("New remote name",remote.get(a.id()).name()); assertTrue(q.status(WORLD,config).pending());
        }
    }
    @Test void newerEditsAfterRecoveryPreparationRemainPendingAfterOldAck() throws Exception {
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); assertTrue(await(finalize(q,inventory(a,64)))); persist(q,inventory(a,96));
            await(q.attempt(WORLD,config,true)); assertEquals(64,remote.get(a.id()).total()); assertEquals(1,q.pending(WORLD).size());
            await(q.attempt(WORLD,config,true)); assertEquals(96,remote.get(a.id()).total()); assertFalse(q.status(WORLD,config).pending());
        }
    }
    @Test void reopeningWithoutANewAuthoritativeSnapshotDoesNotRecover() throws Exception {
        try(var q=queue()) { var a=conflict(q); q.opened(WORLD,a.id()); q.release(WORLD,a.id()); assertFalse(await(q.recoverInventory(WORLD,a))); assertEquals(0,gets.get()); }
    }
    @Test void newConfirmationWithUnchangedItemsStillRecovers() throws Exception {
        try(var q=queue()) { var a=conflict(q); q.opened(WORLD,a.id()); assertTrue(await(finalize(q,inventory(a,48)))); await(q.attempt(WORLD,config,true)); assertEquals(48,remote.get(a.id()).total()); assertEquals(0,q.status(WORLD,config).conflicts()); }
    }
    @Test void openingBeforeConflictDoesNotAuthorizeRecoveryAfterwards() throws Exception {
        try(var q=queue()) {
            var a=chest(); persist(q,a); await(q.attempt(WORLD,config,true)); q.opened(WORLD,a.id());
            revisions.put(a.id(),2L); var fresh=inventory(a,48); assertFalse(await(finalize(q,fresh))); await(q.attempt(WORLD,config,true));
            assertFalse(await(finalize(q,inventory(a,96)))); assertEquals(0,gets.get());
            q.opened(WORLD,a.id()); assertTrue(await(finalize(q,inventory(a,100))));
        }
    }
    @Test void manualAttemptWaitsForLookupWithoutBlockingThread() throws Exception {
        getGate=new CountDownLatch(1); config.automatic=false;
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); assertFalse(await(finalize(q,inventory(a,96)))); assertEquals(0,gets.get());
            var send=q.attempt(WORLD,config,true); assertTrue(entered.await(3,TimeUnit.SECONDS)); assertFalse(send.toCompletableFuture().isDone()); getGate.countDown();
            assertEquals(1,await(send)); assertEquals(0,q.status(WORLD,config).conflicts());
        }
    }
    @Test void disabledAutomaticSyncDefersLookupUntilEnabled() throws Exception {
        config.automatic=false;
        try(var q=queue()) {
            var a=conflict(q); q.opened(WORLD,a.id()); assertFalse(await(finalize(q,inventory(a,96))));
            await(q.attempt(WORLD,config,false)); assertEquals(0,gets.get());
            config.automatic=true; await(q.attempt(WORLD,config,false)); assertTrue(entered.await(3,TimeUnit.SECONDS));
            await(q.attempt(WORLD,config,true)); assertEquals(0,q.status(WORLD,config).conflicts());
        }
    }
}
