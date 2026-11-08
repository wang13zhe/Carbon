package first.carbon.compat;

import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.util.List;

public final class SodiumCompatibility {
    private boolean checked;
    private MethodHandle marker;

    public void mark(List<TextureAtlasSprite> sprites) {
        if (!checked) {
            checked = true;
            try {
                Class<?> api = Class.forName("net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil");
                marker = MethodHandles.publicLookup().unreflect(api.getMethod("markSpriteActive", TextureAtlasSprite.class));
            } catch (ReflectiveOperationException ignored) { }
        }
        if (marker != null) {
            try {
                for (TextureAtlasSprite sprite : sprites) marker.invoke(sprite);
            } catch (Throwable exception) {
                marker = null;
                org.slf4j.LoggerFactory.getLogger("Carbon").warn("Unable to keep Sodium sprites active", exception);
            }
        }
    }
}
