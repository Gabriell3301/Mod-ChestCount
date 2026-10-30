package com.chestcount.mod.storage;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.model.ChestRecord;
import com.google.gson.GsonBuilder;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;

/** Historical archives are independent of the synchronization outbox. Disk-worker owned. */
public final class WorldArchiveService {
    private final Path root;
    private final com.google.gson.Gson gson=new GsonBuilder().setPrettyPrinting().create();
    private record AutomaticState(long lastTime,String hash) {}
    public record ImportPreview(JsonStorageService.Document merged,int created,int replaced,String fingerprint) {}
    public WorldArchiveService(Path root) { this.root=root; }
    private Path backups(String world) { return root.resolve("backups").resolve(JsonStorageService.key(world)); }
    private String json(JsonStorageService.Document doc) throws IOException { String value=gson.toJson(doc); JsonStorageService.parse(value,doc.world()); return value; }
    private Path archive(JsonStorageService.Document doc,String prefix,Path directory) throws IOException {
        String value=json(doc);
        var target=directory.resolve(prefix+String.format(java.util.Locale.ROOT,"%019d",System.currentTimeMillis())+"-"+UUID.randomUUID()+".json");
        JsonStorageService.atomic(target,value);
        var written=JsonStorageService.parse(Files.readString(target),doc.world());
        if(!doc.equals(written)) throw new IOException("Backup verification failed");
        return target;
    }
    public Path backup(JsonStorageService.Document doc) throws IOException { return archive(doc,"manual-",backups(doc.world())); }
    public Path export(JsonStorageService.Document doc) throws IOException { return archive(doc,"export-"+JsonStorageService.key(doc.world())+"-",root.resolve("exports")); }
    public List<String> list(String world) throws IOException {
        Path directory=backups(world); if(!Files.isDirectory(directory)) return List.of();
        try(var files=Files.list(directory)) { return files.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().matches("(manual|auto)-[0-9]+-[a-f0-9-]+\\.json")).map(p -> p.getFileName().toString()).sorted(Comparator.reverseOrder()).toList(); }
    }
    public Optional<Path> automatic(JsonStorageService.Document doc,ModConfig.Backup config,long now) throws IOException {
        if(!config.enabled) return Optional.empty();
        Path marker=backups(doc.world()).resolve("automatic-state.json");
        AutomaticState state=Files.exists(marker)?gson.fromJson(Files.readString(marker),AutomaticState.class):new AutomaticState(now,null);
        if(state==null || state.lastTime<0) throw new IOException("Invalid backup state");
        if(!Files.exists(marker)) { JsonStorageService.atomic(marker,gson.toJson(state)); return Optional.empty(); }
        if(now-state.lastTime<config.intervalMinutes*60_000L) return Optional.empty();
        String hash=JsonStorageService.key(json(doc));
        if(config.onlyWhenChanged && hash.equals(state.hash)) return Optional.empty();
        Path result=archive(doc,"auto-",backups(doc.world()));
        JsonStorageService.atomic(marker,gson.toJson(new AutomaticState(now,hash)));
        try(var files=Files.list(backups(doc.world()))) {
            var automatic=files.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().matches("auto-[0-9]+-[a-f0-9-]+\\.json")).sorted(Comparator.comparing(p -> p.getFileName().toString(),Comparator.reverseOrder())).toList();
            for(var old:automatic.subList(Math.min(config.maxAutomaticBackupsPerWorld,automatic.size()),automatic.size())) Files.delete(old);
        }
        return Optional.of(result);
    }
    public List<String> importFiles() throws IOException {
        Path directory=root.resolve("imports"); Files.createDirectories(directory);
        try(var files=Files.list(directory)) { return files.filter(p -> Files.isRegularFile(p,LinkOption.NOFOLLOW_LINKS) && p.getFileName().toString().endsWith(".json")).map(p -> p.getFileName().toString()).sorted().toList(); }
    }
    private Path importFile(String filename) throws IOException {
        if(!filename.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,200}\\.json") || filename.contains("..")) throw new IOException("Invalid import filename");
        Path directory=root.resolve("imports"); Files.createDirectories(directory);
        if(Files.isSymbolicLink(directory) || !directory.toRealPath().getParent().equals(root.toRealPath())) throw new IOException("Import directory must not be a symlink");
        Path path=directory.resolve(filename);
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().getParent().equals(directory.toRealPath()) || Files.size(path)>8*1024*1024) throw new IOException("Import file missing, unsafe or too large");
        return path;
    }
    public ImportPreview preview(String filename,JsonStorageService.Document current) throws IOException {
        String source=Files.readString(importFile(filename));
        var imported=JsonStorageService.parse(source,current.world());
        var merged=new LinkedHashMap<String,ChestRecord>(); current.chests().forEach(r -> merged.put(r.id(),r));
        int created=0,replaced=0;
        for(var chest:imported.chests()) {
            var old=merged.get(chest.id());
            if(old!=null && !old.identity().equals(chest.identity())) throw new IOException("Existing UUID has another identity");
            if(old==null) created++; else replaced++;
            merged.put(chest.id(),chest);
        }
        var result=new JsonStorageService.Document(1,current.world(),List.copyOf(merged.values())); json(result);
        return new ImportPreview(result,created,replaced,JsonStorageService.key(source+"\n"+json(current)));
    }
    public JsonStorageService.Document apply(String filename,JsonStorageService.Document current,String expected,JsonStorageService storage) throws IOException {
        var preview=preview(filename,current);
        if(expected==null || !expected.equals(preview.fingerprint())) throw new IOException("File or world changed; preview again before confirming");
        backup(current); storage.save(current.world(),preview.merged().chests());
        return preview.merged();
    }
}
