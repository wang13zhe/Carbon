package first.carbon.mixin;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import first.carbon.Carbon;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GlStateManager.class)
public abstract class GlStateManagerMixin {
    @Inject(method = "glLinkProgram", at = @At("TAIL"))
    private static void carbon$capture(int program, CallbackInfo ci) { Carbon.IRIS.captureProgram(program); }

    @Inject(method = "glDeleteProgram", at = @At("HEAD"))
    private static void carbon$release(int program, CallbackInfo ci) { Carbon.IRIS.releaseProgram(program); }
}
