package cn.yknboss.arena;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record BoneDragonArenaSettings(
        boolean enabled,
        String worldName,
        int instanceCount,
        int radius,
        int floorY,
        long seed,
        boolean autoCreate,
        int summonDurationTicks,
        RaidScalingRules scalingRules,
        int returnDelayTicks,
        int respawnDelayTicks
) {
    public static BoneDragonArenaSettings load(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("bone-dragon-arena");
        if (root == null) throw new IllegalStateException("config.yml 缺少 bone-dragon-arena 配置段");
        String worldName = root.getString("world-name", "bone_dragon_arena").trim();
        if (worldName.isEmpty()) throw new IllegalArgumentException("bone-dragon-arena.world-name 不能为空");
        return new BoneDragonArenaSettings(
                root.getBoolean("enabled", true),
                worldName,
                Math.max(1, Math.min(10, root.getInt("instance-count", 10))),
                Math.max(64, Math.min(512, root.getInt("radius", 100))),
                Math.max(32, Math.min(240, root.getInt("floor-y", 80))),
                root.getLong("seed", 761923846123L),
                root.getBoolean("auto-create", false),
                Math.max(20, Math.min(200, root.getInt("summon-duration-seconds", 3) * 20)),
                RaidScalingRules.load(root, 100.0, java.util.List.of(
                        1.00, 2.00, 3.00, 4.00, 5.00, 6.00, 7.00, 8.00, 9.00, 10.00)),
                Math.max(20, Math.min(72000, root.getInt("return-delay-seconds", 30) * 20)),
                Math.max(20, Math.min(1200, root.getInt("respawn-delay-seconds", 10) * 20))
        );
    }

    public int challengeDurationTicks(int participantCount) {
        return scalingRules.challengeDurationTicks(participantCount);
    }

    public double bossHealthMultiplier(int participantCount) {
        return scalingRules.bossHealthMultiplier(participantCount);
    }
}
