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
        int number = 1;
        while (!byName(identity.world(), "Bau_" + number).isEmpty()) number++;
        var r = new ChestRecord(UUID.randomUUID().toString(), identity, "Bau_" + number, Instant.now().toString(), null, List.of(), false);
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
        id = normalizeName(id);
        if (id.isEmpty()) throw new IllegalArgumentException("not_found");
        if (id.toLowerCase(Locale.ROOT).startsWith("uuid:")) {
            String uuid=id.substring(5);
            return world(world).stream().filter(r -> r.id().equalsIgnoreCase(uuid)).findFirst().orElseThrow(() -> new IllegalArgumentException("not_found"));
        }
        var named = byName(world, id);
        String query = id.toLowerCase(Locale.ROOT);
        var exactId = world(world).stream().filter(r -> r.id().equalsIgnoreCase(query)).toList();
        if (!exactId.isEmpty()) {
            if (named.stream().anyMatch(r -> !r.id().equals(exactId.getFirst().id()))) throw new IllegalArgumentException("ambiguous_chest");
            return exactId.getFirst();
        }
        var matches = named.isEmpty() ? world(world).stream().filter(r -> r.id().startsWith(query)).toList() : named;
        if (matches.size() != 1) throw new IllegalArgumentException(matches.isEmpty() ? "not_found" : "ambiguous_chest");
        return matches.getFirst();
    }
    public static String normalizeName(String name) {
        return java.text.Normalizer.normalize(name.strip(), java.text.Normalizer.Form.NFC);
    }
    public void rename(String id, String world, String name) {
        var r = get(id, world); name = normalizeName(name);
        if (name.isBlank() || name.length() > 128) throw new IllegalArgumentException("invalid_name");
        if (name.toLowerCase(Locale.ROOT).startsWith("uuid:")) throw new IllegalArgumentException("invalid_name");
        if (byName(world,name).stream().anyMatch(other -> !other.id().equals(r.id()))) throw new IllegalArgumentException("duplicate_name");
        // Reserve UUID-looking names: otherwise a name could shadow another record's escape hatch.
        String normalized = name.toLowerCase(Locale.ROOT);
        if (name.matches("(?i)[0-9a-f-]+") && world(world).stream().anyMatch(other -> !other.id().equals(r.id()) && other.id().startsWith(normalized)))
            throw new IllegalArgumentException("duplicate_name");
        records.put(r.id(), r.rename(name)); revision++;
    }
    public void remove(String id, String world) { records.remove(get(id, world).id()); revision++; }
    public void snapshot(String id, ChestIdentity identity, List<ItemRecord> items) {
        var r = records.get(id);
        if (r == null || r.structureChanged() || !r.identity().equals(identity)) throw new IllegalStateException("Inventory identity changed");
        records.put(id, r.snapshot(items)); revision++;
    }
    public List<ChestRecord> byName(String world, String name) { return world(world).stream().filter(r -> normalizeName(r.name()).equalsIgnoreCase(normalizeName(name))).toList(); }
    public String identifier(ChestRecord r) {
        if (byName(r.identity().world(),r.name()).size() == 1) {
            try { if (get(r.name(),r.identity().world()).id().equals(r.id())) return r.name(); } catch (IllegalArgumentException ignored) { }
        }
        for (int size=8;size<r.id().length();size++) {
            String prefix=r.id().substring(0,size);
            try { if (get(prefix,r.identity().world()).id().equals(r.id())) return prefix; } catch (IllegalArgumentException ignored) { }
        }
        try { if (get(r.id(),r.identity().world()).id().equals(r.id())) return r.id(); } catch (IllegalArgumentException ignored) { }
        return "uuid:"+r.id();
    }
    public List<String> suggestions(String world) { return world(world).stream().map(this::identifier).toList(); }
    public List<ChestRecord> nearby(String world, String dimension, double x, double y, double z, double radius) {
        return world(world).stream().filter(r -> r.identity().dimension().equals(dimension) && r.identity().blocks().getFirst().distance(x,y,z) <= radius)
            .sorted(Comparator.comparingDouble(r -> r.identity().blocks().getFirst().distance(x,y,z))).toList();
    }
}
