package first.carbon.mixin;

import first.carbon.Carbon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "com.mojang.renderpearl.backend.opengl.GlCommandEncoder")
public abstract class GlCommandEncoderMixin {
    @Inject(method = "drawFromBuffers", at = @At("HEAD"), cancellable = true)
    private void carbon$draw(CallbackInfo ci) {
        if (Carbon.IRIS.drawCurrent()) ci.cancel();
    }
}
