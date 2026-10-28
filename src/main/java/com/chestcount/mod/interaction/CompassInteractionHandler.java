package com.chestcount.mod.interaction;

import com.chestcount.mod.ChestCountClient;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;

public final class CompassInteractionHandler {
    public static void register(ChestCountClient app) {
        UseBlockCallback.EVENT.register((player,level,hand,hit) -> {
            if (!level.isClientSide() || player.isSpectator() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (app.world() == null || !app.ready()) return InteractionResult.PASS;
            var identity = ChestLocator.locate(app.mc(),app.world(),hit.getBlockPos());
            if (identity.isEmpty()) { app.sync().cancelPending(); return InteractionResult.PASS; }
            app.registry().observe(identity.get());
            if (player.isShiftKeyDown() && (player.getMainHandItem().is(Items.COMPASS) || player.getOffhandItem().is(Items.COMPASS))) {
                try {
                    boolean existed = app.registry().all().stream().anyMatch(r -> r.identity().equals(identity.get()));
                    app.registry().register(identity.get());
                    app.message(existed ? "registered_already" : "registered");
                } catch (RuntimeException e) { app.message("warning",e.getMessage()); }
                app.sync().cancelPending();
                // FAIL cancels locally without sending a packet or swinging. Open normally afterwards.
                return InteractionResult.FAIL;
            }
            if (!player.isShiftKeyDown()) app.sync().clicked(identity.get(),hit.getBlockPos());
            return InteractionResult.PASS;
        });
    }
}
