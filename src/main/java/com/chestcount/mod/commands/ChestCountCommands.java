package com.chestcount.mod.commands;

import com.chestcount.mod.ChestCountClient;
import com.chestcount.mod.model.ChestRecord;
import com.chestcount.mod.service.ItemSearchService;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.*;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.core.registries.BuiltInRegistries;
import java.time.*;
import java.util.*;
import java.util.function.Consumer;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.*;

public final class ChestCountCommands {
    private static final Map<String,Long> removals = new HashMap<>();
    private static final int PAGE_SIZE = 10;
    private static String str(CommandContext<FabricClientCommandSource> c,String key) { return StringArgumentType.getString(c,key); }
    public static String quote(String text) { return "\"" + text.replace("\\","\\\\").replace("\"","\\\"") + "\""; }
    public static String name(String text) {
        text=text.strip();
        if (!text.startsWith("\"")) return text;
        try {
            var reader=new StringReader(text); var value=reader.readString();
            if (reader.canRead()) throw new IllegalArgumentException("invalid_name");
            return value;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new IllegalArgumentException("invalid_name"); }
    }
    private static int run(ChestCountClient app, Runnable action) {
        if (app.world() == null || !app.ready()) { app.message("not_ready"); return 0; }
        try { action.run(); app.flush(); return 1; } catch (RuntimeException e) {
            String key=e.getMessage();
            app.message(Set.of("not_found","ambiguous_chest","invalid_name","duplicate_name").contains(key==null?"":key)?key:"command_error");
            return 0;
        }
    }
    public static String position(ChestRecord r) { var p=r.identity().blocks().getFirst(); return "["+p.x()+", "+p.y()+", "+p.z()+"]"; }
    private static void show(ChestCountClient app, ChestRecord r) {
        app.line(r.name()); app.message("position",position(r));
        if (app.mc().level != null && !r.identity().dimension().equals(app.mc().level.dimension().identifier().toString())) app.message("dimension",r.identity().dimension());
        app.message(r.identity().slots()==54?"double_info":"single_info",r.total());
        if (r.syncedAt()==null) app.message("never_synced");
        else { long minutes=Math.max(0,Duration.between(Instant.parse(r.syncedAt()),Instant.now()).toMinutes());
            app.message(minutes<1?"synced_now":minutes<60?"synced_minutes":"synced_hours",minutes<60?minutes:minutes/60); }
        if(r.structureChanged()) app.message("structure_changed");
    }
    private static void warnIncomplete(ChestCountClient app) {
        if (app.registry().world(app.world()).stream().anyMatch(r -> r.structureChanged() || r.syncedAt()==null)) app.message("incomplete_data");
    }
    private static <T> void page(ChestCountClient app,List<T> list,int page,String next,Consumer<T> show) {
        if(list.isEmpty()) { app.message("no_results"); return; }
        long start=(long)(page-1)*PAGE_SIZE;
        list.stream().skip(start).limit(PAGE_SIZE).forEach(show);
        if(start+PAGE_SIZE<list.size()) app.message("more",next+(page+1));
    }
    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<FabricClientCommandSource,String> id(ChestCountClient app,boolean greedy) {
        return argument("id",greedy?StringArgumentType.greedyString():StringArgumentType.string()).suggests((c,b) -> {
            String remaining=b.getRemainingLowerCase();
            for(var value:app.registry().suggestions(app.world())) {
                String suggestion=value.matches("[A-Za-z0-9_+.-]+")?value:quote(value);
                if(value.toLowerCase(Locale.ROOT).startsWith(remaining) || suggestion.toLowerCase(Locale.ROOT).startsWith(remaining)) b.suggest(suggestion);
            }
            return b.buildFuture();
        });
    }
    public static void register(ChestCountClient app) {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher,access) -> {
            var root=literal("chestcount").executes(c -> help(app));
            root.then(literal("help").executes(c -> help(app)));
            root.then(literal("count").executes(c -> run(app,() -> {
                var list=app.registry().world(app.world());
                app.message("count",list.size(),list.stream().mapToLong(ChestRecord::total).sum(),list.stream().flatMap(r -> r.items().stream()).map(i -> i.item_id()).distinct().count());
                warnIncomplete(app);
            })));
            root.then(literal("list").executes(c -> list(app,1)).then(argument("page",IntegerArgumentType.integer(1)).executes(c -> list(app,IntegerArgumentType.getInteger(c,"page")))));
            root.then(literal("info").then(id(app,true).executes(c -> run(app,() -> show(app,app.registry().get(name(str(c,"id")),app.world()))))));
            root.then(literal("location").then(id(app,true).executes(c -> run(app,() -> {
                var r=app.registry().get(name(str(c,"id")),app.world()); app.line(r.name()+" "+position(r));
            }))));
            root.then(literal("rename").then(id(app,false).then(argument("name",StringArgumentType.greedyString()).executes(c -> run(app,() -> {
                var r=app.registry().get(str(c,"id"),app.world());
                app.registry().rename("uuid:"+r.id(),app.world(),name(str(c,"name"))); app.message("renamed",app.registry().get("uuid:"+r.id(),app.world()).name());
            })))));
            root.then(literal("remove").then(id(app,false).executes(c -> run(app,() -> {
                var r=app.registry().get(str(c,"id"),app.world()); removals.put(r.id(),System.nanoTime()+30_000_000_000L);
                app.message("confirm_remove",r.name(),quote(app.registry().identifier(r)));
            })).then(literal("confirm").executes(c -> run(app,() -> {
                var r=app.registry().get(str(c,"id"),app.world()); var expiry=removals.remove(r.id());
                if(expiry==null || expiry<System.nanoTime()) { app.message("confirmation_expired"); return; }
                app.registry().remove("uuid:"+r.id(),app.world()); app.message("removed");
            })))));
            root.then(literal("nearby").executes(c -> nearby(app,app.config().nearbyRadius,1)).then(argument("radius",IntegerArgumentType.integer(1,4096))
                .executes(c -> nearby(app,IntegerArgumentType.getInteger(c,"radius"),1))
                .then(argument("page",IntegerArgumentType.integer(1)).executes(c -> nearby(app,IntegerArgumentType.getInteger(c,"radius"),IntegerArgumentType.getInteger(c,"page"))))));
            root.then(literal("where").then(argument("item",StringArgumentType.greedyString()).suggests((c,b) -> {
                BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).filter(s -> s.contains(b.getRemainingLowerCase())).limit(100).forEach(b::suggest); return b.buildFuture();
            }).executes(c -> run(app,() -> {
                String query=str(c,"item").toLowerCase(Locale.ROOT); int number=1;
                int space=query.lastIndexOf(' ');
                if(space>0 && query.substring(space+1).matches("[0-9]+")) { number=Integer.parseInt(query.substring(space+1)); query=query.substring(0,space); }
                if(number<1) throw new IllegalArgumentException();
                final String search=query;
                var exact=BuiltInRegistries.ITEM.keySet().stream().filter(i -> i.toString().equals(search.contains(":")?search:"minecraft:"+search)).toList();
                var matches=exact.isEmpty()?BuiltInRegistries.ITEM.keySet().stream().filter(i -> i.toString().contains(search) || BuiltInRegistries.ITEM.getValue(i).getDefaultInstance().getHoverName().getString().toLowerCase(Locale.ROOT).equals(search)).toList():exact;
                if(matches.size()!=1) { app.message("ambiguous"); return; }
                var item=matches.getFirst(); var results=ItemSearchService.find(app.registry().world(app.world()),item.toString());
                app.message("found",BuiltInRegistries.ITEM.getValue(item).getDefaultInstance().getHoverName().getString(),results.stream().mapToLong(ItemSearchService.Match::count).sum(),results.size());
                page(app,results,number,"/cc where "+item+" ",m -> app.line("• "+m.chest().name()+" — ×"+m.count()+" "+position(m.chest())));
                warnIncomplete(app);
            }))));
            root.then(literal("sync").executes(c -> { app.manualSync(); return 1; })
                .then(literal("status").executes(c -> { app.syncStatus(); return 1; }))
                .then(literal("on").executes(c -> { app.automaticSync(true); return 1; }))
                .then(literal("off").executes(c -> { app.automaticSync(false); return 1; })));
            root.then(literal("backup").executes(c -> { app.archiveCommand("backup",null,false); return 1; })
                .then(literal("list").executes(c -> { app.archiveCommand("list",null,false); return 1; })));
            root.then(literal("export").executes(c -> { app.archiveCommand("export",null,false); return 1; }));
            root.then(literal("import").then(argument("file",StringArgumentType.word()).suggests((c,b) -> {
                app.importSuggestions().stream().filter(f -> f.startsWith(b.getRemaining())).forEach(b::suggest); return b.buildFuture();
            }).executes(c -> { app.archiveCommand("import",str(c,"file"),false); return 1; })
                .then(literal("confirm").executes(c -> { app.archiveCommand("import",str(c,"file"),true); return 1; }))));
            root.then(literal("reload").executes(c -> { app.reload(); return 1; }));
            var node=dispatcher.register(root);
            dispatcher.register(literal("cc").executes(c -> help(app)).redirect(node));
        });
    }
    private static int list(ChestCountClient app,int number) { return run(app,() -> {
        var list=app.registry().world(app.world()); app.message("listed",list.size());
        page(app,list,number,"/cc list ",r -> {
            String identifier=app.registry().identifier(r);
            app.line("• "+r.name()+(identifier.equals(r.name())?"":" ("+identifier+")")+" "+position(r)+(r.structureChanged()?" ⚠":""));
        });
    }); }
    private static int nearby(ChestCountClient app,int radius,int number) { return run(app,() -> {
        var p=app.mc().player;
        var list=app.registry().nearby(app.world(),app.mc().level.dimension().identifier().toString(),p.getX(),p.getY(),p.getZ(),radius);
        app.message("nearby",list.size());
        page(app,list,number,"/cc nearby "+radius+" ",r -> app.message("nearby_entry",r.name(),Math.round(r.identity().blocks().getFirst().distance(p.getX(),p.getY(),p.getZ()))));
    }); }
    private static int help(ChestCountClient app) { app.message("help"); return 1; }
}
