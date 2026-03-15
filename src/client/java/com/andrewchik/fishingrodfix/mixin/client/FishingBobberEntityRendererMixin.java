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
    // Vanilla constants from FishingBobberEntityRenderer
    @Unique private static final int   VANILLA_SEGMENT_COUNT   = 16;
    @Unique private static final float VANILLA_FOV_SCALE       = 960f;   // field_33632
    @Unique private static final float VANILLA_ROD_TIP_NDC_X   = 0.525f; // getHandPos() screen x of rod tip
    @Unique private static final float VANILLA_NEAR_PLANE      = 0.05f;  // Camera.getProjection() depth
    @Unique private static final float CATENARY_SCALE          = 0.5f;   // parabola normalizer
    @Unique private static final float CATENARY_BOBBER_OFFSET  = 0.25f;  // vertical offset at bobber end

    // Reference aspect ratio at which vanilla's rod-tip calculation is correct
    @Unique private static final float REFERENCE_ASPECT_RATIO  = 16f / 9f;


    // Empirical sway correction factors (camera-movement lag of the held item)
    @Unique private static final float YAW_SWAY_FACTOR         = 0.00011f;
    @Unique private static final float PITCH_SWAY_FACTOR       = 0.0001f;

    @Unique private static final Vector3f translate = new Vector3f();
    @Unique private static float crouchOffset = 0;
    @Unique private static int   counter      = 0;

    @Inject(method = "Lnet/minecraft/client/render/entity/FishingBobberEntityRenderer;renderFishingLine(FFFLnet/minecraft/client/render/VertexConsumer;Lnet/minecraft/client/util/math/MatrixStack$Entry;FFF)V", at = @At("HEAD"), cancellable = true)
    private static void renderFishingLine(float x, float y, float z, VertexConsumer buffer, MatrixStack.Entry matrices, float segmentStart, float segmentEnd, float getMinimumLineWidth, CallbackInfo ci) {
        Vector3f t = getTranslate();
        // Use (y - crouchOffset) for the catenary so the sag stays consistent during the
        // crouch animation: crouchOffset = camera_y - standing_eye_y ≤ 0 when crouching,
        // so (y - crouchOffset) ≈ the standing y, keeping curvature constant.
        // The linear crouchOffset*s term is not needed: at s=1 the endpoint is
        //   (y - crouchOffset) + 0.25 + t.y  which correctly tracks the rod tip.
        float yc = y + crouchOffset;
        float f = x * segmentStart                                          + t.x * segmentStart;
        float g = yc * (segmentStart * segmentStart + segmentStart) * CATENARY_SCALE + CATENARY_BOBBER_OFFSET + t.y * segmentStart;
        float h = z * segmentStart                                          + t.z * segmentStart;
        float i = x * segmentEnd - f                                        + t.x * segmentEnd;
        float j = yc * (segmentEnd * segmentEnd + segmentEnd) * CATENARY_SCALE + CATENARY_BOBBER_OFFSET - g + t.y * segmentEnd;
        float k = z * segmentEnd - h                                        + t.z * segmentEnd;
        float l = MathHelper.sqrt(i * i + j * j + k * k);
        i /= l;
        j /= l;
        k /= l;
        buffer.vertex(matrices.getPositionMatrix(), f, g, h).color(0xFF000000).normal(matrices, i, j, k).lineWidth(getMinimumLineWidth);
        ci.cancel();
    }

    @Unique
    private static Vector3f getTranslate() {
        if (counter == VANILLA_SEGMENT_COUNT) {
            calculateTranslate();
            counter = 0;
        }
        counter++;
        return translate;
    }

    @Unique
    private static void calculateTranslate() {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientPlayerEntity player = mc.player;

        if (mc.gameRenderer.getCamera().isThirdPerson() || player == null) {
            translate.set(0, 0, 0);
            crouchOffset = 0;
            return;
        }

        float tickDelta = mc.renderTickCounter.getDynamicDeltaTicks();

        float width    = mc.getWindow().getWidth();
        float height   = mc.getWindow().getHeight();
        float baseFov  = mc.options.getFov().getValue().floatValue();

        // Actual rendered FOV includes the smoothed sprint/speed-potion multiplier
        float actualFov = baseFov * MathHelper.lerp(tickDelta,
                mc.gameRenderer.lastFovMultiplier,
                mc.gameRenderer.fovMultiplier);

        // The hand is rendered without the fovMultiplier (uses baseFov from options).
        // The world (and fishing line) is rendered with actualFov = baseFov * fovMultiplier.
        // getHandPos() also uses baseFov, so at 16:9 and no sprint it is self-consistent.
        //
        // Two sources of error:
        //   1. Aspect ratio: vanilla scales rod-tip by AR, but arm is calibrated at 16:9.
        //   2. Sprint/speed: world projected with actualFov, arm stays at baseFov.
        //
        // Correction = where arm actually is - where vanilla placed the rod-tip:
        //   arm view-space x    = -tan(baseFov/2)   * near * AR_ref * NDC_X * (960/baseFov)
        //   vanilla rod-tip x   = -tan(baseFov/2)   * near * AR     * NDC_X * (960/baseFov)
        //   [world projected with actualFov, arm projected with baseFov → NDC mismatch for sprint]
        //   correction = NEAR * NDC_X * (960/baseFov) * (tan(baseFov/2)*AR - tan(actualFov/2)*AR_ref)
        float aspectRatio = width / height;
        float ratio = (float)(VANILLA_NEAR_PLANE * VANILLA_ROD_TIP_NDC_X * (VANILLA_FOV_SCALE / baseFov) * (
                  Math.tan(Math.toRadians(baseFov  / 2.0)) * aspectRatio
                - Math.tan(Math.toRadians(actualFov / 2.0)) * REFERENCE_ASPECT_RATIO
        ));

        if (mc.options.getMainArm().getValue() == Arm.LEFT) {
            ratio *= -1;
        }
        if (player.getStackInHand(Hand.OFF_HAND).getItem() instanceof FishingRodItem) {
            ratio *= -1;
        }

        // Apply item acceleration when camera is moving
        float smoothPitch = MathHelper.lerp(tickDelta, player.lastRenderPitch, player.renderPitch);
        float smoothYaw   = MathHelper.lerp(tickDelta, player.lastRenderYaw,   player.renderYaw);
        float yaw         = player.getYaw(tickDelta);

        // Crouching animation: difference between current camera y and standing eye height
        if (player.isOnGround()) {
            crouchOffset = (float)(mc.gameRenderer.getCamera().getCameraPos().y - player.getEntityPos().y) - player.getStandingEyeHeight();
        } else {
            crouchOffset = 0;
        }

        translate.x = ratio + (yaw - smoothYaw) * YAW_SWAY_FACTOR;
        translate.y = (player.getPitch(tickDelta) - smoothPitch) * PITCH_SWAY_FACTOR;
        translate.z = 0;

        translate.rotateY(-yaw * (MathHelper.PI / 180f));
    }
}
