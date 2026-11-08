package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ShadowFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ShadowFeatureRenderer.class)
public abstract class ShadowFeatureRendererMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<ShadowFeatureRenderer.Submit> carbon$shadows(List<ShadowFeatureRenderer.Submit> submits) {
        List<ShadowFeatureRenderer.Submit> remaining = Carbon.SHADOWS.collect(submits);
        return remaining == null ? submits : remaining;
    }

    @Inject(method = "buildGroup", at = @At("TAIL"))
    private void carbon$flush(FeatureFrameContext context, List<ShadowFeatureRenderer.Submit> submits, CallbackInfo ci) {
        Carbon.SHADOWS.flush();
    }
}
