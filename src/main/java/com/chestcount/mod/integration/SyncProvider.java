package com.chestcount.mod.integration;
import com.chestcount.mod.model.ChestRecord;
import java.util.concurrent.CompletionStage;
/** Upload-only adapter boundary. Future credentials belong in a separate credential store. */
public interface SyncProvider {
    CompletionStage<Void> enqueue(ChestRecord snapshot, long localRevision);
}
