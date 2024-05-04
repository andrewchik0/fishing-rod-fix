package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.MinecraftClient;
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
    @Inject(at = @At("HEAD"), method = "renderFishingLine", cancellable = true)
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

        buffer.vertex(matrices.getPositionMatrix().translate(calculateTranslate()), f, g, h).color(0, 0, 0, 255).normal(matrices.getNormalMatrix(), i, j, k).next();
        ci.cancel();
    }

    @Unique
    private static Vector3f calculateTranslate() {

        if (MinecraftClient.getInstance().gameRenderer.getCamera().isThirdPerson())
            return new Vector3f();

        float yaw = 0;
        if (MinecraftClient.getInstance().player != null) {
            yaw = MinecraftClient.getInstance().player.getYaw();
        }
        float width = MinecraftClient.getInstance().getWindow().getWidth();
        float height = MinecraftClient.getInstance().getWindow().getHeight();
        float ratio = width / height;
        ratio -= 16f / 9f;

        ratio /= 52f; // Experimental value that normalizes rendering
        ratio /= (110 - MinecraftClient.getInstance().options.getFov().getValue()) / 160f + 1; // FOV corrections

        if (MinecraftClient.getInstance().options.getMainArm().getValue() == Arm.LEFT) {
            ratio *= -1;
        }
        if (MinecraftClient.getInstance().player.getStackInHand(Hand.OFF_HAND).getItem() instanceof FishingRodItem) {
            ratio *= -1;
        }

        return new Vector3f(ratio, 0f, 0f).rotateY(yaw / -180f * MathHelper.PI);
    }
}
