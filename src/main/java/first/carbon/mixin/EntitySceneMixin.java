package first.carbon.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import first.carbon.client.ItemBatchRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntitySceneMixin {
    @Inject(method = "setPosRaw", at = @At("HEAD"))
    private void imo$position(double x, double y, double z, CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && RenderSystem.isOnRenderThread()
                && (item.getX() != x || item.getY() != y || item.getZ() != z)) {
            Carbon.RENDERER.sceneDirty = true;
        }
    }

    @Inject(method = "setYRot", at = @At("HEAD"))
    private void imo$rotation(float yRot, CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && RenderSystem.isOnRenderThread()
                && Carbon.CONFIG.physicalDroppedItems && item.getYRot() != yRot) {
            Carbon.RENDERER.sceneDirty = true;
        }
    }

    @Inject(method = "onSyncedDataUpdated(Lnet/minecraft/network/syncher/EntityDataAccessor;)V", at = @At("HEAD"))
    private void imo$changed(CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity && RenderSystem.isOnRenderThread()) {
            ((ItemBatchRenderer.EntityCacheAccess) this).imo$invalidate();
            Carbon.RENDERER.sceneDirty = true;
        }
    }

    @Inject(method = "setRemainingFireTicks", at = @At("HEAD"))
    private void imo$fire(int fireTicks, CallbackInfo ci) {
        if ((Object) this instanceof ItemEntity item && RenderSystem.isOnRenderThread()
                && (item.getRemainingFireTicks() > 0) != (fireTicks > 0)) {
            ((ItemBatchRenderer.EntityCacheAccess) this).imo$invalidate();
            Carbon.RENDERER.sceneDirty = true;
        }
    }
}
