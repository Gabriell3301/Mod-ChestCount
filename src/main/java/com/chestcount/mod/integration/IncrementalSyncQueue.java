package com.chestcount.mod.integration;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.model.ChestRecord;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.*;
import java.nio.file.*;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static com.chestcount.mod.integration.SyncProtocol.*;

/** Durable per-destination outbox. Sent changes are immutable until explicitly acknowledged. */
public final class IncrementalSyncQueue implements AutoCloseable {
    private static final Gson GSON=new GsonBuilder().setPrettyPrinting().create();
    private static final class State {
        int formatVersion=1;
        boolean bootstrapped;
        transient boolean dirty;
        String world;
        Map<String,ChestRecord> desired=new LinkedHashMap<>(), staged=new LinkedHashMap<>();
        Map<String,Long> revisions=new HashMap<>(), serverRevisions=new HashMap<>();
        Set<String> held=new HashSet<>();
        List<Change> changes=new ArrayList<>();
        Map<String,String> diagnostics=new LinkedHashMap<>();
        // Keep old conflicts until this replacement is accepted, including across restart.
        Map<String,Set<String>> inventoryRecoveries=new HashMap<>();
        long lastSuccess, due;
        int failures;
    }
    public record Status(boolean pending,boolean offline,long lastSuccess,boolean sending,boolean awaitingConfirmations,int pendingCount,int conflicts,List<String> diagnostics) {}
    private static final class ReopenedInventory {
        final Set<String> conflicts;
        final ChestRecord atOpen;
        ChestRecord snapshot;
        CompletableFuture<Boolean> lookup;
        ReopenedInventory(Set<String> conflicts,ChestRecord atOpen) { this.conflicts=conflicts; this.atOpen=atOpen; }
    }
    private final Map<String,ReopenedInventory> reopened=new HashMap<>();
    private final Path root;
    private final Map<String,State> states=new HashMap<>();
    private final Map<String,CompletableFuture<Integer>> flights=new HashMap<>();
    private final Map<String,Status> statuses=new ConcurrentHashMap<>();
    private final ExecutorService worker=Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"Chest Count incremental confirmations"); t.setDaemon(true); return t; });
    private ModConfig.Sync settings=new ModConfig.Sync();
    private String clientId;
    private boolean closed;
    public IncrementalSyncQueue(Path root) { this.root=root; }
    public synchronized void configure(ModConfig.Sync config) { settings=GSON.fromJson(GSON.toJson(config),ModConfig.Sync.class); }
    private String key(String world) { return JsonStorageService.key(settings.endpoint)+"/"+JsonStorageService.key(world); }
    private Path file(String key) { return root.resolve("incremental").resolve(key+".json"); }
    public synchronized String clientId() throws IOException {
        if(clientId==null) {
            var path=root.resolve("client-id.json");
            if(Files.exists(path)) clientId=GSON.fromJson(Files.readString(path),String.class);
            else { clientId=UUID.randomUUID().toString(); JsonStorageService.atomic(path,GSON.toJson(clientId)); }
            try { UUID.fromString(clientId); } catch(RuntimeException e) { throw new IOException("Invalid persistent client ID"); }
        } return clientId;
    }
    private State state(String world) throws IOException {
        String key=key(world);
        if(!states.containsKey(key)) {
            State s;
            try { s=read(file(key),world); }
            catch(IOException e) {
                var backup=file(key).resolveSibling(file(key).getFileName()+".bak");
                if(!Files.exists(backup)) throw e;
                s=read(backup,world);
                Files.copy(file(key),file(key).resolveSibling(file(key).getFileName()+".corrupt-"+UUID.randomUUID()));
                JsonStorageService.atomic(file(key),GSON.toJson(s));
            }
            if(s==null) {
                s=new State(); s.world=world;
                // Preserve unresolved inventory sessions from the previous full-document outbox.
                Path legacy=root.resolve("outbox").resolve(JsonStorageService.key(world)+".json");
                if(Files.exists(legacy)) {
                    try { var json=JsonParser.parseString(Files.readString(legacy)).getAsJsonObject();
                        if(json.has("held")) for(var id:json.getAsJsonArray("held")) s.held.add(id.getAsString());
                    } catch(RuntimeException e) { throw new IOException("Invalid legacy outbox; original preserved"); }
                }
            }
            states.put(key,s); publish(key,s);
        } return states.get(key);
    }
    private State read(Path path,String world) throws IOException {
        if(!Files.exists(path)) return null;
        try {
            State s=GSON.fromJson(Files.readString(path),State.class);
            if(s==null || s.formatVersion!=1 || !world.equals(s.world) || s.changes==null || s.held==null || s.desired==null || s.staged==null || s.revisions==null || s.serverRevisions==null || s.diagnostics==null || s.inventoryRecoveries==null) throw new IllegalArgumentException();
            var ids=new HashSet<String>();
            for(var c:s.changes) if(!world.equals(c.worldId()) || !ids.add(c.changeId()) || c.revision()<1) throw new IllegalArgumentException();
            return s;
        } catch(RuntimeException e) { throw new IOException("Invalid incremental queue; original preserved"); }
    }
    private void publish(String key,State s) {
        int conflicts=(int)s.changes.stream().filter(c -> Set.of("conflict","rejected").contains(c.status())).count();
        statuses.put(key,new Status(!s.changes.isEmpty(),s.failures>0,s.lastSuccess,flights.containsKey(key),!s.held.isEmpty(),s.changes.size(),conflicts,List.copyOf(s.diagnostics.values())));
    }
    private void persist(String key,State s) throws IOException {
        s.dirty=true;
        var path=file(key);
        if(Files.exists(path)) Files.copy(path,path.resolveSibling(path.getFileName()+".bak"),StandardCopyOption.REPLACE_EXISTING);
        JsonStorageService.atomic(path,GSON.toJson(s)); s.dirty=false; publish(key,s);
    }
    private boolean same(ChestRecord a,ChestRecord b) {
        if(a==null || b==null) return a==b;
        return a.id().equals(b.id()) && a.name().equals(b.name()) && a.identity().equals(b.identity()) && a.items().equals(b.items()) && a.structureChanged()==b.structureChanged() && Objects.equals(a.createdAt(),b.createdAt()) && (a.syncedAt()==null)==(b.syncedAt()==null);
    }
    public synchronized void update(JsonStorageService.Document doc) throws IOException {
        var s=state(doc.world()); var next=new LinkedHashMap<String,ChestRecord>();
        for(var r:doc.chests()) if(!doc.world().equals(r.identity().world()) || next.put(r.id(),r)!=null) throw new IOException("Foreign/duplicate chest");
        if(s.desired.equals(next) && !s.dirty) return;
        s.desired=next;
        s.held.removeIf(id -> !id.equals("*") && !next.containsKey(id));
        stage(s); persist(key(doc.world()),s);
    }
    private void stage(State s) throws IOException {
        var ids=new LinkedHashSet<>(s.staged.keySet()); ids.addAll(s.desired.keySet());
        boolean changed=false;
        for(String id:ids) {
            if(s.held.contains("*") || s.held.contains(id)) continue;
            ChestRecord old=s.staged.get(id), current=s.desired.get(id);
            if(same(old,current)) continue;
            // A blocked conflict stays available for diagnosis; newer desired state is retained locally.
            if(s.changes.stream().anyMatch(c -> c.chestId().equals(id) && Set.of("conflict","rejected").contains(c.status()))) continue;
            boolean sent=s.changes.stream().anyMatch(c -> c.chestId().equals(id) && c.status().equals("sent"));
            boolean compacting=s.changes.stream().anyMatch(c -> c.chestId().equals(id) && c.status().equals("pending"));
            s.changes.removeIf(c -> c.chestId().equals(id) && c.status().equals("pending"));
            if(current==null && !sent && s.serverRevisions.getOrDefault(id,0L)==0) { s.staged.remove(id); changed=true; continue; }
            String op=current==null?"delete":"upsert";
            JsonObject data=current==null?new JsonObject():GSON.toJsonTree(current).getAsJsonObject();
            if(current!=null && old!=null && !sent && !compacting && s.serverRevisions.getOrDefault(id,0L)>0 && old.identity().equals(current.identity()) && old.items().equals(current.items())) {
                if(old.structureChanged()!=current.structureChanged() && old.name().equals(current.name())) { op="structure_changed"; data=new JsonObject(); data.addProperty("structureChanged",current.structureChanged()); }
                else if(old.structureChanged()==current.structureChanged() && !old.name().equals(current.name())) { op="rename"; data=new JsonObject(); data.addProperty("name",current.name()); }
            }
            // If compacting an unsent change, send the complete final snapshot to preserve prior edits.
            if(current!=null && s.changes.stream().noneMatch(c -> c.chestId().equals(id)) && !same(old,current) && s.serverRevisions.getOrDefault(id,0L)==0) op="upsert";
            long rev=s.revisions.merge(id,1L,Long::sum);
            s.changes.add(new Change(UUID.randomUUID().toString(),clientId(),s.world,id,(current==null?old:current).identity().dimension(),rev,op,null,data,Instant.now().toString(),"pending"));
            if(current==null) s.staged.remove(id); else s.staged.put(id,current);
            changed=true;
        }
        if(changed) s.due=Math.max(System.currentTimeMillis()+settings.debounceSeconds*1000L,s.failures>0?s.due:0);
    }
    public synchronized void hold(String world,String chest) throws IOException { var s=state(world); s.held.add(chest); persist(key(world),s); }
    public synchronized void release(String world,String chest) throws IOException { var s=state(world); s.held.remove(chest); stage(s); persist(key(world),s); }
    /** Called once for an actual menu opening, not for each slot confirmation. */
    public synchronized void opened(String world,String chest) throws IOException {
        var s=state(world);
        var ids=new HashSet<String>();
        for(var c:s.changes) if(c.chestId().equals(chest) && c.status().equals("conflict")) ids.add(c.changeId());
        reopened.put(key(world)+"/"+chest,new ReopenedInventory(Set.copyOf(ids),s.desired.get(chest)));
        hold(world,chest);
    }
    private boolean sameMetadata(ChestRecord a,ChestRecord b) {
        return a!=null && b!=null && a.id().equals(b.id()) && a.identity().equals(b.identity())
            && a.name().equals(b.name()) && Objects.equals(a.createdAt(),b.createdAt()) && a.structureChanged()==b.structureChanged();
    }
    private boolean recoverable(State s,String chest,ReopenedInventory session) {
        if(session.conflicts.isEmpty() || s.held.contains("*") || s.held.contains(chest) || session.snapshot==null
            || session.snapshot.syncedAt()==null || session.snapshot.structureChanged()
            || !session.snapshot.equals(s.desired.get(chest))
            || session.atOpen!=null && Objects.equals(session.atOpen.syncedAt(),session.snapshot.syncedAt())) return false;
        var changes=s.changes.stream().filter(c -> c.chestId().equals(chest)).toList();
        if(changes.size()!=session.conflicts.size()) return false;
        try {
            for(var c:changes) if(!session.conflicts.contains(c.changeId()) || !c.status().equals("conflict") || !c.operation().equals("upsert")
                || !sameMetadata(GSON.fromJson(c.data(),ChestRecord.class),session.snapshot)) return false;
        } catch(RuntimeException e) { return false; }
        return true;
    }
    /** Caller must first persist this authoritative closed-menu snapshot and update/release the outbox. */
    public synchronized CompletionStage<Boolean> recoverInventory(String world,ChestRecord persistedSnapshot) {
        return recoverInventory(world,persistedSnapshot,false);
    }
    private CompletionStage<Boolean> recoverInventory(String world,ChestRecord persistedSnapshot,boolean manual) {
        if(closed) return CompletableFuture.completedFuture(false);
        String key=key(world), chest=persistedSnapshot.id(), sessionKey=key+"/"+chest;
        try {
            var s=state(world); var session=reopened.get(sessionKey);
            if(session==null) return CompletableFuture.completedFuture(false);
            session.snapshot=persistedSnapshot;
            if(session.lookup!=null) return session.lookup;
            if(!recoverable(s,chest,session) || !manual && (!settings.enabled || !settings.automatic)) return CompletableFuture.completedFuture(false);
            var result=new CompletableFuture<Boolean>(); session.lookup=result;
            final var requestSettings=GSON.fromJson(GSON.toJson(settings),ModConfig.Sync.class);
            new HttpSyncProvider(requestSettings).readChest(world,chest).whenCompleteAsync((body,error) -> {
                synchronized(this) {
                    try {
                        // A new opening, local rename/delete/structural edit, or unconfirmed slots invalidates the lookup.
                        if(closed || reopened.get(sessionKey)!=session || !recoverable(s,chest,session)) { result.complete(false); return; }
                        if(error!=null) throw new IOException("Inventory revision lookup unavailable");
                        var remote=JsonParser.parseString(body).getAsJsonObject();
                        var deleted=remote.get("deleted"); var revision=remote.get("serverRevision");
                        if(deleted==null || !deleted.isJsonPrimitive() || !deleted.getAsJsonPrimitive().isBoolean()
                            || revision==null || !revision.isJsonPrimitive() || !revision.getAsJsonPrimitive().isNumber()
                            || !revision.getAsString().matches("[0-9]+")) throw new IOException("Invalid remote chest metadata");
                        long base=revision.getAsLong();
                        var fresh=session.snapshot;
                        var remoteChest=GSON.fromJson(remote,ChestRecord.class);
                        if(deleted.getAsBoolean() || base<1 || base>=9_007_199_254_740_991L || !sameMetadata(remoteChest,fresh)) {
                            result.complete(false); return;
                        }
                        long localRevision=s.revisions.merge(chest,1L,Long::sum);
                        var replacement=new Change(UUID.randomUUID().toString(),clientId(),world,chest,fresh.identity().dimension(),localRevision,
                            "upsert",base,GSON.toJsonTree(fresh).getAsJsonObject(),Instant.now().toString(),"pending");
                        s.serverRevisions.put(chest,base);
                        s.changes.add(replacement); s.inventoryRecoveries.put(replacement.changeId(),session.conflicts);
                        s.staged.put(chest,fresh);
                        s.due=Math.max(System.currentTimeMillis()+requestSettings.debounceSeconds*1000L,s.failures>0?s.due:0);
                        persist(key,s); result.complete(true);
                    } catch(Exception e) {
                        // One lookup per reopening; the existing conflict remains durable and is never retried in a loop.
                        if(reopened.get(sessionKey)==session) {
                            session.conflicts.stream().findFirst().ifPresent(id -> s.diagnostics.put(id,chest+": inventory recovery unavailable; reopen chest"));
                            try { persist(key,s); } catch(IOException ignored) { }
                        }
                        result.complete(false);
                    }
                }
            },worker);
            return result;
        } catch(Exception e) { return CompletableFuture.completedFuture(false); }
    }
    public Status status(String world,ModConfig.Sync config) {
        return statuses.getOrDefault(JsonStorageService.key(config.endpoint)+"/"+JsonStorageService.key(world),new Status(false,false,0,false,false,0,0,List.of()));
    }
    public synchronized CompletionStage<Integer> attempt(String world,ModConfig.Sync config,boolean manual) {
        configure(config); String key=key(world);
        if(closed) return CompletableFuture.failedFuture(new IOException("Sync stopped"));
        if(flights.containsKey(key)) return flights.get(key);
        try {
            var s=state(world); final var requestSettings=GSON.fromJson(GSON.toJson(settings),ModConfig.Sync.class);
            if(!manual && (!settings.enabled || !settings.automatic || System.currentTimeMillis()<s.due)) return CompletableFuture.completedFuture(0);
            // A fresh, persisted reopening collected while automatic sync was off waits for manual sync/on.
            for(var entry:List.copyOf(reopened.entrySet())) {
                var session=entry.getValue();
                if(entry.getKey().startsWith(key+"/") && session.snapshot!=null && session.lookup==null)
                    recoverInventory(world,session.snapshot,manual);
            }
            var batch=new ArrayList<Change>(); var ids=new HashSet<String>();
            for(int i=0;i<s.changes.size() && batch.size()<128;i++) {
                var c=s.changes.get(i);
                if(Set.of("conflict","rejected").contains(c.status()) || s.held.contains("*") || s.held.contains(c.chestId()) || !ids.add(c.chestId())) continue;
                if(c.status().equals("pending")) { c=c.status("sent",s.serverRevisions.getOrDefault(c.chestId(),0L)); s.changes.set(i,c); }
                batch.add(c);
            }
            if(batch.isEmpty()) {
                if(manual) {
                    var lookups=reopened.entrySet().stream().filter(e -> e.getKey().startsWith(key+"/"))
                        .map(e -> e.getValue().lookup).filter(f -> f!=null && !f.isDone()).toArray(CompletableFuture[]::new);
                    if(lookups.length>0) return CompletableFuture.allOf(lookups).thenComposeAsync(ignored -> attempt(world,requestSettings,true),worker);
                }
                return CompletableFuture.completedFuture(0);
            }
            persist(key,s); // Freeze IDs, payloads and bases before any bytes leave the process.
            String json=GSON.toJson(new Request(1,clientId(),world,!s.bootstrapped,List.copyOf(batch)));
            var result=new CompletableFuture<Integer>(); flights.put(key,result); publish(key,s);
            new HttpSyncProvider(requestSettings).exchange(json).whenCompleteAsync((body,error) -> {
                synchronized(this) {
                    try {
                        int accepted=0;
                        if(error!=null) {
                            Throwable cause=error; while(cause instanceof CompletionException && cause.getCause()!=null) cause=cause.getCause();
                            if(cause instanceof HttpSyncProvider.HttpStatusException http && http.status>=400 && http.status<500 && http.status!=408 && http.status!=429 || cause instanceof IllegalArgumentException) {
                                for(var c:batch) {
                                    for(int i=0;i<s.changes.size();i++) if(s.changes.get(i).chestId().equals(c.chestId())) s.changes.set(i,s.changes.get(i).status("rejected",s.changes.get(i).baseServerRevision()));
                                    s.diagnostics.put(c.changeId(),c.chestId()+": rejected (HTTP request/configuration)");
                                }
                                s.failures=0; persist(key,s); result.complete(0); return;
                            }
                            throw new IOException("Sync unavailable");
                        }
                        Response response=GSON.fromJson(body,Response.class);
                        if(response==null || !response.success() || response.protocolVersion()!=1 || response.results()==null) throw new IOException("Invalid protocol response");
                        var responses=new HashMap<String,Result>();
                        for(var r:response.results()) if(r==null || responses.put(r.changeId(),r)!=null) throw new IOException("Duplicate response ID");
                        for(var c:batch) {
                            var r=responses.get(c.changeId());
                            if(r==null || !c.chestId().equals(r.chestId()) || c.revision()!=r.revision() || !Set.of("applied","duplicate","conflict","rejected").contains(r.status()) || Set.of("applied","duplicate").contains(r.status()) && (r.serverRevision()==null || r.serverRevision()!=c.baseServerRevision()+1)) throw new IOException("Unmatched confirmation");
                        }
                        for(var c:batch) {
                            var r=responses.get(c.changeId());
                            if(Set.of("applied","duplicate").contains(r.status())) {
                                s.changes.removeIf(v -> v.changeId().equals(c.changeId())); s.serverRevisions.put(c.chestId(),r.serverRevision()); accepted++;
                                var resolved=s.inventoryRecoveries.remove(c.changeId());
                                if(resolved!=null) {
                                    s.changes.removeIf(v -> resolved.contains(v.changeId()));
                                    for(String id:resolved) { s.diagnostics.remove(id); s.inventoryRecoveries.remove(id); }
                                    s.diagnostics.remove(c.changeId());
                                }
                            } else {
                                for(int i=0;i<s.changes.size();i++) if(s.changes.get(i).changeId().equals(c.changeId())) s.changes.set(i,c.status(r.status(),c.baseServerRevision()));
                                s.diagnostics.put(c.changeId(),c.chestId()+": "+r.status()+" (server revision "+r.serverRevision()+")");
                                // Dependent pending changes must not bypass the conflict.
                                for(int i=0;i<s.changes.size();i++) if(s.changes.get(i).chestId().equals(c.chestId()) && s.changes.get(i).status().equals("pending")) s.changes.set(i,s.changes.get(i).status("conflict",null));
                            }
                        }
                        stage(s); // Preserve edits that arrived while a recovery request was in flight.
                        s.bootstrapped=true; s.failures=0; s.lastSuccess=System.currentTimeMillis(); s.due=System.currentTimeMillis()+requestSettings.debounceSeconds*1000L;
                        persist(key,s); result.complete(accepted);
                    } catch(Exception e) {
                        s.failures++; s.due=System.currentTimeMillis()+Math.min(requestSettings.retryMaxSeconds*1000L,requestSettings.retryInitialSeconds*1000L*(1L<<Math.min(s.failures-1,16)));
                        try { persist(key,s); } catch(IOException ignored) { /* original on-disk sent entries remain retryable */ }
                        result.completeExceptionally(new IOException("Sync unavailable; data retained"));
                    } finally { flights.remove(key); publish(key,s); }
                }
            },worker);
            return result;
        } catch(Exception e) { return CompletableFuture.failedFuture(e); }
    }
    public synchronized List<Change> pending(String world) throws IOException { return List.copyOf(state(world).changes); }
    public synchronized void close() { closed=true; worker.shutdown(); }
}
