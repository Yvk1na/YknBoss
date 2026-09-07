package cn.yknboss.boss;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/** Cross-plugin classification used by the per-player boss hit gates. */
final class PlayerDamageEventSupport {
    private static final StackWalker STACK_WALKER = StackWalker.getInstance(
            StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private PlayerDamageEventSupport() {
    }

    /**
     * YknRpg applies ability damage synchronously through LivingEntity#damage.
     * Its release JAR obfuscates internal names, so compare class loaders rather
     * than class or method names. The active call stack is the only dependency-
     * free signal that distinguishes this synthetic damage from a new physical
     * attack packet; Paper exposes both as a player-attack damage source.
     */
    static boolean isYknRpgSyntheticDamage() {
        Plugin yknRpg = Bukkit.getPluginManager().getPlugin("YknRpg");
        if (yknRpg == null || !yknRpg.isEnabled()) return false;
        ClassLoader pluginClassLoader = yknRpg.getClass().getClassLoader();
        return STACK_WALKER.walk(frames -> frames.limit(32).anyMatch(
                frame -> frame.getDeclaringClass().getClassLoader() == pluginClassLoader));
    }
}
