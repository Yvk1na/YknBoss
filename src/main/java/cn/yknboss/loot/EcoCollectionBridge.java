package cn.yknboss.loot;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;

/** Reflection bridge to EcoCollections with zero compile-time coupling. */
final class EcoCollectionBridge {
    private final Plugin plugin;
    private final Set<String> warned = new HashSet<>();
    private boolean initialized;
    private boolean available;
    private Object collectionsInstance;
    private Method getByIdMethod;
    private Method canGainMethod;
    private Method giveCountMethod;

    EcoCollectionBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    void give(Player player, String collectionId, double amount) {
        if (player == null || !player.isOnline() || collectionId == null || collectionId.isBlank() || !(amount > 0.0)) {
            return;
        }
        if (!ensureReady()) {
            return;
        }
        try {
            if (!Boolean.TRUE.equals(canGainMethod.invoke(null, player))) {
                return;
            }
            Object collection = getByIdMethod.invoke(collectionsInstance, collectionId);
            if (collection == null) {
                return;
            }
            giveCountMethod.invoke(null, player, collection, amount);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (warned.add(collectionId)) {
                plugin.getLogger().log(Level.WARNING, "写入 EcoCollections 收集进度失败: " + collectionId, exception);
            }
        }
    }

    private boolean ensureReady() {
        Plugin ecoCollections = Bukkit.getPluginManager().getPlugin("EcoCollections");
        if (ecoCollections == null || !ecoCollections.isEnabled()) {
            return false;
        }
        if (initialized) {
            return available;
        }
        initialized = true;
        try {
            ClassLoader loader = ecoCollections.getClass().getClassLoader();
            Class<?> collectionsType = Class.forName("com.exanthiax.ecocollections.collections.Collections", true, loader);
            Class<?> collectionType = Class.forName("com.exanthiax.ecocollections.collections.Collection", true, loader);
            Class<?> accType = Class.forName("com.exanthiax.ecocollections.collections.CollectionCountAccumulatorKt", true, loader);
            Class<?> apiType = Class.forName("com.exanthiax.ecocollections.api.CollectionAPI", true, loader);

            this.collectionsInstance = collectionsType.getField("INSTANCE").get(null);
            this.getByIdMethod = collectionsType.getMethod("getByID", String.class);
            this.canGainMethod = accType.getMethod("canGainCollectionProgress", Player.class);
            this.giveCountMethod = apiType.getMethod("giveCollectionCount", Player.class, collectionType, double.class);
            this.available = true;
            return true;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError exception) {
            if (warned.add("init")) {
                plugin.getLogger().log(Level.WARNING, "连接 EcoCollections API 失败，Boss 收集进度未启用。", exception);
            }
            this.available = false;
            return false;
        }
    }
}
