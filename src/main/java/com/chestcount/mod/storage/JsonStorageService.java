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
    private static void string(JsonElement value) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException("Expected string");
    }
    private static void integer(JsonElement value,long low,long high) {
        if(value==null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !value.getAsString().matches("-?[0-9]+")) throw new IllegalArgumentException("Expected integer");
        long number=Long.parseLong(value.getAsString()); if(number<low || number>high) throw new IllegalArgumentException("Integer out of range");
    }
    public static Document parse(String json, String world) throws IOException {
        try {
            var tree=JsonParser.parseString(json).getAsJsonObject();
            integer(tree.get("formatVersion"),1,Long.MAX_VALUE);
            if(tree.get("formatVersion").getAsLong()!=1) throw new UnsupportedFormat();
            string(tree.get("world"));
            for(var element:tree.getAsJsonArray("chests")) {
                var chest=element.getAsJsonObject();
                for(var field:List.of("id","name","createdAt")) string(chest.get(field));
                if(chest.has("syncedAt") && !chest.get("syncedAt").isJsonNull()) string(chest.get("syncedAt"));
                if(!chest.has("structureChanged") || !chest.get("structureChanged").isJsonPrimitive() || !chest.get("structureChanged").getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid structural flag");
                var identity=chest.getAsJsonObject("identity"); string(identity.get("world")); string(identity.get("dimension"));
                for(var block:identity.getAsJsonArray("blocks")) {
                    var coordinates=block.getAsJsonObject();
                    integer(coordinates.get("x"),-30000000,30000000); integer(coordinates.get("z"),-30000000,30000000); integer(coordinates.get("y"),-2048,2048);
                }
                for(var item:chest.getAsJsonArray("items")) {
                    var value=item.getAsJsonObject(); integer(value.get("slot"),0,53); integer(value.get("count"),1,9999); string(value.get("item_id"));
                }
            }
            var d=new Gson().fromJson(tree,Document.class);
            if(d!=null && d.formatVersion!=1) throw new UnsupportedFormat();
            if(d==null || !world.equals(d.world) || d.chests==null) throw new IllegalArgumentException("Invalid document");
            var ids=new HashSet<String>(); var records=new ArrayList<ChestRecord>();
            for(var r:d.chests) {
                if(!r.identity().world().equals(world) || !ids.add(r.id()) || records.stream().anyMatch(v -> v.identity().overlaps(r.identity()))) throw new IllegalArgumentException("Duplicate, overlapping or foreign chest");
                records.add(r);
            }
            return new Document(1,world,List.copyOf(records));
        } catch(RuntimeException e) { throw new IOException("Invalid world JSON",e); }
    }
    private List<ChestRecord> read(Path file, String world) throws IOException {
        return parse(Files.readString(file),world).chests();
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
