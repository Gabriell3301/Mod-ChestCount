package com.chestcount.mod.model;

import java.time.Instant;
import java.util.*;

/**
 * Representa uma observação imutável de baú, incluindo identidade e estado estrutural.
 *
 * Uma data de observação nula exige lista de itens vazia; é distinta de um baú
 * confirmado vazio. A opção autoSync é configuração local do registo.
 */
public record ChestRecord(String id, ChestIdentity identity, String name, String createdAt,
                          String syncedAt, List<ItemRecord> items, boolean structureChanged, boolean autoSync) {
    public ChestRecord(String id, ChestIdentity identity, String name, String createdAt,
                       String syncedAt, List<ItemRecord> items, boolean structureChanged) {
        this(id, identity, name, createdAt, syncedAt, items, structureChanged, false);
    }

    public ChestRecord withAutoSync(boolean enabled) {
        return new ChestRecord(id, identity, name, createdAt, syncedAt, items, structureChanged, enabled);
    }

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
    public ChestRecord rename(String value) { return new ChestRecord(id, identity, value, createdAt, syncedAt, items, structureChanged, autoSync); }
    public ChestRecord snapshot(List<ItemRecord> value) { return new ChestRecord(id, identity, name, createdAt, Instant.now().toString(), value, false, autoSync); }
    public ChestRecord stale() { return new ChestRecord(id, identity, name, createdAt, syncedAt, items, true, autoSync); }
}
