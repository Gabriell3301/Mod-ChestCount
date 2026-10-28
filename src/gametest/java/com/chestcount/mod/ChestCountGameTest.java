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
    private static void check(boolean condition,String message) { if(!condition) throw new AssertionError(message); }
    public void runTest(ClientGameTestContext context) {
        try (var world=context.worldBuilder().create()) {
            context.waitFor(mc -> ChestCountClient.INSTANCE.ready());
            var pos=new BlockPos(0,80,0);
            world.getServer().runCommand("tp @a 0 81 3 180 25");
            world.getServer().runOnServer(server -> {
                var level=server.overworld();
                for(int x=-3;x<=3;x++) for(int z=-3;z<=4;z++) level.setBlockAndUpdate(new BlockPos(x,79,z),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos.below(),Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(pos,Blocks.CHEST.defaultBlockState());
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
                mc.player.setItemInHand(InteractionHand.MAIN_HAND,new ItemStack(Items.COMPASS));
                var original=mc.player.input.keyPresses;
                mc.player.input.keyPresses=new Input(false,false,false,false,false,true,false);
                var hit=new BlockHitResult(Vec3.atCenterOf(pos),Direction.SOUTH,pos,false);
                try {
                    check(mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit)==InteractionResult.FAIL,"Compass registration must cancel without sending a block-use packet");
                    mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
                    mc.player.setItemInHand(InteractionHand.MAIN_HAND,ItemStack.EMPTY);
                    mc.player.setItemInHand(InteractionHand.OFF_HAND,new ItemStack(Items.COMPASS));
                    check(mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit)==InteractionResult.FAIL,"Offhand compass must register safely too");
                    mc.player.setItemInHand(InteractionHand.OFF_HAND,ItemStack.EMPTY);
                    check(app.registry().all().size()==1,"Repeated compass registration must not duplicate the record");
                } finally { mc.player.input.keyPresses=original; }
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
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
            context.runOnClient(mc -> mc.player.closeContainer());
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
        }
    }
}
