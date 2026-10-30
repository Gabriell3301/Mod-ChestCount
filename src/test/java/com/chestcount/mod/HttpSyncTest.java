package com.chestcount.mod;

import com.chestcount.mod.integration.*;
import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.model.*;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class HttpSyncTest {
    @TempDir Path temp;
    HttpServer server;
    ModConfig.Sync config;
    AtomicInteger code=new AtomicInteger(200), calls=new AtomicInteger();
    AtomicReference<String> body=new AtomicReference<>(), contentType=new AtomicReference<>();
    volatile CountDownLatch gate;
    @BeforeEach void start() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/api/sync",exchange -> {
            calls.incrementAndGet(); body.set(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            try { if(gate!=null) gate.await(4,TimeUnit.SECONDS); exchange.sendResponseHeaders(code.get(),-1); }
            catch(InterruptedException e) { Thread.currentThread().interrupt(); } finally { exchange.close(); }
        }); server.start();
        config=new ModConfig.Sync(); config.endpoint="http://127.0.0.1:"+server.getAddress().getPort()+"/api/sync";
    }
    @AfterEach void stop() { if(gate!=null) gate.countDown(); server.stop(0); }
    JsonStorageService.Document document(String name) {
        var record=new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity("local:test","minecraft:overworld",List.of(new Position(100,64,200))),name,
            Instant.now().toString(),Instant.now().toString(),List.of(new ItemRecord(0,"minecraft:diamond",32)),true);
        return new JsonStorageService.Document(1,"local:test",List.of(record));
    }
    <T> T await(CompletionStage<T> future) throws Exception { return future.toCompletableFuture().get(6,TimeUnit.SECONDS); }
    @Test void serializesRealModelAndPosts200() throws Exception {
        var doc=document("Minérios"); await(new HttpSyncProvider(config).send(new Gson().toJson(doc)));
        assertEquals(doc,new Gson().fromJson(body.get(),JsonStorageService.Document.class));
        assertEquals("application/json; charset=utf-8",contentType.get()); assertEquals(1,calls.get());
    }
    @Test void rejects400And500() { for(int status:List.of(400,500)) { code.set(status); assertThrows(Exception.class,() -> await(new HttpSyncProvider(config).send("{\"chests\":[]}"))); } }
    @Test void unavailableServer() { server.stop(0); assertThrows(Exception.class,() -> await(new HttpSyncProvider(config).send("{}"))); }
    @Test void requestTimeout() { gate=new CountDownLatch(1); config.requestTimeoutSeconds=1; assertThrows(Exception.class,() -> await(new HttpSyncProvider(config).send("{}"))); }
    @Test void rejectsUnsafeUrlsAndCredentials() {
        for(String value:List.of("http://example.com/api","ftp://localhost/api","file:///tmp/test","https://user:secret@example.com/api","https://example.com/api?token=secret"))
            assertThrows(IllegalArgumentException.class,() -> HttpSyncProvider.endpoint(value));
        assertEquals("https",HttpSyncProvider.endpoint("https://api.chestcount.pt/v1/sync").getScheme());
    }
    @Test void sizeLimit() { assertThrows(Exception.class,() -> await(new HttpSyncProvider(config).send("x".repeat(HttpSyncProvider.MAX_BYTES+1)))); assertEquals(0,calls.get()); }
    @Test void persistsPendingAndAcknowledgementAcrossRestart() throws Exception {
        var doc=document("A");
        try(var queue=new HttpSyncQueue(temp)) { queue.update(doc); assertTrue(queue.status(doc.world(),config).pending()); }
        try(var queue=new HttpSyncQueue(temp)) { queue.update(doc); assertTrue(queue.status(doc.world(),config).pending()); assertEquals(1,await(queue.attempt(doc.world(),config,true))); assertFalse(queue.status(doc.world(),config).pending()); }
        try(var queue=new HttpSyncQueue(temp)) { queue.update(doc); assertFalse(queue.status(doc.world(),config).pending()); config.endpoint+="/new"; assertTrue(queue.status(doc.world(),config).pending()); }
    }
    @Test void oldResponseDoesNotAcknowledgeNewDocument() throws Exception {
        gate=new CountDownLatch(1); var old=document("A"); var newer=document("B");
        try(var queue=new HttpSyncQueue(temp)) {
            queue.update(old); var flight=queue.attempt(old.world(),config,true);
            queue.update(newer); gate.countDown(); await(flight);
            assertTrue(queue.status(old.world(),config).pending()); await(queue.attempt(old.world(),config,true)); assertFalse(queue.status(old.world(),config).pending());
        }
    }
    @Test void retryKeepsPendingAndBacksOff() throws Exception {
        code.set(500); config.intervalSeconds=1; var doc=document("A");
        try(var queue=new HttpSyncQueue(temp)) {
            queue.update(doc); assertThrows(Exception.class,() -> await(queue.attempt(doc.world(),config,true)));
            assertTrue(queue.status(doc.world(),config).pending()); assertTrue(queue.status(doc.world(),config).offline());
            await(queue.attempt(doc.world(),config,false)); assertEquals(1,calls.get());
            code.set(200); Thread.sleep(5100); await(queue.attempt(doc.world(),config,false)); assertFalse(queue.status(doc.world(),config).offline()); assertFalse(queue.status(doc.world(),config).pending());
        }
    }
    @Test void automaticOffStillAllowsManual() throws Exception {
        var doc=document("A"); config.automatic=false; config.enabled=false;
        try(var queue=new HttpSyncQueue(temp)) { queue.update(doc); await(queue.attempt(doc.world(),config,false)); assertEquals(0,calls.get()); await(queue.attempt(doc.world(),config,true)); assertEquals(1,calls.get()); }
    }
    @Test void automaticDebounceThenSend() throws Exception {
        var doc=document("A");
        try(var queue=new HttpSyncQueue(temp)) { queue.update(doc); await(queue.attempt(doc.world(),config,false)); assertEquals(0,calls.get()); Thread.sleep(2100); await(queue.attempt(doc.world(),config,false)); assertEquals(1,calls.get()); }
    }
    @Test void unresolvedConfirmationsRemainHeldAfterRestart() throws Exception {
        var doc=document("A"); try(var queue=new HttpSyncQueue(temp)) { queue.update(doc,true); }
        try(var queue=new HttpSyncQueue(temp)) { queue.update(doc); assertThrows(Exception.class,() -> await(queue.attempt(doc.world(),config,true))); assertEquals(0,calls.get()); queue.release(doc.world()); await(queue.attempt(doc.world(),config,true)); }
    }
    @Test void configurableUrlAndOldConfig() throws Exception {
        Path file=temp.resolve("config.json"); Files.writeString(file,"{\"formatVersion\":1,\"outlineRadius\":40}");
        var loaded=ModConfig.load(file); assertEquals(40,loaded.outlineRadius); assertNotNull(loaded.sync);
        loaded.sync.endpoint=config.endpoint; loaded.sync.automatic=false; loaded.save(file);
        var reloaded=ModConfig.load(file); assertEquals(config.endpoint,reloaded.sync.endpoint); assertFalse(reloaded.sync.automatic);
    }
    @Test void confirmingAnotherChestDoesNotReleaseUnresolvedChest() throws Exception {
        var a=document("A").chests().getFirst(); var b=document("B").chests().getFirst();
        var doc=new JsonStorageService.Document(1,a.identity().world(),List.of(a,b));
        try(var queue=new HttpSyncQueue(temp)) {
            queue.update(doc); queue.hold(doc.world(),a.id()); queue.hold(doc.world(),b.id());
            queue.release(doc.world(),b.id());
            assertThrows(Exception.class,() -> await(queue.attempt(doc.world(),config,true)));
            queue.release(doc.world(),a.id()); await(queue.attempt(doc.world(),config,true)); assertEquals(1,calls.get());
        }
    }
}
