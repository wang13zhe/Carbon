package first.carbon.mixin;

import first.carbon.Carbon;
import net.minecraft.client.multiplayer.ClientChunkCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class ClientChunkCacheMixin {
    @Inject(method = "onLightUpdate", at = @At("HEAD"))
    private void imo$light(CallbackInfo ci) {
        Carbon.RENDERER.sceneDirty = true;
        Carbon.RENDERER.blockCacheDirty = true;
    }
}
