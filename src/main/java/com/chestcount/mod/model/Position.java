package com.chestcount.mod.model;

public record Position(int x, int y, int z) implements Comparable<Position> {
    public double distance(double px, double py, double pz) {
        return Math.sqrt(Math.pow(x + .5 - px, 2) + Math.pow(y + .5 - py, 2) + Math.pow(z + .5 - pz, 2));
    }
    public int compareTo(Position p) { int c = Integer.compare(x, p.x); if (c == 0) c = Integer.compare(y, p.y); return c == 0 ? Integer.compare(z, p.z) : c; }
    public String toString() { return "X:" + x + " Y:" + y + " Z:" + z; }
}
