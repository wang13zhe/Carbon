package first.carbon.mixin;

import first.carbon.client.ItemBatchRenderer;
import net.minecraft.client.renderer.item.ItemModelResolver;
import net.minecraft.client.renderer.item.ItemStackRenderState;
import net.minecraft.world.entity.ItemOwner;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemModelResolver.class)
public class ItemModelResolverMixin {
    @Inject(method = "updateForTopItem", at = @At("TAIL"))
    private void imo$stack(ItemStackRenderState state, ItemStack stack, ItemDisplayContext display, Level level, ItemOwner owner, int seed, CallbackInfo ci) {
        ((ItemBatchRenderer.ItemStateAccess) state).imo$stack(stack);
    }
}
