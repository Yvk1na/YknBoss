package cn.yknboss.boss;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.model.BetterModelSupport;
import cn.yknboss.util.ParticleCompat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarFlag;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Vex;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public final class EndsunGuard implements ScriptedBossLifecycle {
    private static final long NORMAL_SLASH_TICKS = 14L;
    private static final long ECLIPSE_TRANSFORM_TICKS = 40L;
    private static final long PHASE_TRANSITION_TICKS = 30L;
    private static final long ENTRANCE_TICKS = 52L;
    private static final long DEATH_TICKS = 60L;
    private static final long REACTIVE_CAST_COOLDOWN_TICKS = 20L;
    private static final int BOSS_GLOW_COLOR = 0xFF9D18;
    private static final float ENDSUN_BAKED_MODEL_SCALE = 1.5f;

    private enum Phase { P1, P2, P3 }
    private enum Skill {
        NONE, SCYTHE_RETURN, JUDGEMENT, CROWN_CAST, SHIELD,
        SOUL_DRAIN, SAND_FUNERAL, SCYTHE_ECHO, NETHER_BOUNDARY, SUNFALL, EDICT, TWIN_ECLIPSE,
        PHASE_TRANSITION, ECLIPSE_TRANSFORM, BLACK_SUN, STAGGER, ENTRANCE, DEATH
    }

    private enum EdictType { WEAPON, MOVEMENT, GAZE, GROUP }
    private enum TwinType { SUN, NIGHT }

    private static final class SolarFlame {
        private Location location;
        private Vector velocity;
        private final Map<UUID, Long> nextHit = new HashMap<>();

        private SolarFlame(Location location, Vector velocity) {
            this.location = location;
            this.velocity = velocity;
        }
    }

    private static final class EchoClone {
        private final UUID entityId;
        private final Location origin;
        private final Vector direction;

        private EchoClone(UUID entityId, Location origin, Vector direction) {
            this.entityId = entityId;
            this.origin = origin;
            this.direction = direction;
        }
    }

    private static final class BoundaryReturn {
        private final Location location;
        private final long dueAt;

        private BoundaryReturn(Location location, long dueAt) {
            this.location = location;
            this.dueAt = dueAt;
        }
    }

    private static final class SunWheel {
        private final UUID entityId;
        private final Location impact;
        private final long expiresAt;
        private Vector velocity = new Vector();
        private boolean landed;
        private boolean launched;

        private SunWheel(UUID entityId, Location impact, long expiresAt) {
            this.entityId = entityId;
            this.impact = impact;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TwinBody {
        private final TwinType type;
        private final EnumMap<Skill, Long> cooldowns = new EnumMap<>(Skill.class);
        private final Set<UUID> outwardHits = new HashSet<>();
        private final Set<UUID> returnHits = new HashSet<>();
        private final Map<UUID, Long> nextSandHit = new HashMap<>();
        private final Map<UUID, Long> nextAcceptedPlayerHit = new HashMap<>();
        private final Map<UUID, Integer> suffocationStacks = new HashMap<>();
        private final List<Location> skillLocations = new ArrayList<>();
        private final List<Vector> skillDirections = new ArrayList<>();
        private Husk entity;
        private BetterModelSupport.Instance model;
        private BossBar healthBar;
        private Skill activeSkill = Skill.NONE;
        private long skillStartedAt;
        private long nextSkillAt;
        private final SkillRotation<Skill> skillRotation;
        private UUID skillTargetId;
        private Location skillOrigin;
        private Location skillEnd;
        private double sandGapAngle;
        private int echoTeleports;
        private boolean replayed;
        private EdictType pendingEdict;
        private double shieldEnergy;
        private UUID lastHitSourceId;
        private boolean lastHitIndirect;
        private long lastHitAt = Long.MIN_VALUE;
        private long nextHurtReactionAt;
        private boolean walking;
        private long attackAnimationUntil;
        private boolean defeated;
        private int reactiveHitCount;
        private long nextReactiveCastAt;
        private double maximumHealth;
        private double healthRemaining;

        private TwinBody(TwinType type) {
            this.type = type;
            this.skillRotation = new SkillRotation<>(2, type == TwinType.SUN ? 0 : 5);
        }
    }

    private final YknBossPlugin plugin;
    private Husk controller;
    private DamageSource skillDamageSource;
    private final BossBar bossBar;
    private final Location battlefieldCenter;
    private final double encounterHealthMultiplier;
    private final Map<UUID, Double> eclipseValues = new HashMap<>();
    private final Map<UUID, Double> damageThreat = new HashMap<>();
    private final Map<UUID, Long> nextAcceptedPlayerHit = new HashMap<>();
    private final Set<EntityDamageEvent> preApprovedPlayerDamageEvents =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final EnumMap<Skill, Long> cooldowns = new EnumMap<>(Skill.class);
    private final Map<UUID, Integer> totemHits = new HashMap<>();
    private final Set<UUID> soulReflections = new HashSet<>();
    private final Set<UUID> fragmentEntities = new HashSet<>();
    private final List<SolarFlame> solarFlames = new ArrayList<>();
    private final List<Location> shadowBarriers = new ArrayList<>();
    private final Set<UUID> outwardHits = new HashSet<>();
    private final Set<UUID> returnHits = new HashSet<>();
    private final Map<UUID, Integer> suffocationStacks = new HashMap<>();
    private final Map<UUID, Long> nextSandHit = new HashMap<>();
    private final List<EchoClone> echoClones = new ArrayList<>();
    private final Map<UUID, Double> boundarySides = new HashMap<>();
    private final Map<UUID, Location> boundaryLastLocations = new HashMap<>();
    private final Map<UUID, BoundaryReturn> boundaryReturns = new HashMap<>();
    private final List<SunWheel> sunWheels = new ArrayList<>();
    private final List<Location> sunfallTargets = new ArrayList<>();
    private final Map<UUID, Integer> edictCounters = new HashMap<>();
    private final Map<UUID, Long> edictCooldowns = new HashMap<>();
    private final Map<UUID, Location> edictLastLocations = new HashMap<>();

    private BetterModelSupport.Instance model;
    private Phase phase = Phase.P1;
    private Skill skill = Skill.NONE;
    private boolean walking;
    private boolean eclipsePending;
    private boolean ultimateUsed;
    private boolean recallAnimationPlayed;
    private boolean dealingSkillDamage;
    private boolean teamBarrierActive;
    private boolean dying;
    private Phase pendingPhase;
    private boolean bossBarRefreshQueued;
    private long age;
    private long phaseHealthLockUntil;
    private long skillStartedAt;
    private long nextSkillAt;
    private long slashEndsAt;
    private long phaseInvulnerableUntil;
    private long crownEndsAt;
    private long eclipseFormUntil;
    private long nextReflectionAt;
    private long phaseThreeUltimateAt = Long.MAX_VALUE;
    private long nextHitFeedbackAt;
    private long nextHurtReactionAt;
    private double sandGapAngle;
    private int echoTeleports;
    private boolean echoReplayed;
    private Location boundaryCenter;
    private Vector boundaryNormal;
    private long boundaryExpiresAt;
    private EdictType pendingEdict;
    private EdictType activeEdict;
    private long edictEndsAt;
    private TwinBody twinSun;
    private TwinBody twinNight;
    private long twinReviveAt;
    private boolean twinsSpawned;
    private double shieldEnergy;
    private double furnaceDamageBonus;
    private double ultimateFurnaceHealth;
    // Authoritative health for the current phase.  The controller's Bukkit health
    // is only a carrier and may be rewritten by model/level plugins.
    private double phaseHealthRemaining;
    private int teamFragmentProgress;
    private final SkillRotation<Skill> skillRotation = new SkillRotation<>(
            2, ThreadLocalRandom.current().nextInt());
    private int reactiveHitCount;
    private long nextReactiveCastAt;
    private UUID judgementTarget;
    private Location scytheStart;
    private Location scytheEnd;

    private EndsunGuard(YknBossPlugin plugin, Husk controller) {
        this.plugin = plugin;
        this.controller = controller;
        this.skillDamageSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(controller)
                .withDirectEntity(controller)
                .build();
        Double storedHealthMultiplier = controller.getPersistentDataContainer().get(
                plugin.encounterHealthMultiplierKey(), PersistentDataType.DOUBLE);
        this.encounterHealthMultiplier = normalizeHealthMultiplier(storedHealthMultiplier);
        Integer storedPhase = controller.getPersistentDataContainer()
                .get(plugin.endsunPhaseKey(), PersistentDataType.INTEGER);
        if (storedPhase != null) phase = switch (storedPhase) {
            case 2 -> Phase.P2;
            case 3 -> Phase.P3;
            default -> Phase.P1;
        };
        this.battlefieldCenter = controller.getLocation().clone();
        this.bossBar = Bukkit.createBossBar(
                legacyString(plugin.endsunGuardSettings().displayName()),
                BarColor.RED, BarStyle.SOLID);
        bossBar.setVisible(true);
        applySettings(true);
        persistPhase();
        shieldEnergy = scaledShieldMaximumEnergy();
    }

    public static EndsunGuard spawn(YknBossPlugin plugin, Location location) {
        return spawn(plugin, location, 1.0);
    }

    public static EndsunGuard spawn(YknBossPlugin plugin, Location location, double healthMultiplier) {
        if (location.getWorld() == null) throw new IllegalArgumentException("生成位置没有世界");
        double normalizedMultiplier = normalizeHealthMultiplier(healthMultiplier);
        Husk controller = location.getWorld().spawn(location, Husk.class, entity -> {
            configureController(plugin, entity);
            entity.getPersistentDataContainer().set(plugin.encounterHealthMultiplierKey(),
                    PersistentDataType.DOUBLE, normalizedMultiplier);
        });
        try {
            EndsunGuard boss = new EndsunGuard(plugin, controller);
            boss.refillCurrentPhaseHealth();
            controller.getWorld().spawnParticle(Particle.FLAME, controller.getLocation().add(0, 1.0, 0),
                    35, 0.7, 1.0, 0.7, 0.025);
            controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_SPAWN, 1.6f, 1.25f);
            boss.beginEntrance();
            return boss;
        } catch (RuntimeException | LinkageError exception) {
            controller.remove();
            throw exception;
        }
    }

    public static EndsunGuard restore(YknBossPlugin plugin, Husk controller) {
        EndsunGuard boss = new EndsunGuard(plugin, controller);
        if (boss.phase != Phase.P1) {
            boss.bossBar.addFlag(BarFlag.DARKEN_SKY);
            boss.nextReflectionAt = boss.age + plugin.endsunGuardSettings().furnace().reflectionSpawnIntervalTicks();
        }
        return boss;
    }

    private static void configureController(YknBossPlugin plugin, Husk entity) {
        entity.setAdult();
        entity.setAI(true);
        entity.setInvisible(true);
        entity.setFireTicks(0);
        entity.setVisualFire(false);
        entity.setSilent(true);
        entity.setCanPickupItems(false);
        entity.setRemoveWhenFarAway(false);
        entity.setPersistent(true);
        // The vanilla cooldown is global to the victim and drops concurrent party
        // attacks. A per-player gate below preserves the old solo rate safely.
        entity.setMaximumNoDamageTicks(0);
        entity.setNoDamageTicks(0);
        entity.getEquipment().clear();
        entity.getEquipment().setItemInMainHandDropChance(0.0f);
        entity.getEquipment().setItemInOffHandDropChance(0.0f);
        entity.getPersistentDataContainer().set(
                plugin.bossTypeKey(), PersistentDataType.STRING, EndsunGuardManager.TYPE_ID);
    }

    public UUID id() { return controller.getUniqueId(); }
    public Location location() { return controller.getLocation(); }
    public double health() {
        return phaseHealthRemaining > 0.0 ? phaseHealthRemaining : controller.getHealth();
    }
    public String phaseName() { return phase.name(); }
    public String currentSkill() { return skill.name().toLowerCase(java.util.Locale.ROOT); }
    public double highestEclipse() {
        return eclipseValues.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
    }

    public double maxHealth() {
        // Every phase owns an independent configured health pool.  Do not use the
        // attribute's final value here: other plugins may attach MAX_HEALTH modifiers,
        // which would make a freshly refilled phase appear almost empty on the bar.
        return phaseHealth();
    }

    private double phaseHealth() {
        EndsunGuardSettings settings = plugin.endsunGuardSettings();
        double soloHealth = switch (phase) {
            case P1 -> settings.phaseOneHealth();
            case P2 -> settings.phaseTwoHealth();
            case P3 -> settings.phaseThreeHealth();
        };
        return scaledEncounterHealth(soloHealth);
    }

    private double scaledEncounterHealth(double soloHealth) {
        return Math.min(BossHealthLimits.MAXIMUM, Math.max(1.0, soloHealth) * encounterHealthMultiplier);
    }

    private double ultimateFurnaceMaximumHealth() {
        return scaledEncounterHealth(plugin.endsunGuardSettings().ultimate().furnaceHealth());
    }

    private static double normalizeHealthMultiplier(Double multiplier) {
        if (multiplier == null || !Double.isFinite(multiplier)) return 1.0;
        return Math.max(1.0, Math.min(10.0, multiplier));
    }

    private void persistPhase() {
        controller.getPersistentDataContainer().set(plugin.endsunPhaseKey(), PersistentDataType.INTEGER,
                switch (phase) { case P1 -> 1; case P2 -> 2; case P3 -> 3; });
    }

    public boolean tick() {
        if (!controller.isValid() || controller.isDead()) return false;
        age++;
        controller.setFireTicks(0);
        controller.setVisualFire(false);
        tickPhaseProtection();
        if (pendingPhase != null) {
            controller.setAI(false);
            controller.setInvulnerable(true);
            controller.setVelocity(new Vector());
            synchronizeControllerHealth();
            updateBossBar();
            return true;
        }

        // Twin Eclipse is an exclusive encounter state.  Keeping this gate before the
        // main controller combat loop keeps it hidden, while both bodies run independent
        // skill schedulers. Persistent effects must continue ticking for their casts.
        if (skill == Skill.TWIN_ECLIPSE) {
            tickPersistentMechanics();
            tickCrown();
            tickSoulReflections();
            tickEclipseMeters();
            if (age >= nextReflectionAt) spawnSoulReflections();
            tickSkill();
            if (age % 10L == 0L) updateBossBar();
            return true;
        }

        tickPersistentMechanics();
        tickCrown();
        tickSoulReflections();
        tickEclipseMeters();
        if (phase != Phase.P1 && age >= nextReflectionAt) spawnSoulReflections();
        if (phase == Phase.P3 && age >= phaseThreeUltimateAt && !ultimateUsed) eclipsePending = true;

        if (skill != Skill.NONE) tickSkill();
        else if (eclipsePending) beginEclipseTransform();
        else tickCombat();

        if (age % 10L == 0L) {
            acquireTarget();
            updateBossBar();
        }
        if (age % 12L == 0L) {
            controller.getWorld().spawnParticle(phase == Phase.P1 ? Particle.SMALL_FLAME : Particle.SOUL_FIRE_FLAME,
                    controller.getLocation().add(0, 1.45, 0), phase == Phase.P3 ? 8 : 3,
                    0.35, 0.55, 0.35, 0.01);
        }
        return true;
    }

    private void tickCombat() {
        Player target = targetPlayer();
        if (target == null) {
            acquireTarget();
            target = targetPlayer();
        }
        tickActiveChase(target);
        boolean moving = controller.getTarget() instanceof Player
                && controller.getVelocity().getX() * controller.getVelocity().getX()
                + controller.getVelocity().getZ() * controller.getVelocity().getZ() > 0.0025;
        if (age >= slashEndsAt && moving != walking) {
            walking = moving;
            playLoop(walking ? "walk" : idleAnimation());
        }
        if (age >= nextSkillAt && age % 6L == 0L) selectSkill();
    }

    private void tickSkill() {
        long elapsed = age - skillStartedAt;
        switch (skill) {
            case SCYTHE_RETURN -> tickScytheReturn(elapsed);
            case JUDGEMENT -> tickJudgement(elapsed);
            case CROWN_CAST -> { if (elapsed >= 28L) finishSkill(); }
            case SHIELD -> tickShield(elapsed);
            case SOUL_DRAIN -> tickSoulDrain(elapsed);
            case SAND_FUNERAL -> tickSandFuneral(elapsed);
            case SCYTHE_ECHO -> tickScytheEcho(elapsed);
            case NETHER_BOUNDARY -> tickNetherBoundaryCast(elapsed);
            case SUNFALL -> tickSunfallCast(elapsed);
            case EDICT -> tickEdictCast(elapsed);
            case TWIN_ECLIPSE -> tickTwinEclipse(elapsed);
            case PHASE_TRANSITION -> {
                if (elapsed % 3L == 0L) drawMagicCircle(controller.getLocation(), 3.6,
                        phase == Phase.P3 ? Color.fromRGB(210, 25, 35) : Color.fromRGB(135, 45, 220), age * 0.09);
                if (elapsed >= PHASE_TRANSITION_TICKS) finishSkill();
            }
            case ECLIPSE_TRANSFORM -> {
                if (elapsed % 3L == 0L) drawMagicCircle(controller.getLocation(), 4.2,
                        Color.fromRGB(75, 20, 105), -age * 0.12);
                if (elapsed >= ECLIPSE_TRANSFORM_TICKS) {
                    if (!ultimateUsed) beginBlackSun();
                    else finishSkill();
                }
            }
            case BLACK_SUN -> tickBlackSun(elapsed);
            case STAGGER -> { if (elapsed >= plugin.endsunGuardSettings().shield().staggerTicks()) finishSkill(); }
            case ENTRANCE -> tickEntrance(elapsed);
            case DEATH -> tickDeath(elapsed);
            default -> { }
        }
    }

    private void beginEntrance() {
        beginSkillHold(Skill.ENTRANCE, "entrance");
        phaseInvulnerableUntil = Math.max(phaseInvulnerableUntil, age + ENTRANCE_TICKS);
        controller.getWorld().playSound(controller.getLocation(), Sound.BLOCK_BEACON_ACTIVATE, 2.4f, 0.52f);
        announce("boss.endsun-guard.entrance-start");
    }

    private void tickEntrance(long elapsed) {
        Location base = controller.getLocation();
        if (elapsed % 2L == 0L) {
            double progress = Math.min(1.0, elapsed / (double) ENTRANCE_TICKS);
            drawMagicCircle(base, 5.5 - progress * 2.0, Color.fromRGB(255, 110, 20), age * 0.13);
            controller.getWorld().spawnParticle(Particle.FLAME, base.clone().add(0, 0.4 + progress * 2.0, 0),
                    12, 1.2, 0.55, 1.2, 0.035);
            controller.getWorld().spawnParticle(Particle.LARGE_SMOKE, base.clone().add(0, 0.8, 0),
                    5, 0.9, 0.45, 0.9, 0.02);
        }
        if (elapsed == 28L) controller.getWorld().playSound(base, Sound.ENTITY_BLAZE_AMBIENT, 2.2f, 0.65f);
        if (elapsed < ENTRANCE_TICKS) return;
        // Release the AI before non-critical feedback so a particle API mismatch can never trap the boss in ENTRANCE.
        finishSkill();
        ParticleCompat.spawnFlash(controller.getWorld(), base.clone().add(0, 1.4, 0), 1,
                Color.fromRGB(255, 185, 55));
        controller.getWorld().playSound(base, Sound.ITEM_TRIDENT_THUNDER, 1.7f, 0.72f);
        announce("boss.endsun-guard.entrance-complete");
    }

    private void startDeathSequence() {
        if (dying) return;
        dying = true;
        cleanupAuxiliary();
        eclipsePending = false;
        crownEndsAt = 0L;
        solarFlames.clear();
        removeTotems();
        skill = Skill.DEATH;
        skillStartedAt = age;
        walking = false;
        controller.setAI(false);
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        if (model != null) model.playOnceAndHold("death");
        plugin.endsunArenaManager().onBossDeathStarted(this);
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_DEATH, 2.8f, 1.25f);
        announce("boss.endsun-guard.death-start");
    }

    private void tickDeath(long elapsed) {
        Location center = controller.getLocation().clone().add(0, 1.1, 0);
        if (elapsed % 2L == 0L) {
            controller.getWorld().spawnParticle(Particle.FLAME, center, 10, 0.75, 0.9, 0.75, 0.025);
            controller.getWorld().spawnParticle(Particle.ASH, center, 16, 0.9, 1.0, 0.9, 0.02);
        }
        if (elapsed % 5L == 0L) drawMagicCircle(controller.getLocation(), Math.max(1.2, 4.5 - elapsed * 0.045),
                Color.fromRGB(115, 25, 145), -age * 0.15);
        if (elapsed < DEATH_TICKS) return;
        controller.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, center, 65, 1.1, 1.4, 1.1, 0.06);
        controller.setInvulnerable(false);
        controller.setHealth(0.0);
    }

    private void selectSkill() {
        Player target = targetPlayer();
        if (target == null) {
            nextSkillAt = age + castTimingTicks("retry-delay-seconds", 0.5);
            return;
        }
        List<Skill> candidates = switch (phase) {
            case P1 -> List.of(Skill.SHIELD, Skill.SAND_FUNERAL, Skill.SCYTHE_RETURN, Skill.NETHER_BOUNDARY,
                    Skill.CROWN_CAST, Skill.SCYTHE_ECHO, Skill.JUDGEMENT, Skill.SUNFALL, Skill.EDICT);
            case P2 -> List.of(Skill.SOUL_DRAIN, Skill.SCYTHE_ECHO, Skill.JUDGEMENT, Skill.SAND_FUNERAL,
                    Skill.SCYTHE_RETURN, Skill.SUNFALL, Skill.CROWN_CAST, Skill.NETHER_BOUNDARY,
                    Skill.SHIELD, Skill.EDICT);
            case P3 -> List.of(Skill.TWIN_ECLIPSE, Skill.SCYTHE_ECHO, Skill.SUNFALL, Skill.SAND_FUNERAL,
                    Skill.SCYTHE_RETURN, Skill.EDICT, Skill.JUDGEMENT, Skill.NETHER_BOUNDARY,
                    Skill.CROWN_CAST, Skill.SOUL_DRAIN);
        };
        Skill candidate = skillRotation.choose(candidates, this::ready);
        if (candidate == null) {
            nextSkillAt = age + castTimingTicks("retry-delay-seconds", 0.5);
            return;
        }
        switch (candidate) {
            case SCYTHE_RETURN -> beginScytheReturn(target);
            case JUDGEMENT -> beginJudgement();
            case CROWN_CAST -> beginCrown();
            case SHIELD -> beginShield();
            case SOUL_DRAIN -> beginSoulDrain();
            case SAND_FUNERAL -> beginSandFuneral();
            case SCYTHE_ECHO -> beginScytheEcho(target);
            case NETHER_BOUNDARY -> beginNetherBoundary();
            case SUNFALL -> beginSunfall();
            case EDICT -> beginEdict();
            case TWIN_ECLIPSE -> beginTwinEclipse();
            default -> { }
        }
    }

    private boolean ready(Skill candidate) {
        if (age < cooldowns.getOrDefault(candidate, 0L)) return false;
        if (candidate == Skill.SOUL_DRAIN && soulReflections.isEmpty()) return false;
        if (candidate == Skill.SHIELD && phase == Phase.P3) return false;
        if (candidate == Skill.TWIN_ECLIPSE && (phase != Phase.P3 || twinSun != null || twinNight != null)) return false;
        if (candidate == Skill.CROWN_CAST && (age < crownEndsAt || !totemHits.isEmpty())) return false;
        if (candidate == Skill.NETHER_BOUNDARY && (boundaryCenter != null || !boundaryReturns.isEmpty())) return false;
        if (candidate == Skill.EDICT && activeEdict != null) return false;
        return true;
    }

    private void beginSandFuneral() {
        EndsunGuardSettings.SandFuneral settings = plugin.endsunGuardSettings().sandFuneral();
        sandGapAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
        suffocationStacks.clear();
        nextSandHit.clear();
        beginSkillHold(Skill.SAND_FUNERAL, "sand_funeral");
        cooldowns.put(Skill.SAND_FUNERAL, age + adjustedCooldown(settings.cooldownTicks()));
        announce("boss.endsun-guard.sand-funeral");
        controller.getWorld().playSound(controller.getLocation(), Sound.BLOCK_SAND_BREAK, 2.8f, 0.45f);
    }

    private void tickSandFuneral(long elapsed) {
        EndsunGuardSettings.SandFuneral settings = plugin.endsunGuardSettings().sandFuneral();
        double progress = Math.min(1.0, elapsed / (double) settings.durationTicks());
        double radius = settings.startRadius() + (settings.endRadius() - settings.startRadius()) * progress;
        sandGapAngle = normalizeAngle(sandGapAngle + 0.012 + progress * 0.018);
        Location center = battlefieldCenter.clone();
        center.setY(controller.getLocation().getY());
        Color sand = Color.fromRGB(196, 142, 69);
        int points = Math.max(40, (int) Math.round(radius * 5.0));
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0 * index / points;
            if (angleDistance(angle, sandGapAngle) <= settings.gapWidthRadians() * 0.5) continue;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.4, Math.sin(angle) * radius);
            ParticleCompat.spawnDust(controller.getWorld(), point, 1,
                    0.15, 0.8, 0.15, 0.0, sand, 1.55f);
            if ((index & 3) == 0) controller.getWorld().spawnParticle(Particle.CAMPFIRE_COSY_SMOKE,
                    point, 1, 0.08, 0.35, 0.08, 0.015);
        }
        if (elapsed % 10L == 0L) controller.getWorld().playSound(center, Sound.WEATHER_RAIN, 1.3f, 0.55f);
        for (Player player : combatPlayers()) {
            double distance = Math.sqrt(horizontalDistanceSquared(player.getLocation(), center));
            double angle = Math.atan2(player.getZ() - center.getZ(), player.getX() - center.getX());
            if (Math.abs(distance - radius) > settings.hitRadius()
                    || angleDistance(angle, sandGapAngle) <= settings.gapWidthRadians() * 0.5) continue;
            if (age < nextSandHit.getOrDefault(player.getUniqueId(), 0L)) continue;
            nextSandHit.put(player.getUniqueId(), age + settings.damageIntervalTicks());
            int stacks = Math.min(settings.maximumSuffocation(),
                    suffocationStacks.getOrDefault(player.getUniqueId(), 0) + settings.suffocationPerHit());
            suffocationStacks.put(player.getUniqueId(), stacks);
            damage(player, settings.damage() * (1.0 + Math.max(0, stacks - 1) * 0.12));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 55, 0, true, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 35, Math.min(3, stacks / 3), true, false, true));
            player.sendActionBar(plugin.messages().component("boss.endsun-guard.suffocation", "stacks", stacks));
        }
        if (elapsed >= settings.durationTicks()) finishSkill();
    }

    private void beginScytheEcho(Player target) {
        echoTeleports = 0;
        echoReplayed = false;
        removeEchoClones();
        beginSkill(Skill.SCYTHE_ECHO, "scythe_echo");
        cooldowns.put(Skill.SCYTHE_ECHO, age + adjustedCooldown(plugin.endsunGuardSettings().scytheEcho().cooldownTicks()));
        announce("boss.endsun-guard.scythe-echo", "player", target.getName());
    }

    private void tickScytheEcho(long elapsed) {
        EndsunGuardSettings.ScytheEcho settings = plugin.endsunGuardSettings().scytheEcho();
        Player target = targetPlayer();
        long nextTeleport = 4L + echoTeleports * settings.teleportIntervalTicks();
        if (echoTeleports < settings.teleportCount() && elapsed >= nextTeleport) {
            Location origin = controller.getLocation().clone();
            Vector direction = target == null ? origin.getDirection().setY(0)
                    : target.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
            direction.normalize();
            spawnEchoClone(origin, direction);
            double angle = Math.PI * 2.0 * echoTeleports / Math.max(1, settings.teleportCount()) + age * 0.07;
            Location destination = (target == null ? origin : target.getLocation()).clone()
                    .add(Math.cos(angle) * 5.0, 0.15, Math.sin(angle) * 5.0);
            destination.setDirection((target == null ? battlefieldCenter : target.getLocation())
                    .toVector().subtract(destination.toVector()).setY(0));
            controller.teleport(destination);
            controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, origin.clone().add(0, 1.0, 0),
                    35, 0.55, 0.9, 0.55, 0.08);
            controller.getWorld().playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 1.8f, 0.65f + echoTeleports * 0.12f);
            echoTeleports++;
        }
        for (EchoClone clone : echoClones) {
            Entity entity = Bukkit.getEntity(clone.entityId);
            if (entity != null) ParticleCompat.spawnDust(entity.getWorld(),
                    entity.getLocation().add(0, 1.0, 0), 4,
                    0.28, 0.7, 0.28, 0.01, Color.fromRGB(72, 24, 92), 1.25f);
        }
        ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1.35, 0),
                5, 0.12, 0.18, 0.12, 0.0, Color.fromRGB(235, 75, 255), 1.5f);
        long replayAt = 4L + settings.teleportCount() * settings.teleportIntervalTicks() + settings.replayDelayTicks();
        if (!echoReplayed && elapsed >= replayAt) {
            echoReplayed = true;
            if (model != null) model.playOnce("scythe_slash", idleAnimation());
            replayEchoSlash(controller.getLocation(), controller.getLocation().getDirection(), settings);
            for (EchoClone clone : echoClones) replayEchoSlash(clone.origin, clone.direction, settings);
            controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 3.0f, 0.42f);
        }
        if (elapsed >= replayAt + 12L) {
            removeEchoClones();
            finishSkill();
        }
    }

    private void spawnEchoClone(Location origin, Vector direction) {
        Location feet = origin.clone();
        feet.setDirection(direction);
        ArmorStand stand = controller.getWorld().spawn(feet, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setMarker(true);
            entity.setGravity(false);
            entity.setBasePlate(false);
            entity.setPersistent(false);
            entity.setInvulnerable(true);
            entity.setSilent(true);
        });
        echoClones.add(new EchoClone(stand.getUniqueId(), origin.clone(), direction.clone()));
    }

    private void replayEchoSlash(Location origin, Vector rawDirection, EndsunGuardSettings.ScytheEcho settings) {
        Vector direction = rawDirection.clone().setY(0);
        if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
        direction.normalize();
        Location start = origin.clone().add(0, 1.0, 0);
        Location end = start.clone().add(direction.clone().multiply(settings.slashRange()));
        for (int step = 0; step <= 16; step++) {
            Location point = lerp(start, end, step / 16.0);
            controller.getWorld().spawnParticle(Particle.SWEEP_ATTACK, point, 1, 0.15, 0.1, 0.15, 0.0);
            controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, point, 2, 0.18, 0.18, 0.18, 0.015);
        }
        for (Player player : combatPlayers()) {
            if (horizontalDistanceToSegment(player.getLocation(), start, end) <= settings.hitRadius()) {
                damage(player, settings.damage());
            }
        }
    }

    private void beginNetherBoundary() {
        EndsunGuardSettings.NetherBoundary settings = plugin.endsunGuardSettings().netherBoundary();
        boundaryCenter = controller.getLocation().clone();
        boundaryNormal = controller.getLocation().getDirection().setY(0);
        if (boundaryNormal.lengthSquared() < 0.01) boundaryNormal = new Vector(0, 0, 1);
        boundaryNormal.normalize();
        boundaryExpiresAt = age + settings.durationTicks();
        boundarySides.clear();
        boundaryLastLocations.clear();
        boundaryReturns.clear();
        beginSkill(Skill.NETHER_BOUNDARY, "nether_boundary");
        cooldowns.put(Skill.NETHER_BOUNDARY, age + adjustedCooldown(settings.cooldownTicks()));
        announce("boss.endsun-guard.nether-boundary");
    }

    private void tickNetherBoundaryCast(long elapsed) {
        if (elapsed >= plugin.endsunGuardSettings().netherBoundary().castTicks()) finishSkill();
    }

    private void beginSunfall() {
        EndsunGuardSettings.Sunfall settings = plugin.endsunGuardSettings().sunfall();
        sunfallTargets.clear();
        List<Player> players = combatPlayers();
        for (int index = 0; index < settings.wheelCount(); index++) {
            Location base = players.isEmpty() ? battlefieldCenter.clone()
                    : players.get(index % players.size()).getLocation().clone();
            double angle = index * 2.399963229728653;
            double radius = players.isEmpty() ? 5.0 + index * 1.5 : (index / Math.max(1, players.size())) * 2.5;
            base.add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius);
            sunfallTargets.add(base);
        }
        beginSkill(Skill.SUNFALL, "sunfall");
        cooldowns.put(Skill.SUNFALL, age + adjustedCooldown(settings.cooldownTicks()));
        announce("boss.endsun-guard.sunfall");
    }

    private void tickSunfallCast(long elapsed) {
        EndsunGuardSettings.Sunfall settings = plugin.endsunGuardSettings().sunfall();
        for (Location target : sunfallTargets) {
            drawRing(target, 2.3, Particle.FLAME);
            ParticleCompat.spawnDust(controller.getWorld(), target.clone().add(0, 0.15, 0),
                    8, 0.9, 0.02, 0.9, 0.0, Color.fromRGB(255, 178, 35), 1.4f);
        }
        if (elapsed == settings.telegraphTicks()) {
            for (Location target : sunfallTargets) spawnSunWheel(target, settings);
            controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 3.0f, 0.5f);
        }
        if (elapsed >= settings.telegraphTicks() + 12L) {
            sunfallTargets.clear();
            finishSkill();
        }
    }

    private void spawnSunWheel(Location impact, EndsunGuardSettings.Sunfall settings) {
        Location spawn = impact.clone().add(0, 13.0, 0);
        ArmorStand stand = controller.getWorld().spawn(spawn, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setMarker(true);
            entity.setGravity(false);
            entity.setPersistent(false);
            entity.getEquipment().setHelmet(new ItemStack(Material.MAGMA_BLOCK));
        });
        sunWheels.add(new SunWheel(stand.getUniqueId(), impact.clone(), age + settings.durationTicks()));
    }

    private void beginEdict() {
        EdictType[] values = EdictType.values();
        pendingEdict = values[ThreadLocalRandom.current().nextInt(values.length)];
        beginSkillHold(Skill.EDICT, "pharaoh_edict");
        cooldowns.put(Skill.EDICT, age + adjustedCooldown(plugin.endsunGuardSettings().edict().cooldownTicks()));
        announce("boss.endsun-guard.edict-warning", "edict", edictName(pendingEdict));
    }

    private void tickEdictCast(long elapsed) {
        EndsunGuardSettings.Edict settings = plugin.endsunGuardSettings().edict();
        Color color = edictColor(pendingEdict);
        if (elapsed % 2L == 0L) {
            drawMagicCircle(controller.getLocation(), 4.0, color, age * 0.16);
            ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1.7, 0),
                    14, 0.28, 0.28, 0.28, 0.0, color, 1.7f);
        }
        if (elapsed < settings.telegraphTicks()) return;
        activeEdict = pendingEdict;
        pendingEdict = null;
        edictEndsAt = age + settings.durationTicks();
        edictCounters.clear();
        edictCooldowns.clear();
        edictLastLocations.clear();
        announce("boss.endsun-guard.edict-active", "edict", edictName(activeEdict));
        controller.getWorld().playSound(controller.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 2.8f, 0.5f);
        finishSkill();
    }

    private void beginTwinEclipse() {
        twinsSpawned = false;
        twinReviveAt = 0L;
        clearTwinIncompatibleMechanics();
        activeEdict = null;
        pendingEdict = null;
        edictCounters.clear();
        edictCooldowns.clear();
        edictLastLocations.clear();
        beginSkillHold(Skill.TWIN_ECLIPSE, "twin_eclipse");
        cooldowns.put(Skill.TWIN_ECLIPSE, age + adjustedCooldown(plugin.endsunGuardSettings().twins().cooldownTicks()));
        announce("boss.endsun-guard.twins-start",
                "seconds", plugin.endsunGuardSettings().twins().revivalWindowTicks() / 20);
    }

    private void tickTwinEclipse(long elapsed) {
        if (!twinsSpawned && elapsed >= 18L) {
            twinsSpawned = true;
            closeModel();
            twinSun = spawnTwin(TwinType.SUN, controller.getLocation().clone().add(4.0, 0, 0));
            twinNight = spawnTwin(TwinType.NIGHT, controller.getLocation().clone().add(-4.0, 0, 0));
            controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_SPAWN, 2.8f, 1.35f);
        }
        tickTwinBody(twinSun, Color.fromRGB(255, 150, 25));
        tickTwinBody(twinNight, Color.fromRGB(85, 35, 145));
        if (twinReviveAt > 0L && age >= twinReviveAt) reviveMissingTwin();
    }

    private TwinBody spawnTwin(TwinType type, Location location) {
        return spawnTwin(type, location,
                scaledEncounterHealth(plugin.endsunGuardSettings().twins().bodyHealth()));
    }

    private TwinBody spawnTwin(TwinType type, Location location, double initialHealth) {
        EndsunGuardSettings settings = plugin.endsunGuardSettings();
        double bodyHealth = scaledEncounterHealth(settings.twins().bodyHealth());
        TwinBody body = new TwinBody(type);
        body.maximumHealth = bodyHealth;
        body.healthRemaining = sanitizeTwinHealth(bodyHealth, initialHealth);
        String nameKey = type == TwinType.SUN
                ? "boss.endsun-guard.twins-sun-name" : "boss.endsun-guard.twins-night-name";
        String barKey = type == TwinType.SUN
                ? "boss.endsun-guard.twins-sun-bossbar" : "boss.endsun-guard.twins-night-bossbar";
        body.entity = controller.getWorld().spawn(location, Husk.class, entity -> {
            entity.setAdult();
            entity.setAI(true);
            entity.setInvisible(true);
            entity.setFireTicks(0);
            entity.setVisualFire(false);
            entity.setSilent(true);
            entity.setPersistent(false);
            entity.setRemoveWhenFarAway(false);
            entity.setCanPickupItems(false);
            entity.setInvulnerable(false);
            entity.setMaximumNoDamageTicks(0);
            entity.setNoDamageTicks(0);
            entity.getEquipment().clear();
            AttributeInstance maximum = entity.getAttribute(Attribute.MAX_HEALTH);
            if (maximum != null) maximum.setBaseValue(bodyHealth);
            AttributeInstance damage = entity.getAttribute(Attribute.ATTACK_DAMAGE);
            if (damage != null) damage.setBaseValue(settings.attackDamage() * 0.65);
            entity.setHealth(body.healthRemaining);
            AttributeInstance movement = entity.getAttribute(Attribute.MOVEMENT_SPEED);
            if (movement != null) movement.setBaseValue(Math.min(1.0, settings.movementSpeed() * 1.08));
            AttributeInstance followRange = entity.getAttribute(Attribute.FOLLOW_RANGE);
            if (followRange != null) followRange.setBaseValue(settings.followRange());
            entity.customName(legacyComponent(plugin.messages().raw(nameKey)));
            entity.setCustomNameVisible(true);
        });
        body.healthBar = Bukkit.createBossBar(
                legacyString(plugin.messages().raw(barKey,
                        "health", Math.round(body.healthRemaining),
                        "max", Math.round(bodyHealth))),
                type == TwinType.SUN ? BarColor.RED : BarColor.PURPLE,
                BarStyle.SEGMENTED_10);
        body.healthBar.setVisible(true);
        body.model = plugin.betterModel().attachStable(settings.modelId(), body.entity,
                settings.modelScale() / ENDSUN_BAKED_MODEL_SCALE, "final_stance", false);
        if (body.model != null) {
            body.model.setGlow(true, type == TwinType.SUN ? 0xFF9619 : 0x552391);
            body.model.playLoop("final_stance");
            body.model.setHitBoxDamageModifier((boneName, causingEntityId, indirect, impact, damage) -> {
                body.lastHitSourceId = causingEntityId;
                body.lastHitIndirect = indirect;
                body.lastHitAt = age;
                // Let BetterModel forward the hit to the source Husk so YknRpg can replace
                // the vanilla amount before YknBoss settles the scripted health pool.
                return damage;
            });
        }
        body.nextSkillAt = age + (type == TwinType.SUN ? 24L : 48L);
        acquireTwinTarget(body);
        updateTwinBossBar(body);
        return body;
    }

    private void tickTwinBody(TwinBody body, Color color) {
        if (body == null || body.defeated || body.entity == null || !body.entity.isValid()) return;
        synchronizeTwinEntityHealth(body);
        body.entity.setFireTicks(0);
        body.entity.setVisualFire(false);
        body.entity.setInvulnerable(false);
        body.entity.setNoDamageTicks(0);
        if (age % 10L == 0L) {
            acquireTwinTarget(body);
        }
        if (body.activeSkill == Skill.NONE) tickTwinChase(body);
        else tickTwinSkill(body);
        Player target = body.entity.getTarget() instanceof Player player ? player : null;
        long attackOffset = body.type == TwinType.SUN ? 0L : 10L;
        if (body.activeSkill == Skill.NONE && target != null && age >= body.attackAnimationUntil
                && age % 20L == attackOffset
                && target.getLocation().distanceSquared(body.entity.getLocation()) <= 12.25) {
            body.attackAnimationUntil = age + NORMAL_SLASH_TICKS;
            body.walking = false;
            if (body.model != null) body.model.playOnce("scythe_slash", "final_stance");
        }
        boolean moving = body.entity.getVelocity().getX() * body.entity.getVelocity().getX()
                + body.entity.getVelocity().getZ() * body.entity.getVelocity().getZ() > 0.0025;
        if (body.activeSkill == Skill.NONE && body.model != null
                && age >= body.attackAnimationUntil && moving != body.walking) {
            body.walking = moving;
            body.model.playLoop(moving ? "walk" : "final_stance");
        }
        if (body.activeSkill == Skill.NONE && age >= body.nextSkillAt) selectTwinSkill(body);
        if (age % 5L == 0L) updateTwinBossBar(body);
        ParticleCompat.spawnDust(body.entity.getWorld(), body.entity.getLocation().add(0, 1.3, 0),
                6, 0.45, 0.8, 0.45, 0.01, color, 1.35f);
    }

    private void tickTwinChase(TwinBody body) {
        if (!(body.entity.getTarget() instanceof Player target)
                || !validTarget(target, plugin.endsunGuardSettings().followRange())) return;
        Vector toward = target.getLocation().toVector().subtract(body.entity.getLocation().toVector()).setY(0.0);
        if (toward.lengthSquared() <= 6.25) return;
        double speed = Math.min(0.25, 0.05 + plugin.endsunGuardSettings().movementSpeed() * 0.42);
        Vector desired = toward.normalize().multiply(speed);
        Vector current = body.entity.getVelocity();
        body.entity.setVelocity(new Vector(
                current.getX() * 0.6 + desired.getX() * 0.4,
                current.getY(),
                current.getZ() * 0.6 + desired.getZ() * 0.4));
    }

    private void selectTwinSkill(TwinBody body) {
        if (body == null || body.defeated || body.entity == null || !body.entity.isValid()) return;
        List<Skill> candidates = List.of(
                Skill.SCYTHE_RETURN, Skill.JUDGEMENT, Skill.CROWN_CAST, Skill.SHIELD,
                Skill.SOUL_DRAIN, Skill.SAND_FUNERAL, Skill.SCYTHE_ECHO,
                Skill.NETHER_BOUNDARY, Skill.SUNFALL, Skill.EDICT);
        Skill candidate = body.skillRotation.choose(candidates, selected -> twinSkillReady(body, selected));
        if (candidate != null) beginTwinSkill(body, candidate);
        else body.nextSkillAt = age + 10L;
    }

    private boolean twinSkillReady(TwinBody body, Skill candidate) {
        if (age < body.cooldowns.getOrDefault(candidate, 0L)) return false;
        if (candidate == Skill.SOUL_DRAIN && soulReflections.isEmpty()) return false;
        if (!isTwinSharedSkill(candidate)) return true;
        if (age < crownEndsAt || !totemHits.isEmpty() || boundaryCenter != null
                || !boundaryReturns.isEmpty() || activeEdict != null) return false;
        TwinBody other = body == twinSun ? twinNight : twinSun;
        return other == null || other.defeated || !isTwinSharedSkill(other.activeSkill);
    }

    private static boolean isTwinSharedSkill(Skill skill) {
        return skill == Skill.SOUL_DRAIN || skill == Skill.CROWN_CAST
                || skill == Skill.NETHER_BOUNDARY || skill == Skill.EDICT;
    }

    private void beginTwinSkill(TwinBody body, Skill next) {
        Player target = body.entity.getTarget() instanceof Player player ? player : null;
        if (!validTarget(target, plugin.endsunGuardSettings().followRange())) {
            acquireTwinTarget(body);
            target = body.entity.getTarget() instanceof Player player ? player : null;
        }
        body.activeSkill = next;
        body.skillStartedAt = age;
        body.skillTargetId = target == null ? null : target.getUniqueId();
        body.skillOrigin = body.entity.getLocation().clone();
        body.skillEnd = null;
        body.outwardHits.clear();
        body.returnHits.clear();
        body.nextSandHit.clear();
        body.suffocationStacks.clear();
        body.skillLocations.clear();
        body.skillDirections.clear();
        body.echoTeleports = 0;
        body.replayed = false;
        body.pendingEdict = null;
        body.walking = false;
        body.entity.setAI(false);
        body.entity.setVelocity(new Vector());
        body.cooldowns.put(next, age + twinSkillCooldown(next));

        String animation = switch (next) {
            case SCYTHE_RETURN -> "scythe_slash";
            case JUDGEMENT -> "amethyst_judgement";
            case CROWN_CAST -> "crown_ignite";
            case SHIELD -> "solar_shield";
            case SOUL_DRAIN -> "soul_drain";
            case SAND_FUNERAL -> "sand_funeral";
            case SCYTHE_ECHO -> "scythe_echo";
            case NETHER_BOUNDARY -> "nether_boundary";
            case SUNFALL -> "sunfall";
            case EDICT -> "pharaoh_edict";
            default -> "final_stance";
        };
        if (body.model != null) {
            if (next == Skill.SHIELD || next == Skill.SAND_FUNERAL || next == Skill.EDICT) {
                body.model.playOnceAndHold(animation);
            } else body.model.playOnce(animation, "final_stance");
        }

        switch (next) {
            case SCYTHE_RETURN -> {
                EndsunGuardSettings.Scythe settings = plugin.endsunGuardSettings().scythe();
                Vector direction = target == null ? body.entity.getLocation().getDirection().setY(0)
                        : target.getLocation().toVector().subtract(body.entity.getLocation().toVector()).setY(0);
                if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
                direction.normalize();
                body.skillOrigin = body.entity.getLocation().clone().add(0, 1.2, 0);
                body.skillEnd = body.skillOrigin.clone().add(direction.multiply(settings.range()));
                announce("boss.endsun-guard.scythe-return");
            }
            case JUDGEMENT -> {
                Player marked = combatPlayers().stream().max(Comparator.comparingDouble(player ->
                        eclipseValues.getOrDefault(player.getUniqueId(), 0.0))).orElse(target);
                body.skillTargetId = marked == null ? null : marked.getUniqueId();
                if (marked != null) announce("boss.endsun-guard.judgement-lock", "player", marked.getName());
            }
            case CROWN_CAST -> {
                EndsunGuardSettings.Crown settings = plugin.endsunGuardSettings().crown();
                crownEndsAt = Math.max(crownEndsAt, age + settings.durationTicks());
                spawnSolarFlames(settings);
                spawnTotems(settings);
                announce("boss.endsun-guard.crown-start");
            }
            case SHIELD -> {
                body.shieldEnergy = scaledShieldMaximumEnergy();
                announce("boss.endsun-guard.shield-start");
            }
            case SOUL_DRAIN -> announce("boss.endsun-guard.soul-drain");
            case SAND_FUNERAL -> {
                body.sandGapAngle = ThreadLocalRandom.current().nextDouble(Math.PI * 2.0);
                announce("boss.endsun-guard.sand-funeral");
                body.entity.getWorld().playSound(body.entity.getLocation(), Sound.BLOCK_SAND_BREAK, 2.2f, 0.55f);
            }
            case SCYTHE_ECHO -> {
                if (target != null) announce("boss.endsun-guard.scythe-echo", "player", target.getName());
            }
            case NETHER_BOUNDARY -> {
                EndsunGuardSettings.NetherBoundary settings = plugin.endsunGuardSettings().netherBoundary();
                boundaryCenter = body.entity.getLocation().clone();
                boundaryNormal = body.entity.getLocation().getDirection().setY(0);
                if (boundaryNormal.lengthSquared() < 0.01) boundaryNormal = new Vector(0, 0, 1);
                boundaryNormal.normalize();
                boundaryExpiresAt = age + settings.durationTicks();
                boundarySides.clear();
                boundaryLastLocations.clear();
                boundaryReturns.clear();
                announce("boss.endsun-guard.nether-boundary");
            }
            case SUNFALL -> {
                EndsunGuardSettings.Sunfall settings = plugin.endsunGuardSettings().sunfall();
                List<Player> players = combatPlayers();
                for (int index = 0; index < settings.wheelCount(); index++) {
                    Location base = players.isEmpty() ? battlefieldCenter.clone()
                            : players.get(index % players.size()).getLocation().clone();
                    double angle = index * 2.399963229728653 + (body.type == TwinType.SUN ? 0.0 : Math.PI);
                    double radius = players.isEmpty() ? 5.0 + index * 1.5
                            : (index / Math.max(1, players.size())) * 2.5;
                    body.skillLocations.add(base.add(Math.cos(angle) * radius, 0.1, Math.sin(angle) * radius));
                }
                announce("boss.endsun-guard.sunfall");
            }
            case EDICT -> {
                EdictType[] values = EdictType.values();
                body.pendingEdict = values[ThreadLocalRandom.current().nextInt(values.length)];
                announce("boss.endsun-guard.edict-warning", "edict", edictName(body.pendingEdict));
            }
            default -> { }
        }
    }

    private long twinSkillCooldown(Skill skill) {
        EndsunGuardSettings settings = plugin.endsunGuardSettings();
        return adjustedCooldown(switch (skill) {
            case SCYTHE_RETURN -> settings.scythe().cooldownTicks();
            case JUDGEMENT -> settings.judgement().cooldownTicks();
            case CROWN_CAST -> settings.crown().cooldownTicks();
            case SHIELD -> settings.shield().cooldownTicks();
            case SOUL_DRAIN -> settings.furnace().drainCooldownTicks();
            case SAND_FUNERAL -> settings.sandFuneral().cooldownTicks();
            case SCYTHE_ECHO -> settings.scytheEcho().cooldownTicks();
            case NETHER_BOUNDARY -> settings.netherBoundary().cooldownTicks();
            case SUNFALL -> settings.sunfall().cooldownTicks();
            case EDICT -> settings.edict().cooldownTicks();
            default -> 20L;
        });
    }

    private void tickTwinSkill(TwinBody body) {
        if (body == null || body.activeSkill == Skill.NONE) return;
        long elapsed = age - body.skillStartedAt;
        switch (body.activeSkill) {
            case SCYTHE_RETURN -> tickTwinScytheReturn(body, elapsed);
            case JUDGEMENT -> tickTwinJudgement(body, elapsed);
            case CROWN_CAST -> {
                if (elapsed % 4L == 0L) drawMagicCircle(body.entity.getLocation(), 3.2,
                        Color.fromRGB(255, 125, 20), age * 0.14);
                if (elapsed >= 28L) finishTwinSkill(body);
            }
            case SHIELD -> tickTwinShield(body, elapsed);
            case SOUL_DRAIN -> tickTwinSoulDrain(body, elapsed);
            case SAND_FUNERAL -> tickTwinSandFuneral(body, elapsed);
            case SCYTHE_ECHO -> tickTwinScytheEcho(body, elapsed);
            case NETHER_BOUNDARY -> {
                if (elapsed % 3L == 0L) drawMagicCircle(body.entity.getLocation(), 3.6,
                        Color.fromRGB(85, 25, 135), -age * 0.13);
                if (elapsed >= plugin.endsunGuardSettings().netherBoundary().castTicks()) finishTwinSkill(body);
            }
            case SUNFALL -> tickTwinSunfall(body, elapsed);
            case EDICT -> tickTwinEdict(body, elapsed);
            case STAGGER -> {
                if (elapsed >= plugin.endsunGuardSettings().shield().staggerTicks()) finishTwinSkill(body);
            }
            default -> finishTwinSkill(body);
        }
    }

    private void tickTwinScytheReturn(TwinBody body, long elapsed) {
        EndsunGuardSettings.Scythe settings = plugin.endsunGuardSettings().scythe();
        if (body.skillOrigin == null || body.skillEnd == null) {
            finishTwinSkill(body);
            return;
        }
        if (elapsed % 4L == 0L) drawMagicCircle(body.entity.getLocation(), 2.4,
                body.type == TwinType.SUN ? Color.fromRGB(255, 150, 25) : Color.fromRGB(125, 35, 190),
                age * 0.16);
        long returnStart = settings.outwardTicks() + settings.returnDelayTicks();
        if (elapsed <= settings.outwardTicks()) {
            twinHitScythePoint(body, lerp(body.skillOrigin, body.skillEnd,
                    Math.min(1.0, elapsed / (double) settings.outwardTicks())), false);
            return;
        }
        if (elapsed < returnStart) return;
        if (!body.replayed) {
            body.replayed = true;
            if (body.model != null) body.model.playOnce("scythe_recall", "final_stance");
        }
        long returned = elapsed - returnStart;
        if (returned <= settings.returnTicks()) {
            twinHitScythePoint(body, lerp(body.skillEnd, body.skillOrigin,
                    Math.min(1.0, returned / (double) settings.returnTicks())), true);
        } else finishTwinSkill(body);
    }

    private void twinHitScythePoint(TwinBody body, Location point, boolean returning) {
        EndsunGuardSettings.Scythe settings = plugin.endsunGuardSettings().scythe();
        launchSunWheels(point, returning);
        point.getWorld().spawnParticle(Particle.SWEEP_ATTACK, point, 2, 0.4, 0.3, 0.4, 0.0);
        point.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, point, 7, 0.35, 0.35, 0.35, 0.025);
        Set<UUID> hits = returning ? body.returnHits : body.outwardHits;
        double radiusSquared = settings.hitRadius() * settings.hitRadius();
        for (Player player : combatPlayers()) {
            if (hits.contains(player.getUniqueId()) || player.getLocation().distanceSquared(point) > radiusSquared) continue;
            hits.add(player.getUniqueId());
            damage(player, returning ? settings.returnDamage() : settings.outwardDamage());
            if (returning) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        (int) settings.soulFractureTicks(), 0, false, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                        (int) settings.soulFractureTicks(), 0, false, true, true));
            } else {
                Vector push = player.getLocation().toVector().subtract(point.toVector()).setY(0);
                if (push.lengthSquared() < 0.01) push = body.entity.getLocation().getDirection().multiply(-1);
                player.setVelocity(push.normalize().multiply(settings.knockback()).setY(0.35));
            }
        }
    }

    private void tickTwinJudgement(TwinBody body, long elapsed) {
        EndsunGuardSettings.Judgement settings = plugin.endsunGuardSettings().judgement();
        Player target = body.skillTargetId == null ? null : Bukkit.getPlayer(body.skillTargetId);
        if (!validTarget(target, settings.shadowRadius() + plugin.endsunGuardSettings().followRange())) {
            finishTwinSkill(body);
            return;
        }
        Location center = target.getLocation().clone();
        if (elapsed % 3L == 0L) drawMagicCircle(center, settings.shadowRadius(),
                body.type == TwinType.SUN ? Color.fromRGB(255, 160, 35) : Color.fromRGB(190, 55, 255),
                -age * 0.13);
        for (double y = 0.2; y <= 12.0; y += 1.2) {
            ParticleCompat.spawnDust(center.getWorld(), center.clone().add(0, y, 0), 1,
                    0.08, 0.08, 0.08, 0.0, Color.fromRGB(180, 45, 255), 1.2f);
        }
        showShadowZones(target, settings.shadowRadius());
        if (elapsed < settings.telegraphTicks()) return;
        boolean protectedByShadow = combatPlayers().stream().anyMatch(player -> player != target
                && player.isSneaking()
                && player.getLocation().distanceSquared(target.getLocation())
                <= settings.shadowRadius() * settings.shadowRadius());
        if (protectedByShadow) reduceEclipse(target, plugin.endsunGuardSettings().eclipse().counterReduction());
        else {
            double ratio = eclipseValues.getOrDefault(target.getUniqueId(), 0.0)
                    / plugin.endsunGuardSettings().eclipse().maximum();
            damage(target, settings.damage() * (1.0 + ratio * settings.maximumEclipseBonus()));
            target.getWorld().strikeLightningEffect(target.getLocation());
        }
        finishTwinSkill(body);
    }

    private void tickTwinShield(TwinBody body, long elapsed) {
        EndsunGuardSettings.Shield settings = plugin.endsunGuardSettings().shield();
        if (elapsed % 3L == 0L) drawMagicCircle(body.entity.getLocation(), 2.8,
                Color.fromRGB(255, 185, 45), -age * 0.14);
        ParticleCompat.spawnDust(body.entity.getWorld(), body.entity.getLocation().add(0, 1.2, 0),
                7, 0.8, 1.1, 0.8, 0.02, Color.fromRGB(255, 180, 35), 1.2f);
        if (elapsed >= settings.durationTicks() || body.shieldEnergy <= 0.0) finishTwinSkill(body);
    }

    private void tickTwinSoulDrain(TwinBody body, long elapsed) {
        EndsunGuardSettings.Furnace settings = plugin.endsunGuardSettings().furnace();
        Location heart = body.entity.getLocation().clone().add(0, 1.25, 0);
        if (elapsed % 3L == 0L) drawMagicCircle(body.entity.getLocation(), 3.4,
                Color.fromRGB(120, 30, 170), age * 0.11);
        for (UUID id : new ArrayList<>(soulReflections)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Vex vex) || !vex.isValid()) {
                soulReflections.remove(id);
                continue;
            }
            Vector pull = heart.toVector().subtract(vex.getLocation().toVector());
            if (pull.lengthSquared() > 0.01) vex.teleport(vex.getLocation().add(pull.normalize().multiply(0.22)));
            vex.getWorld().spawnParticle(Particle.SOUL, vex.getLocation(), 3, 0.15, 0.15, 0.15, 0.01);
        }
        if (elapsed < settings.drainChannelTicks()) return;
        int consumed = 0;
        for (UUID id : new ArrayList<>(soulReflections)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()
                    || entity.getLocation().distanceSquared(body.entity.getLocation())
                    > settings.consumeRange() * settings.consumeRange()) continue;
            destroySoul(id, false);
            consumed++;
        }
        if (consumed > 0) {
            furnaceDamageBonus = Math.min(settings.maximumDamageIncrease(),
                    furnaceDamageBonus + consumed * settings.damageIncreasePerSoul());
            body.shieldEnergy = Math.min(scaledShieldMaximumEnergy(),
                    body.shieldEnergy + consumed * settings.shieldRestorePerSoul());
        }
        finishTwinSkill(body);
    }

    private void tickTwinSandFuneral(TwinBody body, long elapsed) {
        EndsunGuardSettings.SandFuneral settings = plugin.endsunGuardSettings().sandFuneral();
        double progress = Math.min(1.0, elapsed / (double) settings.durationTicks());
        double radius = settings.startRadius() + (settings.endRadius() - settings.startRadius()) * progress;
        body.sandGapAngle = normalizeAngle(body.sandGapAngle + 0.012 + progress * 0.018);
        Location center = body.skillOrigin == null ? body.entity.getLocation() : body.skillOrigin;
        center = center.clone();
        int points = Math.max(40, (int) Math.round(radius * 5.0));
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0 * index / points;
            if (angleDistance(angle, body.sandGapAngle) <= settings.gapWidthRadians() * 0.5) continue;
            Location point = center.clone().add(Math.cos(angle) * radius, 0.4, Math.sin(angle) * radius);
            ParticleCompat.spawnDust(point.getWorld(), point, 1,
                    0.15, 0.8, 0.15, 0.0, Color.fromRGB(196, 142, 69), 1.55f);
        }
        for (Player player : combatPlayers()) {
            double distance = Math.sqrt(horizontalDistanceSquared(player.getLocation(), center));
            double angle = Math.atan2(player.getZ() - center.getZ(), player.getX() - center.getX());
            if (Math.abs(distance - radius) > settings.hitRadius()
                    || angleDistance(angle, body.sandGapAngle) <= settings.gapWidthRadians() * 0.5
                    || age < body.nextSandHit.getOrDefault(player.getUniqueId(), 0L)) continue;
            body.nextSandHit.put(player.getUniqueId(), age + settings.damageIntervalTicks());
            int stacks = Math.min(settings.maximumSuffocation(),
                    body.suffocationStacks.getOrDefault(player.getUniqueId(), 0) + settings.suffocationPerHit());
            body.suffocationStacks.put(player.getUniqueId(), stacks);
            damage(player, settings.damage() * (1.0 + Math.max(0, stacks - 1) * 0.12));
            player.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS, 55, 0, true, false, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 35,
                    Math.min(3, stacks / 3), true, false, true));
        }
        if (elapsed >= settings.durationTicks()) finishTwinSkill(body);
    }

    private void tickTwinScytheEcho(TwinBody body, long elapsed) {
        EndsunGuardSettings.ScytheEcho settings = plugin.endsunGuardSettings().scytheEcho();
        Player target = body.skillTargetId == null ? null : Bukkit.getPlayer(body.skillTargetId);
        long nextTeleport = 4L + body.echoTeleports * settings.teleportIntervalTicks();
        if (body.echoTeleports < settings.teleportCount() && elapsed >= nextTeleport) {
            Location origin = body.entity.getLocation().clone();
            Vector direction = target == null ? origin.getDirection().setY(0)
                    : target.getLocation().toVector().subtract(origin.toVector()).setY(0);
            if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
            direction.normalize();
            body.skillLocations.add(origin);
            body.skillDirections.add(direction.clone());
            double angle = Math.PI * 2.0 * body.echoTeleports / Math.max(1, settings.teleportCount())
                    + age * 0.07 + (body.type == TwinType.SUN ? 0.0 : Math.PI);
            Location destination = (target == null ? origin : target.getLocation()).clone()
                    .add(Math.cos(angle) * 5.0, 0.15, Math.sin(angle) * 5.0);
            destination.setDirection((target == null ? battlefieldCenter : target.getLocation())
                    .toVector().subtract(destination.toVector()).setY(0));
            body.entity.teleport(destination);
            body.entity.getWorld().spawnParticle(Particle.REVERSE_PORTAL, origin.add(0, 1.0, 0),
                    35, 0.55, 0.9, 0.55, 0.08);
            body.entity.getWorld().playSound(origin, Sound.ENTITY_ENDERMAN_TELEPORT, 1.8f,
                    0.65f + body.echoTeleports * 0.12f);
            body.echoTeleports++;
        }
        long replayAt = 4L + settings.teleportCount() * settings.teleportIntervalTicks()
                + settings.replayDelayTicks();
        if (!body.replayed && elapsed >= replayAt) {
            body.replayed = true;
            if (body.model != null) body.model.playOnce("scythe_slash", "final_stance");
            replayEchoSlash(body.entity.getLocation(), body.entity.getLocation().getDirection(), settings);
            for (int index = 0; index < body.skillLocations.size(); index++) {
                replayEchoSlash(body.skillLocations.get(index), body.skillDirections.get(index), settings);
            }
        }
        if (elapsed >= replayAt + 12L) finishTwinSkill(body);
    }

    private void tickTwinSunfall(TwinBody body, long elapsed) {
        EndsunGuardSettings.Sunfall settings = plugin.endsunGuardSettings().sunfall();
        for (Location target : body.skillLocations) {
            drawRing(target, 2.3, Particle.FLAME);
            ParticleCompat.spawnDust(target.getWorld(), target.clone().add(0, 0.15, 0),
                    8, 0.9, 0.02, 0.9, 0.0, Color.fromRGB(255, 178, 35), 1.4f);
        }
        if (!body.replayed && elapsed >= settings.telegraphTicks()) {
            body.replayed = true;
            for (Location target : body.skillLocations) spawnSunWheel(target, settings);
            body.entity.getWorld().playSound(body.entity.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 2.4f, 0.55f);
        }
        if (elapsed >= settings.telegraphTicks() + 12L) finishTwinSkill(body);
    }

    private void tickTwinEdict(TwinBody body, long elapsed) {
        EndsunGuardSettings.Edict settings = plugin.endsunGuardSettings().edict();
        Color color = edictColor(body.pendingEdict);
        if (elapsed % 2L == 0L) {
            drawMagicCircle(body.entity.getLocation(), 4.0, color, age * 0.16);
            ParticleCompat.spawnDust(body.entity.getWorld(), body.entity.getLocation().add(0, 1.7, 0),
                    14, 0.28, 0.28, 0.28, 0.0, color, 1.7f);
        }
        if (elapsed < settings.telegraphTicks()) return;
        activeEdict = body.pendingEdict;
        edictEndsAt = age + settings.durationTicks();
        edictCounters.clear();
        edictCooldowns.clear();
        edictLastLocations.clear();
        announce("boss.endsun-guard.edict-active", "edict", edictName(activeEdict));
        finishTwinSkill(body);
    }

    private void finishTwinSkill(TwinBody body) {
        if (body == null || body.entity == null || body.defeated) return;
        body.activeSkill = Skill.NONE;
        body.skillStartedAt = 0L;
        body.skillTargetId = null;
        body.skillOrigin = null;
        body.skillEnd = null;
        body.skillLocations.clear();
        body.skillDirections.clear();
        body.nextSkillAt = age + 14L + (body.type == TwinType.SUN ? 0L : 7L);
        body.entity.setAI(true);
        acquireTwinTarget(body);
        if (body.model != null) body.model.playLoop("final_stance");
    }

    private void updateTwinBossBar(TwinBody body) {
        if (body == null || body.healthBar == null || body.entity == null || body.defeated) return;
        double maximum = Math.max(1.0, body.maximumHealth);
        double current = Math.max(0.0, Math.min(maximum, body.healthRemaining));
        String key = body.type == TwinType.SUN
                ? "boss.endsun-guard.twins-sun-bossbar" : "boss.endsun-guard.twins-night-bossbar";
        body.healthBar.setProgress(current / maximum);
        body.healthBar.setTitle(legacyString(plugin.messages().raw(key,
                "health", Math.round(current), "max", Math.round(maximum))));
        Set<UUID> viewers = new HashSet<>();
        for (Player player : combatPlayers()) {
            viewers.add(player.getUniqueId());
            body.healthBar.addPlayer(player);
        }
        for (Player player : new ArrayList<>(body.healthBar.getPlayers())) {
            if (!viewers.contains(player.getUniqueId())) body.healthBar.removePlayer(player);
        }
    }

    private void synchronizeTwinEntityHealth(TwinBody body) {
        if (body == null || body.defeated || body.entity == null || !body.entity.isValid()) return;
        AttributeInstance maximum = body.entity.getAttribute(Attribute.MAX_HEALTH);
        if (maximum != null && Math.abs(maximum.getBaseValue() - body.maximumHealth) > 0.0001) {
            maximum.setBaseValue(body.maximumHealth);
        }
        body.healthRemaining = sanitizeTwinHealth(body.maximumHealth, body.healthRemaining);
        double carrierMaximum = maximum == null ? body.maximumHealth : maximum.getValue();
        double carrierHealth = Math.min(body.healthRemaining, Math.max(0.01, carrierMaximum));
        if (Math.abs(body.entity.getHealth() - carrierHealth) > 0.0001) {
            body.entity.setHealth(carrierHealth);
        }
    }

    private double sanitizeTwinHealth(double maximum, double health) {
        double safeMaximum = Double.isFinite(maximum) ? Math.max(0.01, maximum) : 0.01;
        if (!Double.isFinite(health)) return 0.01;
        return Math.max(0.01, Math.min(safeMaximum, health));
    }

    private void clearTwinIncompatibleMechanics() {
        crownEndsAt = 0L;
        solarFlames.clear();
        shadowBarriers.clear();
        removeTotems();
        removeEchoClones();
        removeSunWheels();
        sunfallTargets.clear();
        boundaryCenter = null;
        boundaryNormal = null;
        boundaryExpiresAt = 0L;
        boundarySides.clear();
        boundaryLastLocations.clear();
        boundaryReturns.clear();
        activeEdict = null;
        pendingEdict = null;
        edictEndsAt = 0L;
        edictCounters.clear();
        edictCooldowns.clear();
        edictLastLocations.clear();
    }

    private void acquireTwinTarget(TwinBody body) {
        Player nearest = combatPlayers().stream().min(Comparator.comparingDouble(player ->
                player.getLocation().distanceSquared(body.entity.getLocation()))).orElse(null);
        body.entity.setTarget(nearest);
    }

    private void beginScytheReturn(Player target) {
        EndsunGuardSettings.Scythe settings = plugin.endsunGuardSettings().scythe();
        Vector direction = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
        if (direction.lengthSquared() < 0.01) direction = controller.getLocation().getDirection().setY(0);
        direction.normalize();
        scytheStart = controller.getLocation().clone().add(0, 1.2, 0);
        scytheEnd = scytheStart.clone().add(direction.multiply(settings.range()));
        outwardHits.clear();
        returnHits.clear();
        recallAnimationPlayed = false;
        beginSkill(Skill.SCYTHE_RETURN, "scythe_slash");
        cooldowns.put(Skill.SCYTHE_RETURN, age + adjustedCooldown(settings.cooldownTicks()));
        announce("boss.endsun-guard.scythe-return");
    }

    private void tickScytheReturn(long elapsed) {
        EndsunGuardSettings.Scythe settings = plugin.endsunGuardSettings().scythe();
        if (elapsed % 4L == 0L) drawMagicCircle(controller.getLocation(), 2.4,
                Color.fromRGB(125, 35, 190), age * 0.16);
        long returnStart = settings.outwardTicks() + settings.returnDelayTicks();
        if (elapsed <= settings.outwardTicks()) {
            double progress = Math.min(1.0, elapsed / (double) settings.outwardTicks());
            hitScythePoint(lerp(scytheStart, scytheEnd, progress), false);
            return;
        }
        if (elapsed < returnStart) return;
        if (!recallAnimationPlayed) {
            recallAnimationPlayed = true;
            if (model != null) model.playOnce("scythe_recall", idleAnimation());
        }
        long returnElapsed = elapsed - returnStart;
        if (returnElapsed <= settings.returnTicks()) {
            double progress = Math.min(1.0, returnElapsed / (double) settings.returnTicks());
            hitScythePoint(lerp(scytheEnd, scytheStart, progress), true);
        } else finishSkill();
    }

    private void hitScythePoint(Location point, boolean returning) {
        EndsunGuardSettings.Scythe settings = plugin.endsunGuardSettings().scythe();
        launchSunWheels(point, returning);
        controller.getWorld().spawnParticle(Particle.SWEEP_ATTACK, point, 2, 0.4, 0.3, 0.4, 0.0);
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, point, 7, 0.35, 0.35, 0.35, 0.025);
        double radiusSquared = settings.hitRadius() * settings.hitRadius();
        Set<UUID> hits = returning ? returnHits : outwardHits;
        for (Player player : combatPlayers()) {
            if (hits.contains(player.getUniqueId()) || player.getLocation().distanceSquared(point) > radiusSquared) continue;
            hits.add(player.getUniqueId());
            damage(player, returning ? settings.returnDamage() : settings.outwardDamage());
            if (returning) {
                player.addPotionEffect(new PotionEffect(PotionEffectType.WITHER,
                        (int) settings.soulFractureTicks(), 0, false, true, true));
                player.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,
                        (int) settings.soulFractureTicks(), 0, false, true, true));
                player.sendMessage(plugin.messages().component("boss.endsun-guard.soul-fracture"));
            } else {
                Vector push = player.getLocation().toVector().subtract(point.toVector()).setY(0);
                if (push.lengthSquared() < 0.01) push = controller.getLocation().getDirection().multiply(-1);
                player.setVelocity(push.normalize().multiply(settings.knockback()).setY(0.35));
            }
        }
        for (UUID soulId : new ArrayList<>(soulReflections)) {
            Entity entity = Bukkit.getEntity(soulId);
            if (entity != null && entity.isValid() && entity.getLocation().distanceSquared(point) <= radiusSquared) {
                destroySoul(soulId, true);
            }
        }
    }

    private void beginJudgement() {
        Player target = combatPlayers().stream()
                .max(Comparator.comparingDouble(player -> eclipseValues.getOrDefault(player.getUniqueId(), 0.0)))
                .orElse(targetPlayer());
        if (target == null) return;
        judgementTarget = target.getUniqueId();
        beginSkill(Skill.JUDGEMENT, "amethyst_judgement");
        cooldowns.put(Skill.JUDGEMENT, age + adjustedCooldown(plugin.endsunGuardSettings().judgement().cooldownTicks()));
        announce("boss.endsun-guard.judgement-lock", "player", target.getName());
    }

    private void tickJudgement(long elapsed) {
        EndsunGuardSettings.Judgement settings = plugin.endsunGuardSettings().judgement();
        Player target = judgementTarget == null ? null : Bukkit.getPlayer(judgementTarget);
        if (!validTarget(target, settings.shadowRadius() + plugin.endsunGuardSettings().followRange())) {
            finishSkill();
            return;
        }
        Location center = target.getLocation().clone();
        if (elapsed % 3L == 0L) drawMagicCircle(center, settings.shadowRadius(),
                Color.fromRGB(190, 55, 255), -age * 0.13);
        for (double y = 0.2; y <= 12.0; y += 1.2) {
            ParticleCompat.spawnDust(controller.getWorld(), center.clone().add(0, y, 0), 1,
                    0.08, 0.08, 0.08, 0.0, Color.fromRGB(180, 45, 255), 1.2f);
        }
        showShadowZones(target, settings.shadowRadius());
        if (elapsed < settings.telegraphTicks()) return;
        boolean protectedByShadow = combatPlayers().stream().anyMatch(player -> player != target
                && player.isSneaking()
                && player.getLocation().distanceSquared(target.getLocation()) <= settings.shadowRadius() * settings.shadowRadius());
        if (protectedByShadow) {
            target.sendMessage(plugin.messages().component("boss.endsun-guard.judgement-avoided"));
            reduceEclipse(target, plugin.endsunGuardSettings().eclipse().counterReduction());
        } else {
            double value = eclipseValues.getOrDefault(target.getUniqueId(), 0.0);
            double ratio = value / plugin.endsunGuardSettings().eclipse().maximum();
            damage(target, settings.damage() * (1.0 + ratio * settings.maximumEclipseBonus()));
            target.getWorld().strikeLightningEffect(target.getLocation());
        }
        finishSkill();
    }

    private void showShadowZones(Player target, double radius) {
        for (Player teammate : combatPlayers()) {
            if (teammate == target || !teammate.isSneaking()) continue;
            for (int index = 0; index < 16; index++) {
                double angle = Math.PI * 2.0 * index / 16.0;
                Location point = teammate.getLocation().clone().add(Math.cos(angle) * radius, 0.15, Math.sin(angle) * radius);
                teammate.getWorld().spawnParticle(Particle.ASH, point, 1, 0, 0, 0, 0);
            }
        }
    }

    private void beginCrown() {
        EndsunGuardSettings.Crown settings = plugin.endsunGuardSettings().crown();
        crownEndsAt = age + settings.durationTicks();
        spawnSolarFlames(settings);
        spawnTotems(settings);
        beginSkill(Skill.CROWN_CAST, "crown_ignite");
        cooldowns.put(Skill.CROWN_CAST, age + adjustedCooldown(settings.cooldownTicks()));
        announce("boss.endsun-guard.crown-start");
    }

    private void spawnSolarFlames(EndsunGuardSettings.Crown settings) {
        solarFlames.clear();
        for (int index = 0; index < settings.flameCount(); index++) {
            double angle = Math.PI * 2.0 * index / settings.flameCount();
            Location location = battlefieldCenter.clone().add(Math.cos(angle) * 7.0, 0.6, Math.sin(angle) * 7.0);
            Vector velocity = new Vector(-Math.sin(angle), 0, Math.cos(angle)).multiply(settings.flameSpeed());
            solarFlames.add(new SolarFlame(location, velocity));
        }
    }

    private void spawnTotems(EndsunGuardSettings.Crown settings) {
        removeTotems();
        for (int index = 0; index < settings.totemCount(); index++) {
            double angle = Math.PI * 2.0 * index / settings.totemCount();
            Location location = battlefieldCenter.clone().add(Math.cos(angle) * 9.0, 0.0, Math.sin(angle) * 9.0);
            ArmorStand stand = controller.getWorld().spawn(location, ArmorStand.class, entity -> {
                entity.setInvisible(true);
                entity.setGravity(false);
                entity.setBasePlate(false);
                entity.setArms(false);
                entity.setPersistent(true);
                entity.setRemoveWhenFarAway(false);
                entity.customName(plugin.messages().component("boss.endsun-guard.totem-name"));
                entity.setCustomNameVisible(true);
                entity.getEquipment().setHelmet(new ItemStack(Material.CRYING_OBSIDIAN));
                entity.getPersistentDataContainer().set(plugin.bossTypeKey(), PersistentDataType.STRING,
                        EndsunGuardManager.TOTEM_TYPE_ID);
            });
            totemHits.put(stand.getUniqueId(), settings.totemHits());
        }
    }

    private void tickCrown() {
        if (crownEndsAt <= 0L) return;
        if (age >= crownEndsAt) {
            crownEndsAt = 0L;
            solarFlames.clear();
            removeTotems();
            return;
        }
        EndsunGuardSettings.Crown settings = plugin.endsunGuardSettings().crown();
        if (age % 6L == 0L) drawMagicCircle(battlefieldCenter, 6.5,
                Color.fromRGB(255, 120, 20), age * 0.075);
        double maximumRadiusSquared = Math.pow(plugin.endsunGuardSettings().bossbarRange() * 0.55, 2);
        for (SolarFlame flame : solarFlames) {
            Location next = flame.location.clone().add(flame.velocity);
            if (next.distanceSquared(battlefieldCenter) > maximumRadiusSquared || next.getBlock().getType().isSolid()) {
                flame.velocity.multiply(-1.0);
                next = flame.location.clone().add(flame.velocity);
            }
            flame.location = next;
            flame.location.getWorld().spawnParticle(Particle.FLAME, flame.location, 6, 0.35, 0.3, 0.35, 0.025);
            ParticleCompat.spawnDust(flame.location.getWorld(), flame.location, 2,
                    0.15, 0.15, 0.15, 0.0, Color.fromRGB(255, 120, 20), 1.3f);
            for (Player player : combatPlayers()) {
                if (player.getLocation().distanceSquared(flame.location) > settings.flameHitRadius() * settings.flameHitRadius()
                        || age < flame.nextHit.getOrDefault(player.getUniqueId(), 0L)) continue;
                flame.nextHit.put(player.getUniqueId(), age + 20L);
                damage(player, settings.flameDamage());
                player.setFireTicks(Math.max(player.getFireTicks(), settings.fireTicks()));
            }
        }
        for (UUID id : new ArrayList<>(totemHits.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()) {
                totemHits.remove(id);
                continue;
            }
            entity.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                    entity.getLocation().add(0, 1.4, 0), 4, 0.25, 0.35, 0.25, 0.02);
        }
    }

    private void beginShield() {
        EndsunGuardSettings.Shield settings = plugin.endsunGuardSettings().shield();
        shieldEnergy = Math.max(shieldEnergy, scaledShieldMaximumEnergy() * settings.minimumCastEnergyRatio());
        beginSkillHold(Skill.SHIELD, "solar_shield");
        cooldowns.put(Skill.SHIELD, age + adjustedCooldown(settings.cooldownTicks()));
        announce("boss.endsun-guard.shield-start");
    }

    private void tickShield(long elapsed) {
        EndsunGuardSettings.Shield settings = plugin.endsunGuardSettings().shield();
        if (elapsed % 3L == 0L) drawMagicCircle(controller.getLocation(), 2.8,
                Color.fromRGB(255, 185, 45), -age * 0.14);
        ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1.2, 0),
                7, 0.8, 1.1, 0.8, 0.02, Color.fromRGB(255, 180, 35), 1.2f);
        if (elapsed >= settings.durationTicks() || shieldEnergy <= 0.0) finishSkill();
    }

    private void beginSoulDrain() {
        beginSkill(Skill.SOUL_DRAIN, "soul_drain");
        cooldowns.put(Skill.SOUL_DRAIN, age + adjustedCooldown(plugin.endsunGuardSettings().furnace().drainCooldownTicks()));
        announce("boss.endsun-guard.soul-drain");
    }

    private void tickSoulDrain(long elapsed) {
        EndsunGuardSettings.Furnace settings = plugin.endsunGuardSettings().furnace();
        Location heart = controller.getLocation().clone().add(0, 1.25, 0);
        if (elapsed % 3L == 0L) drawMagicCircle(controller.getLocation(), 3.4,
                Color.fromRGB(120, 30, 170), age * 0.11);
        for (UUID id : new ArrayList<>(soulReflections)) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Vex vex) || !vex.isValid()) {
                soulReflections.remove(id);
                continue;
            }
            Vector pull = heart.toVector().subtract(vex.getLocation().toVector());
            if (pull.lengthSquared() > 0.01) vex.teleport(vex.getLocation().add(pull.normalize().multiply(0.22)));
            vex.getWorld().spawnParticle(Particle.SOUL, vex.getLocation(), 3, 0.15, 0.15, 0.15, 0.01);
        }
        if (elapsed < settings.drainChannelTicks()) return;
        int consumed = 0;
        for (UUID id : new ArrayList<>(soulReflections)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity == null || !entity.isValid()
                    || entity.getLocation().distanceSquared(controller.getLocation()) > settings.consumeRange() * settings.consumeRange()) continue;
            destroySoul(id, false);
            consumed++;
        }
        if (consumed > 0) {
            furnaceDamageBonus = Math.min(settings.maximumDamageIncrease(),
                    furnaceDamageBonus + consumed * settings.damageIncreasePerSoul());
            shieldEnergy = Math.min(scaledShieldMaximumEnergy(),
                    shieldEnergy + consumed * settings.shieldRestorePerSoul());
            announce("boss.endsun-guard.soul-consumed", "count", consumed,
                    "bonus", Math.round(furnaceDamageBonus * 100.0));
        }
        finishSkill();
    }

    private void spawnSoulReflections() {
        EndsunGuardSettings.Furnace settings = plugin.endsunGuardSettings().furnace();
        nextReflectionAt = age + settings.reflectionSpawnIntervalTicks();
        for (Player player : combatPlayers()) {
            if (soulReflections.size() >= settings.maximumReflections()) break;
            Location location = player.getLocation().clone().add((Math.random() - 0.5) * 3.0, 1.0,
                    (Math.random() - 0.5) * 3.0);
            Vex vex = controller.getWorld().spawn(location, Vex.class, entity -> {
                entity.setAI(false);
                entity.setSilent(true);
                entity.setPersistent(true);
                entity.setRemoveWhenFarAway(false);
                entity.setGlowing(true);
                entity.customName(plugin.messages().component("boss.endsun-guard.reflection-name", "player", player.getName()));
                entity.setCustomNameVisible(true);
                AttributeInstance maximum = entity.getAttribute(Attribute.MAX_HEALTH);
                if (maximum != null) maximum.setBaseValue(settings.reflectionHealth());
                entity.setHealth(settings.reflectionHealth());
                entity.getPersistentDataContainer().set(plugin.bossTypeKey(), PersistentDataType.STRING,
                        EndsunGuardManager.SOUL_TYPE_ID);
            });
            soulReflections.add(vex.getUniqueId());
        }
        if (!soulReflections.isEmpty()) announce("boss.endsun-guard.reflections-spawned");
    }

    private void tickSoulReflections() {
        if (phase == Phase.P1) return;
        Iterator<UUID> iterator = soulReflections.iterator();
        while (iterator.hasNext()) {
            Entity entity = Bukkit.getEntity(iterator.next());
            if (entity == null || !entity.isValid()) iterator.remove();
            else if (age % 8L == 0L) entity.getWorld().spawnParticle(Particle.SOUL, entity.getLocation(),
                    2, 0.2, 0.25, 0.2, 0.01);
        }
        if (age % 20L == 0L && phase != Phase.P1) {
            controller.getWorld().spawnParticle(Particle.ASH, controller.getLocation(), 6,
                    5.0, 1.5, 5.0, 0.01);
        }
    }

    private void beginEclipseTransform() {
        eclipsePending = false;
        eclipseValues.replaceAll((id, value) -> 0.0);
        eclipseFormUntil = age + plugin.endsunGuardSettings().eclipse().formDurationTicks();
        applyMovementSpeed();
        beginSkill(Skill.ECLIPSE_TRANSFORM, "eclipse_transform");
        announce("boss.endsun-guard.eclipse-form");
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_SPAWN, 3.0f, 0.55f);
    }

    private void beginBlackSun() {
        ultimateUsed = true;
        // Black Sun is a protected ultimate channel. Hits during it damage the
        // ritual furnace, but must never contribute to the hit-count reactive teleport.
        reactiveHitCount = 0;
        eclipseFormUntil = Math.max(eclipseFormUntil,
                age + plugin.endsunGuardSettings().ultimate().durationTicks());
        ultimateFurnaceHealth = ultimateFurnaceMaximumHealth();
        shadowBarriers.clear();
        teamBarrierActive = false;
        teamFragmentProgress = 0;
        dropShieldFragments();
        skill = Skill.BLACK_SUN;
        skillStartedAt = age;
        if (model != null) model.playLoop("black_sun_ritual");
        announce("boss.endsun-guard.black-sun-start");
    }

    private void tickBlackSun(long elapsed) {
        EndsunGuardSettings.Ultimate settings = plugin.endsunGuardSettings().ultimate();
        double progress = Math.min(1.0, elapsed / (double) settings.durationTicks());
        double safeRadius = settings.startRadius() + (settings.endRadius() - settings.startRadius()) * progress;
        Location sun = battlefieldCenter.clone().add(0, 14.0, 0);
        controller.getWorld().spawnParticle(Particle.LARGE_SMOKE, sun, 25, 2.1, 2.1, 2.1, 0.03);
        ParticleCompat.spawnDust(controller.getWorld(), sun, 18,
                1.8, 1.8, 1.8, 0.02, Color.fromRGB(20, 10, 25), 3.2f);
        if (elapsed % 4L == 0L) drawRing(battlefieldCenter, safeRadius, Particle.SOUL_FIRE_FLAME);
        if (elapsed % 4L == 0L) drawMagicCircle(battlefieldCenter, Math.max(4.5, safeRadius * 0.32),
                Color.fromRGB(105, 30, 135), -age * 0.08);
        for (Location barrier : shadowBarriers) drawRing(barrier, settings.barrierRadius(), Particle.ASH);
        if (elapsed % 20L == 0L) {
            for (Player player : combatPlayers()) {
                if (horizontalDistanceSquared(player.getLocation(), battlefieldCenter) > safeRadius * safeRadius) {
                    damage(player, settings.collapseDamage());
                }
            }
            controller.getWorld().playSound(battlefieldCenter, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 2.4f,
                    (float) (0.55 + progress * 0.6));
        }
        if (elapsed < settings.durationTicks()) return;
        if (ultimateFurnaceHealth > 0.0) {
            for (Player player : combatPlayers()) {
                if (teamBarrierActive || insideBarrier(player.getLocation())) {
                    player.sendMessage(plugin.messages().component("boss.endsun-guard.black-sun-protected"));
                } else {
                    // Black Sun is the sole execution mechanic: failing the barrier check
                    // directly clears health and therefore bypasses armor and absorption.
                    player.setHealth(0.0);
                    player.sendMessage(plugin.messages().component(player.isDead()
                            ? "boss.endsun-guard.black-sun-executed"
                            : "boss.endsun-guard.black-sun-survived"));
                }
            }
        }
        finishBlackSun(false);
    }

    private void dropShieldFragments() {
        EndsunGuardSettings.Ultimate settings = plugin.endsunGuardSettings().ultimate();
        for (int index = 0; index < settings.fragmentCount(); index++) {
            double angle = Math.PI * 2.0 * index / settings.fragmentCount();
            Location location = battlefieldCenter.clone().add(Math.cos(angle) * 7.0, 1.0, Math.sin(angle) * 7.0);
            Item item = controller.getWorld().dropItem(location, shieldFragment());
            item.setVelocity(new Vector(Math.cos(angle) * 0.18, 0.35, Math.sin(angle) * 0.18));
            item.setPickupDelay(10);
            item.setUnlimitedLifetime(true);
            item.setGlowing(true);
            fragmentEntities.add(item.getUniqueId());
        }
    }

    private ItemStack shieldFragment() {
        ItemStack stack = new ItemStack(Material.ECHO_SHARD);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(plugin.messages().component("boss.endsun-guard.fragment-name"));
        meta.lore(List.of(plugin.messages().component("boss.endsun-guard.fragment-lore")));
        meta.getPersistentDataContainer().set(plugin.specialItemKey(), PersistentDataType.STRING,
                EndsunGuardManager.FRAGMENT_ITEM_ID);
        stack.setItemMeta(meta);
        return stack;
    }

    public boolean tryBuildBarrier(Player player) {
        if (skill != Skill.BLACK_SUN || player.getWorld() != controller.getWorld()) return false;
        int cost = plugin.endsunGuardSettings().ultimate().fragmentCost();
        int available = countFragments(player);
        if (available <= 0) {
            player.sendMessage(plugin.messages().component("boss.endsun-guard.fragment-not-enough", "cost", cost));
            return true;
        }
        int contributed = Math.min(available, Math.max(0, cost - teamFragmentProgress));
        removeFragments(player, contributed);
        contributeTeamFragments(player, contributed);
        return true;
    }

    public boolean collectShieldFragment(Player player, Item item) {
        if (skill != Skill.BLACK_SUN || player.getWorld() != controller.getWorld()
                || !fragmentEntities.remove(item.getUniqueId()) || !isFragment(item.getItemStack())) return false;
        int amount = Math.max(1, item.getItemStack().getAmount());
        item.remove();
        contributeTeamFragments(player, amount);
        return true;
    }

    private void contributeTeamFragments(Player player, int amount) {
        if (amount <= 0 || teamBarrierActive) return;
        int cost = plugin.endsunGuardSettings().ultimate().fragmentCost();
        teamFragmentProgress = Math.min(cost, teamFragmentProgress + amount);
        announce("boss.endsun-guard.fragment-progress", "value", teamFragmentProgress, "cost", cost);
        if (teamFragmentProgress < cost) return;
        teamBarrierActive = true;
        shadowBarriers.clear();
        shadowBarriers.add(battlefieldCenter.clone());
        controller.getWorld().playSound(battlefieldCenter, Sound.ITEM_SHIELD_BLOCK, 2.6f, 0.62f);
        announce("boss.endsun-guard.barrier-built", "player", player.getName());
    }

    private int countFragments(Player player) {
        int amount = 0;
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isFragment(stack)) amount += stack.getAmount();
        }
        return amount;
    }

    private void removeFragments(Player player, int amount) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (amount <= 0 || !isFragment(stack)) continue;
            int removed = Math.min(amount, stack.getAmount());
            stack.setAmount(stack.getAmount() - removed);
            amount -= removed;
        }
    }

    private boolean isFragment(ItemStack stack) {
        if (stack == null || !stack.hasItemMeta()) return false;
        return EndsunGuardManager.FRAGMENT_ITEM_ID.equals(stack.getItemMeta().getPersistentDataContainer()
                .get(plugin.specialItemKey(), PersistentDataType.STRING));
    }

    private boolean insideBarrier(Location location) {
        double radius = plugin.endsunGuardSettings().ultimate().barrierRadius();
        return shadowBarriers.stream().anyMatch(barrier -> barrier.getWorld() == location.getWorld()
                && barrier.distanceSquared(location) <= radius * radius);
    }

    private void finishBlackSun(boolean interrupted) {
        shadowBarriers.clear();
        teamBarrierActive = false;
        teamFragmentProgress = 0;
        removeFragmentEntities();
        for (Player player : combatPlayers()) removeAllFragments(player);
        eclipseValues.replaceAll((id, value) -> 0.0);
        eclipseFormUntil = 0L;
        applyMovementSpeed();
        if (interrupted) {
            announce("boss.endsun-guard.black-sun-interrupted");
            beginStagger(null);
        } else finishSkill();
    }

    public void handlePlayerFireDamage(Player player, double damage) {
        if (!validTarget(player, plugin.endsunGuardSettings().bossbarRange())) return;
        addEclipse(player, Math.max(0.0, damage) * plugin.endsunGuardSettings().eclipse().fireGainPerDamage());
    }

    /**
     * Runs at LOWEST, before YknRpg's fixed-damage and on-hit listeners. Returning
     * true rejects only a repeated hit from the same player against the same body;
     * different party members remain independent even in the same server tick.
     */
    public boolean rejectRateLimitedPlayerDamage(EntityDamageByEntityEvent event) {
        Map<UUID, Long> gate;
        boolean controllerVictim = event.getEntity().getUniqueId().equals(controller.getUniqueId());
        if (controllerVictim) {
            gate = nextAcceptedPlayerHit;
        } else {
            TwinBody twin = twinBody(event.getEntity().getUniqueId());
            if (twin == null) return false;
            gate = twin.nextAcceptedPlayerHit;
        }
        // A scripted lock is complete immunity: cancel before downstream on-hit
        // plugins can spend procs or cooldowns on a hit that cannot hurt the boss.
        if (controllerVictim && controllerDamageLocked()) return true;
        Player attacker = damagePlayer(event);
        if (attacker == null) return false;
        if (isLinkedPlayerDamage(event, attacker)
                || PlayerDamageEventSupport.isYknRpgSyntheticDamage()) {
            preApprovedPlayerDamageEvents.add(event);
            return false;
        }
        if (event.getDamage() <= 0.0) return false;
        if (!acceptPlayerHit(gate, attacker)) return true;
        preApprovedPlayerDamageEvents.add(event);
        return false;
    }

    public void clearPlayerDamageApproval(EntityDamageEvent event) {
        preApprovedPlayerDamageEvents.remove(event);
    }

    public boolean handleIncomingDamage(EntityDamageEvent event) {
        boolean preApproved = preApprovedPlayerDamageEvents.contains(event);
        if (dying || pendingPhase != null) {
            event.setCancelled(true);
            return true;
        }
        if (phaseHealthLockUntil > 0L || age <= phaseInvulnerableUntil || skill == Skill.PHASE_TRANSITION
                || skill == Skill.ECLIPSE_TRANSFORM || skill == Skill.ENTRANCE
                || skill == Skill.TWIN_ECLIPSE) {
            event.setCancelled(true);
            return true;
        }
        Player attacker = damagePlayer(event);
        if (attacker != null && event.getDamage() > 0.0 && !preApproved
                && !acceptPlayerHit(nextAcceptedPlayerHit, attacker)) {
            event.setCancelled(true);
            return true;
        }
        if (attacker != null) {
            event.setDamage(plugin.slayer().outgoingDamage(attacker, event.getDamage(), executeWindow()));
        }
        double damage = Math.max(0.0, event.getFinalDamage());
        if (attacker != null && damage > 0.0) handleWeaponEdict(attacker);
        if (skill == Skill.BLACK_SUN) {
            event.setCancelled(true);
            if (attacker != null && damage > 0.0) {
                addDamageThreat(attacker, damage);
                ultimateFurnaceHealth = Math.max(0.0, ultimateFurnaceHealth - damage);
                ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1.25, 0),
                        12, 0.4, 0.45, 0.4, 0.02, Color.fromRGB(170, 35, 230), 1.5f);
                attacker.sendActionBar(plugin.messages().component("boss.endsun-guard.furnace-health",
                        "health", Math.round(ultimateFurnaceHealth),
                        "max", Math.round(ultimateFurnaceMaximumHealth())));
                if (ultimateFurnaceHealth <= 0.0) finishBlackSun(true);
            }
            return true;
        }
        if (damage > 0.0 && skill == Skill.SHIELD && attacker != null) {
            double dot = attackerDirectionDot(attacker);
            EndsunGuardSettings.Shield shield = plugin.endsunGuardSettings().shield();
            if (dot >= shield.frontDotThreshold()) {
                event.setCancelled(true);
                shieldEnergy = Math.max(0.0, shieldEnergy - damage);
                addEclipse(attacker, plugin.endsunGuardSettings().eclipse().shieldHitGain());
                controller.getWorld().playSound(controller.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.8f, 0.7f);
                return true;
            }
            if (dot <= shield.rearDotThreshold()) {
                event.setDamage(event.getDamage() * shield.vulnerableMultiplier());
                reduceEclipse(attacker, plugin.endsunGuardSettings().eclipse().counterReduction());
                beginStagger(attacker);
            }
        } else if (skill == Skill.STAGGER) {
            event.setDamage(event.getDamage() * plugin.endsunGuardSettings().shield().vulnerableMultiplier());
        }

        damage = Math.max(0.0, event.getFinalDamage());
        double appliedDamage = Math.min(health(), damage);
        if (appliedDamage > 0.0) {
            addDamageThreat(attacker, appliedDamage);
            playHitFeedback(appliedDamage);
            playHurtReaction(attacker);
        }
        if (consumePhaseBreakingHit(event, damage)) return true;
        if (attacker != null && appliedDamage > 0.0) registerReactiveHit(attacker);
        phaseHealthRemaining = Math.max(0.0, health() - appliedDamage);
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

        // Stage pools are independent.  Lock the outgoing stage at one health and
        // consume the entire Bukkit damage event before refilling the next stage.
        event.setCancelled(true);
        event.setDamage(0.0);
        controller.setNoDamageTicks(controller.getMaximumNoDamageTicks());
        controller.setInvulnerable(true);
        phaseHealthRemaining = Math.min(1.0, phaseHealth());
        controller.setHealth(Math.min(1.0, phaseHealth()));

        if (phase == Phase.P1) queuePhaseTransition(Phase.P2);
        else if (phase == Phase.P2) queuePhaseTransition(Phase.P3);
        else startDeathSequence();
        return true;
    }

    private void queuePhaseTransition(Phase next) {
        if (pendingPhase != null || dying || next == null || next == phase) return;
        pendingPhase = next;
        phaseHealthRemaining = Math.min(1.0, phaseHealth());
        synchronizeControllerHealth();
        controller.setNoDamageTicks(Math.max(20, controller.getMaximumNoDamageTicks()));
        controller.setInvulnerable(true);
        controller.setAI(false);
        controller.setVelocity(new Vector());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (pendingPhase != next || dying || !controller.isValid() || controller.isDead()) return;
            pendingPhase = null;
            if (next == Phase.P2) enterPhaseTwo();
            else if (next == Phase.P3) enterPhaseThree();
        });
    }

    private void playHitFeedback(double damage) {
        if (age < nextHitFeedbackAt) return;
        nextHitFeedbackAt = age + 4L;
        Location impact = controller.getLocation().clone().add(0, 1.25, 0);
        float volume = (float) Math.min(1.8, 0.75 + damage / 35.0);
        controller.getWorld().playSound(impact, Sound.ENTITY_BLAZE_HURT, volume, 0.72f);
        controller.getWorld().playSound(impact, Sound.ENTITY_IRON_GOLEM_DAMAGE, volume * 0.55f, 1.28f);
        controller.getWorld().spawnParticle(Particle.CRIT, impact, 7, 0.45, 0.65, 0.45, 0.08);
        controller.getWorld().spawnParticle(Particle.SMALL_FLAME, impact, 4, 0.35, 0.5, 0.35, 0.025);
    }

    private void playHurtReaction(Player attacker) {
        if (attacker == null || skill != Skill.NONE || dying
                || age <= phaseInvulnerableUntil || phaseHealthLockUntil > 0L
                || age < nextHurtReactionAt) return;
        nextHurtReactionAt = age + hurtAnimationCooldownTicks();
        applyDirectionalHurtRecoil(controller, attacker);
        if (model != null) model.playOnce("hurt", walking ? "walk" : idleAnimation());
    }

    private void playTwinHurtReaction(TwinBody body, Player attacker) {
        if (body == null || body.defeated || attacker == null || body.activeSkill != Skill.NONE
                || age < body.nextHurtReactionAt) return;
        body.nextHurtReactionAt = age + hurtAnimationCooldownTicks();
        applyDirectionalHurtRecoil(body.entity, attacker);
        if (body.model != null) {
            body.model.playOnce("hurt", body.walking ? "walk" : idleAnimation());
        }
    }

    private long hurtAnimationCooldownTicks() {
        double seconds = plugin.getConfig().getDouble(
                "endsun-guard.combat-feedback.hurt-animation-cooldown-seconds", 0.2);
        return Math.max(1L, Math.round(Math.max(0.0, seconds) * 20.0));
    }

    private void applyDirectionalHurtRecoil(Entity entity, Player attacker) {
        Vector away = entity.getLocation().toVector()
                .subtract(attacker.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 0.001) {
            away = entity.getLocation().getDirection().setY(0.0).multiply(-1.0);
        }
        if (away.lengthSquared() < 0.001) return;
        double horizontal = Math.max(0.0, plugin.getConfig().getDouble(
                "endsun-guard.combat-feedback.hurt-knockback-horizontal", 0.16));
        double vertical = Math.max(0.0, plugin.getConfig().getDouble(
                "endsun-guard.combat-feedback.hurt-knockback-vertical", 0.05));
        Vector recoil = away.normalize().multiply(horizontal);
        recoil.setY(Math.max(entity.getVelocity().getY(), vertical));
        entity.setVelocity(recoil);
    }

    private void enterPhaseTwo() {
        reactiveHitCount = 0;
        nextAcceptedPlayerHit.clear();
        long transitionTicks = Math.max(PHASE_TRANSITION_TICKS,
                plugin.endsunGuardSettings().phases().transitionInvulnerabilityTicks());
        phaseInvulnerableUntil = age + transitionTicks;
        phaseHealthLockUntil = phaseInvulnerableUntil;
        controller.setInvulnerable(true);
        phase = Phase.P2;
        persistPhase();
        refillCurrentPhaseHealth();
        bossBar.addFlag(BarFlag.DARKEN_SKY);
        nextReflectionAt = age + plugin.endsunGuardSettings().furnace().reflectionSpawnIntervalTicks();
        beginSkill(Skill.PHASE_TRANSITION, "heart_furnace_open");
        updateBossBar();
        announce("boss.endsun-guard.phase-two");
    }

    private void enterPhaseThree() {
        reactiveHitCount = 0;
        nextAcceptedPlayerHit.clear();
        long transitionTicks = Math.max(PHASE_TRANSITION_TICKS,
                plugin.endsunGuardSettings().phases().transitionInvulnerabilityTicks());
        phaseInvulnerableUntil = age + transitionTicks;
        phaseHealthLockUntil = phaseInvulnerableUntil;
        controller.setInvulnerable(true);
        phase = Phase.P3;
        persistPhase();
        refillCurrentPhaseHealth();
        ultimateUsed = false;
        phaseThreeUltimateAt = age + plugin.endsunGuardSettings().phases().phaseThreeUltimateDelayTicks();
        shieldEnergy = 0.0;
        applyMovementSpeed();
        controller.getWorld().spawnParticle(Particle.ITEM, controller.getLocation().add(0, 1.2, 0),
                24, 1.0, 1.0, 1.0, 0.12, new ItemStack(Material.ECHO_SHARD));
        beginSkill(Skill.PHASE_TRANSITION, "eclipse_transform");
        updateBossBar();
        announce("boss.endsun-guard.phase-three");
    }

    private void beginStagger(Player attacker) {
        skill = Skill.STAGGER;
        skillStartedAt = age;
        controller.setAI(false);
        controller.setVelocity(new Vector());
        if (model != null) model.playOnce("shield_stagger", idleAnimation());
        announce("boss.endsun-guard.shield-broken",
                "player", attacker == null ? "-" : attacker.getName());
    }

    public boolean handleAuxiliaryDamage(EntityDamageEvent event) {
        UUID id = event.getEntity().getUniqueId();
        TwinBody twin = twinBody(id);
        if (twin != null) {
            boolean preApproved = preApprovedPlayerDamageEvents.contains(event);
            double finalDamage = Math.max(0.0, event.getFinalDamage());
            double baseDamage = Math.max(0.0, event.getDamage());
            double amount = finalDamage > 0.0 ? finalDamage : baseDamage;
            Player attacker = damagePlayer(event);
            boolean rangedOrMagic = isRangedOrMagic(event, twin);
            event.setCancelled(true);
            if (amount <= 0.0 || attacker == null) return true;
            if (!preApproved && !acceptPlayerHit(twin.nextAcceptedPlayerHit, attacker)) return true;
            applyTwinDamage(twin, attacker, rangedOrMagic, amount);
            return true;
        }
        if (totemHits.containsKey(id)) {
            event.setCancelled(true);
            Player attacker = damagePlayer(event);
            if (attacker != null) hitTotem(event.getEntity(), attacker);
            return true;
        }
        return soulReflections.contains(id);
    }

    private void applyTwinDamage(TwinBody twin, Player attacker, boolean rangedOrMagic, double rawAmount) {
            double amount = Math.max(0.0,
                    plugin.slayer().outgoingDamage(attacker, rawAmount, executeWindow()));
            boolean allowed = challengeParticipantCount() <= 1
                    || (twin.type == TwinType.SUN ? !rangedOrMagic : rangedOrMagic);
            if (!allowed) {
                attacker.sendActionBar(plugin.messages().component(
                        twin.type == TwinType.SUN ? "boss.endsun-guard.twins-sun-immune"
                                : "boss.endsun-guard.twins-night-immune"));
                twin.entity.getWorld().playSound(twin.entity.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.2f, 1.4f);
                return;
            }
            if (amount <= 0.0) return;
            if (twin.activeSkill == Skill.SHIELD) {
                EndsunGuardSettings.Shield shield = plugin.endsunGuardSettings().shield();
                double dot = attackerDirectionDot(twin.entity, attacker);
                if (dot >= shield.frontDotThreshold()) {
                    twin.shieldEnergy = Math.max(0.0, twin.shieldEnergy - amount);
                    addEclipse(attacker, plugin.endsunGuardSettings().eclipse().shieldHitGain());
                    twin.entity.getWorld().playSound(twin.entity.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.8f, 0.72f);
                    if (twin.shieldEnergy <= 0.0) finishTwinSkill(twin);
                    return;
                }
                if (dot <= shield.rearDotThreshold()) {
                    amount *= shield.vulnerableMultiplier();
                    reduceEclipse(attacker, plugin.endsunGuardSettings().eclipse().counterReduction());
                    twin.activeSkill = Skill.STAGGER;
                    twin.skillStartedAt = age;
                    twin.entity.setAI(false);
                    if (twin.model != null) twin.model.playOnce("shield_stagger", "final_stance");
                }
            } else if (twin.activeSkill == Skill.STAGGER) {
                amount *= plugin.endsunGuardSettings().shield().vulnerableMultiplier();
            }
            double currentHealth = twin.healthRemaining;
            addDamageThreat(attacker, Math.min(currentHealth, amount));
            if (amount >= currentHealth) defeatTwin(twin, attacker);
            else {
                twin.entity.setNoDamageTicks(0);
                twin.healthRemaining = Math.max(0.1, currentHealth - amount);
                synchronizeTwinEntityHealth(twin);
                twin.entity.getWorld().spawnParticle(Particle.CRIT, twin.entity.getLocation().add(0, 1.2, 0),
                        8, 0.45, 0.6, 0.45, 0.08);
                twin.entity.getWorld().playSound(twin.entity.getLocation(), Sound.ENTITY_BLAZE_HURT, 1.1f,
                        twin.type == TwinType.SUN ? 1.25f : 0.72f);
                playTwinHurtReaction(twin, attacker);
                registerTwinReactiveHit(twin, attacker);
            }
            updateTwinBossBar(twin);
    }

    private boolean isRangedOrMagic(EntityDamageEvent event, TwinBody body) {
        if (event instanceof EntityDamageByEntityEvent byEntity) {
            if (byEntity.getDamager() instanceof Projectile) return true;
        }
        String cause = event.getCause().name();
        if (cause.equals("PROJECTILE") || cause.equals("MAGIC") || cause.equals("SONIC_BOOM")) return true;
        if (body.lastHitAt < age - 2L || body.lastHitSourceId == null) return false;
        if (body.lastHitIndirect) return true;
        Entity source = Bukkit.getEntity(body.lastHitSourceId);
        return source instanceof Projectile;
    }

    private TwinBody twinBody(UUID id) {
        if (twinSun != null && twinSun.entity != null && twinSun.entity.getUniqueId().equals(id)) return twinSun;
        if (twinNight != null && twinNight.entity != null && twinNight.entity.getUniqueId().equals(id)) return twinNight;
        return null;
    }

    private void defeatTwin(TwinBody body, Player attacker) {
        if (body.defeated) return;
        body.defeated = true;
        body.healthRemaining = 0.0;
        Location location = body.entity.getLocation();
        location.getWorld().spawnParticle(Particle.SOUL, location.add(0, 1.0, 0), 45,
                0.8, 1.0, 0.8, 0.08);
        location.getWorld().playSound(location, Sound.ENTITY_WITHER_HURT, 2.0f,
                body.type == TwinType.SUN ? 1.35f : 0.65f);
        closeTwinEntity(body);
        announce("boss.endsun-guard.twins-body-defeated", "body",
                twinName(body.type), "player", attacker == null
                        ? plugin.messages().plain("boss.endsun-guard.twins-magic-source") : attacker.getName());
        if (twinSun != null && twinSun.defeated && twinNight != null && twinNight.defeated) {
            finishTwins();
            return;
        }
        twinReviveAt = age + plugin.endsunGuardSettings().twins().revivalWindowTicks();
        announce("boss.endsun-guard.twins-revive-warning",
                "seconds", plugin.endsunGuardSettings().twins().revivalWindowTicks() / 20);
    }

    private void reviveMissingTwin() {
        TwinBody defeated = twinSun != null && twinSun.defeated ? twinSun
                : twinNight != null && twinNight.defeated ? twinNight : null;
        if (defeated == null) {
            twinReviveAt = 0L;
            return;
        }
        TwinBody donor = defeated == twinSun ? twinNight : twinSun;
        if (donor == null || donor.defeated) {
            if (twinSun != null && twinSun.defeated && twinNight != null && twinNight.defeated) {
                finishTwins();
            } else {
                // Keep retrying instead of permanently freezing the twin phase.
                twinReviveAt = age + 1L;
            }
            return;
        }
        if (donor.entity == null || !donor.entity.isValid()) {
            Location donorLocation = controller.getLocation().clone()
                    .add(donor.type == TwinType.SUN ? 4.0 : -4.0, 0, 0);
            TwinBody replacement = spawnTwin(donor.type, donorLocation, donor.healthRemaining);
            if (donor.type == TwinType.SUN) twinSun = replacement;
            else twinNight = replacement;
            donor = replacement;
        }
        double donorHealth = sanitizeTwinHealth(donor.maximumHealth, donor.healthRemaining);
        double transferredHealth = donorHealth * 0.5;
        Location location = controller.getLocation().clone().add(defeated.type == TwinType.SUN ? 4.0 : -4.0, 0, 0);
        TwinBody revived = spawnTwin(defeated.type, location, transferredHealth);
        donor.healthRemaining = sanitizeTwinHealth(donor.maximumHealth, donorHealth - transferredHealth);
        synchronizeTwinEntityHealth(donor);
        synchronizeTwinEntityHealth(revived);
        if (defeated.type == TwinType.SUN) twinSun = revived;
        else twinNight = revived;
        twinReviveAt = 0L;
        updateTwinBossBar(donor);
        updateTwinBossBar(revived);
        drawTwinLifeTransfer(donor, revived);
        announce("boss.endsun-guard.twins-revived-transfer",
                "donor", twinName(donor.type),
                "body", twinName(defeated.type),
                "health", String.format(Locale.ROOT, "%.1f", transferredHealth));
        plugin.getLogger().info(String.format(Locale.ROOT,
                "双生蚀影复活转移: donor=%s before=%.2f after=%.2f revived=%s health=%.2f/%.2f",
                donor.type, donorHealth, donor.healthRemaining, revived.type,
                revived.healthRemaining, revived.maximumHealth));
        controller.getWorld().playSound(location, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2.5f, 0.55f);
    }

    private void drawTwinLifeTransfer(TwinBody donor, TwinBody revived) {
        Location start = donor.entity.getLocation().clone().add(0.0, 1.25, 0.0);
        Location end = revived.entity.getLocation().clone().add(0.0, 1.25, 0.0);
        Vector path = end.toVector().subtract(start.toVector());
        Color color = donor.type == TwinType.SUN
                ? Color.fromRGB(255, 150, 25)
                : Color.fromRGB(110, 40, 185);
        for (int step = 0; step <= 24; step++) {
            Location point = start.clone().add(path.clone().multiply(step / 24.0));
            ParticleCompat.spawnDust(start.getWorld(), point, 2,
                    0.04, 0.04, 0.04, 0.0, color, 1.25f);
        }
        start.getWorld().spawnParticle(Particle.SOUL, end, 28, 0.55, 0.8, 0.55, 0.04);
    }

    private void finishTwins() {
        twinReviveAt = 0L;
        removeTwins();
        ensureModel();
        double rewardDamage = phaseHealth() * plugin.endsunGuardSettings().twins().successDamagePercent();
        setCurrentPhaseHealth(Math.max(1.0, health() - rewardDamage));
        updateBossBar();
        ParticleCompat.spawnFlash(controller.getWorld(), controller.getLocation().add(0, 1.2, 0), 1,
                Color.fromRGB(175, 65, 255));
        announce("boss.endsun-guard.twins-defeated");
        finishSkill();
    }

    public boolean handleTotemInteract(Entity entity, Player attacker) {
        if (!totemHits.containsKey(entity.getUniqueId())) return false;
        hitTotem(entity, attacker);
        return true;
    }

    private void hitTotem(Entity entity, Player attacker) {
        UUID id = entity.getUniqueId();
        int remaining = totemHits.getOrDefault(id, 0) - 1;
        if (remaining > 0) {
            totemHits.put(id, remaining);
            entity.getWorld().playSound(entity.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.2f, 1.1f);
            entity.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, entity.getLocation().add(0, 1.0, 0),
                    8, 0.25, 0.4, 0.25, 0.03);
            return;
        }
        entity.remove();
        totemHits.remove(id);
        if (!totemHits.isEmpty()) return;
        crownEndsAt = 0L;
        solarFlames.clear();
        removeTotems();
        for (Player player : combatPlayers()) {
            reduceEclipse(player, plugin.endsunGuardSettings().eclipse().counterReduction());
        }
        announce("boss.endsun-guard.crown-extinguished", "player", attacker.getName());
    }

    public boolean ownsAuxiliary(Entity entity) {
        UUID id = entity.getUniqueId();
        return totemHits.containsKey(id) || soulReflections.contains(id) || twinBody(id) != null;
    }

    public void handleAuxiliaryDeath(EntityDeathEvent event) {
        event.getDrops().clear();
        event.setDroppedExp(0);
        soulReflections.remove(event.getEntity().getUniqueId());
        totemHits.remove(event.getEntity().getUniqueId());
        TwinBody twin = twinBody(event.getEntity().getUniqueId());
        if (twin != null && !twin.defeated) {
            twin.defeated = true;
            twin.healthRemaining = 0.0;
            closeTwinEntity(twin);
            if (twinSun != null && twinSun.defeated && twinNight != null && twinNight.defeated) {
                finishTwins();
            } else {
                twinReviveAt = age + plugin.endsunGuardSettings().twins().revivalWindowTicks();
            }
        }
    }

    private void tickPersistentMechanics() {
        tickShieldFragmentBeams();
        tickBoundary();
        tickSunWheels();
        tickActiveEdict();
    }

    private void tickShieldFragmentBeams() {
        if (fragmentEntities.isEmpty() || age % 2L != 0L) return;
        Iterator<UUID> iterator = fragmentEntities.iterator();
        while (iterator.hasNext()) {
            Entity entity = Bukkit.getEntity(iterator.next());
            if (!(entity instanceof Item item) || !item.isValid() || item.isDead()) {
                iterator.remove();
                continue;
            }
            Location base = item.getLocation().clone().add(0.0, 0.18, 0.0);
            double pulse = 0.02 + (Math.sin((age + item.getEntityId()) * 0.18) + 1.0) * 0.015;
            for (int step = 0; step <= 10; step++) {
                double height = step * 0.32;
                Location point = base.clone().add(0.0, height, 0.0);
                Color color = (step & 1) == 0
                        ? Color.fromRGB(255, 238, 165)
                        : Color.fromRGB(255, 184, 45);
                ParticleCompat.spawnDust(item.getWorld(), point, 2,
                        pulse, 0.02, pulse, 0.0, color, 0.85f);
                if ((step & 1) == 0) {
                    item.getWorld().spawnParticle(Particle.END_ROD, point, 1,
                            0.015, 0.02, 0.015, 0.0);
                }
            }
        }
    }

    private void tickBoundary() {
        EndsunGuardSettings.NetherBoundary settings = plugin.endsunGuardSettings().netherBoundary();
        if (boundaryCenter == null || boundaryNormal == null) {
            tickBoundaryReturns(settings);
            return;
        }
        if (age >= boundaryExpiresAt) {
            boundaryCenter = null;
            boundaryNormal = null;
            boundarySides.clear();
            boundaryLastLocations.clear();
            tickBoundaryReturns(settings);
            return;
        }
        Vector tangent = new Vector(-boundaryNormal.getZ(), 0, boundaryNormal.getX());
        if (age % 2L == 0L) {
            Color dust = Color.fromRGB(95, 30, 130);
            for (int step = -40; step <= 40; step++) {
                double offset = settings.halfLength() * step / 40.0;
                Location point = boundaryCenter.clone().add(tangent.clone().multiply(offset)).add(0, 0.18, 0);
                ParticleCompat.spawnDust(controller.getWorld(), point, 1,
                        0.0, 0.12, 0.0, 0.0, dust, 1.35f);
                if (step % 5 == 0) controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                        point, 1, 0.05, 0.15, 0.05, 0.01);
            }
        }
        for (Player player : combatPlayers()) {
            UUID id = player.getUniqueId();
            Vector relative = player.getLocation().toVector().subtract(boundaryCenter.toVector());
            double along = relative.dot(tangent);
            double side = relative.dot(boundaryNormal);
            Double previous = boundarySides.put(id, side);
            Location previousLocation = boundaryLastLocations.put(id, player.getLocation().clone());
            if (Math.abs(along) > settings.halfLength() || previous == null || previousLocation == null) continue;
            if (Math.signum(previous) == Math.signum(side) || Math.abs(previous - side) < 0.35) continue;
            if (boundaryReturns.containsKey(id)) continue;
            boundaryReturns.put(id, new BoundaryReturn(previousLocation, age + settings.returnDelayTicks()));
            player.getWorld().spawnParticle(Particle.SOUL, player.getLocation().add(0, 1.0, 0),
                    22, 0.4, 0.8, 0.4, 0.05);
            player.sendMessage(plugin.messages().component("boss.endsun-guard.boundary-crossed",
                    "seconds", settings.returnDelayTicks() / 20));
        }
        tickBoundaryReturns(settings);
    }

    private void tickBoundaryReturns(EndsunGuardSettings.NetherBoundary settings) {
        Iterator<Map.Entry<UUID, BoundaryReturn>> iterator = boundaryReturns.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BoundaryReturn> entry = iterator.next();
            if (age < entry.getValue().dueAt) continue;
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && validTarget(player, plugin.endsunGuardSettings().bossbarRange())) {
                player.teleport(entry.getValue().location);
                damage(player, settings.damage());
                player.getWorld().spawnParticle(Particle.REVERSE_PORTAL, player.getLocation().add(0, 1.0, 0),
                        35, 0.6, 0.9, 0.6, 0.08);
                player.getWorld().playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.8f, 0.55f);
            }
            iterator.remove();
        }
    }

    private void tickSunWheels() {
        if (sunWheels.isEmpty()) return;
        EndsunGuardSettings.Sunfall settings = plugin.endsunGuardSettings().sunfall();
        Iterator<SunWheel> iterator = sunWheels.iterator();
        while (iterator.hasNext()) {
            SunWheel wheel = iterator.next();
            Entity entity = Bukkit.getEntity(wheel.entityId);
            if (entity == null || !entity.isValid() || age >= wheel.expiresAt) {
                if (entity != null) entity.remove();
                iterator.remove();
                continue;
            }
            Location location = entity.getLocation();
            if (!wheel.landed && !wheel.launched) {
                double nextY = Math.max(wheel.impact.getY() + 0.45, location.getY() - 0.8);
                Location next = location.clone();
                next.setY(nextY);
                entity.teleport(next);
                if (nextY <= wheel.impact.getY() + 0.46) {
                    wheel.landed = true;
                    controller.getWorld().spawnParticle(Particle.EXPLOSION, wheel.impact, 3, 1.0, 0.4, 1.0, 0.04);
                    controller.getWorld().playSound(wheel.impact, Sound.ENTITY_GENERIC_EXPLODE, 2.0f, 0.8f);
                    for (Player player : combatPlayers()) {
                        if (horizontalDistanceSquared(player.getLocation(), wheel.impact) <= 8.0) {
                            damage(player, settings.impactDamage());
                            player.setFireTicks(Math.max(player.getFireTicks(), 80));
                        }
                    }
                }
            } else if (wheel.launched) {
                wheel.velocity.multiply(0.94).setY(0.05);
                entity.teleport(location.clone().add(wheel.velocity));
                if (entity.getLocation().distanceSquared(controller.getLocation().add(0, 1.0, 0)) <= 6.25) {
                    strikeBossWithSunWheel(wheel, entity, settings);
                    iterator.remove();
                    continue;
                }
            }
            Location current = entity.getLocation();
            controller.getWorld().spawnParticle(Particle.FLAME, current, 7, 0.5, 0.35, 0.5, 0.035);
            ParticleCompat.spawnDust(controller.getWorld(), current, 4,
                    0.3, 0.25, 0.3, 0.0, Color.fromRGB(255, 175, 35), 1.45f);
            if (wheel.landed && age % settings.radiationIntervalTicks() == 0L) {
                double radiusSquared = settings.radiationRadius() * settings.radiationRadius();
                for (Player player : combatPlayers()) {
                    if (player.getLocation().distanceSquared(current) <= radiusSquared) {
                        damage(player, settings.radiationDamage());
                        player.setFireTicks(Math.max(player.getFireTicks(), 50));
                    }
                }
            }
        }
    }

    private void launchSunWheels(Location scythePoint, boolean returning) {
        for (SunWheel wheel : sunWheels) {
            if (!wheel.landed || wheel.launched) continue;
            Entity entity = Bukkit.getEntity(wheel.entityId);
            if (entity == null || entity.getLocation().distanceSquared(scythePoint) > 5.0) continue;
            Vector direction = returning
                    ? controller.getLocation().toVector().subtract(entity.getLocation().toVector()).setY(0)
                    : entity.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
            if (direction.lengthSquared() < 0.01) direction = new Vector(0, 0, 1);
            wheel.velocity = direction.normalize().multiply(0.72);
            wheel.launched = true;
            wheel.landed = false;
            entity.getWorld().playSound(entity.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 2.0f, 1.25f);
        }
    }

    private void strikeBossWithSunWheel(SunWheel wheel, Entity entity, EndsunGuardSettings.Sunfall settings) {
        double armorBreakDamage = scaledEncounterValue(settings.armorBreakDamage());
        entity.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, entity.getLocation(), 1);
        entity.getWorld().playSound(entity.getLocation(), Sound.BLOCK_ANVIL_LAND, 2.5f, 0.65f);
        if (skill == Skill.SHIELD) {
            shieldEnergy = Math.max(0.0, shieldEnergy - armorBreakDamage);
            announce("boss.endsun-guard.sunwheel-shield-hit", "damage", Math.round(armorBreakDamage));
        } else if (skill == Skill.BLACK_SUN) {
            ultimateFurnaceHealth = Math.max(0.0, ultimateFurnaceHealth - armorBreakDamage);
            announce("boss.endsun-guard.sunwheel-furnace-hit", "damage", Math.round(armorBreakDamage));
            if (ultimateFurnaceHealth <= 0.0) finishBlackSun(true);
        } else {
            setCurrentPhaseHealth(Math.max(1.0, health() - armorBreakDamage * 0.35));
            updateBossBar();
            beginStagger(null);
            announce("boss.endsun-guard.sunwheel-armor-break");
        }
        entity.remove();
    }

    private void tickActiveEdict() {
        if (activeEdict == null) return;
        if (age >= edictEndsAt) {
            announce("boss.endsun-guard.edict-ended", "edict", edictName(activeEdict));
            activeEdict = null;
            edictCounters.clear();
            edictCooldowns.clear();
            edictLastLocations.clear();
            return;
        }
        EndsunGuardSettings.Edict settings = plugin.endsunGuardSettings().edict();
        if (activeEdict == EdictType.MOVEMENT) tickMovementEdict(settings);
        else if (activeEdict == EdictType.GAZE) tickGazeEdict(settings);
        else if (activeEdict == EdictType.GROUP) tickGroupEdict(settings);
    }

    private void handleWeaponEdict(Player attacker) {
        if (activeEdict != EdictType.WEAPON || age < edictCooldowns.getOrDefault(attacker.getUniqueId(), 0L)) return;
        int hits = edictCounters.merge(attacker.getUniqueId(), 1, Integer::sum);
        if (hits < plugin.endsunGuardSettings().edict().weaponHitThreshold()) return;
        edictCounters.put(attacker.getUniqueId(), 0);
        edictCooldowns.put(attacker.getUniqueId(), age + plugin.endsunGuardSettings().edict().triggerCooldownTicks());
        damage(attacker, plugin.endsunGuardSettings().edict().reflectDamage());
        attacker.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, attacker.getLocation().add(0, 1.0, 0),
                10, 0.35, 0.6, 0.35, 0.08);
        attacker.sendMessage(plugin.messages().component("boss.endsun-guard.edict-weapon-punish"));
    }

    private void tickMovementEdict(EndsunGuardSettings.Edict settings) {
        for (Player player : combatPlayers()) {
            UUID id = player.getUniqueId();
            Location previous = edictLastLocations.put(id, player.getLocation().clone());
            int value = edictCounters.getOrDefault(id, 0);
            if (previous != null && horizontalDistanceSquared(previous, player.getLocation()) > 0.015) value++;
            else value = Math.max(0, value - 2);
            edictCounters.put(id, value);
            if (value < settings.movementThresholdTicks() || age < edictCooldowns.getOrDefault(id, 0L)) continue;
            edictCounters.put(id, 0);
            edictCooldowns.put(id, age + settings.triggerCooldownTicks());
            player.getWorld().strikeLightningEffect(player.getLocation());
            damage(player, settings.movementDamage());
            player.sendMessage(plugin.messages().component("boss.endsun-guard.edict-movement-punish"));
        }
    }

    private void tickGazeEdict(EndsunGuardSettings.Edict settings) {
        for (Player player : combatPlayers()) {
            UUID id = player.getUniqueId();
            Vector towardBoss = controller.getLocation().add(0, 1.3, 0).toVector()
                    .subtract(player.getEyeLocation().toVector());
            boolean looking = towardBoss.lengthSquared() > 0.01
                    && player.getEyeLocation().getDirection().normalize().dot(towardBoss.normalize()) >= settings.gazeDotThreshold()
                    && player.hasLineOfSight(controller);
            int value = Math.max(0, edictCounters.getOrDefault(id, 0) + (looking ? 1 : -3));
            edictCounters.put(id, value);
            if (looking) player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 8,
                    Math.min(4, value / 10), true, false, false));
            if (value < settings.gazeThresholdTicks() || age < edictCooldowns.getOrDefault(id, 0L)) continue;
            edictCounters.put(id, 0);
            edictCooldowns.put(id, age + settings.triggerCooldownTicks());
            damage(player, settings.petrifyDamage());
            player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 50, 6, true, true, true));
            player.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 50, 3, true, true, true));
            player.sendMessage(plugin.messages().component("boss.endsun-guard.edict-gaze-punish"));
        }
    }

    private void tickGroupEdict(EndsunGuardSettings.Edict settings) {
        List<Player> players = combatPlayers();
        double radiusSquared = settings.groupRadius() * settings.groupRadius();
        for (int first = 0; first < players.size(); first++) {
            Player a = players.get(first);
            if (age < edictCooldowns.getOrDefault(a.getUniqueId(), 0L)) continue;
            for (int second = first + 1; second < players.size(); second++) {
                Player b = players.get(second);
                if (a.getLocation().distanceSquared(b.getLocation()) > radiusSquared) continue;
                edictCooldowns.put(a.getUniqueId(), age + settings.triggerCooldownTicks());
                edictCooldowns.put(b.getUniqueId(), age + settings.triggerCooldownTicks());
                Location middle = a.getLocation().clone().add(
                        b.getLocation().toVector().subtract(a.getLocation().toVector()).multiply(0.5));
                middle.getWorld().spawnParticle(Particle.EXPLOSION, middle, 2);
                middle.getWorld().playSound(middle, Sound.ENTITY_GENERIC_EXPLODE, 1.6f, 1.4f);
                damage(a, settings.groupDamage());
                damage(b, settings.groupDamage());
                a.sendMessage(plugin.messages().component("boss.endsun-guard.edict-group-punish"));
                b.sendMessage(plugin.messages().component("boss.endsun-guard.edict-group-punish"));
                break;
            }
        }
    }

    private String edictName(EdictType type) {
        if (type == null) return "-";
        return switch (type) {
            case WEAPON -> plugin.messages().plain("boss.endsun-guard.edict-name.weapon");
            case MOVEMENT -> plugin.messages().plain("boss.endsun-guard.edict-name.movement");
            case GAZE -> plugin.messages().plain("boss.endsun-guard.edict-name.gaze");
            case GROUP -> plugin.messages().plain("boss.endsun-guard.edict-name.group");
        };
    }

    private String twinName(TwinType type) {
        return plugin.messages().plain(type == TwinType.SUN
                ? "boss.endsun-guard.twins-sun-name" : "boss.endsun-guard.twins-night-name");
    }

    private Color edictColor(EdictType type) {
        if (type == null) return Color.PURPLE;
        return switch (type) {
            case WEAPON -> Color.fromRGB(230, 45, 95);
            case MOVEMENT -> Color.fromRGB(70, 185, 235);
            case GAZE -> Color.fromRGB(185, 75, 255);
            case GROUP -> Color.fromRGB(245, 70, 205);
        };
    }

    private void tickEclipseMeters() {
        EndsunGuardSettings.Eclipse settings = plugin.endsunGuardSettings().eclipse();
        if (settings.passiveDecayPerSecond() > 0.0 && age % 20L == 0L) {
            eclipseValues.replaceAll((id, value) -> Math.max(0.0, value - settings.passiveDecayPerSecond()));
        }
    }

    private void addEclipse(Player player, double amount) {
        EndsunGuardSettings.Eclipse settings = plugin.endsunGuardSettings().eclipse();
        double value = Math.min(settings.maximum(), eclipseValues.getOrDefault(player.getUniqueId(), 0.0) + amount);
        eclipseValues.put(player.getUniqueId(), value);
        if (value >= settings.maximum() && skill != Skill.BLACK_SUN) eclipsePending = true;
    }

    private void reduceEclipse(Player player, double amount) {
        eclipseValues.compute(player.getUniqueId(), (id, value) -> Math.max(0.0, (value == null ? 0.0 : value) - amount));
    }

    public void handleAttack(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof Player player && player.getFireTicks() > 0) {
            shieldEnergy = Math.min(scaledShieldMaximumEnergy(),
                    shieldEnergy + scaledEncounterValue(
                            plugin.endsunGuardSettings().crown().shieldHealOnBurningHit()));
            ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1.2, 0),
                    6, 0.35, 0.4, 0.35, 0.02, Color.fromRGB(255, 175, 35), 1.1f);
        }
        if (dealingSkillDamage) return;
        double damage = scaledDamage(plugin.endsunGuardSettings().attackDamage());
        if (event.getEntity() instanceof Player player) {
            damage = plugin.slayer().incomingDamage(player, damage);
        }
        event.setDamage(damage);
        if (skill != Skill.NONE || age < slashEndsAt) return;
        slashEndsAt = age + NORMAL_SLASH_TICKS;
        walking = false;
        if (model != null) model.playOnce("scythe_slash", idleAnimation());
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.8f, 0.65f);
        controller.getWorld().spawnParticle(Particle.SWEEP_ATTACK,
                event.getEntity().getLocation().add(0, 1.0, 0), 2, 0.35, 0.35, 0.35, 0.0);
    }

    private double scaledDamage(double base) {
        double multiplier = 1.0 + furnaceDamageBonus;
        EndsunGuardSettings.Phases phases = plugin.endsunGuardSettings().phases();
        if (phase == Phase.P2) multiplier *= 1.0 + phases.phaseTwoDamageBonus();
        if (phase == Phase.P3) multiplier *= 1.0 + phases.phaseThreeDamageBonus();
        if (age < eclipseFormUntil) multiplier *= plugin.endsunGuardSettings().eclipse().damageMultiplier();
        return base * multiplier;
    }

    private long adjustedCooldown(long ticks) {
        double multiplier = 1.0;
        if (phase == Phase.P3) multiplier *= plugin.endsunGuardSettings().phases().phaseThreeAttackSpeedMultiplier();
        if (age < eclipseFormUntil) multiplier *= plugin.endsunGuardSettings().eclipse().speedMultiplier();
        return Math.max(1L, Math.round(ticks / multiplier));
    }

    private long castTimingTicks(String key, double fallbackSeconds) {
        double configured = plugin.getConfig().getDouble(
                "endsun-guard.abilities.cast-timing." + key, fallbackSeconds);
        return Math.max(1L, Math.round(Math.max(0.05, configured) * 20.0));
    }

    private void damage(Player player, double base) {
        if (!validTarget(player, plugin.endsunGuardSettings().bossbarRange())) return;
        dealingSkillDamage = true;
        try {
            plugin.slayer().dealSkillDamage(player,
                    plugin.slayer().incomingDamage(player, scaledDamage(base)), skillDamageSource);
        } finally {
            dealingSkillDamage = false;
        }
    }

    private boolean executeWindow() {
        return phase == Phase.P3 && health() <= phaseHealth() * 0.2;
    }

    public Map<UUID, Double> damageContributions() {
        return Map.copyOf(damageThreat);
    }

    private void registerReactiveHit(Player attacker) {
        if (!canTriggerReactiveCast() || age < nextReactiveCastAt
                || !validTarget(attacker, plugin.endsunGuardSettings().bossbarRange())) return;
        reactiveHitCount++;
        if (reactiveHitCount < reactiveTeleportHitCount() * challengeParticipantCount()) return;
        if (!triggerReactiveCast(attacker)) return;
        reactiveHitCount = 0;
        nextReactiveCastAt = age + REACTIVE_CAST_COOLDOWN_TICKS;
    }

    private boolean canTriggerReactiveCast() {
        return !dying && skill != Skill.BLACK_SUN && skill != Skill.PHASE_TRANSITION
                && skill != Skill.ECLIPSE_TRANSFORM && skill != Skill.TWIN_ECLIPSE
                && skill != Skill.STAGGER && skill != Skill.ENTRANCE && skill != Skill.DEATH;
    }

    private boolean triggerReactiveCast(Player attacker) {
        if (!canTriggerReactiveCast() || !teleportBehindPlayer(controller, attacker)) return false;
        controller.setTarget(attacker);
        if (skill == Skill.NONE && age >= nextSkillAt) selectSkill();
        return true;
    }

    private void registerTwinReactiveHit(TwinBody body, Player attacker) {
        if (body == null || body.defeated || attacker == null || body.activeSkill == Skill.STAGGER
                || age < body.nextReactiveCastAt) return;
        body.reactiveHitCount++;
        int assignedParticipants = Math.max(1, (challengeParticipantCount() + 1) / 2);
        if (body.reactiveHitCount < reactiveTeleportHitCount() * assignedParticipants) return;
        if (!teleportBehindPlayer(body.entity, attacker)) return;
        body.reactiveHitCount = 0;
        body.nextReactiveCastAt = age + REACTIVE_CAST_COOLDOWN_TICKS;
        body.entity.setTarget(attacker);
        if (body.activeSkill == Skill.NONE && age >= body.nextSkillAt) selectTwinSkill(body);
    }

    private int reactiveTeleportHitCount() {
        return Math.max(1, plugin.getConfig().getInt(
                "endsun-guard.reactive-teleport.hit-count", 5));
    }

    private boolean teleportBehindPlayer(Entity entity, Player player) {
        Location origin = entity.getLocation().clone();
        Location destination = findPositionBehind(player);
        if (destination == null) return false;
        Vector towardPlayer = player.getLocation().toVector().subtract(destination.toVector()).setY(0.0);
        if (towardPlayer.lengthSquared() > 0.001) destination.setDirection(towardPlayer);
        if (!entity.teleport(destination)) return false;
        entity.setVelocity(new Vector());
        origin.getWorld().spawnParticle(Particle.REVERSE_PORTAL, origin.clone().add(0, 1.0, 0),
                36, 0.55, 0.85, 0.55, 0.08);
        destination.getWorld().spawnParticle(Particle.REVERSE_PORTAL, destination.clone().add(0, 1.0, 0),
                36, 0.55, 0.85, 0.55, 0.08);
        destination.getWorld().playSound(destination, Sound.ENTITY_ENDERMAN_TELEPORT, 2.0f, 0.72f);
        return true;
    }

    private Location findPositionBehind(Player player) {
        Vector backward = player.getLocation().getDirection().setY(0.0);
        if (backward.lengthSquared() < 0.001) backward = new Vector(0, 0, 1);
        backward.normalize().multiply(-2.4);
        double[] angles = {0.0, Math.PI / 6.0, -Math.PI / 6.0, Math.PI / 3.0, -Math.PI / 3.0};
        int[] verticalOffsets = {0, -1, 1, -2, 2};
        for (double angle : angles) {
            Vector offset = backward.clone().rotateAroundY(angle);
            Location base = player.getLocation().clone().add(offset);
            for (int verticalOffset : verticalOffsets) {
                Location candidate = base.clone();
                candidate.setY(Math.floor(player.getY()) + verticalOffset + 0.05);
                if (candidate.getBlock().isPassable()
                        && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                        && candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private void tickPhaseProtection() {
        if (phaseHealthLockUntil > 0L) {
            if (age <= phaseHealthLockUntil) {
                refillCurrentPhaseHealth();
            } else {
                // Refill once more before releasing the lock.  This also repairs health
                // changed late in the previous server tick by another damage pipeline.
                refillCurrentPhaseHealth();
                phaseHealthLockUntil = 0L;
                updateBossBar();
            }
        }
        boolean damageLocked = pendingPhase != null || phaseHealthLockUntil > 0L || age <= phaseInvulnerableUntil
                || skill == Skill.PHASE_TRANSITION || skill == Skill.ECLIPSE_TRANSFORM
                || skill == Skill.TWIN_ECLIPSE || skill == Skill.ENTRANCE || skill == Skill.DEATH;
        controller.setInvulnerable(damageLocked);
        if (!damageLocked) controller.setNoDamageTicks(0);
    }

    private void refillCurrentPhaseHealth() {
        double maximum = phaseHealth();
        setBase(Attribute.MAX_HEALTH, maximum);
        setCurrentPhaseHealth(maximum);
    }

    private void setCurrentPhaseHealth(double health) {
        double maximum = phaseHealth();
        phaseHealthRemaining = Math.max(0.0, Math.min(maximum, health));
        synchronizeControllerHealth();
    }

    private void synchronizeControllerHealth() {
        if (!controller.isValid() || controller.isDead()) return;
        double safeHealth = Math.max(1.0, Math.min(phaseHealth(), phaseHealthRemaining));
        if (Math.abs(controller.getHealth() - safeHealth) > 1.0E-6) {
            controller.setHealth(safeHealth);
        }
    }

    private void beginSkill(Skill next, String animation) {
        skill = next;
        skillStartedAt = age;
        walking = false;
        controller.setAI(false);
        controller.setVelocity(new Vector());
        if (model != null) model.playOnce(animation, idleAnimation());
    }

    private void beginSkillHold(Skill next, String animation) {
        skill = next;
        skillStartedAt = age;
        walking = false;
        controller.setAI(false);
        controller.setVelocity(new Vector());
        if (model != null) model.playOnceAndHold(animation);
    }

    private void finishSkill() {
        Skill finished = skill;
        skill = Skill.NONE;
        skillStartedAt = 0L;
        judgementTarget = null;
        double fallbackDelay = finished == Skill.ENTRANCE ? 1.5
                : phase == Phase.P3 ? 0.8 : 1.2;
        String delayKey = finished == Skill.ENTRANCE ? "initial-delay-seconds"
                : phase == Phase.P3 ? "phase-three-delay-seconds"
                : "phase-one-two-delay-seconds";
        nextSkillAt = age + castTimingTicks(delayKey, fallbackDelay);
        controller.setAI(true);
        acquireTarget();
        playLoop(idleAnimation());
    }

    private String idleAnimation() {
        return phase == Phase.P3 ? "final_stance" : "idle";
    }

    private void playLoop(String animation) {
        if (model != null) model.playLoop(animation);
    }

    private void acquireTarget() {
        EndsunGuardSettings settings = plugin.endsunGuardSettings();
        Player highestThreatPlayer = null;
        double bestThreat = -1.0;
        double bestDistance = Double.MAX_VALUE;
        double followRangeSquared = settings.followRange() * settings.followRange();
        Location origin = controller.getLocation();
        for (Player player : controller.getWorld().getPlayers()) {
            if (!validTarget(player, settings.followRange())) continue;
            double distance = player.getLocation().distanceSquared(origin);
            if (distance > followRangeSquared) continue;
            double threat = damageThreat.getOrDefault(player.getUniqueId(), 0.0);
            if (threat > bestThreat + 0.0001
                    || (Math.abs(threat - bestThreat) <= 0.0001 && distance < bestDistance)) {
                highestThreatPlayer = player;
                bestThreat = threat;
                bestDistance = distance;
            }
        }
        controller.setTarget(highestThreatPlayer);
    }

    private void addDamageThreat(Player attacker, double amount) {
        if (attacker == null || amount <= 0.0 || attacker.getWorld() != controller.getWorld()) return;
        damageThreat.merge(attacker.getUniqueId(), amount, Double::sum);
        acquireTarget();
    }

    private void tickActiveChase(Player target) {
        if (target == null || skill != Skill.NONE || dying) return;
        if (!controller.hasAI()) controller.setAI(true);
        controller.setTarget(target);

        Vector toward = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0.0);
        if (toward.lengthSquared() <= 7.84) return;

        double speed = plugin.endsunGuardSettings().movementSpeed();
        if (phase == Phase.P3) speed *= plugin.endsunGuardSettings().phases().phaseThreeMovementSpeedMultiplier();
        if (age < eclipseFormUntil) speed *= plugin.endsunGuardSettings().eclipse().speedMultiplier();
        double chaseSpeed = Math.min(0.22, 0.04 + speed * 0.4);
        Vector desired = toward.normalize().multiply(chaseSpeed);
        Vector current = controller.getVelocity();
        controller.setVelocity(new Vector(
                current.getX() * 0.65 + desired.getX() * 0.35,
                current.getY(),
                current.getZ() * 0.65 + desired.getZ() * 0.35));
    }

    private Player targetPlayer() {
        return controller.getTarget() instanceof Player player
                && validTarget(player, plugin.endsunGuardSettings().followRange()) ? player : null;
    }

    private List<Player> combatPlayers() {
        double range = plugin.endsunGuardSettings().bossbarRange();
        return controller.getWorld().getPlayers().stream().filter(player -> validTarget(player, range)).toList();
    }

    private boolean validTarget(Player player, double range) {
        return player != null && player.isOnline() && !player.isDead()
                && player.getWorld() == controller.getWorld()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getLocation().distanceSquared(controller.getLocation()) <= range * range;
    }

    private Player damagePlayer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        return damagePlayer(byEntity.getDamager());
    }

    private Player damagePlayer(Entity damager) {
        if (damager == null) return null;
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) return player;
        }
        return null;
    }

    private boolean isLinkedPlayerDamage(EntityDamageEvent event, Player attacker) {
        UUID victimId = event.getEntity().getUniqueId();
        UUID attackerId = attacker.getUniqueId();
        for (EntityDamageEvent active : preApprovedPlayerDamageEvents) {
            if (!active.getEntity().getUniqueId().equals(victimId)) continue;
            Player activeAttacker = damagePlayer(active);
            if (activeAttacker != null && activeAttacker.getUniqueId().equals(attackerId)) return true;
        }
        return false;
    }

    private double attackerDirectionDot(Player attacker) {
        return attackerDirectionDot(controller, attacker);
    }

    private double attackerDirectionDot(Entity defender, Player attacker) {
        Vector forward = defender.getLocation().getDirection().setY(0);
        Vector toward = attacker.getLocation().toVector().subtract(defender.getLocation().toVector()).setY(0);
        if (forward.lengthSquared() < 0.001 || toward.lengthSquared() < 0.001) return 0.0;
        return forward.normalize().dot(toward.normalize());
    }

    private int challengeParticipantCount() {
        return Math.max(1, plugin.endsunArenaManager()
                .challengeParticipantCount(id(), controller.getWorld()));
    }

    private boolean acceptPlayerHit(Map<UUID, Long> nextAcceptedHits, Player attacker) {
        UUID attackerId = attacker.getUniqueId();
        if (age < nextAcceptedHits.getOrDefault(attackerId, Long.MIN_VALUE)) return false;
        nextAcceptedHits.put(attackerId, age + plugin.endsunGuardSettings().playerHitCooldownTicks());
        return true;
    }

    private double scaledShieldMaximumEnergy() {
        return scaledEncounterValue(plugin.endsunGuardSettings().shield().maximumEnergy());
    }

    private double scaledEncounterValue(double baseValue) {
        return Math.max(0.0, baseValue) * encounterHealthMultiplier;
    }

    public void applySettings(boolean preserveHealthFraction) {
        EndsunGuardSettings settings = plugin.endsunGuardSettings();
        double oldMaximum = maxHealth();
        double fraction = preserveHealthFraction && oldMaximum > 0.0 ? health() / oldMaximum : 1.0;
        double newMaximum = phaseHealth();
        setBase(Attribute.MAX_HEALTH, newMaximum);
        setBase(Attribute.ATTACK_DAMAGE, settings.attackDamage());
        setBase(Attribute.ARMOR, settings.armor());
        setBase(Attribute.FOLLOW_RANGE, settings.followRange());
        setBase(Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
        applyMovementSpeed();
        controller.setAI(skill == Skill.NONE && !dying);
        controller.setInvisible(true);
        controller.setMaximumNoDamageTicks(0);
        controller.setNoDamageTicks(0);
        controller.customName(legacyComponent(settings.displayName()));
        controller.setCustomNameVisible(false);
        setCurrentPhaseHealth(Math.max(1.0, Math.min(newMaximum, newMaximum * fraction)));
        double maximumShieldEnergy = scaledShieldMaximumEnergy();
        shieldEnergy = Math.min(shieldEnergy, maximumShieldEnergy);
        if (twinSun != null) twinSun.shieldEnergy = Math.min(twinSun.shieldEnergy, maximumShieldEnergy);
        if (twinNight != null) twinNight.shieldEnergy = Math.min(twinNight.shieldEnergy, maximumShieldEnergy);
        ensureModel();
        if (skill == Skill.NONE && !dying) acquireTarget();
    }

    private void applyMovementSpeed() {
        double speed = plugin.endsunGuardSettings().movementSpeed();
        if (phase == Phase.P3) speed *= plugin.endsunGuardSettings().phases().phaseThreeMovementSpeedMultiplier();
        if (age < eclipseFormUntil) speed *= plugin.endsunGuardSettings().eclipse().speedMultiplier();
        setBase(Attribute.MOVEMENT_SPEED, Math.min(1.0, speed));
        AttributeInstance attackSpeed = controller.getAttribute(Attribute.ATTACK_SPEED);
        if (attackSpeed != null) {
            double multiplier = plugin.endsunGuardSettings().attackSpeedMultiplier() * (phase == Phase.P3
                    ? plugin.endsunGuardSettings().phases().phaseThreeAttackSpeedMultiplier() : 1.0);
            if (age < eclipseFormUntil) multiplier *= plugin.endsunGuardSettings().eclipse().speedMultiplier();
            attackSpeed.setBaseValue(4.0 * multiplier);
        }
    }

    private void setBase(Attribute attribute, double value) {
        AttributeInstance instance = controller.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private void ensureModel() {
        if (model != null && model.valid()) return;
        closeModel();
        EndsunGuardSettings settings = plugin.endsunGuardSettings();
        model = plugin.betterModel().attachStable(settings.modelId(), controller,
                settings.modelScale() / ENDSUN_BAKED_MODEL_SCALE, idleAnimation(), false);
        if (model != null) model.setGlow(true, BOSS_GLOW_COLOR);
    }

    public void recoverModelAfterSpectralArrow() {
        if (!controller.isValid() || controller.isDead()) return;
        controller.removePotionEffect(PotionEffectType.GLOWING);
        controller.setGlowing(false);
        controller.setInvisible(true);
        if (skill == Skill.TWIN_ECLIPSE) return;
        ensureModel();
        if (model != null) {
            model.refresh();
            model.setGlow(true, BOSS_GLOW_COLOR);
            model.playLoop(skill == Skill.BLACK_SUN ? "black_sun_ritual" : idleAnimation());
        }
    }

    private void updateBossBar() {
        if (skill == Skill.TWIN_ECLIPSE) {
            bossBar.removeAll();
            updateTwinBossBar(twinSun);
            updateTwinBossBar(twinNight);
            return;
        }
        updateBossBarHealth(phaseHealthRemaining);
        double highest = eclipseValues.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
        bossBar.setTitle(legacyString(plugin.messages().raw("boss.endsun-guard.bossbar",
                "name", plugin.endsunGuardSettings().displayName(), "phase", phase.name(),
                "shield", Math.round(shieldEnergy), "eclipse", Math.round(highest))));
        double rangeSquared = plugin.endsunGuardSettings().bossbarRange()
                * plugin.endsunGuardSettings().bossbarRange();
        for (Player player : controller.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(controller.getLocation()) <= rangeSquared) bossBar.addPlayer(player);
            else bossBar.removePlayer(player);
        }
    }

    private void updateBossBarHealth(double displayedHealth) {
        double maximum = Math.max(1.0, phaseHealth());
        bossBar.setProgress(Math.max(0.0, Math.min(1.0, displayedHealth / maximum)));
    }

    public void onDeath(EntityDeathEvent event) {
        event.getDrops().clear();
        event.setDroppedExp(plugin.endsunGuardSettings().experience());
        bossBar.removeAll();
        cleanupAuxiliary();
        closeModel();
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME,
                controller.getLocation().add(0, 1.0, 0), 55, 0.8, 1.0, 0.8, 0.04);
        announce("boss.endsun-guard.death");
        plugin.endsunArenaManager().onBossDefeated(this);
    }

    @Override
    public boolean finalDeathSequenceActive() {
        return dying;
    }

    @Override
    public boolean controllerDamageLocked() {
        return dying || pendingPhase != null || phaseHealthLockUntil > 0L || age <= phaseInvulnerableUntil
                || skill == Skill.PHASE_TRANSITION || skill == Skill.ECLIPSE_TRANSFORM
                || skill == Skill.TWIN_ECLIPSE || skill == Skill.ENTRANCE || skill == Skill.DEATH;
    }

    @Override
    public UUID recoverUnexpectedControllerDeath() {
        Phase defeatedPhase = phase;
        Location recoveryLocation = controller.getLocation().clone();
        World world = recoveryLocation.getWorld();
        if (world == null) throw new IllegalStateException("阿努赫异常死亡位置没有世界");

        cleanupAuxiliary();
        closeModel();
        Husk replacement = world.spawn(recoveryLocation, Husk.class, entity -> {
            configureController(plugin, entity);
            entity.getPersistentDataContainer().set(plugin.encounterHealthMultiplierKey(),
                    PersistentDataType.DOUBLE, encounterHealthMultiplier);
            entity.getPersistentDataContainer().set(plugin.endsunPhaseKey(), PersistentDataType.INTEGER,
                    defeatedPhase == Phase.P1 ? 1 : defeatedPhase == Phase.P2 ? 2 : 3);
        });
        controller = replacement;
        skillDamageSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(replacement)
                .withDirectEntity(replacement)
                .build();
        pendingPhase = null;
        skill = Skill.NONE;
        skillStartedAt = 0L;
        phaseHealthLockUntil = 0L;
        phaseInvulnerableUntil = 0L;
        walking = false;
        eclipsePending = false;
        bossBarRefreshQueued = false;
        applySettings(false);
        controller.setNoDamageTicks(Math.max(20, controller.getMaximumNoDamageTicks()));
        controller.setAI(false);
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
            if (nextPhase == Phase.P2) enterPhaseTwo();
            else if (nextPhase == Phase.P3) enterPhaseThree();
            else startDeathSequence();
        });
        return replacement.getUniqueId();
    }

    public void remove() {
        bossBar.removeAll();
        cleanupAuxiliary();
        closeModel();
        controller.remove();
    }

    public void detach() {
        bossBar.removeAll();
        cleanupAuxiliary();
        closeModel();
    }

    private void cleanupAuxiliary() {
        removeTotems();
        for (UUID id : new ArrayList<>(soulReflections)) destroySoul(id, false);
        removeEchoClones();
        removeSunWheels();
        removeTwins();
        solarFlames.clear();
        shadowBarriers.clear();
        sunfallTargets.clear();
        suffocationStacks.clear();
        nextSandHit.clear();
        boundaryCenter = null;
        boundaryNormal = null;
        boundarySides.clear();
        boundaryLastLocations.clear();
        boundaryReturns.clear();
        activeEdict = null;
        pendingEdict = null;
        edictCounters.clear();
        edictCooldowns.clear();
        edictLastLocations.clear();
        damageThreat.clear();
        nextAcceptedPlayerHit.clear();
        preApprovedPlayerDamageEvents.clear();
        teamBarrierActive = false;
        teamFragmentProgress = 0;
        removeFragmentEntities();
        for (Player player : controller.getWorld().getPlayers()) removeAllFragments(player);
    }

    private void removeEchoClones() {
        for (EchoClone clone : echoClones) {
            Entity entity = Bukkit.getEntity(clone.entityId);
            if (entity != null) entity.remove();
        }
        echoClones.clear();
    }

    private void removeSunWheels() {
        for (SunWheel wheel : sunWheels) {
            Entity entity = Bukkit.getEntity(wheel.entityId);
            if (entity != null) entity.remove();
        }
        sunWheels.clear();
    }

    private void removeTwins() {
        closeTwinEntity(twinSun);
        closeTwinEntity(twinNight);
        twinSun = null;
        twinNight = null;
        twinReviveAt = 0L;
        twinsSpawned = false;
    }

    private void closeTwinEntity(TwinBody body) {
        if (body == null) return;
        if (body.healthBar != null) {
            body.healthBar.removeAll();
            body.healthBar.setVisible(false);
            body.healthBar = null;
        }
        if (body.model != null) {
            body.model.close();
            body.model = null;
        }
        if (body.entity != null) {
            body.entity.remove();
            body.entity = null;
        }
    }

    private void removeTotems() {
        for (UUID id : new ArrayList<>(totemHits.keySet())) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        totemHits.clear();
    }

    private void destroySoul(UUID id, boolean countered) {
        Entity entity = Bukkit.getEntity(id);
        if (entity != null) {
            entity.getWorld().spawnParticle(Particle.SOUL, entity.getLocation(), 12,
                    0.35, 0.35, 0.35, 0.04);
            entity.remove();
        }
        soulReflections.remove(id);
        if (countered) {
            for (Player player : combatPlayers()) reduceEclipse(player,
                    plugin.endsunGuardSettings().eclipse().counterReduction() * 0.25);
        }
    }

    private void removeFragmentEntities() {
        for (UUID id : new ArrayList<>(fragmentEntities)) {
            Entity entity = Bukkit.getEntity(id);
            if (entity != null) entity.remove();
        }
        fragmentEntities.clear();
    }

    private void removeAllFragments(Player player) {
        for (ItemStack stack : player.getInventory().getContents()) {
            if (isFragment(stack)) stack.setAmount(0);
        }
    }

    private void announce(String key, Object... replacements) {
        Component message = plugin.messages().component(key, replacements);
        for (Player player : combatPlayers()) player.sendMessage(message);
    }

    private void drawRing(Location center, double radius, Particle particle) {
        for (int index = 0; index < 48; index++) {
            double angle = Math.PI * 2.0 * index / 48.0;
            center.getWorld().spawnParticle(particle,
                    center.clone().add(Math.cos(angle) * radius, 0.25, Math.sin(angle) * radius),
                    1, 0, 0, 0, 0);
        }
    }

    private void drawMagicCircle(Location center, double radius, Color color, double rotation) {
        if (center.getWorld() == null || radius <= 0.0) return;
        Location floor = center.clone();
        floor.setY(Math.floor(center.getY()) + 0.08);
        int points = 36;
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2.0 * index / points + rotation;
            spawnDust(floor, Math.cos(angle) * radius, Math.sin(angle) * radius, color);
            if ((index & 1) == 0) {
                double innerAngle = -angle * 2.0 + rotation;
                spawnDust(floor, Math.cos(innerAngle) * radius * 0.55,
                        Math.sin(innerAngle) * radius * 0.55, color);
            }
        }
        for (int spoke = 0; spoke < 6; spoke++) {
            double angle = rotation + Math.PI * 2.0 * spoke / 6.0;
            for (int step = 1; step <= 5; step++) {
                double distance = radius * step / 6.0;
                spawnDust(floor, Math.cos(angle) * distance, Math.sin(angle) * distance, color);
            }
        }
    }

    private void spawnDust(Location center, double x, double z, Color color) {
        ParticleCompat.spawnDust(center.getWorld(), center.clone().add(x, 0.0, z), 1,
                0.0, 0.0, 0.0, 0.0, color, 1.15f);
    }

    private static Location lerp(Location start, Location end, double progress) {
        return start.clone().add(end.toVector().subtract(start.toVector()).multiply(progress));
    }

    private static double horizontalDistanceSquared(Location first, Location second) {
        double x = first.getX() - second.getX();
        double z = first.getZ() - second.getZ();
        return x * x + z * z;
    }

    private static double horizontalDistanceToSegment(Location point, Location start, Location end) {
        double vx = end.getX() - start.getX();
        double vz = end.getZ() - start.getZ();
        double lengthSquared = vx * vx + vz * vz;
        if (lengthSquared < 0.0001) return Math.sqrt(horizontalDistanceSquared(point, start));
        double progress = ((point.getX() - start.getX()) * vx + (point.getZ() - start.getZ()) * vz) / lengthSquared;
        progress = Math.max(0.0, Math.min(1.0, progress));
        double closestX = start.getX() + vx * progress;
        double closestZ = start.getZ() + vz * progress;
        return Math.hypot(point.getX() - closestX, point.getZ() - closestZ);
    }

    private static double normalizeAngle(double angle) {
        double result = angle % (Math.PI * 2.0);
        return result < 0.0 ? result + Math.PI * 2.0 : result;
    }

    private static double angleDistance(double first, double second) {
        double difference = Math.abs(normalizeAngle(first) - normalizeAngle(second));
        return Math.min(difference, Math.PI * 2.0 - difference);
    }

    private void closeModel() {
        if (model == null) return;
        model.close();
        model = null;
    }

    private static Component legacyComponent(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value);
    }

    private static String legacyString(String value) {
        return LegacyComponentSerializer.legacySection().serialize(legacyComponent(value));
    }
}
