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
    @Test void jsonRoundTrip() throws Exception { var s=new JsonStorageService(temp); var data=data(); s.save("world",data); assertEquals(data,s.load("world")); }
    @Test void recoveryFromBackupPreservesCorruptFile() throws Exception { var s=new JsonStorageService(temp); var data=data(); s.save("world",data); s.save("world",List.of()); Files.writeString(s.file("world"),"{broken"); assertEquals(data,s.load("world")); try(var files=Files.list(temp.resolve("backups"))) { assertEquals(1,files.count()); } }
    @Test void unrecoverableFileCannotBeOverwritten() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); Files.writeString(s.file("world"),"{broken"); assertThrows(java.io.IOException.class,() -> s.load("world")); assertThrows(java.io.IOException.class,() -> s.save("world",List.of())); assertEquals("{broken",Files.readString(s.file("world"))); }
    @Test void separateWorldFiles() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); assertTrue(s.load("another").isEmpty()); assertNotEquals(s.file("world"),s.file("another")); }
    @Test void futureFormatIsPreserved() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); var text=Files.readString(s.file("world")).replace("\"formatVersion\": 1","\"formatVersion\": 99"); Files.writeString(s.file("world"),text); assertThrows(java.io.IOException.class,() -> s.load("world")); assertEquals(text,Files.readString(s.file("world"))); }
    @Test void externalCorruptionBlocksSave() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); Files.writeString(s.file("world"),"null"); assertThrows(java.io.IOException.class,() -> s.save("world",List.of())); assertEquals("null",Files.readString(s.file("world"))); }
    @Test void futureVersionDoesNotFallBackToOldBackup() throws Exception { var s=new JsonStorageService(temp); s.save("world",data()); s.save("world",data()); var text=Files.readString(s.file("world")).replace("\"formatVersion\": 1","\"formatVersion\": 2"); Files.writeString(s.file("world"),text); assertThrows(java.io.IOException.class,() -> s.load("world")); assertEquals(text,Files.readString(s.file("world"))); }
}
