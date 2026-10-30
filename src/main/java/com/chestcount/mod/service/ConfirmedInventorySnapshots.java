package com.chestcount.mod.service;

import com.chestcount.mod.model.ChestRecord;
import com.chestcount.mod.storage.JsonStorageService;
import java.io.IOException;
import java.util.*;

/** Disk-worker owned. A closed, authoritative snapshot is finalized only after its exact local write succeeds. */
public final class ConfirmedInventorySnapshots {
    @FunctionalInterface public interface Finalizer { void finish(ChestRecord snapshot) throws IOException; }
    private final Map<String,Map<String,ChestRecord>> pending=new HashMap<>();
    public void confirmed(ChestRecord snapshot) {
        if(snapshot.syncedAt()==null) throw new IllegalArgumentException("Unconfirmed inventory");
        pending.computeIfAbsent(snapshot.identity().world(),ignored -> new LinkedHashMap<>()).put(snapshot.id(),snapshot);
    }
    public void invalidate(String world,String chest) {
        var records=pending.get(world); if(records!=null) records.remove(chest);
    }
    public void persisted(JsonStorageService.Document document,Finalizer finalizer) throws IOException {
        var records=pending.get(document.world()); if(records==null) return;
        var byId=new HashMap<String,ChestRecord>(); document.chests().forEach(r -> byId.put(r.id(),r));
        var iterator=records.entrySet().iterator();
        while(iterator.hasNext()) {
            var entry=iterator.next(); var written=byId.get(entry.getKey());
            if(written==null) continue; // The cached persisted document may predate registration after a failed write.
            if(!entry.getValue().equals(written)) continue;
            finalizer.finish(written); // Retain the proof on an I/O error so a later successful write can retry.
            iterator.remove();
        }
        if(records.isEmpty()) pending.remove(document.world());
    }
}
