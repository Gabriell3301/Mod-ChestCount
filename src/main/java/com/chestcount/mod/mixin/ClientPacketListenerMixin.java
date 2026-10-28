package com.chestcount.mod.mixin;

import com.chestcount.mod.ChestCountClient;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.*;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {
    @Inject(method="handleOpenScreen", at=@At("TAIL"))
    private void chestcount$open(ClientboundOpenScreenPacket p, CallbackInfo ci) { ChestCountClient.INSTANCE.sync().open(p); }
    @Inject(method="handleContainerContent", at=@At("TAIL"))
    private void chestcount$content(ClientboundContainerSetContentPacket p, CallbackInfo ci) { ChestCountClient.INSTANCE.sync().content(p); }
    @Inject(method="handleContainerSetSlot", at=@At("TAIL"))
    private void chestcount$slot(ClientboundContainerSetSlotPacket p, CallbackInfo ci) { ChestCountClient.INSTANCE.sync().slot(p); }
}
