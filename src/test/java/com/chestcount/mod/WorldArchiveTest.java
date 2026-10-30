package com.chestcount.mod;
import com.chestcount.mod.storage.*;
import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.model.*;
import com.google.gson.Gson;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WorldArchiveTest {
    @TempDir Path root;
    JsonStorageService.Document doc() { return new JsonStorageService.Document(1,"local:test",List.of(new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity("local:test","minecraft:overworld",List.of(new Position(1,64,2))),"A",Instant.now().toString(),null,List.of(),false))); }
    @Test void automaticFiveMinutesOnlyChangedAndRetentionPreservesManual() throws Exception {
        var service=new WorldArchiveService(root); var config=new ModConfig.Backup(); config.maxAutomaticBackupsPerWorld=2; var doc=doc();
        Path manual=service.backup(doc); assertTrue(service.automatic(doc,config,0).isEmpty()); assertTrue(service.automatic(doc,config,299999).isEmpty()); assertTrue(service.automatic(doc,config,300000).isPresent()); assertTrue(service.automatic(doc,config,600000).isEmpty());
        for(int i=2;i<6;i++) { doc=new JsonStorageService.Document(1,doc.world(),List.of(doc.chests().getFirst().rename("A"+i))); service.automatic(doc,config,i*300000L); }
        assertEquals(3,service.list(doc.world()).size()); assertTrue(Files.exists(manual));
    }
    @Test void backupAndExportRoundTripAndRestart() throws Exception { var doc=doc(); var service=new WorldArchiveService(root); var path=service.export(doc); assertEquals(doc,JsonStorageService.parse(Files.readString(path),doc.world())); service.backup(doc); assertEquals(1,new WorldArchiveService(root).list(doc.world()).size()); }
    void writeImport(String file,JsonStorageService.Document doc) throws Exception { Files.createDirectories(root.resolve("imports")); Files.writeString(root.resolve("imports").resolve(file),new Gson().toJson(doc)); }
    @Test void importPreviewRequiresConfirmationAndCreatesBackup() throws Exception {
        var doc=doc(); var changed=new JsonStorageService.Document(1,doc.world(),List.of(doc.chests().getFirst().rename("New"))); writeImport("world.json",changed);
        var storage=new JsonStorageService(root); storage.save(doc.world(),doc.chests()); var service=new WorldArchiveService(root); var preview=service.preview("world.json",doc);
        assertEquals(1,preview.replaced()); assertThrows(Exception.class,() -> service.apply("world.json",doc,null,storage)); assertEquals(doc.chests(),storage.load(doc.world()));
        service.apply("world.json",doc,preview.fingerprint(),storage); assertEquals(changed.chests(),storage.load(doc.world())); assertEquals(1,service.list(doc.world()).size());
    }
    @Test void changedFileOrCurrentWorldInvalidatesConfirmation() throws Exception {
        var doc=doc(); writeImport("world.json",doc); var service=new WorldArchiveService(root); var preview=service.preview("world.json",doc); writeImport("world.json",new JsonStorageService.Document(1,doc.world(),List.of(doc.chests().getFirst().rename("Altered")))); assertThrows(Exception.class,() -> service.apply("world.json",doc,preview.fingerprint(),new JsonStorageService(root)));
    }
    @Test void rejectsForeignWorldDuplicatesOverlapAndTraversal() throws Exception {
        var doc=doc(); var service=new WorldArchiveService(root);
        writeImport("foreign.json",new JsonStorageService.Document(1,"foreign",List.of())); assertThrows(Exception.class,() -> service.preview("foreign.json",doc));
        writeImport("duplicate.json",new JsonStorageService.Document(1,doc.world(),List.of(doc.chests().getFirst(),doc.chests().getFirst()))); assertThrows(Exception.class,() -> service.preview("duplicate.json",doc));
        for(var file:List.of("../test.json","C:/test.json","..\\world.json")) assertThrows(Exception.class,() -> service.preview(file,doc));
        var other=doc(); writeImport("overlap.json",other); assertThrows(Exception.class,() -> service.preview("overlap.json",doc));
    }
    @Test void configBackwardsCompatibleAndValidatesNewOptions() throws Exception { Path path=root.resolve("config.json"); Files.writeString(path,"{\"formatVersion\":1,\"sync\":{\"endpoint\":\"http://127.0.0.1:8080/api/sync\"}}"); var config=ModConfig.load(path); assertTrue(config.sync.endpoint.endsWith("/api/v1/sync")); assertEquals(5,config.backup.intervalMinutes); config.backup.maxAutomaticBackupsPerWorld=0; assertThrows(IllegalArgumentException.class,config.backup::validate); }
    @Test void rejectsNonIntegralOrInvalidCoordinatesOnImport() throws Exception {
        var doc=doc(); String json=new Gson().toJson(doc);
        assertThrows(Exception.class,() -> JsonStorageService.parse(json.replace("\"x\":1","\"x\":1.5"),doc.world()));
        assertThrows(Exception.class,() -> JsonStorageService.parse(json.replace("\"x\":1","\"x\":30000001"),doc.world()));
    }
}
