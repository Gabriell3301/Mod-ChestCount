package com.chestcount.mod.integration;

import com.chestcount.mod.config.ModConfig;
import com.google.gson.Gson;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Recolhe observações pessoais na thread do cliente e envia apenas um pedido de cada vez.
 *
 * A impressão sem data evita reenvios desnecessários; observações sem alterações têm
 * renovação periódica. Esta classe não mantém um histórico durável de snapshots.
 */
public final class PlayerSnapshotSync {
    private static final Gson GSON = new Gson();
    private static final List<EquipmentSlot> ARMOR = List.of(EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD);

    private record Slot(String area, int slot, String id, int count) {}
    private record State(float health, float maxHealth, int food, float saturation, int armor) {}
    private record Receipt(boolean success, long observedAt) {}
    private record Context(String endpoint, String world, String dimension, String player) {}
    private record Snapshot(String worldId, String dimension, String playerUuid, long observedAt,
                            int selectedSlot, List<Slot> slots, State state,
                            String minecraftVersion, String fabricVersion, String modVersion) {}

    private Context context;
    private long due;
    private int failures;
    private boolean inFlight;
    private String lastFingerprint;
    private long lastSent;
    private HttpSyncProvider provider;
    private String providerKey;

    /**
     * Recolhe slots ocupados e estado atual, respeitando configuração, contexto e intervalo de envio.
     */
    public void tick(Minecraft client, String world, ModConfig.Sync settings, Executor worker) {
        var player = client.player;
        if (!settings.enabled || !settings.automatic || !settings.playerSnapshots || world == null
                || player == null || client.level == null || client.getConnection() == null) {
            context = null;
            return;
        }
        var next = new Context(settings.endpoint, world, client.level.dimension().identifier().toString(), player.getUUID().toString());
        if (!next.equals(context)) {
            context = next;
            lastFingerprint = null;
            due = 0;
            failures = 0;
        }
        long now = System.currentTimeMillis();
        if (inFlight || now < due) return;
        var slots = new ArrayList<Slot>(41);
        for (int i = 0; i < 36; i++) add(slots, "inventory", i, player.getInventory().getItem(i));
        for (int i = 0; i < ARMOR.size(); i++) add(slots, "armor", i, player.getItemBySlot(ARMOR.get(i)));
        add(slots, "offhand", 0, player.getOffhandItem());
        var food = player.getFoodData();
        var state = new State(player.getHealth(), player.getMaxHealth(), food.getFoodLevel(), food.getSaturationLevel(), player.getArmorValue());
        var versions = SyncProtocol.Versions.current();
        var snapshot = new Snapshot(world, next.dimension(), next.player(), now, player.getInventory().getSelectedSlot(),
                slots, state, versions.minecraftVersion(), versions.fabricVersion(), versions.modVersion());
        String fingerprint = GSON.toJson(new Snapshot(world, next.dimension(), next.player(), 0,
                snapshot.selectedSlot(), slots, state, snapshot.minecraftVersion(), snapshot.fabricVersion(), snapshot.modVersion()));
        if (fingerprint.equals(lastFingerprint) && now - lastSent < Math.max(120, settings.playerSnapshotSeconds) * 1000L) {
            due = now + settings.playerSnapshotSeconds * 1000L;
            return;
        }
        Context active = context;
        var options = GSON.fromJson(GSON.toJson(settings), ModConfig.Sync.class);
        inFlight = true;
        worker.execute(() -> {
            try {
                String token = DeviceCredentials.token(next.endpoint());
                String key = next.endpoint() + "|" + options.connectTimeoutSeconds + "|" + options.requestTimeoutSeconds;
                if (!key.equals(providerKey)) {
                    provider = new HttpSyncProvider(options);
                    providerKey = key;
                }
                provider.deviceRequest("player/snapshot", GSON.toJson(snapshot), token)
                        .thenAccept(body -> {
                            var receipt = GSON.fromJson(body, Receipt.class);
                            if (receipt == null || !receipt.success() || receipt.observedAt() < snapshot.observedAt()) {
                                throw new IllegalArgumentException("Invalid player snapshot confirmation");
                            }
                        })
    /**
     * Aplica o resultado no contexto ainda ativo e calcula o atraso da próxima observação.
     *
     * Erros transitórios aumentam o backoff; falhas de credenciais evitam repetição imediata.
     */
                        .whenComplete((ignored, error) -> client.execute(() -> finish(active, options, fingerprint, error)));
            } catch (RuntimeException error) {
                client.execute(() -> finish(active, options, fingerprint, error));
            }
        });
    }

    /**
     * Aplica o resultado no contexto ainda ativo e calcula o atraso da próxima observação.
     *
     * Erros transitórios aumentam o backoff; falhas de credenciais evitam repetição imediata.
     */
    private void finish(Context sent, ModConfig.Sync settings, String fingerprint, Throwable error) {
        inFlight = false;
        if (sent != context) return;
        while (error instanceof CompletionException && error.getCause() != null) error = error.getCause();
        long delay = settings.playerSnapshotSeconds;
        if (error == null) {
            lastFingerprint = fingerprint;
            lastSent = System.currentTimeMillis();
            failures = 0;
        } else if (error instanceof IllegalStateException || error instanceof HttpSyncProvider.HttpStatusException status
                && (status.status == 401 || status.status == 403 || status.status == 404)) {
            delay = Math.max(delay, 30);
        } else {
            failures++;
            delay = Math.min(settings.retryMaxSeconds, settings.retryInitialSeconds * (1L << Math.min(failures - 1, 16)));
        }
        due = System.currentTimeMillis() + delay * 1000L;
    }

    public void reconnect() {
        context = null;
    }

    private static void add(List<Slot> slots, String area, int index, ItemStack stack) {
        if (!stack.isEmpty()) slots.add(new Slot(area, index, BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), stack.getCount()));
    }
}
