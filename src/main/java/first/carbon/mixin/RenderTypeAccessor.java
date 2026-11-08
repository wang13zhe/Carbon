package first.carbon.mixin;

import first.carbon.compat.IrisCompatibility;
import net.minecraft.client.renderer.rendertype.RenderSetup;
import net.minecraft.client.renderer.rendertype.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderType.class)
public interface RenderTypeAccessor extends IrisCompatibility.RenderTypeAccess {
    @Override @Accessor("state") RenderSetup carbon$setup();
}
