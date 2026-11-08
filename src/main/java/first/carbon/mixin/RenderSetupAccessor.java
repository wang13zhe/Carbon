package first.carbon.mixin;

import first.carbon.compat.IrisCompatibility;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.TextureTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderSetup.class)
public interface RenderSetupAccessor extends IrisCompatibility.RenderSetupAccess {
    @Override @Accessor("textureTransform") TextureTransform carbon$textureTransform();
}
