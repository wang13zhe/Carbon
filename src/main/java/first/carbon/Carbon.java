package first.carbon;

import com.mojang.brigadier.arguments.BoolArgumentType;
import first.carbon.client.GenericItemRenderer;
import first.carbon.client.GenericShadowRenderer;
import first.carbon.client.ItemBatchRenderer;
import first.carbon.compat.IrisCompatibility;
import first.carbon.compat.SodiumCompatibility;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class Carbon implements ClientModInitializer {
    public static final IrisCompatibility IRIS = new IrisCompatibility();
    public static final SodiumCompatibility SODIUM = new SodiumCompatibility();
    public static final GenericItemRenderer GENERIC = new GenericItemRenderer();
    public static final GenericShadowRenderer SHADOWS = new GenericShadowRenderer();
    public static final ItemBatchRenderer RENDERER = new ItemBatchRenderer();
    public static final Settings CONFIG = new Settings();

    @Override
    public void onInitializeClient() {
        Path configDir = FabricLoader.getInstance().getConfigDir();
        Path oldConfig = configDir.resolve("item_model_optimization-client.toml");
        Path config = configDir.resolve("carbon-client.toml");
        if (Files.isRegularFile(oldConfig) && !Files.exists(config)) {
            try { Files.copy(oldConfig, config); }
            catch (IOException exception) { org.slf4j.LoggerFactory.getLogger("Carbon").warn("Unable to migrate configuration", exception); }
        }
        CONFIG.load(config);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            IRIS.beginFrame();
            GENERIC.begin();
        });
    }

    public static final class Settings {
        public boolean enabled = true;
        public boolean optimizeDroppedItems = true;
        public boolean physicalDroppedItems = false;
        public boolean skipVisibilityChecks = true;
        public double cacheUpdateBudgetMs = 2.0;

        public void load(Path path) {
            if (!Files.exists(path)) return;
            try {
                for (String line : Files.readAllLines(path)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    String key = line.substring(0, eq).trim();
                    String value = line.substring(eq + 1).trim();
                    switch (key) {
                        case "enabled" -> enabled = Boolean.parseBoolean(value);
                        case "optimizeDroppedItems" -> optimizeDroppedItems = Boolean.parseBoolean(value);
                        case "physicalDroppedItems" -> physicalDroppedItems = Boolean.parseBoolean(value);
                        case "skipVisibilityChecks" -> skipVisibilityChecks = Boolean.parseBoolean(value);
                        case "cacheUpdateBudgetMs" -> cacheUpdateBudgetMs = Double.parseDouble(value);
                    }
                }
            } catch (IOException ignored) {}
        }

        public void save(Path path) {
            try {
                Files.writeString(path, String.join("\n",
                        "enabled = " + enabled,
                        "optimizeDroppedItems = " + optimizeDroppedItems,
                        "physicalDroppedItems = " + physicalDroppedItems,
                        "skipVisibilityChecks = " + skipVisibilityChecks,
                        "cacheUpdateBudgetMs = " + cacheUpdateBudgetMs
                ));
            } catch (IOException ignored) {}
        }
    }
}
