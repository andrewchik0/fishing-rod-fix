package com.andrewchik.fishingrodfix;

import com.llamalad7.mixinextras.MixinExtrasBootstrap;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Starts the MixinExtras copy shaded into this jar. The mod uses {@code @ModifyExpressionValue},
 * {@code injector.v2.@WrapWithCondition} and {@code @Local}, and rather than demand a Fabric Loader
 * new enough to ship MixinExtras itself - no loader does before 0.15.0, and Fabulously Optimized
 * pins 0.14.21 for 1.20 and 0.14.23 for 1.20.1 - the build shades the library in under
 * {@code com.andrewchik.fishingrodfix.shadow.mixinextras} (see {@code shadeJar} in build.gradle).
 * A shaded copy has no {@code preLaunch} entrypoint to start it, so it is started here, the way
 * MixinExtras documents for a non-Fabric or relocated copy.
 *
 * <p>{@code onLoad} is called from {@code MixinConfig.onSelect}, i.e. while Mixin selects the configs
 * at the first transformed class. {@code MixinProcessor.select} runs {@code selectConfigs}, then
 * {@code Extensions.select}, then {@code prepareConfigs}, so this runs before <em>any</em> config's
 * mixins are prepared - therefore before the first {@code @ModifyExpressionValue} in the jar is
 * parsed, which is what an injector annotation needs (an unregistered one is not an error, it is
 * simply not an injector, so the handler would be dropped without a word) - and the extension
 * MixinExtras registers here is picked up by the {@code Extensions.select} right after it, in time
 * for the very first transformed class.
 *
 * <p>The {@code catch} is for visibility, not recovery. {@code MixinProcessor.selectConfigs} wraps
 * this call in {@code catch (Exception)} and, on a throw, drops the config with one
 * {@code Failed to select mixin config} WARN - all fourteen mixins gone, the game booting normally,
 * {@code "required": true} no help because that check is elsewhere. One ERROR of our own in front of
 * it is the difference between a bug report that says something and one that says nothing.
 *
 * <p>Both copies present is the normal case on a modern loader and needs nothing special.
 * {@code MixinExtrasBootstrap.init()} is idempotent, and MixinExtras arbitrates between copies over
 * one shared Mixin blackboard key ({@code MixinExtrasServiceInstance}, a plain string, so the
 * relocation does not hide ours from the loader's): a strictly newer copy takes control and the other
 * hands over the packages, injectors and extensions it owns, re-registering them under the loser's
 * package; on a tie the newcomer concedes. Which of an equal pair ends up in control therefore
 * depends on load order, and either way our relocated package is served and no other mod's
 * annotations change meaning. In a dev run (runClient) nothing is relocated and this call reaches the
 * loader's own MixinExtras, which its {@code preLaunch} entrypoint has already started - the guard
 * inside makes that a no-op.
 *
 * <p>Everything else on the interface is left at Mixin's defaults: every mixin in the config applies,
 * there is no refmap and no dynamic mixin list.
 */
public class MixinExtrasBootstrapPlugin implements IMixinConfigPlugin {
    @Override
    public void onLoad(String mixinPackage) {
        try {
            MixinExtrasBootstrap.init();
        } catch (Throwable t) {
            // FishingRodFix is only touched here, so the happy path loads nothing extra this early.
            FishingRodFix.LOGGER.error("Failed to start the shaded MixinExtras; the fix will not be "
                    + "applied and Mixin is about to drop {} with a single warning", mixinPackage, t);
            throw t;
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}
