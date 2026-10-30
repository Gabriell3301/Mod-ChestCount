package com.chestcount.mod.integration;

import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.model.ChestRecord;
import com.chestcount.mod.storage.JsonStorageService;
import com.google.gson.Gson;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** Upload-only transport. Redirects and remote inventory writes are deliberately unsupported. */
public final class HttpSyncProvider implements SyncProvider {
    public static final class HttpStatusException extends java.io.IOException {
        public final int status;
        HttpStatusException(int status) { super("Sync HTTP "+status); this.status=status; }
    }
    public static final int MAX_BYTES = 8 * 1024 * 1024;
    private final HttpClient client;
    private final URI uri;
    private final Duration timeout;
    public static URI endpoint(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing sync endpoint");
        URI uri;
        try { uri=URI.create(value); } catch(RuntimeException e) { throw new IllegalArgumentException("Invalid sync endpoint"); }
        String host = uri.getHost();
        if (host == null || uri.getRawUserInfo() != null || uri.getRawFragment() != null || uri.getRawQuery() != null)
            throw new IllegalArgumentException("Invalid sync endpoint (credentials/query/fragment forbidden)");
        boolean local = host.equalsIgnoreCase("localhost") || host.equals("127.0.0.1") || host.equals("[::1]") || host.equals("::1");
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !("http".equalsIgnoreCase(uri.getScheme()) && local))
            throw new IllegalArgumentException("Remote sync endpoints require HTTPS");
        return uri;
    }
    public HttpSyncProvider(ModConfig.Sync config) {
        config.validate(); uri = endpoint(config.endpoint);
        timeout = Duration.ofSeconds(config.requestTimeoutSeconds);
        client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(config.connectTimeoutSeconds))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }
    public CompletionStage<Void> send(String json) {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("Sync document exceeds 8 MiB"));
        var request = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        return client.sendAsync(request,HttpResponse.BodyHandlers.discarding()).thenApply(response -> {
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new CompletionException(new java.io.IOException("HTTP " + response.statusCode()));
            return null;
        });
    }
    private static HttpResponse.BodySubscriber<byte[]> limitedResponse() {
        var delegate=HttpResponse.BodySubscribers.ofByteArray();
        return new HttpResponse.BodySubscriber<>() {
            private java.util.concurrent.Flow.Subscription subscription;
            private long size;
            public CompletionStage<byte[]> getBody() { return delegate.getBody(); }
            public void onSubscribe(java.util.concurrent.Flow.Subscription s) { subscription=s; delegate.onSubscribe(s); }
            public void onNext(java.util.List<java.nio.ByteBuffer> buffers) {
                for(var buffer:buffers) size+=buffer.remaining();
                if(size>1024*1024) { subscription.cancel(); delegate.onError(new java.io.IOException("Sync response too large")); }
                else delegate.onNext(buffers);
            }
            public void onError(Throwable error) { delegate.onError(error); }
            public void onComplete() { delegate.onComplete(); }
        };
    }
    /** Bounded JSON response, read off the Minecraft thread. */
    public CompletionStage<String> exchange(String json) {
        byte[] bytes=json.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES) return CompletableFuture.failedFuture(new IllegalArgumentException("Sync request too large"));
        var request=HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type","application/json; charset=utf-8")
            .POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build();
        return client.sendAsync(request,info -> limitedResponse()).thenApply(response -> {
            if(response.statusCode()!=200) throw new CompletionException(new HttpStatusException(response.statusCode()));
            if(response.body().length>1024*1024) throw new CompletionException(new java.io.IOException("Invalid sync response"));
            return new String(response.body(),StandardCharsets.UTF_8);
        });
    }
    /** Read only the revision/metadata needed for an explicitly reopened chest. */
    public CompletionStage<String> readChest(String world,String chest) {
        java.util.UUID.fromString(chest);
        String encoded=java.net.URLEncoder.encode(world,StandardCharsets.UTF_8).replace("+","%20");
        URI target=uri.resolve("worlds/"+encoded+"/chests/"+chest);
        var request=HttpRequest.newBuilder(target).timeout(timeout).header("Accept","application/json").GET().build();
        return client.sendAsync(request,info -> limitedResponse()).thenApply(response -> {
            if(response.statusCode()!=200) throw new CompletionException(new HttpStatusException(response.statusCode()));
            return new String(response.body(),StandardCharsets.UTF_8);
        });
    }
    public CompletionStage<Void> enqueue(ChestRecord snapshot, long localRevision) {
        return send(new Gson().toJson(new JsonStorageService.Document(1,snapshot.identity().world(),List.of(snapshot))));
    }
}
