package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.client.renderer.special.SpecialModelRenderer;
import net.minecraft.client.resources.model.geometry.ItemQuads;
import net.minecraft.client.resources.model.cuboid.ItemTransform;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * One resolved model layer: its baked geometry and the transforms {@code LayerRenderState.applyTransform}
 * draws it with (the model's display transform for the context, then the item definition's own).
 */
@Mixin(ItemStackRenderState.LayerRenderState.class)
public interface LayerRenderStateAccessor {
    @Accessor("quads")
    ItemQuads fishingrodfix$getQuads();

    @Accessor("itemTransform")
    ItemTransform fishingrodfix$getItemTransform();

    @Accessor("localTransform")
    Matrix4f fishingrodfix$getLocalTransform();

    @Accessor("specialRenderer")
    @Nullable SpecialModelRenderer<Object> fishingrodfix$getSpecialRenderer();
}
