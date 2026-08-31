package cn.yknboss.arena;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Shared challenge-time and multiplayer-health rules for every YknBoss raid.
 * Future arena settings should load and delegate to this record instead of
 * reimplementing participant scaling.
 */
public record RaidScalingRules(
        int challengeDurationTicks,
        int challengeReductionPerExtraPlayerTicks,
        int minimumChallengeDurationTicks,
        double bossHealthIncreasePerExtraPlayer
) {
    public static RaidScalingRules load(ConfigurationSection root) {
        return new RaidScalingRules(
                Math.max(20, Math.min(72000, root.getInt("challenge-duration-seconds", 900) * 20)),
                Math.max(0, Math.min(72000,
                        root.getInt("challenge-duration-reduction-per-extra-player-seconds", 60) * 20)),
                Math.max(20, Math.min(72000,
                        root.getInt("minimum-challenge-duration-seconds", 60) * 20)),
                Math.max(0.0, Math.min(10.0,
                        root.getDouble("boss-health-increase-per-extra-player-percent", 10.0) / 100.0))
        );
    }

    public int challengeDurationTicks(int participantCount) {
        long reduction = (long) Math.max(0, participantCount - 1)
                * challengeReductionPerExtraPlayerTicks;
        return (int) Math.max(minimumChallengeDurationTicks, challengeDurationTicks - reduction);
    }

    public double bossHealthMultiplier(int participantCount) {
        return 1.0 + Math.max(0, participantCount - 1) * bossHealthIncreasePerExtraPlayer;
    }
}
