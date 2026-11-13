package com.chestcount.mod.service;

import com.chestcount.mod.ChestCountClient;
import com.chestcount.mod.interaction.ChestLocator;
import com.chestcount.mod.model.ChestRecord;
import com.chestcount.mod.model.ItemRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.ChestType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Observa automaticamente baús registados no servidor integrado do mundo local.
 *
 * Lê apenas chunks já carregados, sem abrir menus nem gerar loot; no multijogador
 * esta via não tem acesso ao inventário remoto e não é executada.
 */
public final class AutoChestSync {
    private long nextCheck;
    private String world;
    private boolean checking;

    /**
     * Agenda a leitura na thread do servidor integrado e valida o contexto ao regressar ao cliente.
     */
    public void tick(ChestCountClient app) {
        var client = app.mc();
        var server = client.getSingleplayerServer();
        var player = client.player;
        var settings = app.config().sync;
        if (!app.ready() || !settings.enabled || !settings.automatic || server == null || client.level == null
                || player == null || player.containerMenu != player.inventoryMenu || !app.sync().uploadSafe()) return;
        if (!java.util.Objects.equals(world, app.world())) {
            world = app.world();
            nextCheck = 0;
        }
        if (checking || app.ticks() < nextCheck) return;
        nextCheck = app.ticks() + settings.autoChestSeconds * 20L;
        var dimension = client.level.dimension();
        var id = player.getUUID();
        var candidates = app.registry().world(world).stream()
                .filter(chest -> chest.autoSync() && !chest.structureChanged() && chest.identity().dimension().equals(dimension.identifier().toString()))
                .toList();
        if (candidates.isEmpty()) return;
        checking = true;
        server.execute(() -> {
            var results = new LinkedHashMap<ChestRecord, List<ItemRecord>>();
            try {
                var serverPlayer = server.getPlayerList().getPlayer(id);
                var level = server.getLevel(dimension);
                if (serverPlayer == null || level == null || serverPlayer.level() != level
                        || serverPlayer.containerMenu != serverPlayer.inventoryMenu) return;
                for (var chest : candidates) {
    /**
     * Lê apenas estruturas válidas em chunks carregados e recusa contentores com loot por gerar.
     */
                    var items = read(level, chest);
                    if (items != null && (chest.syncedAt() == null || !chest.items().equals(items))) results.put(chest, items);
                }
            } finally {
                client.execute(() -> {
                    checking = false;
                    if (client.getSingleplayerServer() != server || client.level == null || !client.level.dimension().equals(dimension)
                            || client.player != player || player.containerMenu != player.inventoryMenu || !app.ready() || !app.sync().uploadSafe()) return;
                    results.forEach((chest, items) -> {
                        if (app.registry().exact(chest.identity()).orElse(null) == chest) {
                            app.registry().snapshot(chest.id(), chest.identity(), items);
                        }
                    });
                    if (!results.isEmpty()) app.flush();
                });
            }
        });
    }

    static List<ItemRecord> read(ServerLevel level, ChestRecord record) {
        var positions = record.identity().blocks().stream().map(ChestLocator::block).toList();
        for (var pos : positions) {
            if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null
                    || !level.getBlockState(pos).is(Blocks.CHEST)
                    || !(level.getBlockEntity(pos) instanceof ChestBlockEntity chest) || chest.getLootTable() != null) return null;
        }
        var first = positions.getFirst();
        var state = level.getBlockState(first);
        if (positions.size() == 1) {
            if (state.getValue(ChestBlock.TYPE) != ChestType.SINGLE) return null;
        } else {
            var second = positions.getLast();
            var other = level.getBlockState(second);
            if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE || other.getValue(ChestBlock.TYPE) == ChestType.SINGLE
                    || state.getValue(ChestBlock.TYPE) == other.getValue(ChestBlock.TYPE)
                    || state.getValue(ChestBlock.FACING) != other.getValue(ChestBlock.FACING)
                    || !ChestBlock.getConnectedBlockPos(first, state).equals(second)
                    || !ChestBlock.getConnectedBlockPos(second, other).equals(first)) return null;
        }
        var inventory = ChestBlock.getContainer((ChestBlock) Blocks.CHEST, state, level, first, true);
        if (inventory == null || inventory.getContainerSize() != record.identity().slots()) return null;
        var items = new ArrayList<ItemRecord>();
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            var stack = inventory.getItem(slot);
            if (!stack.isEmpty()) items.add(new ItemRecord(slot, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
        }
        return items;
    }
}
