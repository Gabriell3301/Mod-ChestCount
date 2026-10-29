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
            try { var loaded = ModConfig.load(root.resolve("config.json")); mc().execute(() -> config = loaded); }
            catch (Exception e) { error(e); }
        });
        CompassInteractionHandler.register(this); ChestCountCommands.register(this); ChestOutlineRenderer.register(this);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> tick());
        ClientPlayConnectionEvents.DISCONNECT.register((handler,mc) -> { flush(); generation++; world=null; ready=false; sync.reset(); registry.load(List.of()); });
        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
            flush(); disk.shutdown();
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
        if (ticks%40 == 0) flush();
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
            try { var records = storage.load(requested); mc().execute(() -> {
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
                if (dirty) storage.save(requested,unsaved);
                var loadedConfig = ModConfig.load(root.resolve("config.json"));
                var records = storage.load(requested);
                mc().execute(() -> { if (token == generation) { config=loadedConfig; registry.load(records); savedRevision=registry.revision(); ready=true; message("reloaded"); } });
            } catch (Exception e) { mc().execute(() -> { if (token == generation) ready=previouslyReady; }); error(e); }
        });
    }
    public void flush() {
        if (!ready || world == null || savedRevision == registry.revision()) return;
        var target = world; var data = registry.world(world); var revision = registry.revision(); var token = generation;
        savedRevision=revision;
        disk.execute(() -> { try { storage.save(target,data); } catch (Exception e) {
            mc().execute(() -> { if (token == generation && savedRevision == revision) savedRevision=-1; }); error(e);
        } });
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
