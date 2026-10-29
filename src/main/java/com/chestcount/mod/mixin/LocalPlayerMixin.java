package com.chestcount.mod.mixin;

import com.chestcount.mod.ChestCountClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public abstract class LocalPlayerMixin {
    @Inject(method="clientSideCloseContainer", at=@At("HEAD"))
    private void chestcount$close(CallbackInfo ci) { ChestCountClient.INSTANCE.sync().close(); }
}
