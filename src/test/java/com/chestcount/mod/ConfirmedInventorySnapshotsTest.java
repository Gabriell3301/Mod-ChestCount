package com.chestcount.mod;
import com.chestcount.mod.service.ConfirmedInventorySnapshots;
import com.chestcount.mod.model.*;
import com.chestcount.mod.storage.JsonStorageService;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import java.io.IOException;
import static org.junit.jupiter.api.Assertions.*;
class ConfirmedInventorySnapshotsTest {
    ChestRecord chest() { return new ChestRecord(UUID.randomUUID().toString(),new ChestIdentity("local:test","minecraft:overworld",List.of(new Position(1,64,2))),"A",Instant.now().toString(),Instant.now().toString(),List.of(new ItemRecord(0,"minecraft:diamond",32)),false); }
    JsonStorageService.Document doc(ChestRecord value) { return new JsonStorageService.Document(1,value.identity().world(),List.of(value)); }
    @Test void waitsForExactSuccessfulWriteAndFinalizesOnce() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); var old=chest(); var fresh=old.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",48))); var events=new ArrayList<ChestRecord>();
        pending.confirmed(fresh); pending.persisted(doc(old),events::add); assertTrue(events.isEmpty());
        pending.persisted(doc(fresh),events::add); pending.persisted(doc(fresh),events::add); assertEquals(List.of(fresh),events);
    }
    @Test void failedOutboxFinalizationCanRetryAfterPersistence() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); var fresh=chest(); pending.confirmed(fresh);
        assertThrows(IOException.class,() -> pending.persisted(doc(fresh),r -> { throw new IOException("Injected outbox error"); }));
        var events=new ArrayList<ChestRecord>(); pending.persisted(doc(fresh),events::add); assertEquals(List.of(fresh),events);
    }
    @Test void newOpeningOrClickInvalidatesOldFinalization() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); var fresh=chest(); pending.confirmed(fresh); pending.invalidate(fresh.identity().world(),fresh.id());
        pending.persisted(doc(fresh),r -> fail("Old session must not finalize a new opening"));
    }
    @Test void persistedImportsDoNotCountAsAuthoritativeReopening() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); pending.persisted(doc(chest()),r -> fail("No authoritative confirmation"));
    }
    @Test void lateConfirmationSupersedesOlderWriteProof() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); var old=chest(); var latest=old.snapshot(List.of(new ItemRecord(0,"minecraft:diamond",48))); var events=new ArrayList<ChestRecord>();
        pending.confirmed(old); pending.confirmed(latest); pending.persisted(doc(old),events::add); assertTrue(events.isEmpty()); pending.persisted(doc(latest),events::add); assertEquals(List.of(latest),events);
    }
    @Test void worldWritesCannotFinalizeAnotherWorld() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); var fresh=chest(); pending.confirmed(fresh);
        var other=new ChestRecord(fresh.id(),new ChestIdentity("local:other",fresh.identity().dimension(),fresh.identity().blocks()),fresh.name(),fresh.createdAt(),fresh.syncedAt(),fresh.items(),false);
        pending.persisted(doc(other),r -> fail("Foreign world")); var events=new ArrayList<ChestRecord>(); pending.persisted(doc(fresh),events::add); assertEquals(List.of(fresh),events);
    }
    @Test void missingRecordInOlderPersistedDocumentDoesNotLoseFinalization() throws Exception {
        var pending=new ConfirmedInventorySnapshots(); var fresh=chest(); var events=new ArrayList<ChestRecord>(); pending.confirmed(fresh);
        pending.persisted(new JsonStorageService.Document(1,fresh.identity().world(),List.of()),events::add); assertTrue(events.isEmpty());
        pending.persisted(doc(fresh),events::add); assertEquals(List.of(fresh),events);
    }
}
