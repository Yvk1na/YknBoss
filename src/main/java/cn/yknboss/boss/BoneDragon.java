package cn.yknboss.boss;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.model.BetterModelSupport;
import cn.yknboss.util.ParticleCompat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Skeleton;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class BoneDragon implements ScriptedBossLifecycle {
    private enum Phase { P1, P2, P3 }
    private enum Skill {
        NONE, TAIL_SWEEP, GRAB, FIRE_BREATH, SUMMON, METEOR,
        SKULL_BARRAGE, BONE_PRISON, BONE_SPIKES, GROUND_CHARGE, DEATH_SUMMON, SKELETON_ABSORB,
        WING_BREAK, STUN
    }

    private static final long TAIL_ANIMATION_TICKS = 22L;
    private static final long ENTRANCE_ANIMATION_TICKS = 68L;
    private static final long ENTRANCE_ASCENT_START_TICK = 10L;
    private static final long ENTRANCE_WING_OPEN_TICK = 52L;
    private static final long GRAB_CHASE_TICKS = 40L;
    private static final long GRAB_HOLD_TICKS = 36L;
    private static final long SUMMON_ANIMATION_TICKS = 40L;
    private static final long SKELETON_ABSORB_ANIMATION_TICKS = 48L;
    private static final long SKULL_BARRAGE_ANIMATION_TICKS = 48L;
    private static final long BONE_PRISON_ANIMATION_TICKS = 35L;
    private static final long WING_BREAK_ANIMATION_TICKS = 67L;
    // The pitched ground pose reaches almost exactly two blocks below the model origin at scale 1.
    private static final double GROUND_MODEL_BASE_Y_OFFSET = 2.0;
    private static final int ABSORPTION_CURSE_INTERVAL = 15;
    private static final int ABSORPTION_CURSE_DURATION_TICKS = 160;
    private final YknBossPlugin plugin;
    private Zombie controller;
    private DamageSource skillDamageSource;
    private final BossBar bossBar;
    private final double encounterHealthMultiplier;
    private final Set<UUID> minions = new HashSet<>();
    private final Set<UUID> deathRulers = new HashSet<>();
    private final Map<UUID, Long> deathRulerNextHealAt = new HashMap<>();
    private final Map<UUID, Double> damageThreat = new HashMap<>();
    private final List<UUID> absorbTargets = new ArrayList<>();
    private final List<Meteor> meteors = new ArrayList<>();
    private final List<TrackingSkull> trackingSkulls = new ArrayList<>();
    private final List<PrisonBlock> prisonBlocks = new ArrayList<>();
    private final List<BoneSpike> boneSpikes = new ArrayList<>();
    private final Set<UUID> boneSpikeHitPlayers = new HashSet<>();
    private final Set<UUID> chargeHitPlayers = new HashSet<>();
    private BetterModelSupport.Instance model;
    private Vector facing = new Vector(0, 0, 1);
    private Vector breathAim = new Vector(0, 0, 1);
    private Vector chargeDirection = new Vector(0, 0, 1);
    private Vector movementVelocity = new Vector();
    private Phase phase = Phase.P1;
    private Skill skill = Skill.NONE;
    private UUID targetId;
    private UUID capturedPlayerId;
    private long age;
    private boolean entranceActive;
    private boolean groundWalking;
    private long entranceStartedAt;
    private double entranceGroundY;
    private double entranceTargetY;
    private float entranceBaseYaw;
    private long skillStartedAt;
    private long grabCapturedAt;
    private long deathStartedAt;
    private boolean dying;
    private Phase pendingPhase;
    private int p1DamageHits;
    private int absorbedSkeletons;
    private int absorbedThisCast;
    private int deathSummonMask;
    private double stunStartY;
    private double stunGroundY;
    private double wingBreakStartY;
    private boolean stunImpactPlayed;
    private long attackCooldown = 20L;
    private long tailCooldown = 80L;
    private long grabCooldown = 60L;
    private long breathCooldown = 120L;
    private long summonCooldown = 260L;
    private long meteorCooldown = 320L;
    private long skullCooldown = 200L;
    private long prisonCooldown = 180L;
    private long boneSpikesCooldown = 140L;
    private long chargeCooldown = 80L;
    private long p3SummonCooldown;
    private long phaseHealthLockUntil;
    private long nextHitFeedbackAt;
    private boolean bossBarRefreshQueued;
    // Authoritative health for the current phase. The Bukkit entity is only the
    // carrier because model/level plugins may rewrite its health attributes.
    private double phaseHealthRemaining;
    private UUID pendingHeadCriticalAttackerId;
    private long pendingHeadCriticalAt = -1L;
    private Location pendingHeadCriticalImpact;
    private double grabStartY;

    private BoneDragon(YknBossPlugin plugin, Zombie controller, boolean restored) {
        this.plugin = plugin;
        this.controller = controller;
        this.skillDamageSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(controller)
                .withDirectEntity(controller)
                .build();
        Double storedHealthMultiplier = controller.getPersistentDataContainer().get(
                plugin.encounterHealthMultiplierKey(), PersistentDataType.DOUBLE);
        this.encounterHealthMultiplier = normalizeHealthMultiplier(storedHealthMultiplier);
        this.bossBar = Bukkit.createBossBar(
                legacyString(plugin.boneDragonSettings().displayName()),
                BarColor.WHITE,
                BarStyle.SOLID,
                BarFlag.DARKEN_SKY,
                BarFlag.CREATE_FOG
        );
        if (restored) {
            phase = readStoredPhase();
            absorbedSkeletons = readAbsorbedSkeletons();
            deathSummonMask = readDeathSummonMask();
        }
        applyPhaseBossBarColor();
        bossBar.setVisible(true);
        applySettings(restored);
        if (restored) {
            if (phase != Phase.P1 && model != null) {
                model.setWingsVisible(false);
                model.playLoop("ground_idle");
            }
            if (phase == Phase.P3 && model != null) model.setRedGlow(true);
            p3SummonCooldown = plugin.boneDragonSettings().p3SummonIntervalTicks();
            beginDeathSummon();
        }
    }

    public static BoneDragon spawn(YknBossPlugin plugin, Location requested) {
        return spawn(plugin, requested, 1.0);
    }

    public static BoneDragon spawn(YknBossPlugin plugin, Location requested, double healthMultiplier) {
        World world = requested.getWorld();
        if (world == null) throw new IllegalArgumentException("生成位置没有世界");
        int ground = world.getHighestBlockYAt(requested, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        Location spawn = requested.clone();
        spawn.setY(ground + 1.0);
        double normalizedMultiplier = normalizeHealthMultiplier(healthMultiplier);
        Zombie controller = world.spawn(spawn, Zombie.class, entity -> {
            configureController(plugin, entity);
            entity.getPersistentDataContainer().set(plugin.encounterHealthMultiplierKey(),
                    PersistentDataType.DOUBLE, normalizedMultiplier);
        });
        try {
            BoneDragon dragon = new BoneDragon(plugin, controller, false);
            dragon.refillCurrentPhaseHealth();
            dragon.spawnEntrance();
            return dragon;
        } catch (RuntimeException | LinkageError exception) {
            controller.remove();
            throw exception;
        }
    }

    public static BoneDragon restore(YknBossPlugin plugin, Zombie controller) {
        return new BoneDragon(plugin, controller, true);
    }

    private static void configureController(YknBossPlugin plugin, Zombie entity) {
        entity.setAdult();
        entity.setAI(false);
        entity.setGravity(false);
        entity.setInvisible(true);
        entity.setFireTicks(0);
        entity.setVisualFire(false);
        entity.setSilent(true);
        entity.setCollidable(false);
        entity.setCanPickupItems(false);
        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(true);
        entity.getEquipment().clear();
        entity.getPersistentDataContainer().set(
                plugin.bossTypeKey(), PersistentDataType.STRING, BoneDragonManager.TYPE_ID);
        entity.getPersistentDataContainer().set(
                plugin.boneDragonPhaseKey(), PersistentDataType.STRING, Phase.P1.name());
        entity.getPersistentDataContainer().set(
                plugin.boneDragonAbsorbedKey(), PersistentDataType.INTEGER, 0);
        entity.getPersistentDataContainer().set(
                plugin.boneDragonDeathSummonMaskKey(), PersistentDataType.INTEGER, 0);
    }

    public UUID id() {
        return controller.getUniqueId();
    }

    public Location location() {
        return controller.getLocation();
    }

    public double health() {
        return phaseHealthRemaining > 0.0 ? phaseHealthRemaining : controller.getHealth();
    }

    public double maxHealth() {
        // The configured phase pool is authoritative.  Attribute modifiers installed
        // by other plugins must not dilute the independent P1/P2/P3 boss bars.
        return configuredPhaseHealth(phase);
    }

    public String currentSkill() {
        if (dying) return "death";
        if (entranceActive) return "spawn";
        return skill.name().toLowerCase();
    }

    public String phaseName() {
        return phase.name();
    }

    @Override
    public boolean finalDeathSequenceActive() {
        return dying;
    }

    @Override
    public boolean controllerDamageLocked() {
        return dying || pendingPhase != null || entranceActive || phaseHealthLockUntil > 0L
                || skill == Skill.WING_BREAK;
    }

    @Override
    public UUID recoverUnexpectedControllerDeath() {
        Phase defeatedPhase = phase;
        Location recoveryLocation = controller.getLocation().clone();
        World world = recoveryLocation.getWorld();
        if (world == null) throw new IllegalStateException("骨龙异常死亡位置没有世界");

        cancelSkeletonAbsorption();
        releaseCapturedPlayer();
        closeModel();
        Zombie replacement = world.spawn(recoveryLocation, Zombie.class, entity -> {
            configureController(plugin, entity);
            entity.getPersistentDataContainer().set(plugin.encounterHealthMultiplierKey(),
                    PersistentDataType.DOUBLE, encounterHealthMultiplier);
            entity.getPersistentDataContainer().set(plugin.boneDragonPhaseKey(),
                    PersistentDataType.STRING, defeatedPhase.name());
            entity.getPersistentDataContainer().set(plugin.boneDragonAbsorbedKey(),
                    PersistentDataType.INTEGER, absorbedSkeletons);
            entity.getPersistentDataContainer().set(plugin.boneDragonDeathSummonMaskKey(),
                    PersistentDataType.INTEGER, deathSummonMask);
        });
        controller = replacement;
        skillDamageSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(replacement)
                .withDirectEntity(replacement)
                .build();
        pendingPhase = null;
        entranceActive = false;
        skill = Skill.NONE;
        skillStartedAt = 0L;
        phaseHealthLockUntil = 0L;
        movementVelocity.zero();
        groundWalking = false;
        applySettings(false);
        controller.setNoDamageTicks(Math.max(20, controller.getMaximumNoDamageTicks()));
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        Phase nextPhase = defeatedPhase == Phase.P1 ? Phase.P2
                : defeatedPhase == Phase.P2 ? Phase.P3 : null;
        pendingPhase = nextPhase;
        phaseHealthLockUntil = age + 2L;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (controller != replacement || !replacement.isValid() || replacement.isDead()) return;
            pendingPhase = null;
            phaseHealthLockUntil = 0L;
            if (nextPhase == Phase.P2) beginPhaseTwoTransition();
            else if (nextPhase == Phase.P3) enterPhaseThree();
            else startDeathSequence();
        });
        return replacement.getUniqueId();
    }

    public boolean tick() {
        if (!controller.isValid() || controller.isDead()) return false;
        age++;
        controller.setFireTicks(0);
        controller.setVisualFire(false);
        if (pendingPhase != null) {
            controller.setInvulnerable(true);
            controller.setVelocity(new Vector());
            synchronizeControllerHealth();
            updateBossBar();
            return true;
        }
        if (dying) {
            tickDeathSequence();
            return controller.isValid();
        }
        tickDeathRulerHealing();
        tickMeteors();
        tickTrackingSkulls();
        tickPrisonBlocks();
        tickBoneSpikeDisplays();
        tickPhaseHealthLock();
        if (age % 10L == 0L) updateBossBar();
        if (entranceActive) {
            tickEntrance();
            return true;
        }
        decrementCooldowns();
        if (phase == Phase.P3 && age % 5L == 0L) {
            ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation(), 18,
                    3.8, 2.2, 3.8, 0.02, org.bukkit.Color.RED, 1.6f);
        }
        if (age % 10L == 0L) {
            acquireTarget();
            cleanMinions();
            cleanDeathRulers();
        }

        Player target = targetPlayer();
        if (skill == Skill.NONE) {
            if (phase == Phase.P1) tickNormalFlight(target);
            else tickGroundMovement(target);
            tryNormalAttack(target);
            tryBeginSkill(target);
        } else {
            tickSkill(target);
        }
        return true;
    }

    public void applySettings(boolean preserveHealthFraction) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        double fraction = preserveHealthFraction ? healthFraction() : 1.0;
        double phaseMaximum = configuredPhaseHealth(phase);
        setBase(Attribute.MAX_HEALTH, phaseMaximum);
        setBase(Attribute.ATTACK_DAMAGE, Math.min(2048.0, scaledDamage(settings.attackDamage())));
        setBase(Attribute.ARMOR, settings.armor());
        setBase(Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
        setBase(Attribute.FOLLOW_RANGE, settings.followRange());
        controller.setAI(false);
        controller.setGravity(false);
        controller.setInvulnerable(dying || skill == Skill.WING_BREAK || phaseHealthLockUntil > age);
        controller.setInvisible(true);
        controller.customName(legacyComponent(settings.displayName()));
        controller.setCustomNameVisible(false);
        setCurrentPhaseHealth(Math.max(1.0, Math.min(phaseMaximum, phaseMaximum * fraction)));
        enforceMinionLimit();
        refreshDeathRulerTimers();
        ensureModel();
        if (model != null) model.setWingsVisible(phase == Phase.P1);
    }

    public void recoverModelAfterSpectralArrow() {
        if (!controller.isValid() || controller.isDead()) return;
        controller.removePotionEffect(PotionEffectType.GLOWING);
        controller.setGlowing(false);
        controller.setInvisible(true);
        ensureModel();
        if (model == null) return;
        model.refresh();
        model.setWingsVisible(phase == Phase.P1);
        if (phase == Phase.P3) model.setRedGlow(true);
    }

    public boolean breakPrisonBlock(Block block) {
        Iterator<PrisonBlock> iterator = prisonBlocks.iterator();
        while (iterator.hasNext()) {
            PrisonBlock prison = iterator.next();
            if (!prison.block.equals(block)) continue;
            if (block.getType() == Material.BONE_BLOCK) {
                block.setBlockData(prison.original, false);
                block.getWorld().spawnParticle(Particle.BLOCK, block.getLocation().add(0.5, 0.5, 0.5),
                        18, 0.35, 0.35, 0.35, 0.06, Material.BONE_BLOCK.createBlockData());
                block.getWorld().playSound(block.getLocation(), Sound.BLOCK_BONE_BLOCK_BREAK, 1.0f, 0.9f);
            }
            iterator.remove();
            return true;
        }
        return false;
    }

    private void ensureModel() {
        if (model != null && model.valid()) return;
        closeModel();
        BoneDragonSettings settings = plugin.boneDragonSettings();
        model = plugin.betterModel().attach(settings.modelId(), controller, settings.modelScale());
        model.setHitBoxDamageModifier(this::modifyHitBoxDamage);
    }

    private void setBase(Attribute attribute, double value) {
        AttributeInstance instance = controller.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private void decrementCooldowns() {
        if (attackCooldown > 0) attackCooldown--;
        if (tailCooldown > 0) tailCooldown--;
        if (grabCooldown > 0) grabCooldown--;
        if (breathCooldown > 0) breathCooldown--;
        if (summonCooldown > 0) summonCooldown--;
        if (meteorCooldown > 0) meteorCooldown--;
        if (skullCooldown > 0) skullCooldown--;
        if (prisonCooldown > 0) prisonCooldown--;
        if (boneSpikesCooldown > 0) boneSpikesCooldown--;
        if (chargeCooldown > 0) chargeCooldown--;
        if (p3SummonCooldown > 0) p3SummonCooldown--;
    }

    private void acquireTarget() {
        Player highestThreatPlayer = null;
        double bestThreat = -1.0;
        double bestDistance = Double.MAX_VALUE;
        double followRangeSquared = plugin.boneDragonSettings().followRange()
                * plugin.boneDragonSettings().followRange();
        for (Player player : controller.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
            double distance = player.getLocation().distanceSquared(controller.getLocation());
            if (distance > followRangeSquared) continue;
            double threat = damageThreat.getOrDefault(player.getUniqueId(), 0.0);
            if (threat > bestThreat + 0.0001
                    || (Math.abs(threat - bestThreat) <= 0.0001 && distance < bestDistance)) {
                highestThreatPlayer = player;
                bestThreat = threat;
                bestDistance = distance;
            }
        }
        targetId = highestThreatPlayer == null ? null : highestThreatPlayer.getUniqueId();
    }

    private Player targetPlayer() {
        if (targetId == null) return null;
        Player player = Bukkit.getPlayer(targetId);
        return player != null && isCombatPlayer(player) && player.getWorld() == controller.getWorld() ? player : null;
    }

    private boolean isCombatPlayer(Player player) {
        return player.isOnline() && !player.isDead()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR;
    }

    private void beginPhaseTwoTransition() {
        if (phase != Phase.P1 || dying || skill == Skill.WING_BREAK) return;
        cancelSkeletonAbsorption();
        releaseCapturedPlayer();
        wingBreakStartY = controller.getY();
        controller.setVelocity(new Vector());
        phaseHealthLockUntil = age + WING_BREAK_ANIMATION_TICKS
                + plugin.boneDragonSettings().phaseTransitionInvulnerabilityTicks();
        controller.setInvulnerable(true);
        setPhase(Phase.P2);
        refillCurrentPhaseHealth();
        beginSkill(Skill.WING_BREAK, "wing_break", "ground_idle");
        updateBossBar();
        announce(plugin.messages().component("boss.bone-dragon.wing-break-start"));
    }

    private void enterPhaseThree() {
        if (phase != Phase.P2 || dying) return;
        cancelSkeletonAbsorption();
        releaseCapturedPlayer();
        phaseHealthLockUntil = age + plugin.boneDragonSettings().phaseTransitionInvulnerabilityTicks();
        controller.setInvulnerable(true);
        skill = Skill.NONE;
        skillStartedAt = 0L;
        movementVelocity.zero();
        controller.setVelocity(new Vector());
        setPhase(Phase.P3);
        refillCurrentPhaseHealth();
        groundWalking = false;
        p3SummonCooldown = plugin.boneDragonSettings().p3SummonIntervalTicks();
        if (model != null) {
            model.setWingsVisible(false);
            model.setRedGlow(true);
            model.playLoop("ground_idle");
        }
        ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation(), 120,
                4.5, 2.5, 4.5, 0.03, org.bukkit.Color.RED, 2.2f);
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 4.0f, 0.55f);
        updateBossBar();
        announce(plugin.messages().component("boss.bone-dragon.phase-three"));
        beginDeathSummon();
    }

    private void tickPhaseHealthLock() {
        if (phaseHealthLockUntil <= 0L) return;
        if (age <= phaseHealthLockUntil) {
            refillCurrentPhaseHealth();
            controller.setInvulnerable(true);
            return;
        }
        // Refill at the exact hand-off from invulnerable transition to combat so no
        // late damage from the preceding tick can leave the new phase partially empty.
        refillCurrentPhaseHealth();
        phaseHealthLockUntil = 0L;
        controller.setInvulnerable(dying || entranceActive || skill == Skill.WING_BREAK);
        updateBossBar();
    }

    private void tickGroundMovement(Player target) {
        tickGroundMovement(target, 1.0);
    }

    private void tickGroundMovement(Player target, double speedMultiplier) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Location current = controller.getLocation();
        Location desired = current.clone();
        desired.setY(groundY(current) + groundModelYOffset());
        boolean pursuing = false;
        if (target != null) {
            face(target.getEyeLocation());
            double horizontal = horizontalDistanceSquared(current, target.getLocation());
            double stopDistance = Math.max(1.8, Math.min(3.0, settings.attackRange() * 0.7));
            if (horizontal > stopDistance * stopDistance) {
                pursuing = true;
                desired.setX(target.getX() - facing.getX() * stopDistance);
                desired.setZ(target.getZ() - facing.getZ() * stopDistance);
                Vector side = new Vector(-facing.getZ(), 0, facing.getX())
                        .multiply(Math.sin(age * 0.16) * 0.8);
                desired.add(side);
                desired.setY(groundY(desired) + groundModelYOffset());
            }
        }
        moveToward(desired, Math.max(0.26, settings.flightSpeed() * 0.72) * speedMultiplier);
        if (skill == Skill.NONE) {
            setGroundWalking(pursuing && movementVelocity.lengthSquared() > 0.01);
            if (groundWalking && age % 9L == 0L) {
                Location step = controller.getLocation().clone().add(0, -2.1, 0);
                controller.getWorld().playSound(step, Sound.BLOCK_BONE_BLOCK_STEP, 2.2f, 0.62f);
                controller.getWorld().spawnParticle(Particle.BLOCK, step, 8,
                        1.5, 0.12, 1.5, 0.025, Material.BONE_BLOCK.createBlockData());
            }
        }
    }

    private void setGroundWalking(boolean walking) {
        if (phase == Phase.P1 || skill != Skill.NONE || groundWalking == walking) return;
        groundWalking = walking;
        if (model != null) model.playLoop(walking ? "ground_walk" : "ground_idle");
    }

    private static double horizontalDistanceSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private void tickNormalFlight(Player target) {
        tickNormalFlight(target, 1.0);
    }

    private void tickNormalFlight(Player target, double speedMultiplier) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        if (target == null) {
            Location hover = controller.getLocation();
            hover.setY(groundY(hover) + settings.flightAltitude());
            moveToward(hover, settings.flightSpeed() * 0.55 * speedMultiplier);
            return;
        }
        double orbitAngle = age * 0.024;
        Location desired = target.getLocation().clone().add(
                Math.cos(orbitAngle) * settings.orbitRadius(), 0,
                Math.sin(orbitAngle) * settings.orbitRadius());
        desired.setY(groundY(desired) + settings.flightAltitude());
        face(target.getEyeLocation());
        moveToward(desired, Math.max(0.50, settings.flightSpeed() * 1.25) * speedMultiplier);
    }

    private void tryNormalAttack(Player target) {
        if (target == null || attackCooldown > 0) return;
        BoneDragonSettings settings = plugin.boneDragonSettings();
        if (target.getLocation().distanceSquared(controller.getLocation()) > settings.attackRange() * settings.attackRange()) return;
        target.damage(plugin.slayer().incomingDamage(target, scaledDamage(settings.attackDamage())), controller);
        Vector push = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
        if (push.lengthSquared() < 0.01) push = facing.clone();
        target.setVelocity(target.getVelocity().add(push.normalize().multiply(0.8)).setY(0.35));
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 1.8f, 1.35f);
        attackCooldown = settings.attackCooldownTicks();
    }

    private void tryBeginSkill(Player target) {
        if (target == null) return;
        BoneDragonSettings settings = plugin.boneDragonSettings();
        double distanceSquared = target.getLocation().distanceSquared(controller.getLocation());
        if (phase == Phase.P1) {
            double horizontal = horizontalDistanceSquared(target.getLocation(), controller.getLocation());
            double grabStartRange = Math.max(32.0, settings.orbitRadius() + 12.0);
            if (grabCooldown <= 0 && horizontal <= grabStartRange * grabStartRange) {
                beginSkill(Skill.GRAB, "dive_grab");
                grabCooldown = settings.grabCooldownTicks();
                grabStartY = controller.getY();
                grabCapturedAt = 0L;
            } else if (skullCooldown <= 0) {
                beginSkill(Skill.SKULL_BARRAGE, "skull_barrage");
                skullCooldown = settings.skullCooldownTicks();
            } else if (breathCooldown <= 0 && distanceSquared <= settings.breathRange() * settings.breathRange()) {
                beginSkill(Skill.FIRE_BREATH, "fire_breath");
                breathCooldown = settings.breathCooldownTicks();
            } else if (summonCooldown <= 0) {
                beginSkill(Skill.SUMMON, "summon");
                summonCooldown = settings.summonCooldownTicks();
            } else if (meteorCooldown <= 0) {
                beginSkill(Skill.METEOR, "meteor");
                meteorCooldown = settings.meteorCooldownTicks();
            }
            return;
        }

        if (phase == Phase.P3 && p3SummonCooldown <= 0) {
            beginSkill(Skill.SUMMON, "summon");
            p3SummonCooldown = settings.p3SummonIntervalTicks();
            summonCooldown = settings.summonCooldownTicks();
        } else if (summonCooldown <= 0) {
            beginSkill(Skill.SUMMON, "summon");
            summonCooldown = settings.summonCooldownTicks();
        } else if (prisonCooldown <= 0) {
            beginSkill(Skill.BONE_PRISON, "bone_prison");
            prisonCooldown = settings.prisonCooldownTicks();
        } else if (boneSpikesCooldown <= 0) {
            beginSkill(Skill.BONE_SPIKES, "bone_spikes");
            boneSpikesCooldown = settings.boneSpikesCooldownTicks();
            boneSpikeHitPlayers.clear();
            announce(plugin.messages().component("boss.bone-dragon.bone-spikes"));
        } else if (chargeCooldown <= 0) {
            beginSkill(Skill.GROUND_CHARGE, "ground_charge");
            chargeCooldown = settings.chargeCooldownTicks();
            chargeDirection = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
            if (chargeDirection.lengthSquared() < 0.0001) chargeDirection = facing.clone();
            else chargeDirection.normalize();
            chargeHitPlayers.clear();
        } else if (breathCooldown <= 0 && distanceSquared <= settings.breathRange() * settings.breathRange()) {
            beginSkill(Skill.FIRE_BREATH, "fire_breath");
            breathCooldown = settings.breathCooldownTicks();
        } else if (tailCooldown <= 0 && distanceSquared <= settings.tailRange() * settings.tailRange()) {
            beginSkill(Skill.TAIL_SWEEP, "tail_sweep");
            tailCooldown = settings.tailCooldownTicks();
        }
    }

    private void beginSkill(Skill next, String animation) {
        beginSkill(next, animation, phaseIdleAnimation());
    }

    private void beginSkill(Skill next, String animation, String fallbackAnimation) {
        skill = next;
        skillStartedAt = age;
        groundWalking = false;
        movementVelocity.zero();
        controller.setVelocity(new Vector());
        if (model != null) model.playOnce(animation, fallbackAnimation);
    }

    private String phaseIdleAnimation() {
        return phase == Phase.P1 ? "idle_fly" : "ground_idle";
    }

    private void tickSkill(Player target) {
        long elapsed = age - skillStartedAt;
        switch (skill) {
            case TAIL_SWEEP -> tickTailSweep(elapsed, target);
            case GRAB -> tickGrab(elapsed, target);
            case FIRE_BREATH -> tickFireBreath(elapsed, target);
            case SUMMON -> tickSummon(elapsed, target);
            case METEOR -> tickMeteorRain(elapsed, target);
            case SKULL_BARRAGE -> tickSkullBarrage(elapsed, target);
            case BONE_PRISON -> tickBonePrison(elapsed, target);
            case BONE_SPIKES -> tickBoneSpikes(elapsed, target);
            case GROUND_CHARGE -> tickGroundCharge(elapsed, target);
            case DEATH_SUMMON -> tickDeathSummon(elapsed, target);
            case SKELETON_ABSORB -> tickSkeletonAbsorb(elapsed, target);
            case WING_BREAK -> tickWingBreak(elapsed);
            case STUN -> tickStun(elapsed);
            case NONE -> { }
        }
    }

    private void tickTailSweep(long elapsed, Player target) {
        controller.setVelocity(new Vector());
        if (elapsed == 0L && target != null) face(target.getEyeLocation());
        controller.setRotation(controller.getYaw() + 18.0f, 0.0f);
        if (elapsed == 7L) controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_ENDER_DRAGON_FLAP, 2.5f, 0.65f);
        if (elapsed == 8L) executeTailSweep();
        if (elapsed >= TAIL_ANIMATION_TICKS) finishSkill();
    }

    private void executeTailSweep() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Location center = controller.getLocation();
        for (Player player : controller.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
            Vector toward = player.getLocation().toVector().subtract(center.toVector());
            if (toward.lengthSquared() > settings.tailRange() * settings.tailRange()) continue;
            Vector flat = toward.clone().setY(0);
            damageWithSkill(player, scaledDamage(settings.tailDamage()));
            if (flat.lengthSquared() < 0.01) flat = facing.clone().multiply(-1);
            player.setVelocity(flat.normalize().multiply(settings.tailKnockback()).setY(0.55));
        }
        for (int degrees = 0; degrees < 360; degrees += 8) {
            Vector ray = facing.clone().rotateAroundY(Math.toRadians(degrees));
            for (double distance = 2.5; distance <= settings.tailRange(); distance += 1.1) {
                Location point = center.clone().add(ray.clone().multiply(distance)).add(0, 0.5, 0);
                controller.getWorld().spawnParticle(Particle.SWEEP_ATTACK, point, 1, 0, 0, 0, 0);
                controller.getWorld().spawnParticle(Particle.SOUL, point, 1, 0.15, 0.1, 0.15, 0.01);
            }
        }
        controller.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 3.0f, 0.45f);
    }

    private void tickGrab(long elapsed, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Player captured = capturedPlayer();
        if (captured == null && capturedPlayerId == null && elapsed <= GRAB_CHASE_TICKS && target != null) {
            face(target.getEyeLocation());
            Location targetCenter = target.getLocation().clone().add(0, 1.0, 0);
            Location low = targetCenter.clone()
                    .subtract(facing.clone().multiply(1.25))
                    .add(0, 2.35, 0);
            moveToward(low, Math.max(0.85, settings.flightSpeed() * 2.0));
            Location claw = clawLocation();
            controller.getWorld().spawnParticle(Particle.SOUL, claw, 2, 0.2, 0.2, 0.2, 0.01);
            if (claw.distanceSquared(targetCenter)
                    <= settings.grabCaptureRange() * settings.grabCaptureRange()) {
                capturedPlayerId = target.getUniqueId();
                captured = target;
                grabStartY = controller.getY();
                grabCapturedAt = age;
                damageWithSkill(target, scaledDamage(settings.grabCaptureDamage()));
                controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_PHANTOM_BITE, 2.5f, 0.6f);
            }
        }
        if (captured != null) {
            long heldTicks = Math.max(0L, age - grabCapturedAt);
            double linearProgress = Math.max(0.0, Math.min(1.0, heldTicks / 32.0));
            double progress = smoothStep(linearProgress);
            Location ascent = controller.getLocation().clone();
            ascent.setY(grabStartY + settings.grabLiftHeight() * progress);
            moveToward(ascent, 0.68);
            Location held = clawLocation();
            captured.teleport(held);
            captured.setVelocity(new Vector());
            captured.setFallDistance(0.0f);
            controller.getWorld().spawnParticle(Particle.SOUL, held, 3, 0.35, 0.3, 0.35, 0.02);
            if (heldTicks >= GRAB_HOLD_TICKS) {
                releaseCapturedPlayer();
                finishSkill();
                return;
            }
        }
        if (capturedPlayerId == null && elapsed > GRAB_CHASE_TICKS) finishSkill();
    }

    private Location clawLocation() {
        return controller.getLocation().clone()
                .add(facing.clone().multiply(1.25)).add(0, -2.35, 0);
    }

    private Player capturedPlayer() {
        if (capturedPlayerId == null) return null;
        Player player = Bukkit.getPlayer(capturedPlayerId);
        if (player == null || !isCombatPlayer(player) || player.getWorld() != controller.getWorld()) {
            capturedPlayerId = null;
            return null;
        }
        return player;
    }

    private void releaseCapturedPlayer() {
        Player player = capturedPlayer();
        capturedPlayerId = null;
        if (player == null) return;
        player.setFallDistance(0.0f);
        player.setVelocity(facing.clone().multiply(0.2).setY(-1.1));
        player.sendActionBar(plugin.messages().component("boss.bone-dragon.grab-released"));
    }

    private void tickSkullBarrage(long elapsed, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        if (target != null) face(target.getEyeLocation());
        if (elapsed < 18L) {
            controller.setVelocity(new Vector(0, 0.16, 0));
            controller.getWorld().spawnParticle(Particle.SOUL, controller.getLocation(),
                    8, 2.8, 1.2, 2.8, 0.035);
        } else {
            controller.setVelocity(controller.getVelocity().multiply(0.25));
        }
        int interval = 5;
        if (elapsed >= 18L && (elapsed - 18L) % interval == 0L) {
            int index = (int) ((elapsed - 18L) / interval);
            if (index < settings.skullCount()) spawnTrackingSkull(target, index);
        }
        if (elapsed == 15L) {
            controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_SHOOT, 3.5f, 0.55f);
        }
        if (elapsed >= SKULL_BARRAGE_ANIMATION_TICKS) finishSkill();
    }

    private void spawnTrackingSkull(Player target, int index) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Location spawn = mouthLocation().add(0, 1.8 + index * 0.25, 0);
        ItemDisplay display = controller.getWorld().spawn(spawn, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.WITHER_SKELETON_SKULL));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setViewRange(96.0f);
            entity.setShadowRadius(0.0f);
            entity.setTransformation(new Transformation(
                    new Vector3f(), new AxisAngle4f(), new Vector3f(2.35f), new AxisAngle4f()));
        });
        double angle = index * Math.PI * 2.0 / Math.max(1, settings.skullCount());
        Vector velocity = new Vector(Math.cos(angle) * 0.08, 0.34, Math.sin(angle) * 0.08);
        trackingSkulls.add(new TrackingSkull(display,
                target == null ? null : target.getUniqueId(), velocity,
                (int) Math.min(Integer.MAX_VALUE, settings.skullLifetimeTicks())));
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, spawn, 30, 0.8, 0.8, 0.8, 0.04);
        controller.getWorld().playSound(spawn, Sound.ENTITY_WITHER_SHOOT, 1.8f, 0.75f + index * 0.05f);
    }

    private void tickTrackingSkulls() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Iterator<TrackingSkull> iterator = trackingSkulls.iterator();
        while (iterator.hasNext()) {
            TrackingSkull skull = iterator.next();
            if (!skull.display.isValid() || --skull.remainingTicks <= 0) {
                destroyTrackingSkull(skull, false, null);
                iterator.remove();
                continue;
            }
            Location current = skull.display.getLocation();
            Player target = skull.targetId == null ? null : Bukkit.getPlayer(skull.targetId);
            if (target != null && isCombatPlayer(target) && target.getWorld() == controller.getWorld()) {
                Vector desired = target.getEyeLocation().toVector().subtract(current.toVector());
                if (desired.lengthSquared() > 0.0001) {
                    desired.normalize().multiply(settings.skullSpeed());
                    skull.velocity.multiply(1.0 - settings.skullTurnRate())
                            .add(desired.multiply(settings.skullTurnRate()));
                }
            }
            if (skull.velocity.lengthSquared() > settings.skullSpeed() * settings.skullSpeed()) {
                skull.velocity.normalize().multiply(settings.skullSpeed());
            }
            Location next = current.clone().add(skull.velocity);
            if (next.getBlock().getType().isSolid()) {
                destroyTrackingSkull(skull, false, null);
                iterator.remove();
                continue;
            }
            next.setDirection(skull.velocity);
            skull.display.teleport(next);
            controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, next, 3, 0.24, 0.24, 0.24, 0.012);
            controller.getWorld().spawnParticle(Particle.SMOKE, next, 2, 0.18, 0.18, 0.18, 0.01);

            Player hit = null;
            double hitRadiusSquared = settings.skullHitRadius() * settings.skullHitRadius();
            for (Player player : controller.getWorld().getPlayers()) {
                if (isCombatPlayer(player) && player.getLocation().add(0, 1, 0).distanceSquared(next) <= hitRadiusSquared) {
                    hit = player;
                    break;
                }
            }
            if (hit != null) {
                damageWithSkill(hit, scaledDamage(settings.skullDamage()));
                destroyTrackingSkull(skull, true, hit);
                iterator.remove();
            }
        }
    }

    private void destroyTrackingSkull(TrackingSkull skull, boolean summonGuards, Player target) {
        Location location = skull.display.getLocation();
        if (skull.display.isValid()) skull.display.remove();
        controller.getWorld().spawnParticle(Particle.SOUL, location, summonGuards ? 45 : 18,
                1.0, 1.0, 1.0, 0.06);
        controller.getWorld().playSound(location,
                summonGuards ? Sound.ENTITY_GENERIC_EXPLODE : Sound.BLOCK_SOUL_SAND_BREAK,
                summonGuards ? 2.0f : 1.2f, summonGuards ? 0.75f : 1.4f);
        if (summonGuards) summonSkullGuards(location, target);
    }

    private void summonSkullGuards(Location center, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        for (int i = 0; i < settings.skullGuardCount(); i++) {
            double angle = i * Math.PI * 2.0 / Math.max(1, settings.skullGuardCount());
            Location spawn = center.clone().add(Math.cos(angle) * 1.8, 0, Math.sin(angle) * 1.8);
            spawn.setY(groundY(spawn));
            Skeleton guard = spawnSkeletonGuard(spawn, "骷髅侍卫",
                    settings.skullGuardHealth(), settings.skullGuardDamage(), target);
            if (guard == null) break;
            guard.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_SWORD));
            guard.getEquipment().setItemInMainHandDropChance(0.0f);
        }
    }

    private void tickBonePrison(long elapsed, Player target) {
        controller.setVelocity(new Vector());
        if (target != null) face(target.getEyeLocation());
        if (elapsed < 16L && elapsed % 3L == 0L) {
            for (Player player : prisonTargets()) {
                player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation(), 18,
                        2.5, 0.15, 2.5, 0.04, Material.BONE_BLOCK.createBlockData());
            }
        }
        if (elapsed == 16L) castBonePrison();
        if (elapsed >= BONE_PRISON_ANIMATION_TICKS) finishSkill();
    }

    private List<Player> prisonTargets() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        return controller.getWorld().getPlayers().stream()
                .filter(this::isCombatPlayer)
                .filter(player -> player.getLocation().distanceSquared(controller.getLocation())
                        <= settings.followRange() * settings.followRange())
                .sorted(Comparator.comparingDouble(player ->
                        player.getLocation().distanceSquared(controller.getLocation())))
                .limit(settings.prisonTargetCount())
                .toList();
    }

    private void castBonePrison() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        for (Player player : prisonTargets()) {
            Location center = player.getLocation();
            int radius = Math.max(2, (int) Math.ceil(settings.prisonRadius()));
            int baseY = center.getBlockY();
            for (int x = -radius; x <= radius; x++) {
                for (int z = -radius; z <= radius; z++) {
                    double distance = Math.sqrt(x * x + z * z);
                    if (distance < radius - 0.65 || distance > radius + 0.65) continue;
                    for (int y = 0; y < settings.prisonHeight(); y++) {
                        placePrisonBlock(center.getWorld().getBlockAt(
                                center.getBlockX() + x, baseY + y, center.getBlockZ() + z),
                                age + settings.prisonDurationTicks());
                    }
                }
            }
            damageWithSkill(player, scaledDamage(settings.prisonDamage()));
            player.setVelocity(player.getVelocity().setY(settings.prisonLaunchVelocity()));
            player.getWorld().spawnParticle(Particle.BLOCK, center, 90,
                    radius, 1.2, radius, 0.08, Material.BONE_BLOCK.createBlockData());
            player.getWorld().playSound(center, Sound.BLOCK_BONE_BLOCK_PLACE, 2.6f, 0.55f);
        }
    }

    private void placePrisonBlock(Block block, long expiresAt) {
        if ((!block.getType().isAir() && !block.isPassable()) || isTemporaryPrisonBlock(block)) return;
        BlockData original = block.getBlockData().clone();
        block.setType(Material.BONE_BLOCK, false);
        prisonBlocks.add(new PrisonBlock(block, original, expiresAt));
    }

    private boolean isTemporaryPrisonBlock(Block block) {
        for (PrisonBlock prison : prisonBlocks) {
            if (prison.block.equals(block)) return true;
        }
        return false;
    }

    private void tickPrisonBlocks() {
        Iterator<PrisonBlock> iterator = prisonBlocks.iterator();
        while (iterator.hasNext()) {
            PrisonBlock prison = iterator.next();
            if (age < prison.expiresAt) continue;
            if (prison.block.getType() == Material.BONE_BLOCK) {
                prison.block.setBlockData(prison.original, false);
            }
            iterator.remove();
        }
    }

    private void tickBoneSpikes(long elapsed, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        controller.setVelocity(new Vector());
        if (target != null) face(target.getEyeLocation());

        Location center = controller.getLocation().clone();
        if (elapsed < 10L && elapsed % 2L == 0L) {
            double warningRadius = 2.0 + elapsed * 0.22;
            for (int degrees = 0; degrees < 360; degrees += 18) {
                double angle = Math.toRadians(degrees);
                Location warning = center.clone().add(
                        Math.cos(angle) * warningRadius, -2.1, Math.sin(angle) * warningRadius);
                controller.getWorld().spawnParticle(Particle.BLOCK, warning, 2,
                        0.12, 0.05, 0.12, 0.02, Material.BONE_BLOCK.createBlockData());
            }
        }
        if (elapsed == 6L) {
            controller.getWorld().playSound(center, Sound.ENTITY_ENDER_DRAGON_GROWL, 3.0f, 0.52f);
        }

        int waveInterval = 5;
        if (elapsed >= 10L && (elapsed - 10L) % waveInterval == 0L) {
            int ring = (int) ((elapsed - 10L) / waveInterval);
            if (ring < settings.boneSpikesRings()) spawnBoneSpikeRing(ring);
        }
        if (elapsed >= 60L) finishSkill();
    }

    private void spawnBoneSpikeRing(int ring) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        int rings = settings.boneSpikesRings();
        int spikeCount = settings.boneSpikesPerRing();
        double radius = settings.boneSpikesRadius() * (ring + 1.0) / rings;
        double offset = (ring & 1) == 0 ? 0.0 : Math.PI / spikeCount;
        Location center = controller.getLocation().clone();

        for (int index = 0; index < spikeCount; index++) {
            double angle = Math.PI * 2.0 * index / spikeCount + offset;
            Location surface = center.clone().add(Math.cos(angle) * radius, 0.0, Math.sin(angle) * radius);
            surface.setY(groundY(surface));
            float scale = 2.15f + (float) radius * 0.035f;
            ItemDisplay display = controller.getWorld().spawn(
                    surface.clone().subtract(0.0, 1.8, 0.0), ItemDisplay.class, entity -> {
                        entity.setItemStack(new ItemStack(Material.BONE));
                        entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                        entity.setBillboard(Display.Billboard.FIXED);
                        entity.setGravity(false);
                        entity.setPersistent(false);
                        entity.setInvulnerable(true);
                        entity.setViewRange(80.0f);
                        entity.setShadowRadius(0.0f);
                        entity.setTransformation(new Transformation(
                                new Vector3f(),
                                new AxisAngle4f((float) Math.toRadians(-58.0), 1.0f, 0.0f, 0.0f),
                                new Vector3f(scale, scale, scale),
                                new AxisAngle4f((float) -angle, 0.0f, 1.0f, 0.0f)));
                    });
            boneSpikes.add(new BoneSpike(display, surface.getY(), age));
        }

        damageBoneSpikeRing(center, radius);
        controller.getWorld().spawnParticle(Particle.BLOCK, center.clone().add(0.0, -2.0, 0.0),
                45, radius * 0.65, 0.25, radius * 0.65, 0.06, Material.BONE_BLOCK.createBlockData());
        controller.getWorld().playSound(center, Sound.BLOCK_BONE_BLOCK_BREAK, 2.8f,
                Math.min(1.45f, 0.62f + ring * 0.11f));
    }

    private void damageBoneSpikeRing(Location center, double radius) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        for (Player player : controller.getWorld().getPlayers()) {
            if (!isCombatPlayer(player) || boneSpikeHitPlayers.contains(player.getUniqueId())) continue;
            Location playerLocation = player.getLocation();
            double horizontal = Math.sqrt(horizontalDistanceSquared(center, playerLocation));
            if (Math.abs(horizontal - radius) > settings.boneSpikesHitRadius()) continue;
            if (Math.abs(playerLocation.getY() - groundY(playerLocation)) > 5.0) continue;
            boneSpikeHitPlayers.add(player.getUniqueId());
            double damage = scaledDamage(settings.boneSpikesDamage());
            damageWithSkill(player, damage);
            Vector knockback = playerLocation.toVector().subtract(center.toVector()).setY(0.0);
            if (knockback.lengthSquared() < 0.01) knockback = facing.clone();
            player.setVelocity(player.getVelocity().add(knockback.normalize().multiply(0.38)).setY(0.72));
            player.getWorld().spawnParticle(Particle.ITEM, playerLocation.clone().add(0.0, 1.0, 0.0),
                    22, 0.7, 0.8, 0.7, 0.08, new ItemStack(Material.BONE));
        }
    }

    private void tickBoneSpikeDisplays() {
        Iterator<BoneSpike> iterator = boneSpikes.iterator();
        while (iterator.hasNext()) {
            BoneSpike spike = iterator.next();
            if (!spike.display.isValid()) {
                iterator.remove();
                continue;
            }
            long elapsed = age - spike.spawnedAt;
            if (elapsed >= 32L) {
                spike.display.remove();
                iterator.remove();
                continue;
            }
            double height;
            if (elapsed <= 6L) {
                height = -1.8 + smoothStep(elapsed / 6.0) * 2.15;
            } else if (elapsed < 23L) {
                height = 0.35;
            } else {
                height = 0.35 - smoothStep((elapsed - 23L) / 9.0) * 2.15;
            }
            Location next = spike.display.getLocation();
            next.setY(spike.surfaceY + height);
            spike.display.teleport(next);
            if (elapsed <= 8L && elapsed % 2L == 0L) {
                controller.getWorld().spawnParticle(Particle.SOUL, next, 2,
                        0.18, 0.25, 0.18, 0.015);
            }
        }
    }

    private void tickGroundCharge(long elapsed, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        if (elapsed < 15L) {
            controller.setVelocity(new Vector());
            if (target != null) {
                face(target.getEyeLocation());
                chargeDirection = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
                if (chargeDirection.lengthSquared() > 0.0001) chargeDirection.normalize();
            }
            controller.getWorld().spawnParticle(Particle.CLOUD, controller.getLocation().add(0, -2, 0),
                    10, 2.5, 0.25, 2.5, 0.03);
        } else {
            Vector velocity = chargeDirection.clone().multiply(settings.chargeSpeed());
            Location next = controller.getLocation().add(velocity);
            next.setY(groundY(next) + groundModelYOffset());
            if (next.clone().add(chargeDirection.clone().multiply(2.0)).getBlock().getType().isSolid()) {
                controller.getWorld().spawnParticle(Particle.EXPLOSION, controller.getLocation(), 3,
                        1.5, 1.0, 1.5, 0.04);
                finishSkill();
                return;
            }
            controller.setVelocity(velocity.setY(next.getY() - controller.getY()));
            controller.getWorld().spawnParticle(Particle.BLOCK, controller.getLocation().add(0, -2, 0),
                    18, 2.0, 0.2, 2.0, 0.08,
                    controller.getLocation().subtract(0, 2.4, 0).getBlock().getBlockData());
            double radiusSquared = settings.chargeHitRadius() * settings.chargeHitRadius();
            for (Player player : controller.getWorld().getPlayers()) {
                if (!isCombatPlayer(player) || chargeHitPlayers.contains(player.getUniqueId())
                        || player.getLocation().distanceSquared(controller.getLocation()) > radiusSquared) continue;
                chargeHitPlayers.add(player.getUniqueId());
                damageWithSkill(player, scaledDamage(settings.chargeDamage()));
                Vector push = player.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
                if (push.lengthSquared() < 0.0001) push = chargeDirection.clone();
                player.setVelocity(push.normalize().multiply(settings.chargeKnockback()).setY(0.65));
                controller.getWorld().playSound(player.getLocation(), Sound.ENTITY_IRON_GOLEM_ATTACK, 2.0f, 0.65f);
            }
        }
        if (elapsed >= settings.chargeDurationTicks()) finishSkill();
    }

    private void tickWingBreak(long elapsed) {
        double progress = smoothStep(elapsed / (double) WING_BREAK_ANIMATION_TICKS);
        Location location = controller.getLocation();
        double ground = groundY(location) + groundModelYOffset();
        location.setY(wingBreakStartY + (ground - wingBreakStartY) * progress);
        location.setYaw(location.getYaw() + (float) Math.sin(elapsed * 0.65) * 9.0f);
        controller.teleport(location);
        controller.setVelocity(new Vector());
        if (elapsed % 8L == 0L) {
            controller.getWorld().spawnParticle(Particle.BLOCK, location, 22,
                    4.0, 2.0, 4.0, 0.05, Material.BONE_BLOCK.createBlockData());
            controller.getWorld().playSound(location, Sound.ENTITY_SKELETON_HURT, 2.2f,
                    0.65f + (float) elapsed / 180.0f);
        }
        if (elapsed >= WING_BREAK_ANIMATION_TICKS) {
            controller.setInvulnerable(true);
            skill = Skill.NONE;
            groundWalking = false;
            skillStartedAt = 0L;
            if (model != null) {
                model.setWingsVisible(false);
                model.playLoop("ground_idle");
            }
            updateBossBar();
            announce(plugin.messages().component("boss.bone-dragon.phase-two"));
            beginDeathSummon();
        }
    }

    private void beginDeathSummon() {
        if (dying || !controller.isValid()) return;
        int phaseBit = 1 << phase.ordinal();
        if ((deathSummonMask & phaseBit) != 0) return;
        deathSummonMask |= phaseBit;
        controller.getPersistentDataContainer().set(
                plugin.boneDragonDeathSummonMaskKey(), PersistentDataType.INTEGER, deathSummonMask);
        int summoned = summonDeathRulers(targetPlayer());
        beginSkill(Skill.DEATH_SUMMON, "death_summon");
        announce(plugin.messages().component("boss.bone-dragon.death-summon", "count", summoned));
    }

    private void tickDeathSummon(long elapsed, Player target) {
        controller.setVelocity(new Vector());
        if (target != null) face(target.getEyeLocation());
        if (elapsed % 4L == 0L) {
            Location center = controller.getLocation().clone().add(0, -1.2, 0);
            controller.getWorld().spawnParticle(Particle.SOUL, center, 18, 4.0, 1.2, 4.0, 0.035);
            controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center, 10, 3.0, 0.8, 3.0, 0.08);
        }
        if (elapsed % 10L == 0L) {
            controller.getWorld().playSound(controller.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2.5f, 0.62f);
        }
        if (elapsed >= SUMMON_ANIMATION_TICKS) beginSkeletonAbsorption();
    }

    private void beginSkeletonAbsorption() {
        cancelSkeletonAbsorption();
        cleanMinions();
        BoneDragonSettings settings = plugin.boneDragonSettings();
        int alive = (int) minions.stream()
                .map(Bukkit::getEntity)
                .filter(entity -> entity instanceof Skeleton && entity.isValid() && !entity.isDead())
                .count();
        int maximum = settings.skeletonAbsorbMaxCount();
        if (damageBonusRatio() + 1.0E-9 >= settings.skeletonAbsorbMaxDamageIncreaseRatio()) {
            maximum = Math.min(maximum, settings.skeletonAbsorbPostCapMaxCount());
        }
        maximum = Math.min(maximum, Math.max(0, alive - settings.skeletonAbsorbMinRemaining()));
        if (maximum <= 0) {
            finishSkill();
            return;
        }
        minions.stream()
                .map(Bukkit::getEntity)
                .filter(entity -> entity instanceof Skeleton && entity.isValid() && !entity.isDead())
                .sorted(Comparator.comparingDouble(entity ->
                        entity.getLocation().distanceSquared(controller.getLocation())))
                .limit(maximum)
                .map(Entity::getUniqueId)
                .forEach(absorbTargets::add);
        if (absorbTargets.isEmpty()) {
            finishSkill();
            return;
        }
        absorbedThisCast = 0;
        for (UUID id : absorbTargets) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Skeleton skeleton)) continue;
            skeleton.setTarget(null);
            skeleton.setAI(false);
            skeleton.setGravity(false);
            skeleton.setInvulnerable(true);
            skeleton.setVelocity(new Vector());
        }
        beginSkill(Skill.SKELETON_ABSORB, "skeleton_absorb");
        announce(plugin.messages().component("boss.bone-dragon.absorb-start"));
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_AMBIENT, 3.0f, 0.48f);
    }

    private void tickSkeletonAbsorb(long elapsed, Player target) {
        controller.setVelocity(new Vector());
        if (target != null) face(target.getEyeLocation());
        Location destination = controller.getLocation().clone().add(0.0, phase == Phase.P1 ? 0.5 : 1.8, 0.0);
        for (int index = 0; index < absorbTargets.size();) {
            UUID id = absorbTargets.get(index);
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Skeleton skeleton) || !entity.isValid() || entity.isDead()) {
                absorbTargets.remove(index);
                minions.remove(id);
                continue;
            }
            long pullStart = 6L + index * 2L;
            if (elapsed >= pullStart) {
                Location current = skeleton.getLocation();
                Vector delta = destination.toVector().subtract(current.toVector());
                double distance = delta.length();
                if (distance > 0.05) {
                    double speed = Math.min(1.25, 0.20 + distance * 0.10);
                    skeleton.teleport(current.add(delta.multiply(speed / distance)));
                }
                renderAbsorptionTrail(skeleton.getLocation().clone().add(0.0, 1.0, 0.0), destination);
                if (distance <= 1.15 || elapsed >= 30L + index * 2L) {
                    consumeSkeleton(id, skeleton, destination);
                    absorbTargets.remove(index);
                    continue;
                }
            }
            index++;
        }
        if (elapsed % 4L == 0L) {
            controller.getWorld().spawnParticle(Particle.SOUL, destination, 12, 2.4, 1.5, 2.4, 0.04);
            controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, destination, 8, 2.0, 1.0, 2.0, 0.08);
        }
        if (elapsed < SKELETON_ABSORB_ANIMATION_TICKS && !absorbTargets.isEmpty()) return;
        for (UUID id : new ArrayList<>(absorbTargets)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Skeleton skeleton && entity.isValid() && !entity.isDead()) {
                consumeSkeleton(id, skeleton, destination);
            }
        }
        absorbTargets.clear();
        if (absorbedThisCast > 0) {
            double bonus = damageBonusRatio() * 100.0;
            announce(plugin.messages().component("boss.bone-dragon.absorb-complete",
                    "count", absorbedThisCast,
                    "bonus", String.format(Locale.ROOT, "%.1f", bonus)));
            controller.getWorld().playSound(destination, Sound.ENTITY_ENDER_DRAGON_GROWL, 3.5f, 0.62f);
        }
        absorbedThisCast = 0;
        finishSkill();
    }

    private void consumeSkeleton(UUID id, Skeleton skeleton, Location destination) {
        Location source = skeleton.getLocation().clone().add(0.0, 1.0, 0.0);
        renderAbsorptionTrail(source, destination);
        controller.getWorld().spawnParticle(Particle.ITEM, source, 18,
                0.35, 0.6, 0.35, 0.08, new ItemStack(Material.BONE));
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, destination, 18,
                1.1, 0.8, 1.1, 0.04);
        controller.getWorld().playSound(source, Sound.ENTITY_SKELETON_DEATH, 1.3f, 0.72f);
        skeleton.remove();
        minions.remove(id);
        int previousAbsorbed = absorbedSkeletons;
        if (absorbedSkeletons < Integer.MAX_VALUE) absorbedSkeletons++;
        absorbedThisCast++;
        controller.getPersistentDataContainer().set(
                plugin.boneDragonAbsorbedKey(), PersistentDataType.INTEGER, absorbedSkeletons);
        setBase(Attribute.ATTACK_DAMAGE, Math.min(2048.0,
                scaledDamage(plugin.boneDragonSettings().attackDamage())));
        if (absorbedSkeletons / ABSORPTION_CURSE_INTERVAL
                > previousAbsorbed / ABSORPTION_CURSE_INTERVAL) {
            releaseAbsorptionCurse();
        }
    }

    private void releaseAbsorptionCurse() {
        announce(plugin.messages().component("boss.bone-dragon.absorb-curse",
                "count", absorbedSkeletons));
        for (Player player : controller.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    ABSORPTION_CURSE_DURATION_TICKS,
                    0,
                    false,
                    false,
                    true
            ));
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS,
                    ABSORPTION_CURSE_DURATION_TICKS,
                    0,
                    false,
                    false,
                    true
            ));
            player.getWorld().spawnParticle(Particle.SQUID_INK,
                    player.getEyeLocation(), 24, 0.6, 0.8, 0.6, 0.04);
            player.getWorld().spawnParticle(Particle.SOUL,
                    player.getLocation().clone().add(0.0, 1.0, 0.0), 18, 0.8, 1.0, 0.8, 0.03);
        }
        controller.getWorld().playSound(controller.getLocation(),
                Sound.ENTITY_WITHER_AMBIENT, 4.0f, 0.38f);
    }

    private void renderAbsorptionTrail(Location source, Location destination) {
        Vector delta = destination.toVector().subtract(source.toVector());
        for (int step = 1; step <= 7; step++) {
            Location point = source.clone().add(delta.clone().multiply(step / 8.0));
            controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1,
                    0.025, 0.025, 0.025, 0.0);
        }
    }

    private void cancelSkeletonAbsorption() {
        for (UUID id : absorbTargets) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Skeleton skeleton) || !entity.isValid() || entity.isDead()) continue;
            skeleton.setAI(true);
            skeleton.setGravity(true);
            skeleton.setInvulnerable(false);
        }
        absorbTargets.clear();
        absorbedThisCast = 0;
    }

    private int summonDeathRulers(Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        int count = ThreadLocalRandom.current().nextInt(
                settings.deathSummonMinCount(), settings.deathSummonMaxCount() + 1);
        int spawned = 0;
        for (int index = 0; index < count; index++) {
            double angle = Math.PI * 2.0 * index / Math.max(1, count) + ThreadLocalRandom.current().nextDouble(0.4);
            Location location = controller.getLocation().clone().add(
                    Math.cos(angle) * 5.5, 0.0, Math.sin(angle) * 5.5);
            location.setY(groundY(location));
            spawnDeathRuler(location, target);
            spawned++;
        }
        return spawned;
    }

    private void spawnDeathRuler(Location location, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Skeleton ruler = controller.getWorld().spawn(location, Skeleton.class, entity -> {
            entity.customName(plugin.messages().component("entity.death-ruler"));
            entity.setCustomNameVisible(true);
            entity.setRemoveWhenFarAway(false);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(plugin.bossTypeKey(), PersistentDataType.STRING,
                    BoneDragonManager.MINION_TYPE_ID);
        });
        setMinionBase(ruler, Attribute.MAX_HEALTH, Math.min(1024.0, settings.deathRulerHealth()));
        setMinionBase(ruler, Attribute.ATTACK_DAMAGE, 0.0);
        setMinionBase(ruler, Attribute.MOVEMENT_SPEED, 0.27);
        setMinionBase(ruler, Attribute.FOLLOW_RANGE, 64.0);
        ruler.setHealth(Math.min(1024.0, settings.deathRulerHealth()));
        enforceDeathRulerPacifism(ruler);
        UUID id = ruler.getUniqueId();
        deathRulers.add(id);
        deathRulerNextHealAt.put(id, age + settings.deathRulerHealIntervalTicks());
        applyMinionResistance(ruler);
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                location.clone().add(0, 1.0, 0), 45, 0.9, 1.2, 0.9, 0.045);
    }

    private void tickDeathRulerHealing() {
        cleanDeathRulers();
        if (deathRulers.isEmpty()) return;
        BoneDragonSettings settings = plugin.boneDragonSettings();
        List<Entity> healers = new ArrayList<>();
        boolean healingEnabled = !entranceActive && skill != Skill.WING_BREAK;
        for (UUID id : deathRulers) {
            Entity healer = Bukkit.getEntity(id);
            if (healer instanceof Skeleton ruler && healer.isValid() && !healer.isDead()) {
                enforceDeathRulerPacifism(ruler);
                if (age % 2L == 0L) renderDeathRulerLink(ruler, false);
            }
            if (!healingEnabled) continue;
            long nextHeal = deathRulerNextHealAt.getOrDefault(id, age + settings.deathRulerHealIntervalTicks());
            if (age < nextHeal) continue;
            deathRulerNextHealAt.put(id, age + settings.deathRulerHealIntervalTicks());
            if (healer != null && healer.isValid() && !healer.isDead()) healers.add(healer);
        }
        if (!healingEnabled || healers.isEmpty()) return;
        double missing = Math.max(0.0, maxHealth() - health());
        double heal = Math.min(missing,
                missing * settings.deathRulerMissingHealthHealRatio() * healers.size());
        if (heal <= 0.0) return;
        setCurrentPhaseHealth(Math.min(maxHealth(), health() + heal));
        for (Entity healer : healers) renderDeathRulerHealing(healer);
        controller.getWorld().spawnParticle(Particle.HEART,
                controller.getLocation().clone().add(0, 2.0, 0), 10, 2.2, 1.4, 2.2, 0.03);
        controller.getWorld().playSound(controller.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 2.0f, 0.72f);
        updateBossBar();
    }

    private void enforceDeathRulerPacifism(Skeleton ruler) {
        ruler.setAI(false);
        ruler.setTarget(null);
        ruler.setCanPickupItems(false);
        ruler.getEquipment().clear();
        setMinionBase(ruler, Attribute.ATTACK_DAMAGE, 0.0);
    }

    private void renderDeathRulerHealing(Entity healer) {
        renderDeathRulerLink(healer, true);
        ParticleCompat.spawnDust(controller.getWorld(), healer.getLocation().clone().add(0, 1.25, 0),
                16, 0.45, 0.75, 0.45, 0.01, org.bukkit.Color.fromRGB(255, 25, 18), 1.45f);
    }

    private void renderDeathRulerLink(Entity ruler, boolean healingPulse) {
        Location from = ruler.getLocation().clone().add(0, 1.25, 0);
        Location to = controller.getLocation().clone().add(0, phase == Phase.P1 ? 1.4 : 2.0, 0);
        Vector delta = to.toVector().subtract(from.toVector());
        double distance = delta.length();
        if (distance < 0.05) return;

        int maximumPoints = healingPulse ? 32 : 20;
        double spacing = healingPulse ? 0.5 : 0.9;
        int points = Math.max(5, Math.min(maximumPoints, (int) Math.ceil(distance / spacing)));
        Vector sideways = new Vector(-delta.getZ(), 0.0, delta.getX());
        if (sideways.lengthSquared() > 0.0001) sideways.normalize();
        org.bukkit.Color color = healingPulse
                ? org.bukkit.Color.fromRGB(255, 15, 8)
                : org.bukkit.Color.fromRGB(210, 0, 12);
        float size = healingPulse ? 1.55f : 1.05f;

        for (int step = 0; step <= points; step++) {
            double progress = step / (double) points;
            Location point = from.clone().add(delta.clone().multiply(progress));
            double wave = Math.sin(age * 0.42 + progress * Math.PI * 4.0)
                    * (healingPulse ? 0.16 : 0.09) * Math.sin(progress * Math.PI);
            point.add(sideways.clone().multiply(wave));
            ParticleCompat.spawnDust(controller.getWorld(), point, 1,
                    0.015, 0.015, 0.015, 0.0, color, size);
        }
    }

    private void tickFireBreath(long elapsed, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        if (target != null) {
            if (phase == Phase.P1) tickNormalFlight(target, 0.42);
            else tickGroundMovement(target, 0.45);
            face(target.getEyeLocation());
            updateBreathAim(target);
        } else {
            movementVelocity.multiply(0.45);
            controller.setVelocity(new Vector());
        }
        if (elapsed % 2L == 0L) renderBreathCone();
        if (elapsed % settings.breathDamageIntervalTicks() == 0L) damageBreathCone();
        if (elapsed % 10L == 0L) {
            controller.getWorld().playSound(mouthLocation(), Sound.ITEM_FIRECHARGE_USE, 2.2f, 0.55f);
        }
        if (elapsed >= settings.breathDurationTicks()) finishSkill();
    }

    private Location mouthLocation() {
        return controller.getLocation().clone().add(facing.clone().multiply(3.0)).add(0, 0.2, 0);
    }

    private void renderBreathCone() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Location mouth = mouthLocation();
        Vector direction = normalizedBreathAim();
        Vector side = direction.clone().crossProduct(new Vector(0, 1, 0));
        if (side.lengthSquared() < 0.0001) side = new Vector(1, 0, 0);
        else side.normalize();
        Vector vertical = side.clone().crossProduct(direction).normalize();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (double distance = 0.5; distance <= settings.breathRange(); distance += 0.85) {
            double spread = Math.tan(Math.toRadians(settings.breathConeDegrees())) * distance * 0.35;
            Location point = mouth.clone().add(direction.clone().multiply(distance))
                    .add(side.clone().multiply(random.nextDouble(-spread, spread)))
                    .add(vertical.clone().multiply(random.nextDouble(-spread * 0.45, spread * 0.45)));
            controller.getWorld().spawnParticle(Particle.FLAME, point, 2, 0.15, 0.12, 0.15, 0.025);
            if (random.nextInt(4) == 0) controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, point, 1, 0.08, 0.08, 0.08, 0.01);
            if (random.nextInt(5) == 0) controller.getWorld().spawnParticle(Particle.SMOKE, point, 1, 0.1, 0.1, 0.1, 0.015);
        }
    }

    private void damageBreathCone() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        Location mouth = mouthLocation();
        Vector direction = normalizedBreathAim();
        double minimumDot = Math.cos(Math.toRadians(settings.breathConeDegrees()));
        for (Player player : controller.getWorld().getPlayers()) {
            if (!isCombatPlayer(player)) continue;
            Vector toward = player.getEyeLocation().toVector().subtract(mouth.toVector());
            double distance = toward.length();
            if (distance < 0.01 || distance > settings.breathRange()
                    || toward.normalize().dot(direction) < minimumDot) continue;
            damageWithSkill(player, scaledDamage(settings.breathDamage()));
            player.setFireTicks(Math.max(player.getFireTicks(), settings.breathFireTicks()));
        }
    }

    private void updateBreathAim(Player target) {
        Vector towardTarget = target.getEyeLocation().toVector().subtract(mouthLocation().toVector());
        if (towardTarget.lengthSquared() >= 0.0001) breathAim = towardTarget.normalize();
    }

    private Vector normalizedBreathAim() {
        if (breathAim.lengthSquared() < 0.0001) return facing.clone().normalize();
        return breathAim.clone().normalize();
    }

    private void tickSummon(long elapsed, Player target) {
        if (target != null) {
            if (phase == Phase.P1) tickNormalFlight(target, 0.48);
            else tickGroundMovement(target, 0.40);
            face(target.getEyeLocation());
        } else {
            movementVelocity.multiply(0.45);
            controller.setVelocity(new Vector());
        }
        if (elapsed % 4L == 0L) {
            Location center = controller.getLocation().add(0, -2, 0);
            controller.getWorld().spawnParticle(Particle.SOUL, center, 12, 3.5, 1.0, 3.5, 0.025);
        }
        if (elapsed == 20L) summonSkeletons(target);
        if (elapsed >= SUMMON_ANIMATION_TICKS) beginSkeletonAbsorption();
    }

    private void summonSkeletons(Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        for (int i = 0; i < settings.summonCount(); i++) {
            double angle = Math.PI * 2.0 * i / Math.max(1, settings.summonCount());
            Location location = controller.getLocation().clone().add(Math.cos(angle) * 6.0, 0, Math.sin(angle) * 6.0);
            location.setY(groundY(location));
            Skeleton guard = spawnSkeletonGuard(
                    location, "骨龙近卫", settings.summonHealth(), settings.summonDamage(), target);
            if (guard == null) break;
            controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, location.clone().add(0, 1, 0), 35, 0.8, 1.1, 0.8, 0.04);
        }
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_SPAWN, 2.5f, 1.35f);
    }

    private Skeleton spawnSkeletonGuard(Location location, String name, double health, double damage, Player target) {
        cleanMinions();
        if (minions.size() >= plugin.boneDragonSettings().maxAliveMinions()) return null;
        Skeleton skeleton = controller.getWorld().spawn(location, Skeleton.class, entity -> {
            entity.customName(null);
            entity.setCustomNameVisible(false);
            entity.setRemoveWhenFarAway(true);
            entity.setPersistent(false);
            entity.getPersistentDataContainer().set(plugin.bossTypeKey(), PersistentDataType.STRING,
                    BoneDragonManager.MINION_TYPE_ID);
        });
        setMinionBase(skeleton, Attribute.MAX_HEALTH, Math.min(1024.0, health));
        setMinionBase(skeleton, Attribute.ATTACK_DAMAGE, damage);
        setMinionBase(skeleton, Attribute.MOVEMENT_SPEED, 0.29);
        skeleton.setHealth(Math.min(1024.0, health));
        skeleton.setTarget(target);
        minions.add(skeleton.getUniqueId());
        applyMinionResistance(skeleton);
        return skeleton;
    }

    private void setMinionBase(Skeleton skeleton, Attribute attribute, double value) {
        AttributeInstance instance = skeleton.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private void tickMeteorRain(long elapsed, Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        if (target != null) {
            if (phase == Phase.P1) tickNormalFlight(target, 0.72);
            else tickGroundMovement(target, 0.48);
            face(target.getEyeLocation());
        } else {
            movementVelocity.multiply(0.45);
            controller.setVelocity(new Vector());
        }
        if (elapsed % 3L == 0L) {
            controller.getWorld().spawnParticle(Particle.ENCHANT, controller.getLocation(), 18, 4.0, 2.0, 4.0, 0.2);
            controller.getWorld().spawnParticle(Particle.SOUL, controller.getLocation().add(0, 2, 0), 8, 3.0, 1.0, 3.0, 0.03);
        }
        if (elapsed % settings.meteorSpawnIntervalTicks() == 0L) spawnMeteor(target);
        if (elapsed >= settings.meteorDurationTicks()) finishSkill();
    }

    private void spawnMeteor(Player target) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        ThreadLocalRandom random = ThreadLocalRandom.current();
        Location center = target == null ? controller.getLocation() : target.getLocation();
        double angle = random.nextDouble(Math.PI * 2.0);
        double radius = Math.sqrt(random.nextDouble()) * settings.meteorTargetRadius();
        Location impact = center.clone().add(Math.cos(angle) * radius, 0, Math.sin(angle) * radius);
        impact.setY(groundY(impact) + 0.05);
        Location spawn = impact.clone().add(0, settings.meteorFallHeight(), 0);
        ItemDisplay display = controller.getWorld().spawn(spawn, ItemDisplay.class, entity -> {
            entity.setItemStack(new ItemStack(Material.BONE));
            entity.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            entity.setBillboard(Display.Billboard.FIXED);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setViewRange(96.0f);
            entity.setShadowRadius(0.0f);
            entity.setRotation(random.nextFloat() * 360.0f, 65.0f);
        });
        meteors.add(new Meteor(display, impact, settings.meteorFallTicks()));
        drawMeteorWarning(impact, settings.meteorImpactRadius());
        controller.getWorld().playSound(spawn, Sound.ENTITY_BREEZE_WIND_BURST, 1.2f, 0.6f);
    }

    private void tickMeteors() {
        Iterator<Meteor> iterator = meteors.iterator();
        while (iterator.hasNext()) {
            Meteor meteor = iterator.next();
            if (!meteor.display.isValid()) {
                iterator.remove();
                continue;
            }
            meteor.remainingTicks--;
            if (meteor.remainingTicks % 2 == 0) {
                drawMeteorWarning(meteor.impact, plugin.boneDragonSettings().meteorImpactRadius());
            }
            Location current = meteor.display.getLocation();
            if (meteor.remainingTicks <= 0 || current.getY() <= meteor.impact.getY()) {
                impactMeteor(meteor);
                iterator.remove();
                continue;
            }
            double nextY = current.getY() + (meteor.impact.getY() - current.getY()) / meteor.remainingTicks;
            current.setY(nextY);
            current.setYaw(current.getYaw() + 28.0f);
            meteor.display.teleport(current);
            controller.getWorld().spawnParticle(Particle.FLAME, current, 4, 0.15, 0.45, 0.15, 0.02);
            controller.getWorld().spawnParticle(Particle.SMOKE, current, 3, 0.2, 0.5, 0.2, 0.01);
            controller.getWorld().spawnParticle(Particle.SOUL, current, 1, 0.1, 0.25, 0.1, 0.01);
        }
    }

    private void drawMeteorWarning(Location impact, double radius) {
        int points = Math.max(28, (int) Math.ceil(radius * 12.0));
        World world = impact.getWorld();
        if (world == null) return;
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0 * index / points;
            Location point = impact.clone().add(
                    Math.cos(angle) * radius,
                    0.12,
                    Math.sin(angle) * radius);
            ParticleCompat.spawnDust(world, point, 1,
                    0.0, 0.0, 0.0, 0.0, org.bukkit.Color.WHITE, 1.15f);
        }
    }

    private void impactMeteor(Meteor meteor) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        meteor.display.remove();
        World world = controller.getWorld();
        world.spawnParticle(Particle.EXPLOSION_EMITTER, meteor.impact, 1);
        world.spawnParticle(Particle.SOUL_FIRE_FLAME, meteor.impact, 45, 1.5, 0.7, 1.5, 0.05);
        world.playSound(meteor.impact, Sound.ENTITY_GENERIC_EXPLODE, 2.2f, 0.75f);
        double radiusSquared = settings.meteorImpactRadius() * settings.meteorImpactRadius();
        for (Player player : world.getPlayers()) {
            if (!isCombatPlayer(player) || player.getLocation().distanceSquared(meteor.impact) > radiusSquared) continue;
            damageWithSkill(player, scaledDamage(settings.meteorDamage()));
            Vector push = player.getLocation().toVector().subtract(meteor.impact.toVector()).setY(0);
            if (push.lengthSquared() < 0.01) push = new Vector(0.1, 0, 0);
            player.setVelocity(push.normalize().multiply(0.7).setY(0.75));
        }
    }

    public boolean handleIncomingDamage(EntityDamageEvent event) {
        if (dying || pendingPhase != null) {
            event.setCancelled(true);
            return true;
        }
        if (phaseHealthLockUntil > 0L) {
            event.setCancelled(true);
            refillCurrentPhaseHealth();
            return true;
        }
        Player attacker = damagePlayer(event);
        Location headCriticalImpact = consumeHeadCritical(attacker);
        if (phase == Phase.P3 && isProjectileDamage(event)) {
            event.setCancelled(true);
            controller.getWorld().spawnParticle(Particle.SOUL, controller.getLocation().add(0.0, 1.5, 0.0),
                    8, 1.6, 1.0, 1.6, 0.025);
            controller.getWorld().playSound(controller.getLocation(), Sound.ITEM_SHIELD_BLOCK, 0.9f, 0.62f);
            return true;
        }
        if (entranceActive) {
            event.setCancelled(true);
            return true;
        }
        if (skill == Skill.WING_BREAK) {
            event.setCancelled(true);
            return true;
        }
        if (attacker != null) {
            event.setDamage(plugin.slayer().outgoingDamage(attacker, event.getDamage(), executeWindow()));
        }
        if (attacker != null && attacker.getWorld() == controller.getWorld()) {
            double actualDamage = Math.min(health(), Math.max(0.0, event.getFinalDamage()));
            if (actualDamage > 0.0) {
                damageThreat.merge(attacker.getUniqueId(), actualDamage, Double::sum);
                acquireTarget();
                playHitFeedback(actualDamage);
                if (headCriticalImpact != null) playHeadCriticalFeedback(attacker, headCriticalImpact);
            }
        }
        if (consumePhaseBreakingHit(event, Math.max(0.0, event.getFinalDamage()))) return true;
        if (!entranceActive && skill != Skill.STUN && skill != Skill.WING_BREAK && attacker != null) {
            BoneDragonSettings settings = plugin.boneDragonSettings();
            if (skill == Skill.GRAB) {
                if (!event.isCancelled() && event.getFinalDamage() > 0.0) startStun();
            } else if (phase == Phase.P1) {
                p1DamageHits++;
                if (p1DamageHits >= settings.p1StunHitCount()) startStun();
            }
        }
        phaseHealthRemaining = Math.max(0.0,
                health() - Math.max(0.0, event.getFinalDamage()));
        updateBossBarHealth(phaseHealthRemaining);
        queueBossBarRefreshAfterDamage();
        return false;
    }

    private void queueBossBarRefreshAfterDamage() {
        if (bossBarRefreshQueued) return;
        bossBarRefreshQueued = true;
        Bukkit.getScheduler().runTask(plugin, () -> {
            bossBarRefreshQueued = false;
            if (!controller.isValid() || controller.isDead() || dying) return;
            synchronizeControllerHealth();
            updateBossBar();
        });
    }

    private boolean consumePhaseBreakingHit(EntityDamageEvent event, double finalDamage) {
        if (finalDamage < health()) return false;

        // Consume the phase-ending hit completely.  The controller is locked at one
        // health before the next independent phase pool is installed, so overkill can
        // never be applied to that pool by the current event or another hit this tick.
        event.setCancelled(true);
        event.setDamage(0.0);
        controller.setNoDamageTicks(controller.getMaximumNoDamageTicks());
        controller.setInvulnerable(true);
        setCurrentPhaseHealth(Math.min(1.0, maxHealth()));

        if (phase == Phase.P1) queuePhaseTransition(Phase.P2);
        else if (phase == Phase.P2) queuePhaseTransition(Phase.P3);
        else startDeathSequence();
        return true;
    }

    private void queuePhaseTransition(Phase next) {
        if (pendingPhase != null || dying || next == null || next == phase) return;
        pendingPhase = next;
        setCurrentPhaseHealth(Math.min(1.0, maxHealth()));
        controller.setNoDamageTicks(Math.max(20, controller.getMaximumNoDamageTicks()));
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pendingPhase != next || dying || !controller.isValid() || controller.isDead()) return;
            pendingPhase = null;
            if (next == Phase.P2) beginPhaseTwoTransition();
            else if (next == Phase.P3) enterPhaseThree();
        });
    }

    private float modifyHitBoxDamage(String boneName, UUID causingEntityId, boolean indirect,
                                     Location impact, float damage) {
        if (damage <= 0.0f || boneName == null || causingEntityId == null) return damage;
        String normalized = boneName.toLowerCase(Locale.ROOT);
        if (!normalized.equals("b_head_hitbox")
                && !normalized.equals("head_hitbox")
                && !normalized.endsWith("_head_hitbox")) return damage;
        Player attacker = Bukkit.getPlayer(causingEntityId);
        if (attacker == null || attacker.getWorld() != controller.getWorld() || !isCombatPlayer(attacker)) return damage;

        pendingHeadCriticalAttackerId = causingEntityId;
        pendingHeadCriticalAt = age;
        pendingHeadCriticalImpact = impact == null
                ? controller.getLocation().clone().add(facing.clone().multiply(4.0)).add(0.0, 0.8, 0.0)
                : impact.clone();
        double modified = damage * plugin.boneDragonSettings().headCriticalDamageMultiplier();
        return (float) Math.min(Float.MAX_VALUE, Math.max(0.0, modified));
    }

    private Location consumeHeadCritical(Player attacker) {
        boolean matches = pendingHeadCriticalAt == age
                && attacker != null
                && attacker.getUniqueId().equals(pendingHeadCriticalAttackerId);
        Location impact = matches && pendingHeadCriticalImpact != null ? pendingHeadCriticalImpact.clone() : null;
        pendingHeadCriticalAttackerId = null;
        pendingHeadCriticalAt = -1L;
        pendingHeadCriticalImpact = null;
        return impact;
    }

    private void playHeadCriticalFeedback(Player attacker, Location impact) {
        double bonus = (plugin.boneDragonSettings().headCriticalDamageMultiplier() - 1.0) * 100.0;
        attacker.sendActionBar(plugin.messages().component("boss.bone-dragon.head-critical",
                "bonus", String.format(Locale.ROOT, "%.0f", bonus)));
        controller.getWorld().spawnParticle(Particle.CRIT, impact, 22, 0.65, 0.55, 0.65, 0.18);
        ParticleCompat.spawnDust(controller.getWorld(), impact, 10,
                0.45, 0.4, 0.45, 0.02, org.bukkit.Color.fromRGB(255, 45, 35), 1.35f);
        controller.getWorld().playSound(impact, Sound.ENTITY_PLAYER_ATTACK_CRIT, 1.4f, 0.72f);
        controller.getWorld().playSound(impact, Sound.BLOCK_BONE_BLOCK_BREAK, 1.1f, 1.45f);
    }

    private void playHitFeedback(double damage) {
        if (age < nextHitFeedbackAt) return;
        nextHitFeedbackAt = age + 4L;
        Location impact = controller.getLocation().clone().add(0.0, 1.6, 0.0);
        float volume = (float) Math.min(2.0, 0.8 + damage / 40.0);
        controller.getWorld().playSound(impact, Sound.ENTITY_SKELETON_HURT, volume, 0.52f);
        controller.getWorld().playSound(impact, Sound.BLOCK_BONE_BLOCK_BREAK, volume * 0.65f, 0.72f);
        controller.getWorld().spawnParticle(Particle.BLOCK, impact, 9,
                1.15, 0.9, 1.15, 0.08, Material.BONE_BLOCK.createBlockData());
        controller.getWorld().spawnParticle(Particle.SOUL, impact, 4, 0.75, 0.65, 0.75, 0.025);
    }

    private static boolean isProjectileDamage(EntityDamageEvent event) {
        return event.getCause() == EntityDamageEvent.DamageCause.PROJECTILE
                || event instanceof EntityDamageByEntityEvent byEntity
                && byEntity.getDamager() instanceof Projectile;
    }

    private void damageWithSkill(Player player, double damage) {
        if (damage <= 0.0 || !player.isValid() || player.isDead()) return;
        boolean blocking = player.isBlocking();
        player.damage(plugin.slayer().incomingDamage(player, damage), skillDamageSource);
        if (!blocking) return;
        int cooldown = (int) Math.min(Integer.MAX_VALUE, plugin.boneDragonSettings().shieldDisableTicks());
        player.setCooldown(Material.SHIELD, cooldown);
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, 0.82f);
    }

    private boolean executeWindow() {
        return phase == Phase.P3 && health() <= maxHealth() * 0.2;
    }

    public Map<UUID, Double> damageContributions() {
        return Map.copyOf(damageThreat);
    }

    private Player damagePlayer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        if (byEntity.getDamager() instanceof Player player) return player;
        if (byEntity.getDamager() instanceof Projectile projectile
                && projectile.getShooter() instanceof Player player) return player;
        return null;
    }

    private void startStun() {
        if (dying || skill == Skill.STUN || skill == Skill.WING_BREAK) return;
        cancelSkeletonAbsorption();
        releaseCapturedPlayer();
        skill = Skill.STUN;
        skillStartedAt = age;
        stunStartY = controller.getY();
        stunGroundY = groundY(controller.getLocation()) + groundModelYOffset();
        stunImpactPlayed = false;
        p1DamageHits = 0;
        controller.setVelocity(new Vector());
        if (model != null) model.playOnce("stun", phaseIdleAnimation());
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_ENDER_DRAGON_HURT, 3.5f, 0.65f);
        announce(plugin.messages().component("boss.bone-dragon.stunned"));
    }

    private void tickStun(long elapsed) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        long descentTicks = Math.min(20L, Math.max(8L, settings.stunDurationTicks() / 3L));
        double fall = smoothStep(elapsed / (double) descentTicks);
        Location location = controller.getLocation();
        location.setY(stunStartY + (stunGroundY - stunStartY) * fall);
        controller.teleport(location);
        controller.setVelocity(new Vector());
        if (!stunImpactPlayed && elapsed >= descentTicks) {
            stunImpactPlayed = true;
            controller.getWorld().spawnParticle(Particle.EXPLOSION, location, 4, 2.5, 0.8, 2.5, 0.05);
            controller.getWorld().spawnParticle(Particle.BLOCK, location, 55, 3.0, 0.5, 3.0, 0.08,
                    location.clone().subtract(0, 2.4, 0).getBlock().getBlockData());
            controller.getWorld().playSound(location, Sound.ENTITY_GENERIC_EXPLODE, 3.0f, 0.65f);
        }
        if (elapsed % 10L == 0L) {
            controller.getWorld().spawnParticle(Particle.CRIT, location.clone().add(0, 2.0, 0),
                    12, 1.8, 0.8, 1.8, 0.05);
            controller.getWorld().spawnParticle(Particle.SOUL, location.clone().add(0, 2.5, 0),
                    7, 1.3, 0.6, 1.3, 0.025);
        }
        if (elapsed < settings.stunDurationTicks()) return;

        skill = Skill.NONE;
        skillStartedAt = 0L;
        if (phase == Phase.P1) {
            if (model != null) model.playLoop("idle_fly");
            controller.getWorld().playSound(location, Sound.ENTITY_ENDER_DRAGON_FLAP, 3.0f, 0.85f);
            announce(plugin.messages().component("boss.bone-dragon.recovered-flight"));
        } else {
            if (model != null) model.playLoop("ground_idle");
            announce(plugin.messages().component("boss.bone-dragon.recovered-ground"));
        }
    }

    private void startDeathSequence() {
        if (dying) return;
        cancelSkeletonAbsorption();
        dying = true;
        plugin.arenaManager().onBossDeathStarted(this);
        deathStartedAt = age;
        entranceActive = false;
        releaseCapturedPlayer();
        skill = Skill.NONE;
        controller.setVelocity(new Vector());
        controller.setInvulnerable(true);
        setCurrentPhaseHealth(Math.min(1.0, maxHealth()));
        bossBar.setProgress(0.0);
        bossBar.setTitle(legacyString(plugin.messages().raw("boss.bone-dragon.death-bossbar")));
        clearProjectiles();
        restoreAllPrisonBlocks();
        if (model != null) model.playOnceAndHold("death");
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 4.0f, 0.55f);
        announce(plugin.messages().component("boss.bone-dragon.death-start"));
    }

    private void tickDeathSequence() {
        long elapsed = age - deathStartedAt;
        Location location = controller.getLocation();
        controller.setVelocity(new Vector());
        if (elapsed % 4L == 0L) {
            controller.getWorld().spawnParticle(Particle.ITEM, location, 18,
                    4.0, 3.0, 4.0, 0.08, new ItemStack(Material.BONE));
            controller.getWorld().spawnParticle(Particle.SOUL, location, 12,
                    4.0, 3.0, 4.0, 0.04);
        }
        if (elapsed % 20L == 0L) {
            controller.getWorld().playSound(location, Sound.ENTITY_SKELETON_DEATH, 2.5f,
                    0.6f + (float) elapsed / 240.0f);
        }
        if (elapsed < plugin.boneDragonSettings().deathDurationTicks()) return;

        controller.getWorld().spawnParticle(Particle.SOUL, location, 240, 6.0, 4.0, 6.0, 0.12);
        controller.getWorld().spawnParticle(Particle.ITEM, location, 120,
                5.0, 3.0, 5.0, 0.15, new ItemStack(Material.BONE));
        controller.getWorld().playSound(location, Sound.BLOCK_BONE_BLOCK_BREAK, 4.0f, 0.45f);
        int experience = plugin.boneDragonSettings().experience();
        if (experience > 0) {
            controller.getWorld().spawn(location, ExperienceOrb.class, orb -> orb.setExperience(experience));
        }
        announce(plugin.messages().component("boss.bone-dragon.death-complete"));
        plugin.arenaManager().onBossDefeated(this);
        cleanup();
        if (controller.isValid()) controller.remove();
    }

    private void finishSkill() {
        if (skill == Skill.GRAB) releaseCapturedPlayer();
        skill = Skill.NONE;
        groundWalking = false;
        skillStartedAt = 0L;
        if (model != null) model.playLoop(phaseIdleAnimation());
    }

    private void face(Location target) {
        Vector direction = target.toVector().subtract(controller.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() < 0.0001) return;
        facing = direction.normalize();
        Location rotation = controller.getLocation();
        rotation.setDirection(facing);
        controller.setRotation(rotation.getYaw() + (float) plugin.boneDragonSettings().modelYawOffsetDegrees(), 0.0f);
    }

    private void moveToward(Location target, double speed) {
        Location current = controller.getLocation();
        Vector delta = target.toVector().subtract(current.toVector());
        double length = delta.length();
        if (length < 0.05) {
            movementVelocity.multiply(0.45);
            if (movementVelocity.lengthSquared() > 0.0004) {
                controller.teleport(current.clone().add(movementVelocity));
            }
            controller.setVelocity(new Vector());
            return;
        }
        Vector desired = delta.multiply(Math.min(speed, length) / length);
        movementVelocity.multiply(0.22).add(desired.multiply(0.78));
        Location next = current.clone().add(movementVelocity);
        if (!controller.teleport(next)) controller.setVelocity(movementVelocity);
        else controller.setVelocity(new Vector());
    }

    private static double smoothStep(double value) {
        double clamped = Math.max(0.0, Math.min(1.0, value));
        return clamped * clamped * (3.0 - 2.0 * clamped);
    }

    private double groundY(Location location) {
        return controller.getWorld().getHighestBlockYAt(location, HeightMap.MOTION_BLOCKING_NO_LEAVES) + 1.0;
    }

    private double groundModelYOffset() {
        return GROUND_MODEL_BASE_Y_OFFSET * plugin.boneDragonSettings().modelScale();
    }

    private void updateBossBar() {
        updateBossBarHealth(phaseHealthRemaining);
        bossBar.setTitle(legacyString(plugin.messages().raw("boss.bone-dragon.bossbar",
                "name", plugin.boneDragonSettings().displayName(), "phase", phase.name())));
        double rangeSquared = plugin.boneDragonSettings().bossbarRange() * plugin.boneDragonSettings().bossbarRange();
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean inRange = player.getWorld() == controller.getWorld()
                    && player.getLocation().distanceSquared(controller.getLocation()) <= rangeSquared;
            if (inRange && !bossBar.getPlayers().contains(player)) bossBar.addPlayer(player);
            else if (!inRange && bossBar.getPlayers().contains(player)) bossBar.removePlayer(player);
        }
    }

    private void updateBossBarHealth(double displayedHealth) {
        double maximum = Math.max(1.0, configuredPhaseHealth(phase));
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, displayedHealth / maximum)));
    }

    private void cleanMinions() {
        minions.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
    }

    private void cleanDeathRulers() {
        deathRulers.removeIf(id -> {
            Entity entity = Bukkit.getEntity(id);
            return entity == null || !entity.isValid() || entity.isDead();
        });
        deathRulerNextHealAt.keySet().retainAll(deathRulers);
    }

    private void refreshDeathRulerTimers() {
        long nextHeal = age + plugin.boneDragonSettings().deathRulerHealIntervalTicks();
        deathRulerNextHealAt.replaceAll((id, ignored) -> nextHeal);
    }

    private void enforceMinionLimit() {
        cleanMinions();
        int excess = minions.size() - plugin.boneDragonSettings().maxAliveMinions();
        if (excess <= 0) return;
        Iterator<UUID> iterator = minions.iterator();
        while (excess-- > 0 && iterator.hasNext()) {
            Entity entity = Bukkit.getEntity(iterator.next());
            if (entity != null && entity.isValid()) entity.remove();
            iterator.remove();
        }
    }

    private void spawnEntrance() {
        Location location = controller.getLocation();
        entranceActive = true;
        entranceStartedAt = age;
        entranceGroundY = location.getY();
        entranceTargetY = entranceGroundY + plugin.boneDragonSettings().flightAltitude();
        entranceBaseYaw = location.getYaw();
        controller.setVelocity(new Vector());
        if (model != null) model.playOnce("spawn");
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, location, 90, 3.5, 0.8, 3.5, 0.05);
        controller.getWorld().spawnParticle(Particle.CLOUD, location, 60, 3.5, 0.4, 3.5, 0.035);
        controller.getWorld().playSound(location, Sound.ENTITY_WITHER_SPAWN, 3.2f, 0.6f);
        announce(plugin.messages().component("boss.bone-dragon.entrance-start"));
    }

    private void tickEntrance() {
        long elapsed = age - entranceStartedAt;
        double ascentLinear = (elapsed - ENTRANCE_ASCENT_START_TICK)
                / (double) (ENTRANCE_WING_OPEN_TICK - ENTRANCE_ASCENT_START_TICK);
        double ascent = smoothStep(ascentLinear);
        double spin = smoothStep(elapsed / (double) ENTRANCE_WING_OPEN_TICK);

        Location position = controller.getLocation();
        position.setY(entranceGroundY + (entranceTargetY - entranceGroundY) * ascent);
        position.setYaw(entranceBaseYaw + (float) (spin * 1080.0));
        position.setPitch(0.0f);
        controller.teleport(position);
        controller.setVelocity(new Vector());

        World world = controller.getWorld();
        if (elapsed % 2L == 0L) {
            double radius = 2.8 - ascent * 1.2;
            for (int arm = 0; arm < 3; arm++) {
                double angle = elapsed * 0.38 + arm * Math.PI * 2.0 / 3.0;
                Location spiral = position.clone().add(
                        Math.cos(angle) * radius,
                        -1.5 + (elapsed % 18L) * 0.18,
                        Math.sin(angle) * radius);
                world.spawnParticle(Particle.SOUL_FIRE_FLAME, spiral, 2, 0.08, 0.08, 0.08, 0.015);
                world.spawnParticle(Particle.REVERSE_PORTAL, spiral, 2, 0.12, 0.12, 0.12, 0.02);
            }
        }
        if (elapsed < 24L && elapsed % 3L == 0L) {
            world.spawnParticle(Particle.CLOUD, position.clone().add(0, -1.8, 0), 12, 2.4, 0.25, 2.4, 0.025);
        }
        if (elapsed == ENTRANCE_ASCENT_START_TICK) {
            world.playSound(position, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 3.0f, 0.55f);
        }
        if (elapsed >= 20L && elapsed < ENTRANCE_WING_OPEN_TICK && elapsed % 10L == 0L) {
            world.playSound(position, Sound.ENTITY_PHANTOM_FLAP, 2.3f, (float) (0.55 + elapsed * 0.006));
        }
        if (elapsed == ENTRANCE_WING_OPEN_TICK) {
            world.spawnParticle(Particle.EXPLOSION, position, 8, 2.8, 1.2, 2.8, 0.08);
            world.spawnParticle(Particle.SOUL, position, 80, 4.0, 2.0, 4.0, 0.08);
            world.playSound(position, Sound.ENTITY_ENDER_DRAGON_FLAP, 4.0f, 0.65f);
        }
        if (elapsed == 62L) {
            world.playSound(position, Sound.ENTITY_ENDER_DRAGON_GROWL, 4.0f, 0.7f);
        }
        if (elapsed >= ENTRANCE_ANIMATION_TICKS) {
            position.setY(entranceTargetY);
            controller.teleport(position);
            entranceActive = false;
            if (model != null) model.playLoop("idle_fly");
            announce(plugin.messages().component("boss.bone-dragon.entrance-complete"));
            beginDeathSummon();
        }
    }

    private void announce(Component message) {
        double rangeSquared = plugin.boneDragonSettings().bossbarRange() * plugin.boneDragonSettings().bossbarRange();
        for (Player player : controller.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(controller.getLocation()) <= rangeSquared) player.sendMessage(message);
        }
    }

    public void onDeath(EntityDeathEvent event) {
        event.getDrops().clear();
        event.setDroppedExp(plugin.boneDragonSettings().experience());
        controller.getWorld().spawnParticle(Particle.SOUL, controller.getLocation(), 240, 5.0, 4.0, 5.0, 0.1);
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_ENDER_DRAGON_DEATH, 4.0f, 0.8f);
        announce(plugin.messages().component("boss.bone-dragon.death-fallback"));
        plugin.arenaManager().onBossDefeated(this);
        cleanup();
    }

    public void remove() {
        cleanup();
        if (controller.isValid()) controller.remove();
    }

    public void detach() {
        cancelSkeletonAbsorption();
        releaseCapturedPlayer();
        bossBar.removeAll();
        closeModel();
        clearProjectiles();
        restoreAllPrisonBlocks();
    }

    private void clearProjectiles() {
        for (Meteor meteor : meteors) if (meteor.display.isValid()) meteor.display.remove();
        meteors.clear();
        for (TrackingSkull skull : trackingSkulls) if (skull.display.isValid()) skull.display.remove();
        trackingSkulls.clear();
        for (BoneSpike spike : boneSpikes) if (spike.display.isValid()) spike.display.remove();
        boneSpikes.clear();
        boneSpikeHitPlayers.clear();
    }

    private void restoreAllPrisonBlocks() {
        for (PrisonBlock prison : prisonBlocks) {
            if (prison.block.getType() == Material.BONE_BLOCK) {
                prison.block.setBlockData(prison.original, false);
            }
        }
        prisonBlocks.clear();
    }

    private void cleanup() {
        detach();
        for (UUID id : new HashSet<>(minions)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) entity.remove();
        }
        minions.clear();
        for (UUID id : new HashSet<>(deathRulers)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null && entity.isValid()) entity.remove();
        }
        deathRulers.clear();
        deathRulerNextHealAt.clear();
    }

    private void closeModel() {
        if (model != null) model.close();
        model = null;
    }

    private double healthFraction() {
        double maximum = maxHealth();
        return maximum <= 0 ? 0 : Math.max(0, Math.min(1, health() / maximum));
    }

    private double configuredPhaseHealth(Phase selected) {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        double soloHealth = switch (selected) {
            case P1 -> settings.phaseOneHealth();
            case P2 -> settings.phaseTwoHealth();
            case P3 -> settings.phaseThreeHealth();
        };
        return Math.min(BossHealthLimits.MAXIMUM, soloHealth * encounterHealthMultiplier);
    }

    private static double normalizeHealthMultiplier(Double multiplier) {
        if (multiplier == null || !Double.isFinite(multiplier)) return 1.0;
        return Math.max(1.0, Math.min(10.0, multiplier));
    }

    private double scaledDamage(double baseDamage) {
        return baseDamage * (1.0 + damageBonusRatio());
    }

    private double damageBonusRatio() {
        BoneDragonSettings settings = plugin.boneDragonSettings();
        return Math.min(
                absorbedSkeletons * settings.skeletonAbsorbDamageIncreaseRatio(),
                settings.skeletonAbsorbMaxDamageIncreaseRatio());
    }

    private void refillCurrentPhaseHealth() {
        double maximum = configuredPhaseHealth(phase);
        setBase(Attribute.MAX_HEALTH, maximum);
        setCurrentPhaseHealth(maximum);
    }

    private void setCurrentPhaseHealth(double health) {
        double maximum = configuredPhaseHealth(phase);
        phaseHealthRemaining = Math.max(0.0, Math.min(maximum, health));
        synchronizeControllerHealth();
    }

    private void synchronizeControllerHealth() {
        if (!controller.isValid() || controller.isDead()) return;
        double safeHealth = Math.max(1.0, Math.min(configuredPhaseHealth(phase), phaseHealthRemaining));
        if (Math.abs(controller.getHealth() - safeHealth) > 1.0E-6) {
            controller.setHealth(safeHealth);
        }
    }

    private Phase readStoredPhase() {
        String stored = controller.getPersistentDataContainer().get(
                plugin.boneDragonPhaseKey(), PersistentDataType.STRING);
        if (stored == null) return Phase.P1;
        try {
            return Phase.valueOf(stored);
        } catch (IllegalArgumentException ignored) {
            return Phase.P1;
        }
    }

    private int readAbsorbedSkeletons() {
        Integer stored = controller.getPersistentDataContainer().get(
                plugin.boneDragonAbsorbedKey(), PersistentDataType.INTEGER);
        return stored == null ? 0 : Math.max(0, stored);
    }

    private int readDeathSummonMask() {
        Integer stored = controller.getPersistentDataContainer().get(
                plugin.boneDragonDeathSummonMaskKey(), PersistentDataType.INTEGER);
        return stored == null ? 0 : Math.max(0, stored);
    }

    private void setPhase(Phase next) {
        phase = next;
        controller.getPersistentDataContainer().set(
                plugin.boneDragonPhaseKey(), PersistentDataType.STRING, next.name());
        applyPhaseBossBarColor();
        applyMinionPhaseResistance();
    }

    private void applyMinionPhaseResistance() {
        Set<UUID> summonedSkeletons = new HashSet<>(minions);
        summonedSkeletons.addAll(deathRulers);
        for (UUID id : summonedSkeletons) {
            Entity entity = Bukkit.getEntity(id);
            if (entity instanceof Skeleton skeleton && entity.isValid() && !entity.isDead()) {
                applyMinionResistance(skeleton);
            }
        }
    }

    private void applyMinionResistance(Skeleton skeleton) {
        int amplifier = switch (phase) {
            case P1 -> -1;
            case P2 -> 0;
            case P3 -> 1;
        };
        if (amplifier < 0) {
            skeleton.removePotionEffect(PotionEffectType.RESISTANCE);
            return;
        }
        skeleton.removePotionEffect(PotionEffectType.RESISTANCE);
        skeleton.addPotionEffect(new PotionEffect(
                PotionEffectType.RESISTANCE,
                PotionEffect.INFINITE_DURATION,
                amplifier,
                false,
                false,
                true
        ));
    }

    private void applyPhaseBossBarColor() {
        bossBar.setColor(switch (phase) {
            case P1 -> BarColor.WHITE;
            case P2 -> BarColor.PURPLE;
            case P3 -> BarColor.RED;
        });
    }

    private static Component legacyComponent(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value);
    }

    private static String legacyString(String value) {
        return LegacyComponentSerializer.legacySection().serialize(legacyComponent(value));
    }

    private static final class Meteor {
        private final ItemDisplay display;
        private final Location impact;
        private int remainingTicks;

        private Meteor(ItemDisplay display, Location impact, int remainingTicks) {
            this.display = display;
            this.impact = impact;
            this.remainingTicks = remainingTicks;
        }
    }

    private static final class TrackingSkull {
        private final ItemDisplay display;
        private final UUID targetId;
        private final Vector velocity;
        private int remainingTicks;

        private TrackingSkull(ItemDisplay display, UUID targetId, Vector velocity, int remainingTicks) {
            this.display = display;
            this.targetId = targetId;
            this.velocity = velocity;
            this.remainingTicks = remainingTicks;
        }
    }

    private static final class BoneSpike {
        private final ItemDisplay display;
        private final double surfaceY;
        private final long spawnedAt;

        private BoneSpike(ItemDisplay display, double surfaceY, long spawnedAt) {
            this.display = display;
            this.surfaceY = surfaceY;
            this.spawnedAt = spawnedAt;
        }
    }

    private static final class PrisonBlock {
        private final Block block;
        private final BlockData original;
        private final long expiresAt;

        private PrisonBlock(Block block, BlockData original, long expiresAt) {
            this.block = block;
            this.original = original;
            this.expiresAt = expiresAt;
        }
    }
}
