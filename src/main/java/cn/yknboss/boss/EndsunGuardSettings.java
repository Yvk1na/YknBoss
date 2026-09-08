package cn.yknboss.boss;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

public record EndsunGuardSettings(
        String displayName, double phaseOneHealth, double phaseTwoHealth, double phaseThreeHealth,
        double attackDamage, double armor, double movementSpeed, double attackSpeedMultiplier,
        double followRange, double knockbackResistance,
        double bossbarRange, int experience, String modelId, float modelScale,
        int playerHitCooldownTicks,
        Eclipse eclipse, Scythe scythe, Judgement judgement, Crown crown,
        Shield shield, Furnace furnace, Phases phases, Ultimate ultimate,
        SandFuneral sandFuneral, ScytheEcho scytheEcho, NetherBoundary netherBoundary,
        Sunfall sunfall, Edict edict, Twins twins
) {
    public record Eclipse(double maximum, double fireGainPerDamage, double shieldHitGain,
                          double counterReduction, double passiveDecayPerSecond,
                          long formDurationTicks, double damageMultiplier, double speedMultiplier) { }

    public record Scythe(long cooldownTicks, double range, double hitRadius,
                         long outwardTicks, long returnDelayTicks, long returnTicks,
                         double outwardDamage, double returnDamage, double knockback,
                         long soulFractureTicks) { }

    public record Judgement(long cooldownTicks, long telegraphTicks, double damage,
                            double maximumEclipseBonus, double shadowRadius) { }

    public record Crown(long cooldownTicks, long durationTicks, int flameCount,
                        double flameSpeed, double flameHitRadius, double flameDamage,
                        int fireTicks, double shieldHealOnBurningHit,
                        int totemCount, int totemHits) { }

    public record Shield(long cooldownTicks, long durationTicks, double maximumEnergy,
                         double minimumCastEnergyRatio, double frontDotThreshold,
                         double rearDotThreshold, long staggerTicks,
                         double vulnerableMultiplier) { }

    public record Furnace(long reflectionSpawnIntervalTicks, int maximumReflections,
                           double reflectionHealth, long drainCooldownTicks,
                           long drainChannelTicks, double consumeRange,
                           double damageIncreasePerSoul, double maximumDamageIncrease,
                           double shieldRestorePerSoul) { }

    public record Phases(long transitionInvulnerabilityTicks,
                         double phaseTwoDamageBonus, double phaseThreeDamageBonus,
                         double phaseThreeAttackSpeedMultiplier,
                         double phaseThreeMovementSpeedMultiplier,
                         long phaseThreeUltimateDelayTicks) { }

    public record Ultimate(long durationTicks, double startRadius, double endRadius,
                           double collapseDamage, double executionDamage, int fragmentCount, int fragmentCost,
                           double barrierRadius, double furnaceHealth) { }

    public record SandFuneral(long cooldownTicks, long durationTicks,
                              double startRadius, double endRadius, double gapWidthRadians,
                              double hitRadius, long damageIntervalTicks, double damage,
                              int suffocationPerHit, int maximumSuffocation) { }

    public record ScytheEcho(long cooldownTicks, int teleportCount, long teleportIntervalTicks,
                             long replayDelayTicks, double slashRange, double hitRadius,
                             double damage) { }

    public record NetherBoundary(long cooldownTicks, long castTicks, long durationTicks,
                                 long returnDelayTicks, double halfLength, double damage) { }

    public record Sunfall(long cooldownTicks, long telegraphTicks, int wheelCount,
                          long durationTicks, double impactDamage, double radiationRadius,
                          double radiationDamage, long radiationIntervalTicks,
                          double armorBreakDamage) { }

    public record Edict(long cooldownTicks, long telegraphTicks, long durationTicks,
                        int weaponHitThreshold, double reflectDamage,
                        long movementThresholdTicks, double movementDamage,
                        double gazeDotThreshold, long gazeThresholdTicks, double petrifyDamage,
                        double groupRadius, double groupDamage, long triggerCooldownTicks) { }

    public record Twins(long cooldownTicks, double bodyHealth, long revivalWindowTicks,
                        double successDamagePercent) { }

    public static EndsunGuardSettings load(FileConfiguration config) {
        ConfigurationSection root = config.getConfigurationSection("endsun-guard");
        if (root == null) throw new IllegalArgumentException("缺少 endsun-guard 配置段");
        return new EndsunGuardSettings(
                root.getString("display-name", "&6&l烬日冥卫·阿努赫"),
                between(root.getDouble("phases.p1-health", 38_100.0), 1.0, BossHealthLimits.MAXIMUM, "phases.p1-health"),
                between(root.getDouble("phases.p2-health", 33_800.0), 1.0, BossHealthLimits.MAXIMUM, "phases.p2-health"),
                between(root.getDouble("phases.p3-health", 29_600.0), 1.0, BossHealthLimits.MAXIMUM, "phases.p3-health"),
                between(root.getDouble("attack-damage", 275.0), 0.0, 2048.0, "attack-damage"),
                between(root.getDouble("armor", 24.0), 0.0, BossAttributeLimits.MAX_ARMOR, "armor"),
                between(root.getDouble("movement-speed", 0.32), 0.01, 1.0, "movement-speed"),
                between(root.getDouble("attack-speed-multiplier", 1.2), 0.1, 5.0, "attack-speed-multiplier"),
                between(root.getDouble("follow-range", 48.0), 1.0, 2048.0, "follow-range"),
                between(root.getDouble("knockback-resistance", 0.9), 0.0, 1.0, "knockback-resistance"),
                between(root.getDouble("bossbar-range", 64.0), 1.0, 256.0, "bossbar-range"),
                Math.max(0, root.getInt("experience", 0)),
                root.getString("model.id", "endsun_anuhe"),
                (float) between(root.getDouble("model.scale", 1.5), 0.1, 4.0, "model.scale"),
                Math.max(1, Math.min(40, root.getInt("damage-intake.player-hit-cooldown-ticks", 10))),
                new Eclipse(
                        positive(root.getDouble("eclipse.maximum", 100.0), "eclipse.maximum"),
                        nonNegative(root.getDouble("eclipse.fire-gain-per-damage", 2.5), "eclipse.fire-gain-per-damage"),
                        nonNegative(root.getDouble("eclipse.shield-hit-gain", 12.0), "eclipse.shield-hit-gain"),
                        nonNegative(root.getDouble("eclipse.counter-reduction", 18.0), "eclipse.counter-reduction"),
                        nonNegative(root.getDouble("eclipse.passive-decay-per-second", 0.0), "eclipse.passive-decay-per-second"),
                        seconds(root.getDouble("eclipse.form-duration-seconds", 15.0)),
                        between(root.getDouble("eclipse.damage-multiplier", 1.35), 1.0, 10.0, "eclipse.damage-multiplier"),
                        between(root.getDouble("eclipse.speed-multiplier", 1.25), 1.0, 5.0, "eclipse.speed-multiplier")
                ),
                new Scythe(
                        seconds(root.getDouble("abilities.scythe-return.cooldown-seconds", 6.5)),
                        positive(root.getDouble("abilities.scythe-return.range", 16.0), "abilities.scythe-return.range"),
                        positive(root.getDouble("abilities.scythe-return.hit-radius", 1.8), "abilities.scythe-return.hit-radius"),
                        seconds(root.getDouble("abilities.scythe-return.outward-seconds", 0.9)),
                        seconds(root.getDouble("abilities.scythe-return.return-delay-seconds", 0.6)),
                        seconds(root.getDouble("abilities.scythe-return.return-seconds", 0.75)),
                        nonNegative(root.getDouble("abilities.scythe-return.outward-damage", 240.0), "abilities.scythe-return.outward-damage"),
                        nonNegative(root.getDouble("abilities.scythe-return.return-damage", 485.0), "abilities.scythe-return.return-damage"),
                        nonNegative(root.getDouble("abilities.scythe-return.knockback", 1.35), "abilities.scythe-return.knockback"),
                        seconds(root.getDouble("abilities.scythe-return.soul-fracture-seconds", 6.0))
                ),
                new Judgement(
                        seconds(root.getDouble("abilities.amethyst-judgement.cooldown-seconds", 14.0)),
                        seconds(root.getDouble("abilities.amethyst-judgement.telegraph-seconds", 3.0)),
                        nonNegative(root.getDouble("abilities.amethyst-judgement.damage", 620.0), "abilities.amethyst-judgement.damage"),
                        nonNegative(root.getDouble("abilities.amethyst-judgement.maximum-eclipse-bonus-percent", 100.0),
                                "abilities.amethyst-judgement.maximum-eclipse-bonus-percent") / 100.0,
                        positive(root.getDouble("abilities.amethyst-judgement.shadow-radius", 3.0),
                                "abilities.amethyst-judgement.shadow-radius")
                ),
                new Crown(
                        seconds(root.getDouble("abilities.soul-crown.cooldown-seconds", 18.0)),
                        seconds(root.getDouble("abilities.soul-crown.duration-seconds", 12.0)),
                        Math.max(1, root.getInt("abilities.soul-crown.flame-count", 6)),
                        positive(root.getDouble("abilities.soul-crown.flame-speed", 0.14), "abilities.soul-crown.flame-speed"),
                        positive(root.getDouble("abilities.soul-crown.flame-hit-radius", 1.4), "abilities.soul-crown.flame-hit-radius"),
                        nonNegative(root.getDouble("abilities.soul-crown.flame-damage", 110.0), "abilities.soul-crown.flame-damage"),
                        Math.max(0, root.getInt("abilities.soul-crown.fire-ticks", 80)),
                        nonNegative(root.getDouble("abilities.soul-crown.shield-heal-on-burning-hit", 97.0),
                                "abilities.soul-crown.shield-heal-on-burning-hit"),
                        Math.max(1, root.getInt("abilities.soul-crown.totem-count", 3)),
                        Math.max(1, root.getInt("abilities.soul-crown.totem-hits", 3))
                ),
                new Shield(
                        seconds(root.getDouble("abilities.solar-shield.cooldown-seconds", 12.0)),
                        seconds(root.getDouble("abilities.solar-shield.duration-seconds", 6.0)),
                        positive(root.getDouble("abilities.solar-shield.maximum-energy", 1_208.0), "abilities.solar-shield.maximum-energy"),
                        between(root.getDouble("abilities.solar-shield.minimum-cast-energy-percent", 35.0), 0.0, 100.0,
                                "abilities.solar-shield.minimum-cast-energy-percent") / 100.0,
                        between(root.getDouble("abilities.solar-shield.front-dot-threshold", 0.15), -1.0, 1.0,
                                "abilities.solar-shield.front-dot-threshold"),
                        between(root.getDouble("abilities.solar-shield.rear-dot-threshold", -0.25), -1.0, 1.0,
                                "abilities.solar-shield.rear-dot-threshold"),
                        seconds(root.getDouble("abilities.solar-shield.stagger-seconds", 4.0)),
                        between(root.getDouble("abilities.solar-shield.vulnerable-damage-multiplier", 1.5), 1.0, 10.0,
                                "abilities.solar-shield.vulnerable-damage-multiplier")
                ),
                new Furnace(
                        seconds(root.getDouble("abilities.heart-furnace.reflection-spawn-interval-seconds", 12.0)),
                        Math.max(1, root.getInt("abilities.heart-furnace.maximum-reflections", 10)),
                        positive(root.getDouble("abilities.heart-furnace.reflection-health", 725.0), "abilities.heart-furnace.reflection-health"),
                        seconds(root.getDouble("abilities.heart-furnace.drain-cooldown-seconds", 15.0)),
                        seconds(root.getDouble("abilities.heart-furnace.drain-channel-seconds", 2.2)),
                        positive(root.getDouble("abilities.heart-furnace.consume-range", 32.0), "abilities.heart-furnace.consume-range"),
                        nonNegative(root.getDouble("abilities.heart-furnace.damage-increase-percent-per-soul", 5.0),
                                "abilities.heart-furnace.damage-increase-percent-per-soul") / 100.0,
                        nonNegative(root.getDouble("abilities.heart-furnace.maximum-damage-increase-percent", 50.0),
                                "abilities.heart-furnace.maximum-damage-increase-percent") / 100.0,
                        nonNegative(root.getDouble("abilities.heart-furnace.shield-restore-per-soul", 12.0),
                                "abilities.heart-furnace.shield-restore-per-soul")
                ),
                new Phases(
                        seconds(root.getDouble("phases.transition-invulnerability-seconds", 1.0)),
                        nonNegative(root.getDouble("phases.phase-two-damage-bonus-percent", 15.0),
                                "phases.phase-two-damage-bonus-percent") / 100.0,
                        nonNegative(root.getDouble("phases.phase-three-damage-bonus-percent", 30.0),
                                "phases.phase-three-damage-bonus-percent") / 100.0,
                        between(root.getDouble("phases.phase-three-attack-speed-multiplier", 1.5), 1.0, 5.0,
                                "phases.phase-three-attack-speed-multiplier"),
                        between(root.getDouble("phases.phase-three-movement-speed-multiplier", 1.2), 1.0, 3.0,
                                "phases.phase-three-movement-speed-multiplier"),
                        seconds(root.getDouble("phases.phase-three-ultimate-delay-seconds", 15.0))
                ),
                new Ultimate(
                        seconds(root.getDouble("abilities.black-sun.duration-seconds", 15.0)),
                        positive(root.getDouble("abilities.black-sun.start-radius", 30.0), "abilities.black-sun.start-radius"),
                        positive(root.getDouble("abilities.black-sun.end-radius", 4.0), "abilities.black-sun.end-radius"),
                        nonNegative(root.getDouble("abilities.black-sun.collapse-damage", 345.0), "abilities.black-sun.collapse-damage"),
                        nonNegative(root.getDouble("abilities.black-sun.execution-damage", 1000.0), "abilities.black-sun.execution-damage"),
                        Math.max(1, root.getInt("abilities.black-sun.fragment-count", 9)),
                        Math.max(1, root.getInt("abilities.black-sun.fragment-cost", 6)),
                        positive(root.getDouble("abilities.black-sun.barrier-radius", 3.5), "abilities.black-sun.barrier-radius"),
                        positive(root.getDouble("abilities.black-sun.furnace-health", 967.0), "abilities.black-sun.furnace-health")
                ),
                new SandFuneral(
                        seconds(root.getDouble("abilities.sand-funeral.cooldown-seconds", 20.0)),
                        seconds(root.getDouble("abilities.sand-funeral.duration-seconds", 9.0)),
                        positive(root.getDouble("abilities.sand-funeral.start-radius", 28.0), "abilities.sand-funeral.start-radius"),
                        positive(root.getDouble("abilities.sand-funeral.end-radius", 4.0), "abilities.sand-funeral.end-radius"),
                        Math.toRadians(between(root.getDouble("abilities.sand-funeral.gap-width-degrees", 42.0),
                                10.0, 160.0, "abilities.sand-funeral.gap-width-degrees")),
                        positive(root.getDouble("abilities.sand-funeral.hit-radius", 1.8), "abilities.sand-funeral.hit-radius"),
                        seconds(root.getDouble("abilities.sand-funeral.damage-interval-seconds", 0.7)),
                        nonNegative(root.getDouble("abilities.sand-funeral.damage", 85.0), "abilities.sand-funeral.damage"),
                        Math.max(1, root.getInt("abilities.sand-funeral.suffocation-per-hit", 1)),
                        Math.max(1, root.getInt("abilities.sand-funeral.maximum-suffocation", 8))
                ),
                new ScytheEcho(
                        seconds(root.getDouble("abilities.scythe-echo.cooldown-seconds", 22.0)),
                        Math.max(1, Math.min(6, root.getInt("abilities.scythe-echo.teleport-count", 3))),
                        seconds(root.getDouble("abilities.scythe-echo.teleport-interval-seconds", 0.45)),
                        seconds(root.getDouble("abilities.scythe-echo.replay-delay-seconds", 2.5)),
                        positive(root.getDouble("abilities.scythe-echo.slash-range", 8.0), "abilities.scythe-echo.slash-range"),
                        positive(root.getDouble("abilities.scythe-echo.hit-radius", 2.0), "abilities.scythe-echo.hit-radius"),
                        nonNegative(root.getDouble("abilities.scythe-echo.damage", 380.0), "abilities.scythe-echo.damage")
                ),
                new NetherBoundary(
                        seconds(root.getDouble("abilities.nether-boundary.cooldown-seconds", 16.0)),
                        seconds(root.getDouble("abilities.nether-boundary.cast-seconds", 1.0)),
                        seconds(root.getDouble("abilities.nether-boundary.duration-seconds", 10.0)),
                        seconds(root.getDouble("abilities.nether-boundary.return-delay-seconds", 3.0)),
                        positive(root.getDouble("abilities.nether-boundary.half-length", 24.0), "abilities.nether-boundary.half-length"),
                        nonNegative(root.getDouble("abilities.nether-boundary.return-damage", 205.0), "abilities.nether-boundary.return-damage")
                ),
                new Sunfall(
                        seconds(root.getDouble("abilities.sunfall.cooldown-seconds", 24.0)),
                        seconds(root.getDouble("abilities.sunfall.telegraph-seconds", 2.5)),
                        Math.max(1, Math.min(12, root.getInt("abilities.sunfall.wheel-count", 5))),
                        seconds(root.getDouble("abilities.sunfall.duration-seconds", 14.0)),
                        nonNegative(root.getDouble("abilities.sunfall.impact-damage", 380.0), "abilities.sunfall.impact-damage"),
                        positive(root.getDouble("abilities.sunfall.radiation-radius", 4.5), "abilities.sunfall.radiation-radius"),
                        nonNegative(root.getDouble("abilities.sunfall.radiation-damage", 70.0), "abilities.sunfall.radiation-damage"),
                        seconds(root.getDouble("abilities.sunfall.radiation-interval-seconds", 1.0)),
                        nonNegative(root.getDouble("abilities.sunfall.armor-break-damage", 1_208.0), "abilities.sunfall.armor-break-damage")
                ),
                new Edict(
                        seconds(root.getDouble("abilities.pharaoh-edict.cooldown-seconds", 26.0)),
                        seconds(root.getDouble("abilities.pharaoh-edict.telegraph-seconds", 2.0)),
                        seconds(root.getDouble("abilities.pharaoh-edict.duration-seconds", 12.0)),
                        Math.max(2, root.getInt("abilities.pharaoh-edict.weapon-hit-threshold", 3)),
                        nonNegative(root.getDouble("abilities.pharaoh-edict.reflect-damage", 170.0), "abilities.pharaoh-edict.reflect-damage"),
                        seconds(root.getDouble("abilities.pharaoh-edict.movement-threshold-seconds", 2.5)),
                        nonNegative(root.getDouble("abilities.pharaoh-edict.movement-damage", 380.0), "abilities.pharaoh-edict.movement-damage"),
                        between(root.getDouble("abilities.pharaoh-edict.gaze-dot-threshold", 0.92), 0.0, 1.0,
                                "abilities.pharaoh-edict.gaze-dot-threshold"),
                        seconds(root.getDouble("abilities.pharaoh-edict.gaze-threshold-seconds", 2.0)),
                        nonNegative(root.getDouble("abilities.pharaoh-edict.petrify-damage", 415.0), "abilities.pharaoh-edict.petrify-damage"),
                        positive(root.getDouble("abilities.pharaoh-edict.group-radius", 3.5), "abilities.pharaoh-edict.group-radius"),
                        nonNegative(root.getDouble("abilities.pharaoh-edict.group-damage", 275.0), "abilities.pharaoh-edict.group-damage"),
                        seconds(root.getDouble("abilities.pharaoh-edict.trigger-cooldown-seconds", 2.0))
                ),
                new Twins(
                        seconds(root.getDouble("abilities.twin-eclipse.cooldown-seconds", 50.0)),
                        between(root.getDouble("abilities.twin-eclipse.body-health", 1_450.0), 1.0, BossHealthLimits.MAXIMUM,
                                "abilities.twin-eclipse.body-health"),
                        seconds(root.getDouble("abilities.twin-eclipse.revival-window-seconds", 20.0)),
                        between(root.getDouble("abilities.twin-eclipse.success-damage-percent", 15.0), 0.0, 100.0,
                                "abilities.twin-eclipse.success-damage-percent") / 100.0
                )
        );
    }

    private static long seconds(double value) {
        if (!Double.isFinite(value) || value < 0.0) throw new IllegalArgumentException("秒数不能小于 0");
        return Math.max(1L, Math.round(value * 20.0));
    }

    private static double positive(double value, String path) {
        if (!Double.isFinite(value) || value <= 0.0) {
            throw new IllegalArgumentException("endsun-guard." + path + " 必须大于 0");
        }
        return value;
    }

    private static double nonNegative(double value, String path) {
        if (!Double.isFinite(value) || value < 0.0) {
            throw new IllegalArgumentException("endsun-guard." + path + " 不能小于 0");
        }
        return value;
    }

    private static double between(double value, double minimum, double maximum, String path) {
        if (!Double.isFinite(value) || value < minimum || value > maximum) {
            throw new IllegalArgumentException("endsun-guard." + path + " 必须在 "
                    + minimum + " 到 " + maximum + " 之间");
        }
        return value;
    }
}
