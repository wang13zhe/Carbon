package first.carbon.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import first.carbon.Carbon;
import first.carbon.client.ItemBatchRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemStack.class)
public abstract class ItemStackSceneMixin {
    @Inject(method = "setCount", at = @At("HEAD"))
    private void imo$changed(int count, CallbackInfo ci) {
        ItemStack stack = (ItemStack) (Object) this;
        if (stack.getCount() != count && stack.getComponents() instanceof ItemBatchRenderer.ComponentVersion components && components.imo$tracked()
                && RenderSystem.isOnRenderThread()) Carbon.RENDERER.sceneDirty = true;
    }
}
