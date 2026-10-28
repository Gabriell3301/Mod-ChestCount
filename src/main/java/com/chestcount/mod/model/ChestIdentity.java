package com.chestcount.mod.model;

import java.util.*;

public record ChestIdentity(String world, String dimension, List<Position> blocks) {
    public ChestIdentity {
        Objects.requireNonNull(world); Objects.requireNonNull(dimension); Objects.requireNonNull(blocks);
        blocks = blocks.stream().distinct().sorted().toList();
        if (world.isBlank() || !dimension.matches("[a-z0-9_.-]+:[a-z0-9_./-]+") || blocks.isEmpty() || blocks.size() > 2)
            throw new IllegalArgumentException("Invalid chest identity");
        if (blocks.size() == 2) {
            var a = blocks.getFirst(); var b = blocks.getLast();
            if (a.y() != b.y() || Math.abs((long)a.x()-b.x()) + Math.abs((long)a.z()-b.z()) != 1)
                throw new IllegalArgumentException("Invalid double chest");
        }
    }
    public int slots() { return blocks.size() * 27; }
    public boolean overlaps(ChestIdentity other) {
        return world.equals(other.world) && dimension.equals(other.dimension) && blocks.stream().anyMatch(other.blocks::contains);
    }
}
