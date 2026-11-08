package first.carbon.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import first.carbon.client.ItemBatchRenderer;
import first.carbon.client.ItemBatchRenderer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityCacheMixin extends Entity implements ItemBatchRenderer.EntityCacheAccess {
    @Unique
    private final ItemBatchRenderer.EntityCache imo$cache = new ItemBatchRenderer.EntityCache();
    @Unique
    private ItemStack imo$renderStack;
    @Unique
    private int imo$renderVersion;
    @Unique
    private int imo$renderTick = Integer.MIN_VALUE;
    @Unique
    private boolean imo$eligible;
    @Unique
    private boolean imo$renderFire;

    protected ItemEntityCacheMixin(EntityType<?> type, Level level) {
        super(type, level);
    }

    @Override
    public ItemBatchRenderer.EntityCache imo$cache() {
        return imo$cache;
    }

    @Override
    public boolean imo$eligible() {
        ItemStack stack = ((ItemEntity) (Object) this).getItem();
        int version = stack.getComponents() instanceof ItemBatchRenderer.ComponentVersion components ? components.imo$version() : 0;
        if (imo$renderTick != tickCount || imo$renderStack != stack || imo$renderVersion != version) {
            imo$renderStack = stack;
            imo$renderVersion = version;
            imo$renderTick = tickCount;
            boolean fire = getRemainingFireTicks() > 0 || getSharedFlag(0);
            imo$eligible = !isInvisible() && !hasCustomName() && !shouldShowName() && !(fire && displayFireAnimation()) && !isCurrentlyGlowing();
            imo$renderFire = fire && isOnFire();
        }
        return imo$eligible;
    }

    @Override
    public boolean imo$hasFire() {
        imo$eligible();
        return imo$renderFire;
    }

    @Override
    public void imo$invalidate() {
        imo$renderTick = Integer.MIN_VALUE;
    }

    @Inject(method = {"readAdditionalSaveData", "setUnlimitedLifetime", "setExtendedLifetime", "makeFakeItem"}, at = @At("HEAD"))
    private void imo$age(CallbackInfo ci) {
        if (RenderSystem.isOnRenderThread()) {
            imo$invalidate();
            Carbon.RENDERER.sceneDirty = true;
        }
    }
}
