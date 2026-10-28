package com.chestcount.mod.commands;

import com.chestcount.mod.ChestCountClient;
import com.chestcount.mod.model.ChestRecord;
import com.chestcount.mod.service.ItemSearchService;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;
import java.util.function.Consumer;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public final class ChestCountCommands {
    private static final Map<String,Long> removals = new HashMap<>();
    private static String str(CommandContext<FabricClientCommandSource> c,String key) { return StringArgumentType.getString(c,key); }
    private static int run(ChestCountClient app, Runnable action) {
        if (app.world() == null || !app.ready()) { app.message("not_ready"); return 0; }
        try { action.run(); return 1; } catch (RuntimeException e) { app.message("error",e.getMessage()); return 0; }
    }
    private static String label(ChestRecord r) { return r.name()+" ["+r.id()+"] — "+r.identity().blocks().getFirst()+" — "+r.identity().dimension(); }
    private static void show(ChestCountClient app, ChestRecord r) { app.line(label(r)); app.message("snapshot",r.total(),r.syncedAt()==null ? "—" : r.syncedAt(),r.structureChanged() ? "⚠" : ""); }
    private static void each(ChestCountClient app, List<ChestRecord> list, Consumer<ChestRecord> show) { if(list.isEmpty()) app.message("no_results"); else list.forEach(show); }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource,String> id(ChestCountClient app) {
        return argument("id",StringArgumentType.word()).suggests((c,b) -> { app.registry().world(app.world()).forEach(r -> { if(r.id().startsWith(b.getRemaining())) b.suggest(r.id()); }); return b.buildFuture(); });
    }
    public static void register(ChestCountClient app) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access) -> {
            var root = literal("chestcount").executes(c -> help(app));
            root.then(literal("help").executes(c -> help(app)));
            root.then(literal("count").executes(c -> run(app,() -> {
                var list=app.registry().world(app.world()); long doubles=list.stream().filter(r -> r.identity().slots()==54).count();
                long types=list.stream().flatMap(r -> r.items().stream()).map(i -> i.item_id()).distinct().count();
                app.message("count",list.size(),list.size()-doubles,doubles,list.stream().mapToLong(ChestRecord::total).sum(),types);
                app.message("last_known");
                list.forEach(r -> app.message("snapshot_named",r.name(),r.syncedAt()==null?"—":r.syncedAt()));
            })));
            root.then(literal("list").executes(c -> run(app,() -> each(app,app.registry().world(app.world()),r -> show(app,r)))));
            root.then(literal("info").then(id(app).executes(c -> run(app,() -> show(app,app.registry().get(str(c,"id"),app.world()))))));
            root.then(literal("location").then(argument("name",StringArgumentType.greedyString()).suggests((c,b) -> {
                app.registry().world(app.world()).forEach(r -> { if(r.name().toLowerCase(Locale.ROOT).startsWith(b.getRemainingLowerCase())) b.suggest(r.name()); }); return b.buildFuture();
            }).executes(c -> run(app,() -> each(app,app.registry().byName(app.world(),str(c,"name")),r -> show(app,r))))));
            root.then(literal("rename").then(id(app).then(argument("name",StringArgumentType.greedyString()).executes(c -> run(app,() -> {
                app.registry().rename(str(c,"id"),app.world(),str(c,"name")); app.message("renamed");
            })))));
            root.then(literal("remove").then(id(app).executes(c -> run(app,() -> {
                var r=app.registry().get(str(c,"id"),app.world()); removals.put(r.id(),System.nanoTime()+30_000_000_000L); app.message("confirm_remove",r.id());
            })).then(literal("confirm").executes(c -> run(app,() -> {
                var r=app.registry().get(str(c,"id"),app.world()); var expiry=removals.remove(r.id());
                if(expiry==null || expiry<System.nanoTime()) { app.message("confirmation_expired"); return; }
                app.registry().remove(r.id(),app.world()); app.message("removed");
            })))));
            root.then(literal("nearby").executes(c -> nearby(app,app.config().nearbyRadius)).then(argument("radius",IntegerArgumentType.integer(1,4096)).executes(c -> nearby(app,IntegerArgumentType.getInteger(c,"radius")))));
            root.then(literal("where").then(argument("item",StringArgumentType.greedyString()).suggests((c,b) -> {
                BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).filter(s -> s.contains(b.getRemainingLowerCase())).forEach(b::suggest); return b.buildFuture();
            }).executes(c -> run(app,() -> {
                String query=str(c,"item").toLowerCase(Locale.ROOT);
                var exact=BuiltInRegistries.ITEM.keySet().stream().filter(i -> i.toString().equals(query.contains(":")?query:"minecraft:"+query)).toList();
                var matches=exact.isEmpty()?BuiltInRegistries.ITEM.keySet().stream().filter(i -> i.toString().contains(query) || BuiltInRegistries.ITEM.getValue(i).getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT).equals(query)).toList():exact;
                if(matches.size()!=1) { app.message("ambiguous",matches.stream().limit(30).map(Object::toString).toList().toString()); return; }
                var results=ItemSearchService.find(app.registry().world(app.world()),matches.getFirst().toString());
                app.message("found",matches.getFirst().toString(),results.size(),results.stream().mapToLong(ItemSearchService.Match::count).sum());
                results.forEach(m -> { app.line(label(m.chest())); app.message("snapshot",m.count(),m.chest().syncedAt(),m.chest().structureChanged()?"⚠":""); });
                app.message("last_known");
            }))));
            root.then(literal("reload").executes(c -> { app.reload(); return 1; }));
            dispatcher.register(root);
        });
    }
    private static int nearby(ChestCountClient app,int radius) { return run(app,() -> {
        var p=app.mc().player;
        each(app,app.registry().nearby(app.world(),app.mc().level.dimension().identifier().toString(),p.getX(),p.getY(),p.getZ(),radius),r -> {
            show(app,r); app.message("distance",Math.round(r.identity().blocks().getFirst().distance(p.getX(),p.getY(),p.getZ())));
        });
    }); }
    private static int help(ChestCountClient app) { app.message("help"); return 1; }
}
