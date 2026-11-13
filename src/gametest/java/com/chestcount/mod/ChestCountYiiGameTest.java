package com.chestcount.mod;

import com.chestcount.mod.integration.*;
import com.chestcount.mod.interaction.ChestLocator;
import com.chestcount.mod.service.WorldIdentityService;
import com.google.gson.*;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.command.v2.*;
import net.minecraft.core.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.*;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Opt-in real graphical client -> Yii2 HTTP -> isolated MySQL, followed by the browser runner. */
public final class ChestCountYiiGameTest implements FabricClientGameTest {
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); }
    private static void command(net.minecraft.client.Minecraft mc,String text) {
        try { check(ClientCommands.getActiveDispatcher().execute(text,(FabricClientCommandSource)mc.getConnection().getSuggestionsProvider())==1,"Client command failed"); }
        catch(com.mojang.brigadier.exceptions.CommandSyntaxException e) { throw new AssertionError(e); }
    }
    private static boolean published(HttpSyncProvider provider,String world,String chest,int total) {
        try {
            var body=provider.readChest(world,chest).toCompletableFuture().get(2,TimeUnit.SECONDS);
            var value=JsonParser.parseString(body).getAsJsonObject(); int observed=0;
            for(var item:value.getAsJsonArray("items")) observed+=item.getAsJsonObject().get("count").getAsInt();
            return !value.get("syncedAt").isJsonNull() && observed==total;
        } catch(Exception e) { return false; }
    }
    public void runTest(ClientGameTestContext context) {
        String fixturePath=System.getenv("CHESTCOUNT_YII_GAME_FIXTURE");
        if(fixturePath==null || fixturePath.isBlank()) return;
        try {
            Path path=Path.of(fixturePath);
            var fixture=JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            String endpoint=fixture.get("endpoint").getAsString();
            check(endpoint.equals("http://127.0.0.1:8090/index-test.php/api/v1/sync"),"Only the isolated local test entrypoint is allowed");
            org.slf4j.LoggerFactory.getLogger("chestcount-test").info("CHESTCOUNT_YII_GAME_TEST_STARTED");
            try(var world=context.worldBuilder().create()) {
                context.waitFor(mc->ChestCountClient.INSTANCE.ready());
                context.runOnClient(mc->{
                    var config=ChestCountClient.INSTANCE.config().sync;config.endpoint=endpoint;config.enabled=true;config.automatic=true;config.debounceSeconds=0;config.playerSnapshotSeconds=10;config.autoChestSeconds=10;
                    command(mc,"cc connect "+fixture.get("code").getAsString());
                });
                context.waitFor(mc->{try{return DeviceCredentials.load(endpoint)!=null;}catch(Exception e){return false;}});
                context.runOnClient(mc->command(mc,"cc status"));
                var simple=new BlockPos(0,80,0);var doublePos=new BlockPos(4,80,0);
                world.getServer().runOnServer(server->{
                    var level=server.overworld();
                    for(int x=-2;x<8;x++) for(int z=-2;z<5;z++) level.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                    level.setBlockAndUpdate(simple,Blocks.CHEST.defaultBlockState());
                    ((ChestBlockEntity)level.getBlockEntity(simple)).setItem(0,new ItemStack(Items.DIAMOND,32));
                    ((ChestBlockEntity)level.getBlockEntity(simple)).setItem(26,new ItemStack(Items.IRON_INGOT,16));
                    level.setBlockAndUpdate(doublePos,Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.LEFT));
                    level.setBlockAndUpdate(doublePos.east(),Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING,Direction.NORTH).setValue(ChestBlock.TYPE,ChestType.RIGHT));
                    ((ChestBlockEntity)level.getBlockEntity(doublePos)).setItem(0,new ItemStack(Items.GOLD_INGOT,64));
                    ((ChestBlockEntity)level.getBlockEntity(doublePos.east())).setItem(26,new ItemStack(Items.DIAMOND,5));
                });
                world.getServer().runCommand("tp @a 0 81 3 180 25");
                context.waitFor(mc->mc.level.hasChunkAt(doublePos)&&mc.level.getBlockState(doublePos.east()).is(Blocks.CHEST));
                context.runOnClient(mc->{
                    var app=ChestCountClient.INSTANCE;
                    var first=app.registry().register(ChestLocator.locate(mc,app.world(),simple).orElseThrow());
                    var second=app.registry().register(ChestLocator.locate(mc,app.world(),doublePos).orElseThrow());
                    fixture.addProperty("gameSourceWorldId",app.world());fixture.addProperty("originalWorldName",WorldIdentityService.name(mc));
                    fixture.addProperty("gameSimpleChestId",first.id());fixture.addProperty("gameDoubleChestId",second.id());
                    app.flush();
                    mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(simple),Direction.SOUTH,simple,false));
                });
                context.waitFor(mc->mc.player.containerMenu instanceof ChestMenu && ChestCountClient.INSTANCE.registry().get("uuid:"+fixture.get("gameSimpleChestId").getAsString(),ChestCountClient.INSTANCE.world()).total()==48);
                context.runOnClient(mc->mc.player.closeContainer());
                var settings=new com.chestcount.mod.config.ModConfig.Sync();settings.endpoint=endpoint;var provider=new HttpSyncProvider(settings);
                context.waitFor(mc->published(provider,fixture.get("gameSourceWorldId").getAsString(),fixture.get("gameSimpleChestId").getAsString(),48));
                world.getServer().runCommand("tp @a 4 81 3 180 25");context.waitTicks(5);
                context.runOnClient(mc->mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(doublePos),Direction.SOUTH,doublePos,false)));
                context.waitFor(mc->mc.player.containerMenu instanceof ChestMenu && ChestCountClient.INSTANCE.registry().get("uuid:"+fixture.get("gameDoubleChestId").getAsString(),ChestCountClient.INSTANCE.world()).total()==69);
                context.runOnClient(mc->mc.player.closeContainer());
                context.waitFor(mc->published(provider,fixture.get("gameSourceWorldId").getAsString(),fixture.get("gameDoubleChestId").getAsString(),69));
                context.runOnClient(mc -> {
                    var app = ChestCountClient.INSTANCE;
                    var record = app.registry().get("uuid:" + fixture.get("gameDoubleChestId").getAsString(), app.world());
                    check(app.registry().toggleAutoSync(record), "Auto-Sync flag was not enabled");
                    app.flush();
                    fixture.addProperty("gamePlayerUuid", mc.player.getUUID().toString());
                });
                world.getServer().runCommand("item replace entity @a hotbar.3 with minecraft:diamond 7");
                world.getServer().runCommand("item replace entity @a weapon.offhand with minecraft:torch 9");
                world.getServer().runOnServer(server ->
                    ((ChestBlockEntity) server.overworld().getBlockEntity(doublePos.east())).setItem(26, new ItemStack(Items.DIAMOND, 12)));
                context.waitFor(mc -> {
                    var app = ChestCountClient.INSTANCE;
                    return app.registry().get("uuid:" + fixture.get("gameDoubleChestId").getAsString(), app.world()).total() == 76;
                }, 800);
                context.waitFor(mc -> published(provider, fixture.get("gameSourceWorldId").getAsString(), fixture.get("gameDoubleChestId").getAsString(), 76), 400);
                fixture.addProperty("gameDoubleTotal", 76);
                context.waitTicks(220);
                Files.writeString(path,new GsonBuilder().setPrettyPrinting().create().toJson(fixture));
                context.runOnClient(mc->command(mc,"cc disconnect"));
                context.waitFor(mc->{try{return DeviceCredentials.load(endpoint)==null;}catch(Exception e){return false;}});
            }
            org.slf4j.LoggerFactory.getLogger("chestcount-test").info("CHESTCOUNT_YII_GAME_TEST_COMPLETED");
        } catch(Exception e) { throw new AssertionError("Real Yii2 game integration failed",e); }
    }
}
