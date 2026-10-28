package com.chestcount.mod.model;

import java.time.Instant;
import java.util.*;

public record ChestRecord(String id, ChestIdentity identity, String name, String createdAt,
                          String syncedAt, List<ItemRecord> items, boolean structureChanged) {
    public ChestRecord {
        UUID.fromString(id); Objects.requireNonNull(identity);
        if (name == null || name.isBlank() || name.length() > 128) throw new IllegalArgumentException("Invalid name");
        Instant.parse(createdAt); if (syncedAt != null) Instant.parse(syncedAt);
        items = List.copyOf(items);
        Set<Integer> slots = new HashSet<>();
        for (var item : items) if (item.slot() >= identity.slots() || !slots.add(item.slot())) throw new IllegalArgumentException("Invalid slots");
        if (syncedAt == null && !items.isEmpty()) throw new IllegalArgumentException("Unconfirmed items");
    }
    public long total() { return items.stream().mapToLong(ItemRecord::count).sum(); }
    public ChestRecord rename(String value) { return new ChestRecord(id, identity, value, createdAt, syncedAt, items, structureChanged); }
    public ChestRecord snapshot(List<ItemRecord> value) { return new ChestRecord(id, identity, name, createdAt, Instant.now().toString(), value, false); }
    public ChestRecord stale() { return new ChestRecord(id, identity, name, createdAt, syncedAt, items, true); }
}
