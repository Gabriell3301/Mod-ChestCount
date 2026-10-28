package com.chestcount.mod.service;

import com.chestcount.mod.model.*;
import java.time.Instant;
import java.util.*;

/** Main-thread owned registry; immutable records are safe to hand to the disk worker. */
public final class ChestRegistryService {
    private final Map<String, ChestRecord> records = new LinkedHashMap<>();
    private long revision;
    public long revision() { return revision; }
    public List<ChestRecord> all() { return List.copyOf(records.values()); }
    public List<ChestRecord> world(String world) { return all().stream().filter(r -> r.identity().world().equals(world)).toList(); }
    public void load(List<ChestRecord> data) {
        var next = new LinkedHashMap<String, ChestRecord>();
        for (var r : data) if (next.put(r.id(), r) != null || next.values().stream().anyMatch(v -> !v.id().equals(r.id()) && v.identity().equals(r.identity()))) throw new IllegalArgumentException("Duplicate record");
        records.clear(); records.putAll(next); revision++;
    }
    public Optional<ChestRecord> exact(ChestIdentity identity) { return all().stream().filter(r -> r.identity().equals(identity) && !r.structureChanged()).findFirst(); }
    public ChestRecord register(ChestIdentity identity) {
        var existing = all().stream().filter(r -> r.identity().equals(identity)).findFirst();
        if (existing.isPresent()) return existing.get();
        if (all().stream().anyMatch(r -> r.identity().overlaps(identity))) throw new IllegalStateException("Structure conflict: remove the old record explicitly before registering the new structure");
        var r = new ChestRecord(UUID.randomUUID().toString(), identity, "Chest " + identity.blocks().getFirst(), Instant.now().toString(), null, List.of(), false);
        records.put(r.id(), r); revision++; return r;
    }
    public void observe(ChestIdentity identity) {
        for (var r : all()) if (r.identity().overlaps(identity) && !r.identity().equals(identity) && !r.structureChanged()) { records.put(r.id(), r.stale()); revision++; }
    }
    public void invalidate(String id) {
        var r=records.get(id);
        if (r != null && !r.structureChanged()) { records.put(id,r.stale()); revision++; }
    }
    public ChestRecord get(String id, String world) {
        var matches = world(world).stream().filter(r -> r.id().startsWith(id)).toList();
        if (matches.size() != 1) throw new IllegalArgumentException("Unknown or ambiguous ID: " + id);
        return matches.getFirst();
    }
    public void rename(String id, String world, String name) { var r = get(id, world); records.put(r.id(), r.rename(name)); revision++; }
    public void remove(String id, String world) { records.remove(get(id, world).id()); revision++; }
    public void snapshot(String id, ChestIdentity identity, List<ItemRecord> items) {
        var r = records.get(id);
        if (r == null || r.structureChanged() || !r.identity().equals(identity)) throw new IllegalStateException("Inventory identity changed");
        records.put(id, r.snapshot(items)); revision++;
    }
    public List<ChestRecord> byName(String world, String name) { return world(world).stream().filter(r -> r.name().equalsIgnoreCase(name)).toList(); }
    public List<ChestRecord> nearby(String world, String dimension, double x, double y, double z, double radius) {
        return world(world).stream().filter(r -> r.identity().dimension().equals(dimension) && r.identity().blocks().getFirst().distance(x,y,z) <= radius)
            .sorted(Comparator.comparingDouble(r -> r.identity().blocks().getFirst().distance(x,y,z))).toList();
    }
}
