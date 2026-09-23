package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.render.Camera;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.util.math.MatrixStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Vanilla's own view bob, hurt tilt and FOV. Both the world pass ({@code renderWorld}) and the hand
 * pass ({@code renderHand}) apply the bob and tilt, and each asks {@code getFov} for its own
 * projection; calling the same methods keeps any change to them (by Mojang or another mod, e.g. a
 * zoom mod hooking {@code getFov}) in sync with the line origin.
 */
@Mixin(GameRenderer.class)
public interface GameRendererInvoker {
    @Invoker("tiltViewWhenHurt")
    void fishingrodfix$tiltViewWhenHurt(MatrixStack matrices, float tickDelta);

    @Invoker("bobView")
    void fishingrodfix$bobView(MatrixStack matrices, float tickDelta);

    /** Returns a double on 1.20.1; 1.21.5 narrowed it to a float. */
    @Invoker("getFov")
    double fishingrodfix$getFov(Camera camera, float tickDelta, boolean changingFov);
}
