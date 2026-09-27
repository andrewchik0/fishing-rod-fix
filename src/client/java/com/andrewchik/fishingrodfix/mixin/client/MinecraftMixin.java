package com.andrewchik.fishingrodfix.mixin.client;

import com.andrewchik.fishingrodfix.FishingLineOrigin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Warms the rod model measurement up at world join and dimension change, behind the loading screen
 * ({@link FishingLineOrigin#onLevelJoin}). Optional ({@code require = 0}): without it the first cast pays.
 */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @Inject(method = "setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V", at = @At("TAIL"), require = 0)
    private void fishingrodfix$warmUp(ClientLevel level, CallbackInfo ci) {
        FishingLineOrigin.onLevelJoin();
    }
}
