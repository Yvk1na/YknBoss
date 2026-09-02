package cn.yknboss.arena;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.mvplugins.multiverse.core.world.MultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.RemoveWorldOptions;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Keeps every generated raid world below one server-side directory.
 */
public final class ArenaWorldStorage {
    public static final String ROOT_FOLDER = "bossmap";
    private static final Pattern WORLD_BASE_PATTERN = Pattern.compile("[a-z0-9._-]+");

    private ArenaWorldStorage() {
    }

    public static String instanceWorldName(String worldBaseName, int slot) {
        String baseName = validateWorldBaseName(worldBaseName);
        if (slot < 1) throw new IllegalArgumentException("副本编号必须大于 0");
        return ROOT_FOLDER + "/" + baseName + "_" + String.format(Locale.ROOT, "%02d", slot);
    }

    public static String legacyInstanceWorldName(String worldBaseName, int slot) {
        String baseName = validateWorldBaseName(worldBaseName);
        if (slot < 1) throw new IllegalArgumentException("副本编号必须大于 0");
        return baseName + "_" + String.format(Locale.ROOT, "%02d", slot);
    }

    public static Path ensureRootDirectory() {
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path rootDirectory = worldContainer.resolve(ROOT_FOLDER).normalize();
        if (!rootDirectory.startsWith(worldContainer)) {
            throw new IllegalStateException("Boss 副本目录超出服务端世界容器：" + rootDirectory);
        }
        try {
            Files.createDirectories(rootDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法创建 Boss 副本目录 " + rootDirectory, exception);
        }
        return rootDirectory;
    }

    /**
     * Moves a legacy flat world folder into bossmap before Multiverse imports the new world name.
     * The old world is removed from Multiverse without deleting its files.
     */
    public static void prepareInstance(WorldManager manager, Logger logger, String managedWorldName,
                                       String legacyWorldName, String bossLabel) {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(logger, "logger");
        Path rootDirectory = ensureRootDirectory();
        Path targetDirectory = resolveManagedDirectory(managedWorldName, rootDirectory);

        if (Files.exists(targetDirectory) || manager.isWorld(managedWorldName)
                || Bukkit.getWorld(managedWorldName) != null) {
            return;
        }

        World legacyBukkitWorld = Bukkit.getWorld(legacyWorldName);
        MultiverseWorld legacyMultiverseWorld = manager.getWorld(legacyWorldName).getOrNull();
        File legacyFolder = legacyBukkitWorld != null
                ? legacyBukkitWorld.getWorldFolder()
                : legacyMultiverseWorld != null
                ? legacyMultiverseWorld.getOfflineWorldFolder()
                : new File(Bukkit.getWorldContainer(), legacyWorldName);
        Path legacyDirectory = legacyFolder.toPath().toAbsolutePath().normalize();
        if (!Files.isDirectory(legacyDirectory)) return;

        if (legacyBukkitWorld != null && !legacyBukkitWorld.getPlayers().isEmpty()) {
            throw new IllegalStateException("旧" + bossLabel + "副本 " + legacyWorldName
                    + " 中仍有玩家，无法迁移到 " + managedWorldName);
        }

        if (legacyMultiverseWorld != null) {
            var removed = manager.removeWorld(RemoveWorldOptions.world(legacyMultiverseWorld)
                    .saveBukkitWorld(true)
                    .unloadBukkitWorld(true));
            if (removed.isFailure()) {
                throw new IllegalStateException("Multiverse 无法注销旧" + bossLabel + "副本 "
                        + legacyWorldName + "：" + removed.getFailureReason());
            }
        } else if (legacyBukkitWorld != null && !Bukkit.unloadWorld(legacyBukkitWorld, true)) {
            throw new IllegalStateException("Bukkit 无法卸载旧" + bossLabel + "副本 " + legacyWorldName);
        }

        try {
            Files.createDirectories(targetDirectory.getParent());
            Files.move(legacyDirectory, targetDirectory);
        } catch (IOException exception) {
            throw new IllegalStateException("无法把旧" + bossLabel + "副本从 " + legacyDirectory
                    + " 移动到 " + targetDirectory, exception);
        }
        logger.info("已把旧" + bossLabel + "副本 " + legacyWorldName + " 迁移到 " + managedWorldName);
    }

    private static Path resolveManagedDirectory(String managedWorldName, Path rootDirectory) {
        String prefix = ROOT_FOLDER + "/";
        if (managedWorldName == null || !managedWorldName.startsWith(prefix)
                || managedWorldName.indexOf('\\') >= 0 || managedWorldName.contains("..")) {
            throw new IllegalArgumentException("无效的 Boss 副本世界名：" + managedWorldName);
        }
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path targetDirectory = worldContainer.resolve(managedWorldName).normalize();
        if (!targetDirectory.startsWith(rootDirectory)) {
            throw new IllegalArgumentException("Boss 副本目录超出 bossmap：" + managedWorldName);
        }
        return targetDirectory;
    }

    private static String validateWorldBaseName(String worldBaseName) {
        String baseName = Objects.requireNonNull(worldBaseName, "worldBaseName").trim()
                .toLowerCase(Locale.ROOT);
        if (!WORLD_BASE_PATTERN.matcher(baseName).matches() || baseName.equals(".") || baseName.equals("..")) {
            throw new IllegalArgumentException("副本 world-name 只能使用小写字母、数字、点、下划线和连字符："
                    + worldBaseName);
        }
        return baseName;
    }
}
