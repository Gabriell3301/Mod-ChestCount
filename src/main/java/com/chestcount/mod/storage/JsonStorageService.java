package com.chestcount.mod.storage;

import com.chestcount.mod.model.ChestRecord;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.*;

/** Called exclusively on one disk executor. Validates the complete document before replacing state. */
public final class JsonStorageService {
    public record Document(int formatVersion, String world, List<ChestRecord> chests) {}
    private final Path root;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Set<String> blocked = new HashSet<>();
    private static final class UnsupportedFormat extends IOException {
        UnsupportedFormat() { super("Unsupported JSON version; original preserved. No migration is available."); }
    }
    public JsonStorageService(Path root) { this.root = root; }
    public static String key(String value) {
        try { return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
    public Path file(String world) { return root.resolve("worlds").resolve(key(world) + ".json"); }
    private List<ChestRecord> read(Path file, String world) throws IOException {
        try {
            var d = gson.fromJson(Files.readString(file), Document.class);
            if (d != null && d.formatVersion != 1) throw new UnsupportedFormat();
            if (d == null || !world.equals(d.world) || d.chests == null) throw new IllegalArgumentException("Invalid document");
            var ids = new HashSet<String>(); var identities = new HashSet<Object>();
            for (var r : d.chests) if (!r.identity().world().equals(world) || !ids.add(r.id()) || !identities.add(r.identity())) throw new IllegalArgumentException("Duplicate or foreign chest");
            return List.copyOf(d.chests);
        } catch (RuntimeException e) { throw new IOException("Invalid JSON: " + file, e); }
    }
    public List<ChestRecord> load(String world) throws IOException {
        var path = file(world); var backup = path.resolveSibling(path.getFileName()+".bak");
        if (!Files.exists(path) && !Files.exists(backup)) return List.of();
        try { var data = read(path,world); blocked.remove(world); return data; }
        catch (IOException original) {
            blocked.add(world);
            if (original instanceof UnsupportedFormat) throw original;
            if (Files.exists(backup)) {
                var data = read(backup,world);
                if (Files.exists(path)) {
                    Files.createDirectories(root.resolve("backups"));
                    Files.copy(path, root.resolve("backups").resolve(key(world)+"-corrupt-"+System.currentTimeMillis()+".json"));
                }
                atomic(path, gson.toJson(new Document(1,world,data))); blocked.remove(world); return data;
            }
            throw original;
        }
    }
    public void save(String world, List<ChestRecord> records) throws IOException {
        if (blocked.contains(world)) throw new IOException("Refusing to overwrite damaged data; repair and reload first");
        var path = file(world); Files.createDirectories(path.getParent());
        if (Files.exists(path)) {
            read(path,world); // Never replace a document changed/corrupted externally.
            Files.copy(path,path.resolveSibling(path.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
        }
        atomic(path,gson.toJson(new Document(1,world,records)));
    }
    public static void atomic(Path path, String value) throws IOException {
        Files.createDirectories(path.getParent()); var temp = Files.createTempFile(path.getParent(),"chestcount-",".tmp");
        try {
            Files.writeString(temp,value,StandardCharsets.UTF_8);
            try { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
}
