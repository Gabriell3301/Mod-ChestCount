package com.chestcount.mod.integration;

import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.io.IOException;
import java.util.*;

/**
 * Guarda segredos locais associados ao endpoint, separados de inventários e backups.
 *
 * Uma desconexão pendente suspende o uso do token até revogação; mantém compatibilidade
 * com a variável do launcher enquanto não existir uma desconexão explícita.
 */
public final class DeviceCredentials {
    private static Path root;
    private static final Gson GSON=new Gson();
    public record Credential(String token,String deviceId,String expiresAt,boolean disconnectPending) {
        @Override public String toString() { return "DeviceCredential[redacted]"; }
    }
    public static synchronized void initialize(Path directory) { root=directory.resolve("credentials"); }
    private static Path file(String endpoint) throws IOException {
        if(root==null) throw new IOException("Device credential store not initialized");
        return root.resolve(JsonStorageService.key(endpoint)+".json");
    }
    public static synchronized Credential load(String endpoint) throws IOException {
        Path path=file(endpoint);
        if(!Files.exists(path)) return null;
        try {
            var value=GSON.fromJson(Files.readString(path),Credential.class);
            if(value==null || value.token()==null || !value.token().matches("[A-Za-z0-9_-]{43,128}")) throw new IOException("Invalid local device credential");
            return value;
        } catch(JsonParseException e) { throw new IOException("Invalid local device credential"); }
    }
    public static synchronized void save(String endpoint,Credential value) throws IOException {
        Path path=file(endpoint);
        Files.createDirectories(root);
        restrict(root,true);
        JsonStorageService.atomic(path,GSON.toJson(value));
        restrict(path,false);
    }
    private static void restrict(Path path,boolean directory) throws IOException {
        var posix=Files.getFileAttributeView(path,PosixFileAttributeView.class);
        if(posix!=null) posix.setPermissions(PosixFilePermissions.fromString(directory?"rwx------":"rw-------"));
        var acl=Files.getFileAttributeView(path,AclFileAttributeView.class);
        if(acl!=null) {
            var builder=AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(Files.getOwner(path)).setPermissions(EnumSet.allOf(AclEntryPermission.class));
            if(directory) builder.setFlags(AclEntryFlag.FILE_INHERIT,AclEntryFlag.DIRECTORY_INHERIT);
            acl.setAcl(List.of(builder.build()));
        }
    }
    public static synchronized void clear(String endpoint) throws IOException { Files.deleteIfExists(file(endpoint)); }
    public static synchronized String token(String endpoint) {
        try {
            if(root!=null) {
                var credential=load(endpoint);
                if(credential!=null) {
                    if(credential.disconnectPending()) throw new IllegalStateException("Disconnect pending; run /cc disconnect to retry");
                    return credential.token();
                }
                // An explicit local disconnect must also disable the legacy launcher token.
                if(Files.exists(root.resolve(JsonStorageService.key(endpoint)+".disconnected"))) throw new IllegalStateException("Use /cc connect CODE");
            }
        } catch(IOException e) { throw new IllegalStateException("Cannot read device credentials"); }
        String legacy=System.getenv("CHESTCOUNT_API_TOKEN");
        if(legacy!=null && legacy.strip().matches("[A-Za-z0-9_-]{43,128}")) return legacy.strip();
        throw new IllegalStateException("Minecraft is disconnected; use /cc connect CODE");
    }
    public static synchronized void markDisconnected(String endpoint) throws IOException {
        Files.createDirectories(root); restrict(root,true);
        Files.writeString(root.resolve(JsonStorageService.key(endpoint)+".disconnected"),"");
    }
}
