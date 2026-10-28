package com.chestcount.mod.interaction;

import com.chestcount.mod.model.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.properties.ChestType;
import java.util.*;

public final class ChestLocator {
    public static Position position(BlockPos pos) { return new Position(pos.getX(),pos.getY(),pos.getZ()); }
    public static BlockPos block(Position pos) { return new BlockPos(pos.x(),pos.y(),pos.z()); }
    public static Optional<ChestIdentity> locate(Minecraft mc, String world, BlockPos pos) {
        if (mc.level == null || world == null || !mc.level.hasChunkAt(pos)) return Optional.empty();
        var state = mc.level.getBlockState(pos);
        if (!state.is(Blocks.CHEST)) return Optional.empty();
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE)
            return Optional.of(new ChestIdentity(world,mc.level.dimension().identifier().toString(),List.of(position(pos))));
        var other = ChestBlock.getConnectedBlockPos(pos,state);
        if (!mc.level.hasChunkAt(other)) return Optional.empty();
        var second = mc.level.getBlockState(other);
        if (!second.is(Blocks.CHEST) || second.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                || second.getValue(ChestBlock.TYPE) == state.getValue(ChestBlock.TYPE)
                || second.getValue(ChestBlock.FACING) != state.getValue(ChestBlock.FACING)
                || !ChestBlock.getConnectedBlockPos(other,second).equals(pos)) return Optional.empty();
        return Optional.of(new ChestIdentity(world,mc.level.dimension().identifier().toString(),List.of(position(pos),position(other))));
    }
}
