package com.chestcount.mod.interaction;

import com.chestcount.mod.ChestCountClient;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;

/**
 * Relaciona a interação com o bloco e o registo local do baú.
 *
 * A combinação de agachar e pena regista o baú ou alterna Auto-Sync local;
 * a abertura normal é encaminhada para a observação confirmada do inventário.
 */
public final class ChestAssociationHandler {
    public static void register(ChestCountClient app) {
        UseBlockCallback.EVENT.register((player,level,hand,hit) -> {
            if (!level.isClientSide() || player.isSpectator() || hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
            if (app.world() == null || !app.ready()) return InteractionResult.PASS;
            var identity = ChestLocator.locate(app.mc(),app.world(),hit.getBlockPos());
            if (identity.isEmpty()) { app.sync().cancelPending(); return InteractionResult.PASS; }
            app.registry().observe(identity.get());
            if (player.isShiftKeyDown() && (player.getMainHandItem().is(Items.FEATHER) || player.getOffhandItem().is(Items.FEATHER))) {
                try {
                    boolean existed = app.registry().all().stream().anyMatch(r -> r.identity().equals(identity.get()));
                    var record = app.registry().register(identity.get());
                    if (!existed) {
                        app.message("registered", record.name());
                    } else if (app.registry().exact(identity.get()).isPresent()) {
                        if (app.mc().hasSingleplayerServer()) {
                            boolean enabled = app.registry().toggleAutoSync(record);
                            app.message(enabled ? "auto_chest_on" : "auto_chest_off", record.name());
                            app.flush();
                        } else {
                            app.message("auto_chest_remote");
                        }
                    }
                } catch (RuntimeException e) { app.message("warning",e.getMessage()); }
                app.sync().cancelPending();
                // FAIL cancels locally without sending a packet or swinging. Open normally afterwards.
                return InteractionResult.FAIL;
            }
            if (!player.isShiftKeyDown()) app.sync().clicked(identity.get(),hit.getBlockPos());
            else app.sync().cancelPending();
            return InteractionResult.PASS;
        });
    }
}
