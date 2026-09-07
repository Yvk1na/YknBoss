package cn.yknboss.arena;

import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared challenge-time and multiplayer-health rules for every YknBoss raid.
 * Future arena settings should load and delegate to this record instead of
 * reimplementing participant scaling.
 */
public record RaidScalingRules(
        int challengeDurationTicks,
        int challengeReductionPerExtraPlayerTicks,
        int minimumChallengeDurationTicks,
        double bossHealthIncreasePerExtraPlayer,
        List<Double> bossHealthMultipliersByPlayerCount
) {
    private static final String HEALTH_MULTIPLIERS_KEY = "boss-health-multipliers-by-player-count";

    public RaidScalingRules {
        bossHealthMultipliersByPlayerCount = bossHealthMultipliersByPlayerCount == null
                ? List.of()
                : validateHealthMultipliers(bossHealthMultipliersByPlayerCount);
    }

    public RaidScalingRules(int challengeDurationTicks,
                            int challengeReductionPerExtraPlayerTicks,
                            int minimumChallengeDurationTicks,
                            double bossHealthIncreasePerExtraPlayer) {
        this(challengeDurationTicks, challengeReductionPerExtraPlayerTicks,
                minimumChallengeDurationTicks, bossHealthIncreasePerExtraPlayer, List.of());
    }

    public static RaidScalingRules load(ConfigurationSection root) {
        return load(root, 10.0, List.of());
    }

    public static RaidScalingRules load(ConfigurationSection root,
                                        double fallbackHealthIncreasePercent,
                                        List<Double> fallbackHealthMultipliers) {
        return new RaidScalingRules(
                Math.max(20, Math.min(72000, root.getInt("challenge-duration-seconds", 600) * 20)),
                Math.max(0, Math.min(72000,
                        root.getInt("challenge-duration-reduction-per-extra-player-seconds", 0) * 20)),
                Math.max(20, Math.min(72000,
                        root.getInt("minimum-challenge-duration-seconds", 600) * 20)),
                Math.max(0.0, Math.min(10.0,
                        root.getDouble("boss-health-increase-per-extra-player-percent",
                                fallbackHealthIncreasePercent) / 100.0)),
                loadHealthMultipliers(root, fallbackHealthMultipliers)
        );
    }

    public int challengeDurationTicks(int participantCount) {
        long reduction = (long) Math.max(0, participantCount - 1)
                * challengeReductionPerExtraPlayerTicks;
        return (int) Math.max(minimumChallengeDurationTicks, challengeDurationTicks - reduction);
    }

    public double bossHealthMultiplier(int participantCount) {
        long extraPlayers = Math.max(0L, (long) participantCount - 1L);
        if (bossHealthMultipliersByPlayerCount.isEmpty()) {
            return 1.0 + extraPlayers * bossHealthIncreasePerExtraPlayer;
        }

        if (extraPlayers < bossHealthMultipliersByPlayerCount.size()) {
            return bossHealthMultipliersByPlayerCount.get((int) extraPlayers);
        }

        int lastIndex = bossHealthMultipliersByPlayerCount.size() - 1;
        return bossHealthMultipliersByPlayerCount.get(lastIndex)
                + (extraPlayers - lastIndex) * bossHealthIncreasePerExtraPlayer;
    }

    private static List<Double> loadHealthMultipliers(ConfigurationSection root,
                                                      List<Double> fallbackHealthMultipliers) {
        if (!root.isSet(HEALTH_MULTIPLIERS_KEY)) return fallbackHealthMultipliers;

        List<?> configured = root.getList(HEALTH_MULTIPLIERS_KEY);
        if (configured == null || configured.isEmpty()) {
            throw new IllegalArgumentException(HEALTH_MULTIPLIERS_KEY + " 必须是非空数字列表");
        }

        List<Double> multipliers = new ArrayList<>(configured.size());
        for (int index = 0; index < configured.size(); index++) {
            Object raw = configured.get(index);
            if (!(raw instanceof Number number)) {
                throw new IllegalArgumentException(HEALTH_MULTIPLIERS_KEY + "[" + index + "] 必须是数字");
            }
            multipliers.add(number.doubleValue());
        }
        return multipliers;
    }

    private static List<Double> validateHealthMultipliers(List<Double> configured) {
        List<Double> multipliers = new ArrayList<>(configured.size());
        double previous = 1.0;
        for (int index = 0; index < configured.size(); index++) {
            Double boxed = configured.get(index);
            if (boxed == null || !Double.isFinite(boxed) || boxed < 1.0) {
                throw new IllegalArgumentException(HEALTH_MULTIPLIERS_KEY + "[" + index
                        + "] 必须是有限且不小于 1 的数字");
            }
            if (index > 0 && boxed < previous) {
                throw new IllegalArgumentException(HEALTH_MULTIPLIERS_KEY + " 必须按玩家人数非递减排列");
            }
            multipliers.add(boxed);
            previous = boxed;
        }
        return List.copyOf(multipliers);
    }
}
