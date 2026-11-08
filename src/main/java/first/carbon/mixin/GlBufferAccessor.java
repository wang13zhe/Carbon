package first.carbon.mixin;

import com.mojang.renderpearl.backend.opengl.GlBuffer;
import first.carbon.client.ItemBatchRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlBuffer.class)
public interface GlBufferAccessor extends ItemBatchRenderer.BufferAccess {
    @Override
    @Accessor("handle")
    int imo$handle();
}
