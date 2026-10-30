package com.chestcount.mod;

import com.chestcount.mod.commands.ChestCountCommands;
import com.chestcount.mod.config.ModConfig;
import com.chestcount.mod.interaction.*;
import com.chestcount.mod.rendering.ChestOutlineRenderer;
import com.chestcount.mod.service.*;
import com.chestcount.mod.storage.JsonStorageService;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.*;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

public final class ChestCountClient implements ClientModInitializer {
    public static ChestCountClient INSTANCE;
    private final ChestRegistryService registry = new ChestRegistryService();
    private final InventorySyncService sync = new InventorySyncService(this);
    private final ExecutorService disk = Executors.newSingleThreadExecutor(r -> new Thread(r,"Chest Count disk"));
    private final Path root = FabricLoader.getInstance().getConfigDir().resolve("chestcount");
    private final JsonStorageService storage = new JsonStorageService(root);
    private final com.chestcount.mod.integration.IncrementalSyncQueue http = new com.chestcount.mod.integration.IncrementalSyncQueue(root);
    private final com.chestcount.mod.storage.WorldArchiveService archives=new com.chestcount.mod.storage.WorldArchiveService(root);
    private final ConfirmedInventorySnapshots confirmedInventories=new ConfirmedInventorySnapshots();
    private final Map<String,JsonStorageService.Document> persistedSnapshots=new HashMap<>();
    private final Map<String,String> importConfirmations=new HashMap<>();
    private volatile List<String> importSuggestions=List.of();
    private ModConfig config = new ModConfig();
    private String world;
    private boolean ready;
    private long ticks, savedRevision, generation;
    private long lastErrorTick = -200;
    public Minecraft mc() { return Minecraft.getInstance(); }
    public ModConfig config() { return config; }
    public ChestRegistryService registry() { return registry; }
    public InventorySyncService sync() { return sync; }
    public String world() { return world; }
    public boolean ready() { return ready; }
    public long ticks() { return ticks; }
    public void onInitializeClient() {
        INSTANCE = this;
        disk.execute(() -> {
            try { var loaded = ModConfig.load(root.resolve("config.json")); http.configure(loaded.sync); mc().execute(() -> config = loaded); }
            catch (Exception e) { error(e); }
        });
        ChestAssociationHandler.register(this); ChestCountCommands.register(this); ChestOutlineRenderer.register(this);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc) -> { flush(); generation++; world=null; ready=false; sync.reset(); registry.load(List.of()); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            flush(); http.close(); disk.shutdown();
            try { if (!disk.awaitTermination(10,TimeUnit.SECONDS)) LoggerFactory.getLogger("chestcount").error("Pending disk writes did not finish before shutdown"); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
    }
    private void tick() {
        ticks++;
        String current = WorldIdentityService.current(mc(),config);
        if (!Objects.equals(current,world)) {
            flush(); generation++; world=current; ready=false; sync.reset(); registry.load(List.of());
            if (world != null) loadWorld();
        }
        sync.tick();
        if (ready && savedRevision!=registry.revision()) flush();
        if (ready && world != null && ticks%20 == 0 && sync.uploadSafe()) {
            var target=world; var settings=config.sync;
            disk.execute(() -> { try {
                var doc=persistedSnapshots.get(target);
                if(doc!=null) { http.configure(settings); http.update(doc); finishConfirmedInventories(doc); http.attempt(target,settings,false); }
            } catch(Exception e) { error(e); } });
        }
        if(ready && world!=null && ticks%20==0) {
            var doc=new JsonStorageService.Document(1,world,registry.world(world)); var options=config.backup;
            disk.execute(() -> { try { archives.automatic(doc,options,System.currentTimeMillis()); importSuggestions=archives.importFiles(); } catch(Exception e) { error(e); } });
        }
        if (ready && ticks%20 == 0 && mc().level != null) {
            for (var r : registry.world(world)) {
                if (!r.identity().dimension().equals(mc().level.dimension().identifier().toString())) continue;
                for (var p : r.identity().blocks()) {
                    var bp = ChestLocator.block(p);
                    if (!mc().level.hasChunkAt(bp)) continue;
                    var observed = ChestLocator.locate(mc(),world,bp);
                    if (observed.isPresent()) registry.observe(observed.get());
                    else if (!mc().level.getBlockState(bp).is(net.minecraft.world.level.block.Blocks.CHEST)) registry.invalidate(r.id());
                }
                if (!r.structureChanged() && registry.get("uuid:"+r.id(),world).structureChanged()) {
                    LoggerFactory.getLogger("chestcount").warn("Chest {} changed structure; preserving snapshot and blocking synchronization",r.id());
                    message("structure_changed");
                }
            }
        }
    }
    private void loadWorld() {
        var requested = world; var token = generation;
        disk.execute(() -> {
            try { var records = storage.load(requested); persistedSnapshots.put(requested,new JsonStorageService.Document(1,requested,records));
                try { http.update(new JsonStorageService.Document(1,requested,records)); } catch(Exception e) { error(e); }
                mc().execute(() -> {
                if (token != generation) return;
                registry.load(records); savedRevision=registry.revision(); ready=true;
            }); } catch (Exception e) { error(e); }
        });
    }
    public void reload() {
        if (world == null) { message("not_ready"); return; }
        boolean previouslyReady=ready;
        boolean dirty=ready && savedRevision != registry.revision();
        var unsaved=registry.world(world);
        ready=false; sync.reset(); var token = ++generation; var requested = world;
        disk.execute(() -> {
            try {
                if (dirty) { storage.save(requested,unsaved); persistedSnapshots.put(requested,new JsonStorageService.Document(1,requested,unsaved)); http.update(new JsonStorageService.Document(1,requested,unsaved)); }
                var loadedConfig = ModConfig.load(root.resolve("config.json")); http.configure(loadedConfig.sync);
                var records = storage.load(requested); persistedSnapshots.put(requested,new JsonStorageService.Document(1,requested,records)); http.update(new JsonStorageService.Document(1,requested,records));
                mc().execute(() -> { if (token == generation) { config=loadedConfig; registry.load(records); savedRevision=registry.revision(); ready=true; message("reloaded"); } });
            } catch (Exception e) { mc().execute(() -> { if (token == generation) ready=previouslyReady; }); error(e); }
        });
    }
    public void flush() {
        if (!ready || world == null || savedRevision == registry.revision()) return;
        var target = world; var data = registry.world(world); var revision = registry.revision(); var token = generation; var settings=config.sync;
        savedRevision=revision;
        disk.execute(() -> {
            try { storage.save(target,data); persistedSnapshots.put(target,new JsonStorageService.Document(1,target,data)); }
            catch(Exception e) {
                mc().execute(() -> { if(token==generation && savedRevision==revision) savedRevision=-1; }); error(e); return;
            }
            try {
                http.configure(settings); var document=new JsonStorageService.Document(1,target,data);
                http.update(document); finishConfirmedInventories(document);
            }
            catch(Exception e) { error(e); } // Local persistence remains usable if the outbox needs repair.
        });
    }

    public void inventoryOpened(String chest) {
        var target=world; var settings=config.sync;
        if(target!=null) disk.execute(() -> { try { confirmedInventories.invalidate(target,chest); http.configure(settings); http.opened(target,chest); } catch(Exception e) { error(e); } });
    }
    public void inventoryPending(String chest) {
        var target=world; var settings=config.sync;
        if(target!=null) disk.execute(() -> { try { confirmedInventories.invalidate(target,chest); http.configure(settings); http.hold(target,chest); } catch(Exception e) { error(e); } });
    }
    public void inventoryFinalized(String chest) {
        var snapshot=registry().get("uuid:"+chest,world);
        flush(); var target=world; var settings=config.sync;
        if(target!=null) disk.execute(() -> { try {
            confirmedInventories.confirmed(snapshot);
            var document=persistedSnapshots.get(target);
            if(document==null) return;
            http.configure(settings); http.update(document); finishConfirmedInventories(document);
        } catch(Exception e) { error(e); } });
    }
    private void finishConfirmedInventories(JsonStorageService.Document document) throws java.io.IOException {
        confirmedInventories.persisted(document,snapshot -> {
            http.release(document.world(),snapshot.id());
            http.recoverInventory(document.world(),snapshot);
        });
    }
    public void manualSync() {
        if (!ready || world==null) { message("not_ready"); return; }
        if (!sync.uploadSafe()) { line("A aguardar o fecho do baú e as confirmações do inventário."); return; }
        flush(); var target=world; var settings=config.sync;
        line("Sincronização iniciada.");
        disk.execute(() -> http.attempt(target,settings,true).whenComplete((count,error) -> mc().execute(() -> {
            if (!Objects.equals(world,target)) return;
            if(error!=null && "Awaiting confirmed inventory".equals(error.getMessage()))
                line("Confirmação de inventário pendente. Reabre e fecha o baú afetado.");
            else if(error==null && http.status(target,settings).conflicts()>0) line("Alterações em conflito ou rejeitadas. Consulta /cc sync status.");
            else line(error==null?count+" alterações confirmadas.":"Servidor indisponível. Dados guardados localmente.");
        })));
    }
    public void syncStatus() {
        if (world==null) { message("not_ready"); return; }
        var status=http.status(world,config.sync);
        line(status.offline()?"Servidor indisponível":"Sincronização "+(config.sync.enabled && config.sync.automatic?"ativa":"automática desativada"));
        if(status.awaitingConfirmations()) line("A aguardar confirmações: reabre e fecha o baú afetado.");
        line("Servidor: "+config.sync.endpoint);
        line("Pendentes: "+status.pendingCount()+" | conflitos/rejeitados: "+status.conflicts());
        status.diagnostics().stream().limit(5).forEach(this::line);
        line(status.lastSuccess()==0?"Último envio: nunca":"Último envio: há "+Math.max(0,(System.currentTimeMillis()-status.lastSuccess())/1000)+" segundos");
    }
    public void automaticSync(boolean enabled) {
        var previous=config.sync.automatic;
        config.sync.automatic=enabled;
        if (enabled) config.sync.enabled=true;
        // Serialize a detached config so later reloads cannot alter this write.
        var copy=new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(config),ModConfig.class);
        disk.execute(() -> { try { copy.save(root.resolve("config.json")); mc().execute(() -> line("Sincronização automática "+(enabled?"ativada.":"desativada."))); }
            catch (Exception e) { mc().execute(() -> config.sync.automatic=previous); error(e); } });
    }
    public List<String> importSuggestions() { return importSuggestions; }
    public void archiveCommand(String operation,String filename,boolean confirm) {
        if(!ready || world==null) { message("not_ready"); return; }
        if(operation.equals("import") && !sync.uploadSafe()) { line("Fecha o baú antes de importar."); return; }
        flush(); var doc=new JsonStorageService.Document(1,world,registry.world(world)); var token=generation;
        if(operation.equals("import")) ready=false;
        disk.execute(() -> {
            try {
                String text;
                switch(operation) {
                    case "backup" -> text="Backup: "+archives.backup(doc);
                    case "list" -> text="Backups: "+String.join(", ",archives.list(doc.world()));
                    case "export" -> text="Exportado: "+archives.export(doc);
                    case "import" -> {
                        if(!confirm) {
                            var preview=archives.preview(filename,doc); importConfirmations.put(doc.world()+"/"+filename,preview.fingerprint());
                            text="Importar: "+preview.created()+" novos, "+preview.replaced()+" existentes. Confirma com /cc import "+filename+" confirm";
                        } else {
                            var result=archives.apply(filename,doc,importConfirmations.remove(doc.world()+"/"+filename),storage);
                            persistedSnapshots.put(doc.world(),result);
                            mc().execute(() -> { if(token==generation) { sync.reset(); registry.load(result.chests()); savedRevision=registry.revision(); } });
                            http.update(result);
                            text="Importação concluída; backup anterior preservado.";
                        }
                    }
                    default -> throw new IllegalArgumentException("Unknown archive action");
                }
                mc().execute(() -> { if(token==generation) { if(operation.equals("import")) ready=true; line(text); } });
            } catch(Exception e) {
                mc().execute(() -> { if(token==generation) { if(operation.equals("import")) { ready=true; savedRevision=-1; } line("Operação recusada: "+e.getMessage()); } });
            }
        });
    }
    private void error(Exception e) {
        LoggerFactory.getLogger("chestcount").error("Storage operation failed",e);
        mc().execute(() -> {
            if (ticks-lastErrorTick >= 200) { lastErrorTick=ticks; message("error"); }
        });
    }
    public void message(String key, Object... args) {
        if (mc().player == null) return;
        ChatFormatting color = key.equals("error") ? ChatFormatting.RED : key.equals("warning") || key.equals("unsafe_menu") ? ChatFormatting.YELLOW
            : Set.of("registered","renamed","removed","reloaded").contains(key) ? ChatFormatting.GREEN : ChatFormatting.AQUA;
        mc().gui.hud.getChat().addClientSystemMessage(Component.literal("[Chest Count] ").withStyle(color).append(Component.translatable("chestcount."+key,args).withStyle(ChatFormatting.GRAY)));
    }
    public void line(String text) { message("text",text); }
}
