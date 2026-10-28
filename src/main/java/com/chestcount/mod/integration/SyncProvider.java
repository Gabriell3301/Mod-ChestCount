package com.chestcount.mod.integration;

import com.chestcount.mod.model.ChestRecord;
import java.util.concurrent.CompletionStage;

/** Future adapter boundary. No HTTP implementation or credentials are shipped.
 * Implementations must provide account linking, shared-world mapping, an offline durable
 * outbox, idempotent revisions and explicit conflict resolution before enabling uploads.
 * Tokens must use an OS credential store, never these JSON files.
 */
public interface SyncProvider {
    CompletionStage<Void> enqueue(ChestRecord snapshot, long localRevision);
}
