/*
 * This file is part of Meteor Client.
 * https://meteorclient.com
 */

package meteordevelopment.meteorclient.mixin;

import meteordevelopment.meteorclient.utils.render.postprocess.PostProcessShaders;
import net.minecraft.client.renderer.feature.FeatureRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Iris-compatible fallback submission point for the entity post-process shaders (Chams,
 * Entity Outline). Under Iris, entity rendering is redirected through Iris's own pipeline
 * and vanilla's {@code addMainPass} entity-outline lambda never reaches
 * {@code OutlineBufferSource.endOutlineBatch} — so the LevelRendererMixin anchor never fires
 * and shader-mode modules render nothing. {@code FeatureRenderDispatcher.renderAllFeatures}
 * is the convergence point that runs under BOTH vanilla and Iris; the per-frame flag in
 * {@link PostProcessShaders} makes this mutually exclusive with the vanilla-path anchor.
 */
@Mixin(FeatureRenderDispatcher.class)
public class FeatureRenderDispatcherMixin {
    @Inject(method = "renderAllFeatures", at = @At("TAIL"))
    private void onSubmitFeatures(CallbackInfo ci) {
        PostProcessShaders.submitEntityVerticesIrisFallback();
    }
}
