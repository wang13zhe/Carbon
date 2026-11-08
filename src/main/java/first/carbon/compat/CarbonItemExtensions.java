package first.carbon.compat;

import net.minecraft.world.item.ItemStack;

public final class CarbonItemExtensions {
    public static final CarbonItemExtensions DEFAULT = new CarbonItemExtensions();

    public static CarbonItemExtensions of(ItemStack stack) {
        return DEFAULT;
    }

    public boolean shouldBobAsEntity(ItemStack stack) {
        return true;
    }

    public boolean shouldSpreadAsEntity(ItemStack stack) {
        return true;
    }
}
