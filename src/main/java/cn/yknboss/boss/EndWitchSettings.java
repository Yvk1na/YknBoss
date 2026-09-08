package cn.yknboss.boss;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

/** Runtime settings for 终焉之魔女·伊芙蕾雅. */
public record EndWitchSettings(
        String displayName,
        double phaseOneHealth,
        double phaseTwoHealth,
        double phaseThreeHealth,
        double attackDamage,
        double armor,
        double movementSpeed,
        double followRange,
        double knockbackResistance,
        double bossbarRange,
        int experience,
        int playerHitCooldownTicks,
        String modelId,
        float modelScale,
        long transitionInvulnerabilityTicks,
        double phaseTwoDamageBonus,
        double phaseThreeDamageBonus,
        double gaugeMaximum,
        double phaseOneGaugePerSecond,
        double phaseTwoGaugePerSecond,
        double phaseThreeGaugePerSecond,
        double crystalReduction,
        double cleanseGaugeIncrease,
        double markDamagePerStack,
        double markSlowness,
        long phaseThreeUltimateTimeoutTicks
) {
    public static EndWitchSettings load(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("end-witch");
        if (root == null) throw new IllegalStateException("config.yml 缺少 end-witch 配置段");
        return new EndWitchSettings(
                root.getString("display-name", "&2&l终焉之魔女·伊芙蕾雅"),
                health(root, "phases.p1-health", 83_300.0),
                health(root, "phases.p2-health", 80_500.0),
                health(root, "phases.p3-health", 77_400.0),
                nonNegative(root.getDouble("attack-damage", 580.0)),
                Math.min(BossAttributeLimits.MAX_ARMOR,
                        nonNegative(root.getDouble("armor", 30.0))),
                between(root.getDouble("movement-speed", 0.29), 0.01, 1.0),
                between(root.getDouble("follow-range", 56.0), 4.0, 2048.0),
                between(root.getDouble("knockback-resistance", 0.8), 0.0, 1.0),
                between(root.getDouble("bossbar-range", 72.0), 8.0, 512.0),
                Math.max(0, root.getInt("experience", 0)),
                Math.max(0, Math.min(100,
                        root.getInt("damage-intake.player-hit-cooldown-ticks", 10))),
                root.getString("model.id", "end_witch"),
                (float) between(root.getDouble("model.scale", 1.0), 0.05, 20.0),
                seconds(root.getDouble("phases.transition-invulnerability-seconds", 2.8)),
                nonNegative(root.getDouble("phases.phase-two-damage-bonus-percent", 20.0)) / 100.0,
                nonNegative(root.getDouble("phases.phase-three-damage-bonus-percent", 40.0)) / 100.0,
                between(root.getDouble("end-gauge.maximum", 100.0), 1.0, 100000.0),
                nonNegative(root.getDouble("end-gauge.phase-one-per-second", 1.0)),
                nonNegative(root.getDouble("end-gauge.phase-two-per-second", 1.5)),
                nonNegative(root.getDouble("end-gauge.phase-three-per-second", 1.0)),
                nonNegative(root.getDouble("end-gauge.crystal-reduction", 20.0)),
                nonNegative(root.getDouble("marks.cleanse-gauge-increase", 5.0)),
                nonNegative(root.getDouble("marks.damage-increase-percent-per-stack", 5.0)) / 100.0,
                between(root.getDouble("marks.second-stack-slow-percent", 10.0), 0.0, 95.0) / 100.0,
                seconds(root.getDouble("phases.phase-three-ultimate-timeout-seconds", 150.0))
        );
    }

    private static double health(ConfigurationSection root, String path, double fallback) {
        return between(root.getDouble(path, fallback), 1.0, BossHealthLimits.MAXIMUM);
    }

    private static long seconds(double seconds) {
        return Math.max(1L, Math.round(Math.max(0.05, seconds) * 20.0));
    }

    private static double nonNegative(double value) {
        return Double.isFinite(value) ? Math.max(0.0, value) : 0.0;
    }

    private static double between(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)) return minimum;
        return Math.max(minimum, Math.min(maximum, value));
    }
}
