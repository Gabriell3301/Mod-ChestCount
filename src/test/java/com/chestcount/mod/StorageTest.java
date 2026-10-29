package com.chestcount.mod;

import com.chestcount.mod.model.*;
import com.chestcount.mod.service.ChestRegistryService;
import com.chestcount.mod.storage.JsonStorageService;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class StorageTest {
    @TempDir Path temp;
    List<ChestRecord> data() { var s=new ChestRegistryService(); s.register(new ChestIdentity("world","minecraft:overworld",List.of(new Position(1,64,2)))); return s.all(); }
    @Test void queuedImmutableSnapshotsWriteNewestLast() throws Exception {
        var registry=new ChestRegistryService(); registry.load(data());
        var r=registry.all().getFirst();
        var storage=new JsonStorageService(temp);
        var gate=new java.util.concurrent.CountDownLatch(1);
        var worker=java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var old=registry.all();
            var first=worker.submit(() -> { gate.await(); storage.save("world",old); return null; });
            registry.snapshot(r.id(),r.identity(),List.of(new ItemRecord(0,"minecraft:diamond",42)));
            var latest=registry.all();
            var second=worker.submit(() -> { storage.save("world",latest); return null; });
            registry.rename(r.id(),"world","Changed after enqueue");
            gate.countDown(); first.get(5,java.util.concurrent.TimeUnit.SECONDS); second.get(5,java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(latest,storage.load("world"));
            assertEquals(42,storage.load("world").getFirst().total());
            assertEquals(0,old.getFirst().total());
            assertTrue(Files.exists(storage.file("world").resolveSibling(storage.file("world").getFileName()+".bak")));
        } finally { gate.countDown(); worker.shutdownNow(); }
    }
    @Test void jsonRoundTrip() throws Exception { var s=new JsonStorageService(temp); var data=data(); s.save("world",data); assertEquals(data,s.load("world")); }
    @Test void recoveryFromBackupPreservesCorruptFile() throws Exception { var s=new JsonStorageService(temp); var data=data(); s.save("world",data); s.save("world",List.of()); Files.writeString(s.file("world"),"{broken"); assertEquals(data,s.load("world")); try(var files=Files.list(temp.resolve("backups"))) { assertEquals(1,files.count()); } }
    @Test void unrecoverableFileCannotBeOverwritten() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); Files.writeString(s.file("world"),"{broken"); assertThrows(java.io.IOException.class,() -> s.load("world")); assertThrows(java.io.IOException.class,() -> s.save("world",List.of())); assertEquals("{broken",Files.readString(s.file("world"))); }
    @Test void separateWorldFiles() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); assertTrue(s.load("another").isEmpty()); assertNotEquals(s.file("world"),s.file("another")); }
    @Test void futureFormatIsPreserved() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); var text=Files.readString(s.file("world")).replace("\"formatVersion\": 1","\"formatVersion\": 99"); Files.writeString(s.file("world"),text); assertThrows(java.io.IOException.class,() -> s.load("world")); assertEquals(text,Files.readString(s.file("world"))); }
    @Test void externalCorruptionBlocksSave() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); Files.writeString(s.file("world"),"null"); assertThrows(java.io.IOException.class,() -> s.save("world",List.of())); assertEquals("null",Files.readString(s.file("world"))); }
    @Test void futureVersionDoesNotFallBackToOldBackup() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); s.save("world",data()); var text=Files.readString(s.file("world")).replace("\"formatVersion\": 1","\"formatVersion\": 2"); Files.writeString(s.file("world"),text); assertThrows(java.io.IOException.class,() -> s.load("world")); assertEquals(text,Files.readString(s.file("world"))); }
}
