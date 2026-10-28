package com.chestcount.mod.model;

public record ItemRecord(int slot, String item_id, int count) {
    public ItemRecord {
        if (slot < 0 || slot >= 54 || item_id == null || !item_id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || count <= 0 || count > 9999)
            throw new IllegalArgumentException("Invalid item");
    }
}
