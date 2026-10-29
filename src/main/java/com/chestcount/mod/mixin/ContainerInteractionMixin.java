package com.chestcount.mod.mixin;

import com.chestcount.mod.ChestCountClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(MultiPlayerGameMode.class)
public abstract class ContainerInteractionMixin {
    /** Vanilla processes the real click, then sends full state on revision mismatch.
     * No extra clicks and no predicted slots are promoted to confirmed data. */
    @Redirect(method="handleContainerInput", at=@At(value="INVOKE", target="Lnet/minecraft/world/inventory/AbstractContainerMenu;getStateId()I"))
    private int chestcount$confirm(AbstractContainerMenu menu) {
        return ChestCountClient.INSTANCE.sync().requestConfirmation(menu.containerId) ? -1 : menu.getStateId();
    }
}
