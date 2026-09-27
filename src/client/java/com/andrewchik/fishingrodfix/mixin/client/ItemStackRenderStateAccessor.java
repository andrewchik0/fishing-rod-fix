package com.andrewchik.fishingrodfix.mixin.client;

import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.item.ItemDisplayContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The layers an item was resolved into and the display context it was resolved for. */
@Mixin(ItemStackRenderState.class)
public interface ItemStackRenderStateAccessor {
    @Accessor("activeLayerCount")
    int fishingrodfix$getActiveLayerCount();

    @Accessor("layers")
    ItemStackRenderState.LayerRenderState[] fishingrodfix$getLayers();

    @Accessor("displayContext")
    ItemDisplayContext fishingrodfix$getDisplayContext();
}
