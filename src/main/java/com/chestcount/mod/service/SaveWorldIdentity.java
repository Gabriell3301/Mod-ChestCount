package com.chestcount.mod.service;

import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.Gson;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;

/**
 * Preserva a identidade de um save e distingue cópias cuja origem ainda existe.
 *
 * Metadados inválidos originam erro sem substituir o original; uma mudança de pasta
 * com a origem ausente conserva o identificador existente.
 */
public final class SaveWorldIdentity {
    private record Metadata(String id,String lastPath) {}
    /**
     * Mantém o id existente ou cria outro quando a pasta é uma cópia com origem ainda presente.
     */
    public static String load(Path directory) throws IOException {
        directory=directory.toAbsolutePath().normalize();
        Path file=directory.resolve("chestcount-world-id.json");
        String id="local:"+JsonStorageService.key(directory.toString());
        if(Files.exists(file)) {
            Metadata metadata;
            try { metadata=new Gson().fromJson(Files.readString(file),Metadata.class); }
            catch(RuntimeException e) { throw new IOException("Invalid ChestCount world metadata; original preserved"); }
            if(metadata==null || metadata.id()==null || !metadata.id().matches("local:[a-f0-9]{64}") || metadata.lastPath()==null) throw new IOException("Invalid ChestCount world identity; original preserved");
            id=metadata.id();
            Path previous;
            try { previous=Path.of(metadata.lastPath()).toAbsolutePath().normalize(); }
            catch(RuntimeException e) { throw new IOException("Invalid ChestCount world path; original preserved"); }
            if(!previous.equals(directory) && Files.exists(previous)) id="local:"+JsonStorageService.key(UUID.randomUUID().toString());
        }
        JsonStorageService.atomic(file,new Gson().toJson(new Metadata(id,directory.toString())));
        return id;
    }
}
