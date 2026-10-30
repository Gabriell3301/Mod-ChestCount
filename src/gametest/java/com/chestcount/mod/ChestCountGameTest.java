package com.chestcount.mod;

import com.chestcount.mod.interaction.ChestLocator;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.core.Direction;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.phys.*;
import net.minecraft.world.entity.player.Input;
import net.fabricmc.fabric.api.client.command.v2.*;

/** Real integrated server packets and UI. Test-only source set, excluded from the release JAR. */
public final class ChestCountGameTest implements FabricClientGameTest {
    private static final class TestHttp implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final java.util.Map<String,com.google.gson.JsonObject> records=new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String,Long> revisions=new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Map<String,com.chestcount.mod.integration.SyncProtocol.Result> receipts=new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.Set<String> conflictOnce=java.util.concurrent.ConcurrentHashMap.newKeySet();
        final java.util.concurrent.atomic.AtomicInteger conflicts=new java.util.concurrent.atomic.AtomicInteger(), revisionReads=new java.util.concurrent.atomic.AtomicInteger();
        TestHttp() {
            try {
                server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
                server.createContext("/api/v1/sync",exchange -> {
                    var gson=new com.google.gson.Gson();
                    var request=gson.fromJson(new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8),com.chestcount.mod.integration.SyncProtocol.Request.class);
                    var results=new java.util.ArrayList<com.chestcount.mod.integration.SyncProtocol.Result>();
                    for(var c:request.changes()) {
                        var receipt=receipts.get(c.changeId());
                        if(receipt==null && conflictOnce.remove(c.chestId())) {
                            var changed=records.get(c.chestId()).deepCopy();
                            changed.getAsJsonArray("items").get(0).getAsJsonObject().addProperty("count",2);
                            records.put(c.chestId(),changed); revisions.merge(c.chestId(),1L,Long::sum);
                        }
                        if(receipt==null && c.baseServerRevision()!=revisions.getOrDefault(c.chestId(),0L)) {
                            receipt=new com.chestcount.mod.integration.SyncProtocol.Result(c.changeId(),c.chestId(),c.revision(),"conflict",revisions.get(c.chestId()),null);
                            receipts.put(c.changeId(),receipt); conflicts.incrementAndGet();
                        }
                        if(receipt==null) {
                            var data=c.data().deepCopy();
                            switch(c.operation()) {
                                case "upsert" -> records.put(c.chestId(),data);
                                case "rename" -> records.get(c.chestId()).addProperty("name",data.get("name").getAsString());
                                case "structure_changed" -> records.get(c.chestId()).addProperty("structureChanged",data.get("structureChanged").getAsBoolean());
                                case "delete" -> records.remove(c.chestId());
                                default -> throw new java.io.IOException("Unexpected operation");
                            }
                            receipt=new com.chestcount.mod.integration.SyncProtocol.Result(c.changeId(),c.chestId(),c.revision(),"applied",revisions.merge(c.chestId(),1L,Long::sum),null);
                            receipts.put(c.changeId(),receipt);
                        }
                        results.add(receipt);
                    }
                    byte[] bytes=gson.toJson(new com.chestcount.mod.integration.SyncProtocol.Response(true,1,results)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                });
                server.createContext("/api/v1/worlds/",exchange -> {
                    revisionReads.incrementAndGet();
                    var path=exchange.getRequestURI().getPath(); String id=path.substring(path.lastIndexOf('/')+1);
                    var data=records.get(id).deepCopy(); data.addProperty("serverRevision",revisions.get(id)); data.addProperty("deleted",false);
                    byte[] bytes=new com.google.gson.Gson().toJson(data).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.sendResponseHeaders(200,bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
                }); server.start();
            } catch(java.io.IOException e) { throw new RuntimeException(e); }
        }
        String endpoint() { return "http://127.0.0.1:"+server.getAddress().getPort()+"/api/v1/sync"; }
        boolean received(long total) {
            return records.size()==1 && records.values().stream().anyMatch(value -> new com.google.gson.Gson().fromJson(value,com.chestcount.mod.model.ChestRecord.class).total()==total);
        }
        public void close() { server.stop(0); }
    }
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    private static long persistedTotal() {
        var root=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("chestcount");
        var app=ChestCountClient.INSTANCE;
        try {
            var text=java.nio.file.Files.readString(new com.chestcount.mod.storage.JsonStorageService(root).file(app.world()));
            return new com.google.gson.Gson().fromJson(text,com.chestcount.mod.storage.JsonStorageService.Document.class).chests().getFirst().total();
        } catch(Exception e) { return -1; }
    }
    private static int persistedConflicts() {
        var app=ChestCountClient.INSTANCE;
        var root=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("chestcount/incremental");
        var file=root.resolve(com.chestcount.mod.storage.JsonStorageService.key(app.config().sync.endpoint))
            .resolve(com.chestcount.mod.storage.JsonStorageService.key(app.world())+".json");
        try {
            var state=com.google.gson.JsonParser.parseString(java.nio.file.Files.readString(file)).getAsJsonObject();
            int conflicts=0;
            for(var value:state.getAsJsonArray("changes")) if(value.getAsJsonObject().get("status").getAsString().equals("conflict")) conflicts++;
            return conflicts;
        } catch(Exception e) { return -1; }
    }
    public void runTest(ClientGameTestContext context) {
        try (var http=new TestHttp(); var world=context.worldBuilder().create()) {
            context.waitFor(mc -> ChestCountClient.INSTANCE.ready());
            context.runOnClient(mc -> {
                var config=ChestCountClient.INSTANCE.config().sync;
                config.endpoint=http.endpoint(); config.enabled=true; config.automatic=true;
            });
            var pos=new BlockPos(0,80,0);
            world.getServer().runCommand("tp @a 0 81 3 180 25");
            world.getServer().runOnServer(server -> {
                var level=server.overworld();
                for(int x=-3;x<=3;x++) for(int z=-3;z<=4;z++) level.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
                level.setBlockAndUpdate(pos.west(2),Blocks.CHEST.defaultBlockState());
                ((ChestBlockEntity)level.getBlockEntity(pos.west(2))).setItem(0,new ItemStack(Items.DIAMOND,5));
                var chest=(ChestBlockEntity)level.getBlockEntity(pos);
                chest.setItem(0,new ItemStack(Items.DIAMOND,32));
                chest.setItem(26,new ItemStack(Items.IRON_INGOT,64));
                server.getPlayerList().getPlayers().getFirst().getInventory().setItem(9,new ItemStack(Items.DIAMOND,11));
            });
            context.waitFor(mc -> mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos).is(Blocks.CHEST));
            context.runOnClient(mc -> {
                var app=ChestCountClient.INSTANCE;
                check(app.registry().all().isEmpty(),"Unregistered chest must not be registered automatically");
                var id=ChestLocator.locate(mc,app.world(),pos).orElseThrow();
                mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FEATHER));
                var original=mc.player.input.keyPresses;
                mc.player.input.keyPresses=new Input(false,false,false,false,false,true,false);
                var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.SOUTH,pos,false);
                try {
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.COMPASS));
                    check(net.fabricmc.fabric.api.event.player.UseBlockCallback.EVENT.invoker().interact(mc.player,mc.level,InteractionHand.MAIN_HAND,hit)==InteractionResult.PASS && app.registry().all().isEmpty(),"Compass no longer associates chests");
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.FEATHER));
                    check(mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit)==InteractionResult.FAIL,"Feather registration must cancel without sending a block-use packet");
                    mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    mc.player.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.FEATHER));
                    check(mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit)==InteractionResult.FAIL,"Offhand feather must register safely too");
                    mc.player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
                    check(app.registry().all().size()==1,"Repeated feather registration must not duplicate the record");
                } finally { mc.player.input.keyPresses=original; }
                // Simulate a pending registered click whose menu never arrived, then open an unmarked chest.
                app.sync().clicked(id,pos);
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos.west(2)),Direction.SOUTH,pos.west(2),false));
            });
            context.waitFor(mc -> mc.player.containerMenu instanceof ChestMenu);
            context.waitTicks(3);
            context.runOnClient(mc -> {
                var app=ChestCountClient.INSTANCE;
                check(app.registry().all().size()==1 && app.registry().all().getFirst().syncedAt()==null,"Unmarked chest cannot consume an older pending association");
                mc.player.closeContainer();
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.SOUTH,pos,false));
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().syncedAt()!=null);
            context.runOnClient(mc -> {
                var r=ChestCountClient.INSTANCE.registry().all().getFirst();
                check(r.total()==96,"Full packet must include 27 chest slots only");
                check(mc.player.containerMenu instanceof ChestMenu,"Actual chest screen opened");
            });
            world.getServer().runOnServer(server -> {
                ((ChestBlockEntity)server.overworld().getBlockEntity(pos)).setItem(0,new ItemStack(Items.DIAMOND,7));
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==71);
            // Real client prediction used to hide these changes from the packet-only snapshot.
            context.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,0,0,net.minecraft.world.inventory.ContainerInput.QUICK_MOVE,mc.player));
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==64);
            context.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,26,0,net.minecraft.world.inventory.ContainerInput.PICKUP,mc.player));
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==0);
            context.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,1,0,net.minecraft.world.inventory.ContainerInput.PICKUP,mc.player));
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==64);
            context.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,1,0,net.minecraft.world.inventory.ContainerInput.SWAP,mc.player));
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==0);
            context.runOnClient(mc -> mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,1,0,net.minecraft.world.inventory.ContainerInput.SWAP,mc.player));
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==64);
            context.runOnClient(mc -> {
                mc.gameMode.handleContainerInput(mc.player.containerMenu.containerId,1,0,net.minecraft.world.inventory.ContainerInput.QUICK_MOVE,mc.player);
                mc.player.closeContainer(); // Do not wait for a reply before closing.
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==0);
            context.waitFor(mc -> persistedTotal()==0); // No command or explicit flush.
            context.runOnClient(mc -> mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.SOUTH,pos,false)));
            context.waitFor(mc -> mc.player.containerMenu instanceof ChestMenu && ChestCountClient.INSTANCE.registry().all().getFirst().total()==0);
            world.getServer().runOnServer(server -> {
                var chest=(ChestBlockEntity)server.overworld().getBlockEntity(pos);
                chest.setItem(0,new ItemStack(Items.DIAMOND,7));
                chest.setItem(26,new ItemStack(Items.IRON_INGOT,64));
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==71);
            context.runOnClient(mc -> mc.player.closeContainer());
            context.waitFor(mc -> persistedTotal()==71);
            context.waitFor(mc -> http.received(71)); // Actual close -> disk -> automatic HTTP, without a sync command.
            // Conflict on one inventory update, followed by a real reopening and a fresh authoritative packet.
            context.runOnClient(mc -> {
                http.conflictOnce.add(ChestCountClient.INSTANCE.registry().all().getFirst().id());
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.SOUTH,pos,false));
            });
            context.waitFor(mc -> mc.player.containerMenu instanceof ChestMenu);
            world.getServer().runOnServer(server -> ((ChestBlockEntity)server.overworld().getBlockEntity(pos)).setItem(0,new ItemStack(Items.DIAMOND,9)));
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().total()==73);
            context.runOnClient(mc -> mc.player.closeContainer());
            context.waitFor(mc -> persistedTotal()==73 && http.conflicts.get()==1 && persistedConflicts()==1);
            context.waitTicks(25);
            context.runOnClient(mc -> {
                check(http.revisionReads.get()==0,"Conflict does not trigger revision lookups before reopening");
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos),Direction.SOUTH,pos,false));
            });
            context.waitFor(mc -> mc.player.containerMenu instanceof ChestMenu);
            context.waitTicks(5); // Receive the authoritative full content and integrated identity verification.
            context.runOnClient(mc -> mc.player.closeContainer());
            context.waitFor(mc -> http.revisionReads.get()>0 && http.received(73) && persistedConflicts()==0);
            world.getServer().runOnServer(server -> {
                var physical=(ChestBlockEntity)server.overworld().getBlockEntity(pos);
                check(physical.getItem(0).getCount()==9,"HTTP conflict recovery must not modify physical inventory");
                physical.setItem(0,new ItemStack(Items.DIAMOND,7)); // Restore the fixture for the existing double-chest assertions.
            });
            context.runOnClient(mc -> {
                var dispatcher=ClientCommands.getActiveDispatcher();
                var source=(FabricClientCommandSource)mc.getConnection().getSuggestionsProvider();
                try {
                    for(var command:java.util.List.of("cc sync status","cc sync off","cc sync","cc sync on","cc backup","cc backup list","cc export"))
                        check(dispatcher.execute(command,source)==1,"Sync command registered: "+command);
                } catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
            });
            context.waitTicks(5);
            // Build an actual double and verify both half states canonicalize to the same identity.
            world.getServer().runOnServer(server -> {
                var level=server.overworld();
                level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT));
                level.setBlockAndUpdate(pos.east(),Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT));
                ((ChestBlockEntity)level.getBlockEntity(pos.east())).setItem(0,new ItemStack(Items.DIAMOND,5));
            });
            context.waitTicks(25);
            context.runOnClient(mc -> {
                var app=ChestCountClient.INSTANCE;
                var left=ChestLocator.locate(mc,app.world(),pos).orElseThrow();
                var right=ChestLocator.locate(mc,app.world(),pos.east()).orElseThrow();
                check(left.equals(right) && left.slots()==54,"Both physical halves must identify the same double");
                check(app.registry().all().getFirst().structureChanged(),"Old single snapshot must be preserved and marked changed");
                app.config().alwaysShowOutlines=true;
                app.registry().remove(app.registry().all().getFirst().id(),app.world());
                app.registry().register(left);
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(pos.east()),Direction.SOUTH,pos.east(),false));
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.registry().all().getFirst().syncedAt()!=null);
            context.runOnClient(mc -> {
                check(ChestCountClient.INSTANCE.registry().all().getFirst().total()==76,"Double must include both halves but not player inventory");
                var dispatcher=ClientCommands.getActiveDispatcher();
                var source=(FabricClientCommandSource)mc.getConnection().getSuggestionsProvider();
                try {
                    check(dispatcher.execute("chestcount count",source)==1,"Count command must be local and registered");
                    check(dispatcher.execute("cc count",source)==1,"Short alias shares handlers");
                    check(dispatcher.execute("cc",source)==1,"Bare alias shows help");
                    var app=ChestCountClient.INSTANCE;
                    var record=app.registry().all().getFirst();
                    check(dispatcher.execute("cc rename "+record.name()+" Minerios",source)==1,"Rename by generated name");
                    check(dispatcher.execute("cc info Minerios",source)==1,"Immediate lookup by name");
                    var suggestions=dispatcher.getCompletionSuggestions(dispatcher.parse("cc info M",source)).join();
                    check(suggestions.getList().stream().anyMatch(s -> s.getText().equals("Minerios")),"Autocomplete names through alias");
                    check(dispatcher.execute("cc rename Minerios \"Baú de Minérios\"",source)==1,"Quoted new name");
                    check(dispatcher.execute("cc info \"Baú de Minérios\"",source)==1,"Quoted lookup");
                    check(dispatcher.execute("cc remove \"Baú de Minérios\"",source)==1,"Quoted removal request");
                    check(app.registry().all().size()==1,"Removal request must not delete");
                    check(dispatcher.execute("cc location Baú de Minérios",source)==1,"Legacy unquoted location");
                    check(dispatcher.execute("cc info Minerios",source)==0,"Old name must stop resolving");
                    check(dispatcher.execute("cc rename \"Baú de Minérios\" Recursos",source)==1,"Quoted source name");
                    check(dispatcher.execute("cc list",source)==1,"Compact list");
                    check(dispatcher.execute("cc nearby",source)==1,"Compact nearby");
                    check(dispatcher.execute("chestcount where diamond",source)==1,"Item search must work through client dispatcher");
                } catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
                mc.player.closeContainer();
                mc.player.setYRot(180); mc.player.setXRot(25);
            });
            context.waitTicks(15); // Exercises outline extraction in an actual rendered world.
            context.takeScreenshot("chest-count-double");
            world.getServer().runOnServer(server -> {
                for(int x=-3;x<=3;x++) for(int y=80;y<=84;y++) server.overworld().setBlockAndUpdate(new BlockPos(x,y,2),Blocks.STONE.defaultBlockState());
            });
            context.waitTicks(25);
            context.takeScreenshot("chest-count-occluded");
            context.runOnClient(mc -> {
                var app=ChestCountClient.INSTANCE;
                var directory=net.fabricmc.loader.api.FabricLoader.getInstance().getConfigDir().resolve("chestcount/imports");
                try {
                    java.nio.file.Files.createDirectories(directory);
                    java.nio.file.Files.writeString(directory.resolve("gametest.json"),new com.google.gson.Gson().toJson(new com.chestcount.mod.storage.JsonStorageService.Document(1,app.world(),app.registry().world(app.world()))));
                    var dispatcher=ClientCommands.getActiveDispatcher();
                    var source=(FabricClientCommandSource)mc.getConnection().getSuggestionsProvider();
                    check(dispatcher.execute("cc import gametest.json",source)==1,"Import preview command");
                } catch(Exception e) { throw new AssertionError(e); }
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.ready());
            context.runOnClient(mc -> {
                try {
                    var dispatcher=ClientCommands.getActiveDispatcher();
                    var source=(FabricClientCommandSource)mc.getConnection().getSuggestionsProvider();
                    check(dispatcher.execute("cc import gametest.json confirm",source)==1,"Confirmed import command");
                } catch(Exception e) { throw new AssertionError(e); }
            });
            context.waitFor(mc -> ChestCountClient.INSTANCE.ready());
            context.runOnClient(mc -> {
                var dispatcher=ClientCommands.getActiveDispatcher();
                var source=(FabricClientCommandSource)mc.getConnection().getSuggestionsProvider();
                try {
                    check(dispatcher.execute("cc remove Recursos confirm",source)==1,"Confirm removal by current name");
                    check(ChestCountClient.INSTANCE.registry().all().isEmpty(),"Only confirmed removal deletes the record");
                } catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
            });
        }
    }
}
