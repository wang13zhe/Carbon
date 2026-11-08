package first.carbon.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import first.carbon.Carbon;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
public abstract class LevelRendererMixin {
    @Inject(method = "extractVisibleEntities", at = @At("HEAD"))
    private void imo$frame(Camera camera, Frustum frustum, DeltaTracker delta, LevelRenderState state, CallbackInfo ci) {
        Carbon.RENDERER.beginFrame(camera, frustum, delta.getGameTimeDeltaPartialTick(false));
    }

    @Inject(method = "extractVisibleEntities", at = @At("RETURN"))
    private void imo$flush(LevelRenderState state, CallbackInfo ci) {
        Carbon.RENDERER.flush(state.cameraRenderState);
    }

    @WrapOperation(method = "extractVisibleEntities", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;entitiesForRendering()Ljava/lang/Iterable;"))
    private Iterable<Entity> imo$entities(ClientLevel level, Operation<Iterable<Entity>> original) {
        return Carbon.RENDERER.collect(original.call(level));
    }
}
