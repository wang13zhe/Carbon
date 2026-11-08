package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.renderer.feature.FeatureFrameContext;
import net.minecraft.client.renderer.feature.ItemFeatureRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

@Mixin(ItemFeatureRenderer.class)
public abstract class ItemFeatureRendererMixin {
    @ModifyVariable(method = "buildGroup", at = @At("HEAD"), argsOnly = true)
    private List<ItemFeatureRenderer.Submit> carbon$filter(List<ItemFeatureRenderer.Submit> submits) {
        Carbon.GENERIC.begin();
        List<ItemFeatureRenderer.Submit> remaining = null;
        for (int i = 0; i < submits.size(); i++) {
            if (!Carbon.GENERIC.submit(submits.get(i))) {
                if (remaining != null) remaining.add(submits.get(i));
            } else if (remaining == null) {
                remaining = new ArrayList<>(submits.size() - i);
                for (int j = 0; j < i; j++) remaining.add(submits.get(j));
            }
        }
        return remaining == null ? submits : remaining;
    }

    @Inject(method = "buildGroup", at = @At("TAIL"))
    private void carbon$flush(FeatureFrameContext context, List<ItemFeatureRenderer.Submit> submits, CallbackInfo ci) {
        Carbon.GENERIC.flush();
    }
}
