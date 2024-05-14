package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.entity.FishingBobberEntityRenderer;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.FishingRodItem;
import net.minecraft.util.Arm;
import net.minecraft.util.Hand;
import net.minecraft.util.math.MathHelper;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(FishingBobberEntityRenderer.class)
public class FishingBobberEntityRendererMixin {
    @Unique
    private static final Vector3f translate = new Vector3f();
    @Unique
    private static int counter = 0;

    @Inject(method = "Lnet/minecraft/client/render/entity/FishingBobberEntityRenderer;renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FF)V", at = @At("HEAD"), cancellable = true)
    private static void renderFishingLine(float x, float y, float z, VertexConsumer buffer, MatrixStack.Entry matrices, float segmentStart, float segmentEnd, CallbackInfo ci) {
        float f = x * segmentStart;
        float g = y * (segmentStart * segmentStart + segmentStart) * 0.5F + 0.25F;
        float h = z * segmentStart;
        float i = x * segmentEnd - f;
        float j = y * (segmentEnd * segmentEnd + segmentEnd) * 0.5F + 0.25F - g;
        float k = z * segmentEnd - h;
        float l = MathHelper.sqrt(i * i + j * j + k * k);
        i /= l;
        j /= l;
        k /= l;
        buffer.vertex(matrices.getPositionMatrix().translate(getTranslate()), f, g, h).color(0, 0, 0, 255).normal(matrices, i, j, k).next();
        ci.cancel();
    }

    @Unique
    private static Vector3f getTranslate() {
        if (counter == 17) {
            calculateTranslate();
            counter = 0;
        }
        counter++;
        return translate;
    }

    @Unique
    private static float getTickDelta() {
        return MinecraftClient.getInstance().isPaused() ? MinecraftClient.getInstance().pausedTickDelta : MinecraftClient.getInstance().renderTickCounter.tickDelta;
    }

    @Unique
    private static void calculateTranslate() {
        ClientPlayerEntity player = MinecraftClient.getInstance().player;

        if (MinecraftClient.getInstance().gameRenderer.getCamera().isThirdPerson() || player == null) {
            translate.x = 0;
            translate.y = 0;
            translate.z = 0;
            return;
        }

        float width = MinecraftClient.getInstance().getWindow().getWidth();
        float height = MinecraftClient.getInstance().getWindow().getHeight();
        float ratio = width / height;
        ratio -= 16f / 9f;

        ratio /= 68f; // Experimental value that normalizes rendering

        // FOV corrections
        float fov = MinecraftClient.getInstance().options.getFov().getValue().floatValue();
        ratio /= (70f - fov) / 180f + 1;

        // TODO: add fov effects
        // float fovEffected = (float) MinecraftClient.getInstance().gameRenderer.getFov(MinecraftClient.getInstance().gameRenderer.getCamera(), getTickDelta(), true);
        // float fovDiff = fovEffected - fov;
        // ratio -= fovDiff / 1000f;

        if (MinecraftClient.getInstance().options.getMainArm().getValue() == Arm.LEFT) {
            ratio *= -1;
        }
        if (player.getStackInHand(Hand.OFF_HAND).getItem() instanceof FishingRodItem) {
            ratio *= -1;
        }

        // Apply item acceleration when camera is moving
        float h = MathHelper.lerp(getTickDelta(), player.lastRenderPitch, player.renderPitch);
        float i = MathHelper.lerp(getTickDelta(), player.lastRenderYaw, player.renderYaw);

        // Crouching animation
        float crouch = 0;
        if (player.isOnGround())
            crouch = (float)(MinecraftClient.getInstance().gameRenderer.getCamera().getPos().y - player.getPos().y) - player.getStandingEyeHeight();

        translate.x = ratio + (player.getYaw(getTickDelta()) - i) * 0.00011f;
        translate.y = (player.getPitch(getTickDelta()) - h) * 0.0001f + crouch / 16f;
        translate.z = 0;

        translate.rotateY(player.getYaw(getTickDelta()) / -180f * MathHelper.PI);
    }
}
