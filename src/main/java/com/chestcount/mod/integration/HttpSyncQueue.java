package com.chestcount.mod.integration;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.Gson;
import java.nio.file.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/** One latest durable document per world; acknowledgement is tied to its content and destination. */
public final class HttpSyncQueue implements AutoCloseable {
    private record State(String world, String document, String acknowledged, String destination, long lastSuccess, Set<String> held) {}
    public record Status(boolean pending, boolean offline, long lastSuccess, boolean sending, boolean awaitingConfirmations) {}
    private final Path root;
    private final Gson gson = new Gson();
    private final Map<String,State> states = new ConcurrentHashMap<>();
    private final Map<String,Long> due = new ConcurrentHashMap<>();
    private final Map<String,Integer> failures = new ConcurrentHashMap<>();
    private final Map<String,CompletableFuture<Integer>> flights = new ConcurrentHashMap<>();
    private final ExecutorService completion = Executors.newSingleThreadExecutor(r -> { var t=new Thread(r,"Chest Count HTTP acknowledgements"); t.setDaemon(true); return t; });
    private boolean closed;
    public HttpSyncQueue(Path root) { this.root=root.resolve("outbox"); }
    private Path file(String world) { return root.resolve(JsonStorageService.key(world)+".json"); }
    private State state(String world) throws IOException {
        if (!states.containsKey(world)) {
            State s=Files.exists(file(world))?gson.fromJson(Files.readString(file(world)),State.class):new State(world,null,null,null,0,Set.of());
            if (s==null || !world.equals(s.world)) throw new IOException("Invalid outbox world");
            states.put(world,s);
        }
        return states.get(world);
    }
    private void persist(State s) throws IOException { JsonStorageService.atomic(file(s.world),gson.toJson(s)); states.put(s.world,s); }
    public synchronized void update(JsonStorageService.Document document) throws IOException { update(document,false); }
    public synchronized void update(JsonStorageService.Document document, boolean hold) throws IOException {
        if(document.chests().stream().anyMatch(chest -> !document.world().equals(chest.identity().world())))
            throw new IOException("Foreign chest in sync document");
        var old=state(document.world()); String json=gson.toJson(document);
        var held=new HashSet<>(old.held);
        // Removed chests cannot block the remaining document indefinitely.
        var ids=document.chests().stream().map(com.chestcount.mod.model.ChestRecord::id).collect(java.util.stream.Collectors.toSet());
        held.removeIf(id -> !id.equals("*") && !ids.contains(id));
        if(hold) held.add("*");
        if (!json.equals(old.document) || !held.equals(old.held)) {
            persist(new State(old.world,json,old.acknowledged,old.destination,old.lastSuccess,Set.copyOf(held)));
            due.put(old.world,Math.max(System.currentTimeMillis()+2000,failures.containsKey(old.world)?due.getOrDefault(old.world,0L):0L));
        }
    }
    public void hold(String world) throws IOException { hold(world,"*"); }
    public synchronized void hold(String world, String chest) throws IOException {
        var s=state(world); var held=new HashSet<>(s.held);
        if(held.add(chest)) persist(new State(s.world,s.document,s.acknowledged,s.destination,s.lastSuccess,Set.copyOf(held)));
    }
    public void release(String world) throws IOException { release(world,"*"); }
    public synchronized void release(String world, String chest) throws IOException {
        var s=state(world); var held=new HashSet<>(s.held);
        if(held.remove(chest)) persist(new State(s.world,s.document,s.acknowledged,s.destination,s.lastSuccess,Set.copyOf(held)));
    }
    private String fingerprint(State s, ModConfig.Sync config) { return JsonStorageService.key(config.endpoint+"\n"+s.document); }
    public Status status(String world, ModConfig.Sync config) {
        State s=states.get(world);
        return new Status(s!=null && s.document!=null && (!s.held.isEmpty() || !fingerprint(s,config).equals(s.acknowledged)),failures.getOrDefault(world,0)>0,s==null?0:s.lastSuccess,flights.containsKey(world),s!=null && !s.held.isEmpty());
    }
    public synchronized CompletionStage<Integer> attempt(String world, ModConfig.Sync settings, boolean manual) {
        if (closed) return CompletableFuture.failedFuture(new IOException("Sync stopped"));
        if (flights.containsKey(world)) return flights.get(world);
        try {
            final ModConfig.Sync config=gson.fromJson(gson.toJson(settings),ModConfig.Sync.class);
            State s=state(world);
            if (!s.held.isEmpty()) return CompletableFuture.failedFuture(new IOException("Awaiting confirmed inventory"));
            if (s.document==null) return CompletableFuture.completedFuture(0);
            if (!manual && (!config.enabled || !config.automatic || !status(world,config).pending || System.currentTimeMillis()<due.getOrDefault(world,0L))) return CompletableFuture.completedFuture(0);
            String hash=fingerprint(s,config);
            int count=gson.fromJson(s.document,JsonStorageService.Document.class).chests().size();
            var result=new CompletableFuture<Integer>(); flights.put(world,result);
            new HttpSyncProvider(config).send(s.document).whenCompleteAsync((ignored,error) -> {
                synchronized (this) {
                    try {
                        if (error==null) {
                            State latest=state(world);
                            persist(new State(world,latest.document,hash,config.endpoint,System.currentTimeMillis(),latest.held));
                            failures.remove(world);
                            due.put(world,System.currentTimeMillis()+ (latest.document.equals(s.document)?config.intervalSeconds*1000L:2000));
                            result.complete(count);
                        } else {
                            int n=failures.merge(world,1,Integer::sum);
                            due.put(world,System.currentTimeMillis()+Math.min(300_000L,Math.max(config.intervalSeconds*1000L,5000L)*(1L<<Math.min(n-1,6))));
                            result.completeExceptionally(new IOException("Sync unavailable"));
                        }
                    } catch (Exception e) { result.completeExceptionally(e); }
                    finally { flights.remove(world); }
                }
            },completion);
            return result;
        } catch (Exception e) { flights.remove(world); return CompletableFuture.failedFuture(e); }
    }
    public synchronized void close() { closed=true; completion.shutdown(); }
}
