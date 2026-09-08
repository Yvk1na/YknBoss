package cn.yknboss.boss;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.model.BetterModelSupport;
import cn.yknboss.special.SpecialItemManager;
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
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.inventory.ItemStack;
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

/**
 * 终焉之魔女·伊芙蕾雅 encounter controller.
 *
 * <p>The Bukkit Husk is only the authoritative hit box and navigation carrier.  Phase health,
 * spell state and the end gauge live here so model/attribute plugins cannot accidentally carry
 * overflow damage into the next phase.</p>
 */
public final class EndWitch implements ScriptedBossLifecycle {
    private static final int GLOW_COLOR = 0x28E6AD;
    private static final long SPAWN_ANIMATION_TICKS = 72L;
    private static final long DEATH_ANIMATION_TICKS = 64L;
    private static final long PHASE_ANIMATION_TICKS = 56L;

    private enum Phase { P1, P2, P3 }
    private enum Skill {
        NONE, FORBIDDEN_EXCHANGE, MAGIC_ARROWS, BLACK_SKIRT, FATE_GAZE, WITCH_BLINK,
        WITCH_SCISSORS, SILENT_CHANT, FUNERAL_FLOWERS, SOUL_PAWN, TIME_FUNERAL,
        DOOM_LINK, NIGHT_METEORS, NEGATE_HEALING, SHADOW_CAGE, HEAVEN_INVERSION,
        SEVEN_ENDS, WORLD_END, PHASE_TRANSITION, SILENCED
    }
    private enum AuxType { RUNE_RED, RUNE_GREEN, RUNE_CYAN, SCISSORS, FLOWER, SOUL, CAGE }
    private enum StrikeType { LINE, CIRCLE, CROSS, RING, TRACKING }

    private static final class Crystal {
        private final EnderCrystal entity;
        private Crystal(EnderCrystal entity) { this.entity = entity; }
    }

    private static final class Bolt {
        private Location location;
        private final Vector velocity;
        private int life;
        private Bolt(Location location, Vector velocity) {
            this.location = location;
            this.velocity = velocity;
        }
    }

    private static final class Auxiliary {
        private final AuxType type;
        private final ArmorStand entity;
        private double health;
        private final long expiresAt;
        private UUID owner;
        private double storedHealth;
        private boolean resolved;
        private Auxiliary(AuxType type, ArmorStand entity, double health, long expiresAt) {
            this.type = type;
            this.entity = entity;
            this.health = health;
            this.expiresAt = expiresAt;
        }
    }

    private static final class HealZone {
        private final Location location;
        private final long expiresAt;
        private HealZone(Location location, long expiresAt) {
            this.location = location;
            this.expiresAt = expiresAt;
        }
    }

    private static final class SevenStrike {
        private final StrikeType type;
        private final Location center;
        private final Vector direction;
        private final long resolvesAt;
        private SevenStrike(StrikeType type, Location center, Vector direction, long resolvesAt) {
            this.type = type;
            this.center = center;
            this.direction = direction;
            this.resolvesAt = resolvesAt;
        }
    }

    private final YknBossPlugin plugin;
    private Husk controller;
    private DamageSource skillDamageSource;
    private final Map<UUID, BossBar> playerHealthBars = new HashMap<>();
    private final Location battlefieldCenter;
    private final double encounterHealthMultiplier;
    private final Map<UUID, Double> threat = new HashMap<>();
    private final Map<UUID, Long> nextAcceptedPlayerHit = new HashMap<>();
    private final Set<EntityDamageEvent> preApprovedPlayerDamageEvents =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Map<UUID, Integer> marks = new HashMap<>();
    private final Map<UUID, Long> cleanseCooldown = new HashMap<>();
    private final Map<UUID, Long> recentlyHealed = new HashMap<>();
    private final Map<UUID, Long> inverseHealingUntil = new HashMap<>();
    private final Map<UUID, Long> rebelUntil = new HashMap<>();
    private final Map<UUID, Auxiliary> auxiliaries = new HashMap<>();
    private final List<Crystal> crystals = new ArrayList<>();
    private final List<Bolt> bolts = new ArrayList<>();
    private final List<HealZone> healZones = new ArrayList<>();
    private final List<SevenStrike> sevenStrikes = new ArrayList<>();
    private final EnumMap<Skill, Long> cooldowns = new EnumMap<>(Skill.class);
    private final Set<UUID> skillHits = new HashSet<>();
    private final Set<UUID> sevenHitPlayers = new HashSet<>();
    private final Map<UUID, Location> recordedLocations = new HashMap<>();

    private BetterModelSupport.Instance model;
    private Phase phase = Phase.P1;
    private Skill skill = Skill.NONE;
    private long age;
    private long skillStartedAt;
    private long nextSkillAt;
    private long phaseInvulnerableUntil;
    private long phaseHealthLockUntil;
    private Phase pendingPhase;
    private long vulnerableUntil;
    private long nextForcedWorldEndAt = Long.MAX_VALUE;
    private long nextHitFeedbackAt;
    private long nextHurtReactionAt;
    private long entranceStartedAt;
    private long deathStartedAt;
    private long skillActionTick;
    private double skillActionAccumulator;
    private double phaseHealthRemaining;
    private double endGauge;
    private double gaugeFraction;
    private double worldEndShield;
    private boolean worldEndPending;
    private boolean worldEndExecuted;
    private long worldEndChannelTicks;
    private boolean walking;
    private boolean entranceActive;
    private boolean dying;
    private final SkillRotation<Skill> skillRotation = new SkillRotation<>(
            2, ThreadLocalRandom.current().nextInt());
    private int reactiveHitCount;
    private int nextSkillDamageBonus;
    private boolean nextSkillHeals;
    private boolean nextSkillChains;
    private boolean executingChainedSkill;
    private boolean dealingSkillDamage;
    private Skill forcedNextSkill;
    private boolean p3SpeedBoost;
    private boolean p3ArrowBoost;
    private boolean p3WaveBoost;
    private UUID skillTargetId;
    private UUID linkFirst;
    private UUID linkSecond;
    private Location skillOrigin;

    private EndWitch(YknBossPlugin plugin, Husk controller) {
        this.plugin = plugin;
        this.controller = controller;
        this.skillDamageSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(controller).withDirectEntity(controller).build();
        Double storedMultiplier = controller.getPersistentDataContainer().get(
                plugin.encounterHealthMultiplierKey(), PersistentDataType.DOUBLE);
        encounterHealthMultiplier = normalizeHealthMultiplier(storedMultiplier);
        Integer storedPhase = controller.getPersistentDataContainer().get(
                plugin.endWitchPhaseKey(), PersistentDataType.INTEGER);
        if (storedPhase != null) phase = storedPhase == 3 ? Phase.P3 : storedPhase == 2 ? Phase.P2 : Phase.P1;
        battlefieldCenter = controller.getLocation().clone();
        applySettings(true);
        if (phase == Phase.P3) {
            nextForcedWorldEndAt = age + plugin.endWitchSettings().phaseThreeUltimateTimeoutTicks();
        }
        persistPhase();
    }

    public static EndWitch spawn(YknBossPlugin plugin, Location location, double healthMultiplier) {
        if (location.getWorld() == null) throw new IllegalArgumentException("生成位置没有世界");
        double multiplier = normalizeHealthMultiplier(healthMultiplier);
        Husk controller = location.getWorld().spawn(location, Husk.class, entity -> {
            configureController(plugin, entity);
            entity.getPersistentDataContainer().set(plugin.encounterHealthMultiplierKey(),
                    PersistentDataType.DOUBLE, multiplier);
        });
        try {
            EndWitch boss = new EndWitch(plugin, controller);
            boss.refillCurrentPhaseHealth();
            boss.spawnInitialCrystals();
            boss.beginEntrance();
            return boss;
        } catch (RuntimeException | LinkageError exception) {
            controller.remove();
            throw exception;
        }
    }

    public static EndWitch restore(YknBossPlugin plugin, Husk controller) {
        EndWitch boss = new EndWitch(plugin, controller);
        boss.refillCurrentPhaseHealth();
        boss.spawnInitialCrystals();
        return boss;
    }

    private static void configureController(YknBossPlugin plugin, Husk entity) {
        entity.setAdult();
        entity.setAI(true);
        entity.setInvisible(true);
        entity.setSilent(true);
        entity.setFireTicks(0);
        entity.setVisualFire(false);
        entity.setCanPickupItems(false);
        entity.setPersistent(true);
        entity.setRemoveWhenFarAway(false);
        entity.setMaximumNoDamageTicks(0);
        entity.setNoDamageTicks(0);
        entity.getEquipment().clear();
        entity.getPersistentDataContainer().set(plugin.bossTypeKey(),
                PersistentDataType.STRING, EndWitchManager.TYPE_ID);
    }

    public UUID id() { return controller.getUniqueId(); }
    public Location location() { return controller.getLocation(); }
    public double health() { return phaseHealthRemaining > 0.0 ? phaseHealthRemaining : controller.getHealth(); }
    public double maxHealth() { return phaseHealth(); }
    public String phaseName() { return phase.name(); }
    public String currentSkill() {
        if (dying) return "death";
        if (entranceActive) return "spawn";
        return skill.name().toLowerCase(Locale.ROOT);
    }
    public double endGauge() { return endGauge; }
    public Map<UUID, Double> damageContributions() { return Map.copyOf(threat); }

    @Override
    public boolean finalDeathSequenceActive() { return dying; }

    @Override
    public boolean controllerDamageLocked() {
        return dying || pendingPhase != null || entranceActive || phaseHealthLockUntil > 0L
                || age <= phaseInvulnerableUntil || skill == Skill.PHASE_TRANSITION;
    }

    @Override
    public UUID recoverUnexpectedControllerDeath() {
        Phase defeatedPhase = phase;
        Location recoveryLocation = controller.getLocation().clone();
        World world = recoveryLocation.getWorld();
        if (world == null) throw new IllegalStateException("终焉之魔女异常死亡位置没有世界");

        closeModel();
        clearAuxiliaries();
        bolts.clear();
        healZones.clear();
        sevenStrikes.clear();
        skillHits.clear();
        recordedLocations.clear();
        Husk replacement = world.spawn(recoveryLocation, Husk.class, entity -> {
            configureController(plugin, entity);
            entity.getPersistentDataContainer().set(plugin.encounterHealthMultiplierKey(),
                    PersistentDataType.DOUBLE, encounterHealthMultiplier);
            entity.getPersistentDataContainer().set(plugin.endWitchPhaseKey(), PersistentDataType.INTEGER,
                    defeatedPhase == Phase.P1 ? 1 : defeatedPhase == Phase.P2 ? 2 : 3);
        });
        controller = replacement;
        nextAcceptedPlayerHit.clear();
        preApprovedPlayerDamageEvents.clear();
        skillDamageSource = DamageSource.builder(DamageType.MOB_ATTACK)
                .withCausingEntity(replacement)
                .withDirectEntity(replacement)
                .build();
        pendingPhase = null;
        entranceActive = false;
        skill = Skill.NONE;
        skillStartedAt = 0L;
        worldEndPending = false;
        forcedNextSkill = null;
        clearChantModifiers();
        phaseHealthLockUntil = 0L;
        phaseInvulnerableUntil = 0L;
        walking = false;
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
            if (nextPhase != null) transitionTo(nextPhase);
            else finishDeath();
        });
        return replacement.getUniqueId();
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
            updateBars();
            return true;
        }
        if (dying) {
            tickDeathSequence();
            return controller.isValid() && !controller.isDead();
        }
        if (entranceActive) {
            tickEntrance();
            tickCodexParticles();
            updateBars();
            synchronizeControllerHealth();
            return true;
        }
        tickGauge();
        tickCodexParticles();
        tickCrystalsAndCleansing();
        tickBolts();
        tickAuxiliaries();
        tickHealZones();
        tickSevenStrikes();
        tickMarks();
        tickP3Passive();

        if (age % 5L == 0L) acquireTarget();
        Player target = targetPlayer();
        if (skill == Skill.NONE) {
            tickChase(target);
            if (worldEndPending && target != null && age > phaseInvulnerableUntil
                    && phaseHealthLockUntil <= 0L) {
                beginWorldEnd();
            } else if (!worldEndPending && target != null && age >= nextSkillAt
                    && age > phaseInvulnerableUntil) {
                chooseSkill();
            }
        } else {
            tickActiveSkill();
        }
        updateBars();
        synchronizeControllerHealth();
        return true;
    }

    private void beginEntrance() {
        entranceActive = true;
        entranceStartedAt = age;
        walking = false;
        controller.setAI(false);
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        nextSkillAt = Long.MAX_VALUE;
        if (model != null) model.playOnceAndHold("spawn");
        Location center = controller.getLocation().clone().add(0, 0.15, 0);
        controller.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 2.0f, 0.55f);
        controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center,
                90, 1.8, 0.35, 1.8, 0.12);
    }

    private void tickEntrance() {
        long elapsed = age - entranceStartedAt;
        Location base = controller.getLocation();
        Location center = base.clone().add(0, 1.1, 0);
        controller.setAI(false);
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        if (elapsed % 2L == 0L) {
            double progress = Math.min(1.0, elapsed / (double) SPAWN_ANIMATION_TICKS);
            drawMagicCircle(base, 6.2 - progress * 2.4, age * 0.11);
            controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center,
                    12, 1.25, 1.6, 1.25, 0.08);
            controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, center,
                    5, 0.65, 1.0, 0.65, 0.025);
        }
        if (elapsed == 20L) {
            controller.getWorld().playSound(center, Sound.ENTITY_ILLUSIONER_PREPARE_MIRROR,
                    2.0f, 0.68f);
        } else if (elapsed == 44L) {
            controller.getWorld().playSound(center, Sound.BLOCK_ENCHANTMENT_TABLE_USE,
                    1.8f, 0.72f);
        } else if (elapsed == 62L) {
            controller.getWorld().playSound(center, Sound.ENTITY_EVOKER_PREPARE_SUMMON,
                    2.2f, 0.82f);
        }
        if (elapsed < SPAWN_ANIMATION_TICKS) return;

        entranceActive = false;
        controller.setAI(true);
        controller.setInvulnerable(false);
        if (model != null) model.playLoop("idle");
        nextSkillAt = age + actionDelayTicks(cfg("abilities.cast-timing.initial-delay-seconds", 2.25));
        controller.getWorld().spawnParticle(Particle.END_ROD, center,
                70, 1.2, 1.6, 1.2, 0.12);
        controller.getWorld().playSound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL,
                2.2f, 0.78f);
    }

    private void tickGauge() {
        if (skill == Skill.WORLD_END || dying || worldEndExecuted) return;
        double perSecond = switch (phase) {
            case P1 -> plugin.endWitchSettings().phaseOneGaugePerSecond();
            case P2 -> plugin.endWitchSettings().phaseTwoGaugePerSecond();
            case P3 -> plugin.endWitchSettings().phaseThreeGaugePerSecond();
        };
        gaugeFraction += perSecond / 20.0;
        if (gaugeFraction >= 0.01) {
            endGauge = Math.min(plugin.endWitchSettings().gaugeMaximum(), endGauge + gaugeFraction);
            gaugeFraction = 0.0;
        }
        if (phase == Phase.P3 && age >= nextForcedWorldEndAt) {
            endGauge = plugin.endWitchSettings().gaugeMaximum();
            nextForcedWorldEndAt = Long.MAX_VALUE;
        }
        if (endGauge < plugin.endWitchSettings().gaugeMaximum()) return;
        worldEndPending = true;
    }

    private void tickCodexParticles() {
        Vector forward = controller.getLocation().getDirection().setY(0);
        if (forward.lengthSquared() < 0.01) forward = new Vector(0, 0, 1);
        forward.normalize();
        Vector left = new Vector(forward.getZ(), 0, -forward.getX());
        double bob = Math.sin(age * 0.16) * 0.075;
        Location center = controller.getEyeLocation().add(left.multiply(1.0))
                .add(forward.multiply(0.18)).add(0, -0.38 + bob, 0);
        SpecialItemManager.spawnAncientCodexParticles(center, age);
    }

    private void tickCrystalsAndCleansing() {
        crystals.removeIf(crystal -> !crystal.entity.isValid());
        for (Crystal crystal : crystals) {
            Location at = crystalCenter(crystal);
            if (age % 5L == 0L) ParticleCompat.spawnDust(at.getWorld(), at, 5,
                    0.35, 0.55, 0.35, 0.01, Color.fromRGB(25, 220, 175), 1.2f);
        }
        double radius = cfg("marks.cleanse-circle-radius", 2.4);
        double offset = cfg("marks.cleanse-circle-distance", 18.0);
        for (int index = 0; index < 4; index++) {
            double angle = index * Math.PI / 2.0;
            Location circle = battlefieldCenter.clone().add(Math.cos(angle) * offset, 0.08, Math.sin(angle) * offset);
            if (age % 3L == 0L) drawCircle(circle, radius, Color.WHITE, 28);
            for (Player player : combatPlayers()) {
                if (marks.getOrDefault(player.getUniqueId(), 0) <= 0
                        || player.getLocation().distanceSquared(circle) > radius * radius
                        || age < cleanseCooldown.getOrDefault(player.getUniqueId(), 0L)) continue;
                marks.remove(player.getUniqueId());
                cleanseCooldown.put(player.getUniqueId(), age + 40L);
                player.removePotionEffect(PotionEffectType.SLOWNESS);
                addGauge(plugin.endWitchSettings().cleanseGaugeIncrease());
                player.sendActionBar(plugin.messages().component("boss.end-witch.mark-cleansed",
                        "gauge", Math.round(endGauge)));
                player.getWorld().playSound(player.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 1.0f, 1.6f);
            }
        }
    }

    private void tickMarks() {
        for (Player player : combatPlayers()) {
            int stacks = marks.getOrDefault(player.getUniqueId(), 0);
            if (stacks >= 2) {
                int amplifier = Math.max(0, (int) Math.round(plugin.endWitchSettings().markSlowness() * 10.0) - 1);
                player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 12, amplifier, true, false, true));
            }
            if (stacks >= 3 && player.isSprinting()) player.setSprinting(false);
        }
    }

    private void tickP3Passive() {
        if (phase != Phase.P3) return;
        double fraction = health() / Math.max(1.0, maxHealth());
        if (!p3SpeedBoost && fraction <= 0.9) {
            p3SpeedBoost = true;
            applyMovementSpeed();
            announce("boss.end-witch.final-speed");
        }
        if (!p3ArrowBoost && fraction <= 0.8) {
            p3ArrowBoost = true;
            announce("boss.end-witch.final-arrows");
        }
        if (!p3WaveBoost && fraction <= 0.7) {
            p3WaveBoost = true;
            announce("boss.end-witch.final-wave");
        }
    }

    private void chooseSkill() {
        if (forcedNextSkill != null) {
            Skill chained = forcedNextSkill;
            forcedNextSkill = null;
            beginSkill(chained);
            return;
        }
        Skill selected = skillRotation.choose(skillPool(), this::skillReady);
        if (selected != null) {
            beginSkill(selected);
            return;
        }
        nextSkillAt = age + actionDelayTicks(cfg("abilities.cast-timing.retry-delay-seconds", 0.5));
    }

    private List<Skill> skillPool() {
        List<Skill> pool = new ArrayList<>(List.of(Skill.FORBIDDEN_EXCHANGE, Skill.MAGIC_ARROWS,
                Skill.BLACK_SKIRT, Skill.FATE_GAZE, Skill.WITCH_BLINK, Skill.WITCH_SCISSORS, Skill.SILENT_CHANT));
        if (phase != Phase.P1) pool.addAll(List.of(Skill.FUNERAL_FLOWERS, Skill.SOUL_PAWN,
                Skill.TIME_FUNERAL, Skill.DOOM_LINK, Skill.NIGHT_METEORS, Skill.NEGATE_HEALING, Skill.SHADOW_CAGE));
        if (phase == Phase.P3) pool.addAll(List.of(Skill.HEAVEN_INVERSION, Skill.SEVEN_ENDS));
        return pool;
    }

    private boolean skillReady(Skill candidate) {
        return age >= cooldowns.getOrDefault(candidate, 0L);
    }

    private void beginSkill(Skill next) {
        skill = next;
        skillStartedAt = age;
        resetSkillActionClock();
        skillHits.clear();
        recordedLocations.clear();
        if (next == Skill.FORBIDDEN_EXCHANGE || next == Skill.DOOM_LINK) {
            linkFirst = null;
            linkSecond = null;
        }
        skillTargetId = targetPlayer() == null ? null : targetPlayer().getUniqueId();
        skillOrigin = controller.getLocation().clone();
        walking = false;
        controller.setAI(false);
        controller.setVelocity(new Vector());
        String animation = switch (next) {
            case BLACK_SKIRT, FUNERAL_FLOWERS, NIGHT_METEORS, HEAVEN_INVERSION -> "float_cast";
            case WITCH_BLINK -> "blink_slash";
            case WORLD_END -> "world_end";
            default -> "cast";
        };
        if (model != null) model.playOnce(animation, "idle");
        announce("boss.end-witch.skill." + next.name().toLowerCase(Locale.ROOT));
    }

    private void tickActiveSkill() {
        Skill active = skill;
        double speed = switch (active) {
            case WORLD_END, PHASE_TRANSITION, SILENCED -> 1.0;
            default -> actionSpeedMultiplier();
        };
        skillActionAccumulator += speed;
        long targetTick = (long) Math.floor(skillActionAccumulator + 1.0E-9);
        while (skill == active && skillActionTick < targetTick) {
            tickSkill(++skillActionTick);
        }
    }

    private void resetSkillActionClock() {
        skillActionTick = 0L;
        skillActionAccumulator = 0.0;
    }

    private void tickSkill(long elapsed) {
        if (skill != Skill.NONE && skill != Skill.SILENCED && skill != Skill.PHASE_TRANSITION
                && elapsed <= 40L && elapsed % 2L == 0L) {
            drawMagicCircle(controller.getLocation(), 2.6, age * .13);
        }
        switch (skill) {
            case FORBIDDEN_EXCHANGE -> tickForbiddenExchange(elapsed);
            case MAGIC_ARROWS -> tickMagicArrows(elapsed);
            case BLACK_SKIRT -> tickBlackSkirt(elapsed);
            case FATE_GAZE -> tickFateGaze(elapsed);
            case WITCH_BLINK -> tickWitchBlink(elapsed);
            case WITCH_SCISSORS -> tickScissors(elapsed);
            case SILENT_CHANT -> tickSilentChant(elapsed);
            case FUNERAL_FLOWERS -> tickFuneralFlowers(elapsed);
            case SOUL_PAWN -> tickSoulPawn(elapsed);
            case TIME_FUNERAL -> tickTimeFuneral(elapsed);
            case DOOM_LINK -> tickDoomLink(elapsed);
            case NIGHT_METEORS -> tickNightMeteors(elapsed);
            case NEGATE_HEALING -> tickNegateHealing(elapsed);
            case SHADOW_CAGE -> tickShadowCage(elapsed);
            case HEAVEN_INVERSION -> tickHeavenInversion(elapsed);
            case SEVEN_ENDS -> tickSevenEnds(elapsed);
            case WORLD_END -> tickWorldEnd(elapsed);
            case PHASE_TRANSITION -> {
                drawMagicCircle(controller.getLocation(), 4.2, age * 0.11);
                if (elapsed >= PHASE_ANIMATION_TICKS) finishSkill();
            }
            case SILENCED -> {
                controller.setAI(false);
                if (elapsed >= 80L) finishSkill();
            }
            case NONE -> { }
        }
    }

    private void tickForbiddenExchange(long elapsed) {
        List<Player> players = combatPlayers();
        if (elapsed == 1L) {
            if (players.size() >= 2) {
                players.sort(Comparator.comparing(Player::getName));
                linkFirst = players.get(0).getUniqueId();
                linkSecond = players.get(1).getUniqueId();
            } else if (!players.isEmpty()) linkFirst = players.getFirst().getUniqueId();
        }
        Player first = player(linkFirst);
        Player second = player(linkSecond);
        if (first != null) drawCircle(first.getLocation().add(0, .05, 0), 1.5, Color.fromRGB(40, 235, 190), 24);
        if (second != null) drawCircle(second.getLocation().add(0, .05, 0), 1.5, Color.fromRGB(40, 235, 190), 24);
        if (elapsed == 40L && first != null) {
            if (second != null) exchangePlayers(first, second);
            else randomCrystal().ifPresent(crystal -> {
                Location playerLocation = first.getLocation().clone();
                Location playerDestination = crystal.entity.getLocation().clone();
                playerDestination.setYaw(playerLocation.getYaw());
                playerDestination.setPitch(playerLocation.getPitch());
                Location crystalDestination = groundedCrystalLocation(playerLocation);
                first.teleport(playerDestination);
                crystal.entity.teleport(crystalDestination);
            });
        }
        if (elapsed >= 50L) finishSkillWithCooldown(8.0);
    }

    private void exchangePlayers(Player first, Player second) {
        Location firstLocation = first.getLocation().clone();
        int firstFire = first.getFireTicks();
        PotionEffect firstPoison = first.getPotionEffect(PotionEffectType.POISON);
        PotionEffect secondPoison = second.getPotionEffect(PotionEffectType.POISON);
        int firstMark = marks.getOrDefault(first.getUniqueId(), 0);
        first.teleport(second.getLocation());
        second.teleport(firstLocation);
        first.setFireTicks(second.getFireTicks());
        second.setFireTicks(firstFire);
        first.removePotionEffect(PotionEffectType.POISON);
        second.removePotionEffect(PotionEffectType.POISON);
        if (secondPoison != null) first.addPotionEffect(secondPoison);
        if (firstPoison != null) second.addPotionEffect(firstPoison);
        int secondMark = marks.getOrDefault(second.getUniqueId(), 0);
        setMark(first, secondMark);
        setMark(second, firstMark);
        first.getWorld().playSound(first.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.2f, 1.25f);
    }

    private void tickMagicArrows(long elapsed) {
        int count = p3ArrowBoost ? 5 : 3;
        if (elapsed >= 12L && elapsed < 12L + count * 10L && (elapsed - 12L) % 10L == 0L) {
            Player target = player(skillTargetId);
            if (target != null) {
                int index = (int) ((elapsed - 12L) / 10L);
                Location aim = target.getEyeLocation();
                if (index == count - 1) aim.add(target.getVelocity().clone().multiply(10.0));
                Location start = controller.getEyeLocation().add(0, .25, 0);
                Vector direction = aim.toVector().subtract(start.toVector()).normalize().multiply(cfg("abilities.magic-arrows.speed", .75));
                bolts.add(new Bolt(start, direction));
                controller.getWorld().playSound(start, Sound.ENTITY_SHULKER_SHOOT, 1.0f, .8f);
            }
        }
        if (elapsed >= 70L) finishSkillWithCooldown(6.0);
    }

    private void tickBlackSkirt(long elapsed) {
        int waveIndex = elapsed == 20L ? 0 : elapsed == 34L ? 1 : elapsed == 58L ? 2 : -1;
        if (waveIndex >= 0) {
            double radius = (p3WaveBoost ? 1.25 : 1.0) * new double[] {5.0, 8.0, 11.0}[waveIndex];
            resolveShockwave(radius);
            controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WARDEN_SONIC_BOOM, 1.0f, .55f + waveIndex * .12f);
        }
        double preview = elapsed < 20 ? 5 : elapsed < 34 ? 8 : 11;
        if (p3WaveBoost) preview *= 1.25;
        drawCircle(controller.getLocation().add(0, .08, 0), preview, Color.WHITE, 48);
        if (elapsed >= 70L) finishSkillWithCooldown(9.0);
    }

    private void resolveShockwave(double radius) {
        Location center = controller.getLocation();
        for (Player player : combatPlayers()) {
            double horizontal = horizontalDistance(player.getLocation(), center);
            if (Math.abs(horizontal - radius) > 1.7 || player.getLocation().getY() > center.getY() + .65) continue;
            hurt(player, cfg("abilities.black-skirt.damage", 380.0), randomGaugeGain(), 0);
            Vector knockback = player.getLocation().toVector().subtract(center.toVector()).setY(.32);
            if (knockback.lengthSquared() > .01) player.setVelocity(knockback.normalize().multiply(1.05).setY(.35));
        }
    }

    private void tickFateGaze(long elapsed) {
        Player target = player(skillTargetId);
        if (target == null) { finishSkillWithCooldown(10.0); return; }
        face(target.getLocation());
        if (elapsed < 40L) {
            ParticleCompat.spawnDust(controller.getWorld(), controller.getEyeLocation(), 5,
                    .25, .12, .25, .01, Color.fromRGB(25, 255, 145), 1.4f);
            drawLine(controller.getEyeLocation(), target.getEyeLocation(), Color.fromRGB(30, 220, 150), .7);
        } else if (elapsed <= 60L) {
            Location end = target.getEyeLocation();
            resolveBeam(controller.getEyeLocation(), end);
        }
        if (elapsed >= 65L) finishSkillWithCooldown(10.0);
    }

    private void resolveBeam(Location start, Location end) {
        Vector delta = end.toVector().subtract(start.toVector());
        double length = Math.min(cfg("abilities.fate-gaze.range", 48.0), delta.length());
        if (length < .1) return;
        Vector step = delta.normalize().multiply(.45);
        Location point = start.clone();
        for (double distance = 0; distance <= length; distance += .45) {
            point.add(step);
            ParticleCompat.spawnDust(point.getWorld(), point, 1, 0, 0, 0, 0,
                    Color.fromRGB(20, 235, 155), 1.2f);
            Crystal crystal = crystalAt(point, 1.1);
            if (crystal != null) { destroyCrystal(crystal, point); return; }
            for (Player player : combatPlayers()) {
                if (skillHits.add(player.getUniqueId()) && player.getBoundingBox().expand(.45).contains(point.toVector())) {
                    hurt(player, cfg("abilities.fate-gaze.damage", 640.0), randomGaugeGain(), 1);
                    return;
                }
            }
            if (point.getBlock().getType().isSolid()) {
                spawnCrystal(point.getBlock().getLocation().add(.5, 1, .5));
                return;
            }
        }
    }

    private void tickWitchBlink(long elapsed) {
        Player target = player(skillTargetId);
        if (target == null) { finishSkillWithCooldown(8.0); return; }
        if (elapsed == 8L) {
            skillOrigin = controller.getLocation().clone();
            Vector behind = target.getLocation().getDirection().setY(0);
            if (behind.lengthSquared() < .01) behind = new Vector(0, 0, 1);
            Location destination = target.getLocation().clone().subtract(behind.normalize().multiply(2.0));
            destination.setYaw(target.getLocation().getYaw());
            controller.teleport(destination);
            controller.getWorld().spawnParticle(Particle.PORTAL, skillOrigin.clone().add(0, 1, 0), 45, .7, 1.1, .7, .12);
        }
        if (elapsed == 18L) slashAt(controller.getLocation(), cfg("abilities.witch-blink.damage", 490.0));
        if (elapsed >= 8L && elapsed <= 60L && elapsed % 3L == 0L) {
            ParticleCompat.spawnDust(controller.getWorld(), controller.getEyeLocation(), 4,
                    .18, .12, .18, .01, Color.fromRGB(30, 255, 135), 1.25f);
        }
        if (elapsed >= 8L && elapsed < 60L && elapsed % 3L == 0L) {
            controller.getWorld().spawnParticle(Particle.PORTAL, skillOrigin.clone().add(0, 1, 0), 6, .4, .8, .4, .02);
        }
        if (elapsed == 60L) slashAt(skillOrigin, cfg("abilities.witch-blink.echo-damage", 435.0));
        if (elapsed >= 68L) finishSkillWithCooldown(8.0);
    }

    private void slashAt(Location center, double damage) {
        center.getWorld().playSound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.4f, .72f);
        center.getWorld().spawnParticle(Particle.SWEEP_ATTACK, center.clone().add(0, 1, 0), 8, 1.3, .7, 1.3, 0);
        for (Player player : combatPlayers()) {
            if (player.getLocation().distanceSquared(center) <= 16.0) hurt(player, damage, randomGaugeGain(), 1);
        }
    }

    private void tickScissors(long elapsed) {
        Player target = player(skillTargetId);
        if (target == null) { finishSkillWithCooldown(12.0); return; }
        if (elapsed == 1L) skillOrigin = target.getLocation().clone();
        if (elapsed < 40L) {
            drawCircle(skillOrigin, cfg("abilities.witch-scissors.radius", 2.2), Color.fromRGB(30, 230, 185), 32);
            drawLine(controller.getEyeLocation(), skillOrigin.clone().add(0, 1, 0), Color.fromRGB(20, 20, 20), .5);
        }
        if (elapsed == 12L) spawnAux(AuxType.SCISSORS, skillOrigin.clone(), Material.SHEARS,
                cfg("abilities.witch-scissors.health", 3_500.0), age + 50L, target.getUniqueId(), 0);
        if (elapsed == 40L && auxiliaries.values().stream().anyMatch(aux -> aux.type == AuxType.SCISSORS && !aux.resolved)) {
            if (target.getLocation().distanceSquared(skillOrigin) <= 9.0) {
                hurt(target, cfg("abilities.witch-scissors.damage", 930.0), randomGaugeGain(), 1);
                target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 40, 8, true, true, true));
                target.setVelocity(new Vector());
            }
        }
        if (elapsed >= 65L) finishSkillWithCooldown(12.0);
    }

    private void tickSilentChant(long elapsed) {
        if (elapsed == 1L) {
            spawnRune(AuxType.RUNE_RED, 0);
            spawnRune(AuxType.RUNE_GREEN, 1);
            spawnRune(AuxType.RUNE_CYAN, 2);
        }
        for (Auxiliary aux : auxiliaries.values()) {
            if (!isRune(aux.type) || !aux.entity.isValid()) continue;
            double angle = age * .08 + switch (aux.type) {
                case RUNE_RED -> 0;
                case RUNE_GREEN -> Math.PI * 2 / 3;
                default -> Math.PI * 4 / 3;
            };
            aux.entity.teleport(controller.getLocation().clone().add(Math.cos(angle) * 2.2, 2.2, Math.sin(angle) * 2.2));
        }
        if (elapsed == 75L) {
            boolean red = hasAux(AuxType.RUNE_RED);
            boolean green = hasAux(AuxType.RUNE_GREEN);
            boolean cyan = hasAux(AuxType.RUNE_CYAN);
            if (!red && !green && !cyan) {
                clearRunes();
                cooldowns.put(Skill.SILENT_CHANT, age + seconds(cfg(
                        "abilities.silent-chant.cooldown-seconds", 18.0)));
                skill = Skill.SILENCED;
                skillStartedAt = age;
                resetSkillActionClock();
                if (model != null) model.playOnce("silenced", "idle");
                announce("boss.end-witch.silenced");
                return;
            }
            nextSkillDamageBonus = red ? 1 : 0;
            nextSkillHeals = green;
            nextSkillChains = cyan;
            clearRunes();
        }
        if (elapsed >= 90L) finishSkillWithCooldown(18.0);
    }

    private void spawnRune(AuxType type, int index) {
        Material material = switch (type) {
            case RUNE_RED -> Material.RED_STAINED_GLASS;
            case RUNE_GREEN -> Material.LIME_STAINED_GLASS;
            default -> Material.CYAN_STAINED_GLASS;
        };
        Location at = controller.getLocation().add(Math.cos(index * Math.PI * 2 / 3) * 2.2,
                2.2, Math.sin(index * Math.PI * 2 / 3) * 2.2);
        spawnAux(type, at, material, cfg("abilities.silent-chant.rune-health", 1_800.0), age + 80L, null, 0);
    }

    private void tickFuneralFlowers(long elapsed) {
        if (elapsed == 1L) {
            int count = Math.max(2, cfgInt("abilities.funeral-flowers.count", 6));
            for (int index = 0; index < count; index++) {
                double angle = Math.PI * 2 * index / count + ThreadLocalRandom.current().nextDouble(-.25, .25);
                double radius = ThreadLocalRandom.current().nextDouble(5, 15);
                Location at = battlefieldCenter.clone().add(Math.cos(angle) * radius, .1, Math.sin(angle) * radius);
                spawnAux(AuxType.FLOWER, at, Material.WITHER_ROSE,
                        cfg("abilities.funeral-flowers.health", 2_400.0), age + seconds(14), null, 0);
            }
        }
        if (elapsed >= 55L) finishSkillWithCooldown(16.0);
    }

    private void tickSoulPawn(long elapsed) {
        if (elapsed == 18L) {
            Player target = player(skillTargetId);
            if (target != null) {
                double stored = Math.max(1.0, target.getHealth() * cfg("abilities.soul-pawn.health-percent", 30) / 100.0);
                target.setHealth(Math.max(1.0, target.getHealth() - stored));
                double scaledHealth = cfg("abilities.soul-pawn.crystal-health", 3_200.0)
                        * (1.0 + Math.max(0, combatPlayers().size() - 1)
                        * cfg("abilities.soul-pawn.health-per-extra-player-percent", 25.0) / 100.0);
                spawnAux(AuxType.SOUL, target.getLocation().clone().add(0, 1.5, 0), Material.ECHO_SHARD,
                        scaledHealth, age + seconds(8), target.getUniqueId(), stored);
            }
        }
        if (elapsed >= 55L) finishSkillWithCooldown(17.0);
    }

    private void tickTimeFuneral(long elapsed) {
        if (elapsed == 1L) {
            for (Player player : combatPlayers()) recordedLocations.put(player.getUniqueId(), player.getLocation().clone());
        }
        for (Location location : recordedLocations.values()) drawCircle(location.clone().add(0, .05, 0), 1.4,
                Color.fromRGB(25, 25, 25), 24);
        if (elapsed == 60L) {
            boolean solo = combatPlayers().size() == 1;
            for (var entry : recordedLocations.entrySet()) {
                Player player = player(entry.getKey());
                if (player == null) continue;
                player.teleport(entry.getValue());
                if (solo) player.addPotionEffect(new PotionEffect(PotionEffectType.JUMP_BOOST, 30, 2, true, true, true));
            }
        }
        if (elapsed == 72L) {
            for (Location location : recordedLocations.values()) {
                location.getWorld().spawnParticle(Particle.EXPLOSION, location, 2);
                for (Player player : combatPlayers()) {
                    if (player.getLocation().distanceSquared(location) <= 9.0)
                        hurt(player, cfg("abilities.time-funeral.damage", 700.0), randomGaugeGain(), 1);
                }
            }
        }
        if (elapsed >= 82L) finishSkillWithCooldown(18.0);
    }

    private void tickDoomLink(long elapsed) {
        if (elapsed == 1L) {
            List<Player> players = combatPlayers();
            if (players.size() >= 2) {
                linkFirst = players.get(0).getUniqueId();
                linkSecond = players.get(1).getUniqueId();
            } else if (!players.isEmpty()) {
                linkFirst = players.getFirst().getUniqueId();
                linkSecond = nearestCrystal(players.getFirst().getLocation(), 100).map(c -> c.entity.getUniqueId()).orElse(null);
            }
        }
        Location first = entityLocation(linkFirst);
        Location second = entityLocation(linkSecond);
        if (first == null || second == null) { finishSkillWithCooldown(18.0); return; }
        drawLine(first.clone().add(0, 1, 0), second.clone().add(0, 1, 0), Color.fromRGB(40, 230, 180), .45);
        double distance = first.distance(second);
        Player firstPlayer = player(linkFirst);
        Player secondPlayer = player(linkSecond);
        if (elapsed % 20L == 0L && (distance > cfg("abilities.doom-link.maximum-distance", 13)
                || distance < cfg("abilities.doom-link.minimum-distance", 5))) {
            if (firstPlayer != null) hurt(firstPlayer, cfg("abilities.doom-link.damage", 290.0), 2, 0);
            if (secondPlayer != null) hurt(secondPlayer, cfg("abilities.doom-link.damage", 290.0), 2, 0);
        }
        if (distancePointToSegment(controller.getLocation().toVector(), first.toVector(), second.toVector()) < 1.8) {
            vulnerableUntil = Math.max(vulnerableUntil, age + 80L);
            ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1, 0),
                    8, .5, .8, .5, .01, Color.fromRGB(230, 250, 250), 1.4f);
        }
        if (elapsed >= 160L) finishSkillWithCooldown(18.0);
    }

    private void tickNightMeteors(long elapsed) {
        if (elapsed == 1L) {
            List<Player> players = combatPlayers();
            int count = Math.max(3, cfgInt("abilities.night-meteors.count", 7));
            for (int index = 0; index < count && !players.isEmpty(); index++) {
                Player target = players.get(index % players.size());
                Location at = target.getLocation().clone().add(target.getVelocity().multiply(index * 3.0));
                at.setYaw(index % 3 == 0 ? 1 : 0); // yaw is a harmless large/small marker flag.
                recordedLocations.put(UUID.randomUUID(), at);
            }
        }
        int index = 0;
        for (Location impact : recordedLocations.values()) {
            long due = 28L + index++ * 10L;
            double radius = impact.getYaw() == 1 ? 4.2 : 2.2;
            drawCircle(impact.clone().add(0, .05, 0), radius, Color.fromRGB(20, 230, 170), 32);
            drawMagicCircle(impact, radius, -age * .12);
            if (elapsed == due) resolveMeteor(impact, radius);
        }
        if (elapsed >= 120L) finishSkillWithCooldown(17.0);
    }

    private void resolveMeteor(Location impact, double radius) {
        impact.getWorld().spawnParticle(Particle.EXPLOSION, impact, 3);
        impact.getWorld().playSound(impact, Sound.ENTITY_GENERIC_EXPLODE, 1.5f, .55f);
        Crystal crystal = crystalAt(impact, radius + 1);
        if (crystal != null) destroyCrystal(crystal, impact);
        for (Player player : combatPlayers()) {
            if (player.getLocation().distanceSquared(impact) <= radius * radius)
                hurt(player, cfg("abilities.night-meteors.damage", 580.0), randomGaugeGain(), 1);
        }
    }

    private void tickNegateHealing(long elapsed) {
        if (elapsed == 20L) {
            Player selected = combatPlayers().stream().max(Comparator.comparingLong(
                    p -> recentlyHealed.getOrDefault(p.getUniqueId(), 0L))).orElse(targetPlayer());
            if (selected != null) {
                skillTargetId = selected.getUniqueId();
                inverseHealingUntil.put(selected.getUniqueId(), age + 80L);
                selected.sendActionBar(plugin.messages().component("boss.end-witch.inverse-healing"));
            }
        }
        Player target = player(skillTargetId);
        if (target != null && elapsed % 4L == 0L) {
            target.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, target.getEyeLocation(), 3, .35, .55, .35, .02);
            ParticleCompat.spawnDust(target.getWorld(), target.getLocation().add(0, 1, 0), 5,
                    .45, .65, .45, .01, Color.fromRGB(25, 25, 25), 1.1f);
        }
        if (elapsed >= 90L) finishSkillWithCooldown(20.0);
    }

    private void tickShadowCage(long elapsed) {
        Player target = player(skillTargetId);
        if (target == null) { finishSkillWithCooldown(18.0); return; }
        if (elapsed == 1L) {
            skillOrigin = target.getLocation().clone();
            for (int index = 0; index < 8; index++) {
                double angle = index * Math.PI / 4;
                Location at = skillOrigin.clone().add(Math.cos(angle) * 4.2, 0, Math.sin(angle) * 4.2);
                spawnAux(AuxType.CAGE, at, Material.CRYING_OBSIDIAN,
                        cfg("abilities.shadow-cage.pillar-health", 2_500.0), age + 110L, target.getUniqueId(), 0);
            }
        }
        double radius = Math.max(1.5, 4.2 - elapsed / 35.0);
        for (Auxiliary aux : auxiliaries.values()) {
            if (aux.type != AuxType.CAGE || !aux.entity.isValid()) continue;
            Vector direction = aux.entity.getLocation().toVector().subtract(skillOrigin.toVector()).setY(0);
            if (direction.lengthSquared() > .01) aux.entity.teleport(skillOrigin.clone().add(direction.normalize().multiply(radius)));
            for (int y = 0; y < 5; y++) ParticleCompat.spawnDust(aux.entity.getWorld(),
                    aux.entity.getLocation().add(0, y * .8, 0), 1, .08, .08, .08, 0,
                    Color.fromRGB(15, 15, 20), 1.5f);
        }
        if (elapsed == 105L) {
            skillOrigin.getWorld().spawnParticle(Particle.EXPLOSION, skillOrigin, 2);
            for (Player player : combatPlayers()) {
                if (player.getLocation().distanceSquared(skillOrigin) <= 12.25)
                    hurt(player, cfg("abilities.shadow-cage.explosion-damage", 700.0), randomGaugeGain(), 1);
            }
        }
        if (elapsed >= 115L) finishSkillWithCooldown(18.0);
    }

    private void tickHeavenInversion(long elapsed) {
        double safeRadius = cfg("abilities.heaven-inversion.safe-radius", 3.0);
        if (elapsed == 1L) {
            recordedLocations.put(UUID.randomUUID(), battlefieldCenter.clone().add(7, .05, 0));
            recordedLocations.put(UUID.randomUUID(), battlefieldCenter.clone().add(-7, .05, 0));
        }
        for (Location safe : recordedLocations.values()) drawCircle(safe, safeRadius, Color.WHITE, 36);
        if (elapsed == 18L) {
            for (Player player : combatPlayers()) player.addPotionEffect(
                    new PotionEffect(PotionEffectType.LEVITATION, 36, 1, true, true, true));
        }
        if (elapsed == 55L) {
            for (Player player : combatPlayers()) {
                player.removePotionEffect(PotionEffectType.LEVITATION);
                player.setVelocity(new Vector(0, -2.1, 0));
                boolean safe = recordedLocations.values().stream().anyMatch(
                        location -> horizontalDistance(location, player.getLocation()) <= safeRadius);
                if (safe) rebelUntil.put(player.getUniqueId(), age + 60L);
                else hurt(player, cfg("abilities.heaven-inversion.damage", 985.0), randomGaugeGain(), 2);
            }
        }
        if (elapsed >= 75L) finishSkillWithCooldown(22.0);
    }

    private void tickSevenEnds(long elapsed) {
        if (elapsed == 1L) sevenHitPlayers.clear();
        if (elapsed >= 10L && elapsed <= 130L && (elapsed - 10L) % 20L == 0L) {
            Player target = targetPlayer();
            if (target != null) {
                StrikeType type = StrikeType.values()[ThreadLocalRandom.current().nextInt(StrikeType.values().length)];
                Vector direction = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
                if (direction.lengthSquared() < .01) direction = new Vector(0, 0, 1);
                sevenStrikes.add(new SevenStrike(type, target.getLocation().clone(), direction.normalize(), age + 12L));
            }
        }
        if (elapsed >= 160L) {
            for (Player player : combatPlayers()) {
                if (!sevenHitPlayers.contains(player.getUniqueId())) rebelUntil.put(player.getUniqueId(), age + 120L);
            }
            finishSkillWithCooldown(28.0);
        }
    }

    private void beginWorldEnd() {
        worldEndPending = false;
        if (phase == Phase.P3) nextForcedWorldEndAt = Long.MAX_VALUE;
        forcedNextSkill = null;
        clearChantModifiers();
        int participants = encounterParticipantCount();
        worldEndShield = cfg("abilities.world-end.shield-health", 2_463.0)
                * Math.max(1.0, participants * cfg("abilities.world-end.shield-player-scaling", .75));
        int playersPerReduction = Math.max(1,
                (int) Math.round(cfg("abilities.world-end.players-per-channel-reduction", 2.0)));
        double reductionSeconds = Math.max(0.0,
                cfg("abilities.world-end.channel-reduction-seconds", 0.0));
        double baseSeconds = Math.max(0.05, cfg("abilities.world-end.channel-seconds", 12.0));
        double minimumSeconds = Math.max(0.05,
                cfg("abilities.world-end.minimum-channel-seconds", 12.0));
        int reductionSteps = Math.max(0, participants - 1) / playersPerReduction;
        worldEndChannelTicks = seconds(Math.max(minimumSeconds,
                baseSeconds - reductionSteps * reductionSeconds));
        controller.teleport(battlefieldCenter.clone());
        beginSkill(Skill.WORLD_END);
        controller.setInvulnerable(false);
        announce("boss.end-witch.world-end-start", "seconds",
                Math.max(1L, (long) Math.ceil(worldEndChannelTicks / 20.0)));
    }

    private void tickWorldEnd(long elapsed) {
        controller.setAI(false);
        drawMagicCircle(battlefieldCenter, 12.0, age * .07);
        drawMagicCircle(battlefieldCenter, 7.0, -age * .12);
        ParticleCompat.spawnDust(controller.getWorld(), controller.getLocation().add(0, 1.5, 0), 12,
                2, 1.2, 2, .02, Color.fromRGB(20, 235, 165), 1.7f);
        if (elapsed % 10L == 0L) {
            for (Player player : combatPlayers()) player.sendActionBar(plugin.messages().component(
                    "boss.end-witch.world-end-shield", "health", Math.round(Math.max(0, worldEndShield))));
        }
        if (elapsed >= Math.max(1L, worldEndChannelTicks)) executeWorldEnd();
    }

    private void executeWorldEnd() {
        if (worldEndExecuted) return;
        worldEndExecuted = true;
        worldEndPending = false;
        announce("boss.end-witch.world-end-failed");
        controller.getWorld().playSound(controller.getLocation(), Sound.ENTITY_WITHER_DEATH, 2.0f, .5f);
        for (Player player : combatPlayers()) player.setHealth(0.0);
        endGauge = plugin.endWitchSettings().gaugeMaximum();
        if (skill == Skill.WORLD_END) finishSkill();
    }

    private void breakWorldEndShield() {
        double maximum = plugin.endWitchSettings().gaugeMaximum();
        endGauge = Math.max(0.0, Math.min(maximum,
                cfg("abilities.world-end.gauge-after-shield-break", 30.0)));
        gaugeFraction = 0.0;
        worldEndPending = false;
        worldEndShield = 0.0;
        vulnerableUntil = age + 160L;
        skill = Skill.SILENCED;
        skillStartedAt = age;
        resetSkillActionClock();
        controller.setInvulnerable(false);
        if (model != null) model.playOnce("silenced", "idle");
        announce("boss.end-witch.world-end-broken", "gauge", Math.round(endGauge));
        controller.getWorld().playSound(controller.getLocation(), Sound.BLOCK_GLASS_BREAK, 2.0f, .55f);
    }

    private void tickBolts() {
        Iterator<Bolt> iterator = bolts.iterator();
        while (iterator.hasNext()) {
            Bolt bolt = iterator.next();
            bolt.life++;
            boolean remove = false;
            for (int substep = 0; substep < 2 && !remove; substep++) {
                bolt.location.add(bolt.velocity.clone().multiply(.5));
                ParticleCompat.spawnDust(bolt.location.getWorld(), bolt.location, 3,
                        .09, .09, .09, .01, Color.fromRGB(10, 205, 155), 1.2f);
                Crystal crystal = crystalAt(bolt.location, 1.0);
                if (crystal != null) {
                    destroyCrystal(crystal, bolt.location);
                    remove = true;
                    continue;
                }
                if (bolt.location.getBlock().getType().isSolid()) { remove = true; continue; }
                for (Player player : combatPlayers()) {
                    if (!player.getBoundingBox().expand(.35).contains(bolt.location.toVector())) continue;
                    hurt(player, cfg("abilities.magic-arrows.damage", 405.0), randomGaugeGain(), 1);
                    remove = true;
                    break;
                }
            }
            if (remove || bolt.life > 80) iterator.remove();
        }
    }

    private void tickAuxiliaries() {
        Iterator<Auxiliary> iterator = auxiliaries.values().iterator();
        while (iterator.hasNext()) {
            Auxiliary aux = iterator.next();
            if (!aux.entity.isValid()) { iterator.remove(); continue; }
            // Auxiliary ArmorStands are only hitboxes/holders.  Keep their
            // equipment visible, but never expose the stand hitbox through a
            // glowing outline (including after a spectral-arrow effect).
            aux.entity.removePotionEffect(PotionEffectType.GLOWING);
            aux.entity.setGlowing(false);
            if (aux.type == AuxType.FLOWER) tickFlower(aux);
            if (age < aux.expiresAt) continue;
            if (aux.type == AuxType.SOUL && !aux.resolved) {
                aux.resolved = true;
                healBoss(aux.storedHealth);
                Player owner = player(aux.owner);
                if (owner != null) owner.sendActionBar(plugin.messages().component("boss.end-witch.soul-lost"));
            }
            aux.entity.remove();
            iterator.remove();
        }
    }

    private void tickFlower(Auxiliary aux) {
        if (age % 10L == 0L) aux.entity.getWorld().spawnParticle(Particle.SPORE_BLOSSOM_AIR,
                aux.entity.getLocation().add(0, .8, 0), 5, 1, .5, 1, .01);
        if (age % 20L != 0L) return;
        double radius = cfg("abilities.funeral-flowers.drain-radius", 5.0);
        for (Player player : combatPlayers()) {
            if (player.getLocation().distanceSquared(aux.entity.getLocation()) <= radius * radius)
                hurt(player, cfg("abilities.funeral-flowers.drain-damage", 130.0), 2, 0);
        }
    }

    private void tickHealZones() {
        Iterator<HealZone> iterator = healZones.iterator();
        while (iterator.hasNext()) {
            HealZone zone = iterator.next();
            if (age >= zone.expiresAt) { iterator.remove(); continue; }
            drawCircle(zone.location, 3.2, Color.fromRGB(55, 245, 145), 30);
            if (age % 20L == 0L && controller.getLocation().distanceSquared(zone.location) <= 10.24)
                healBoss(cfg("abilities.funeral-flowers.boss-heal", 800.0));
        }
    }

    private void tickSevenStrikes() {
        Iterator<SevenStrike> iterator = sevenStrikes.iterator();
        while (iterator.hasNext()) {
            SevenStrike strike = iterator.next();
            telegraphStrike(strike);
            if (age < strike.resolvesAt) continue;
            resolveStrike(strike);
            iterator.remove();
        }
    }

    private void telegraphStrike(SevenStrike strike) {
        Color danger = Color.fromRGB(25, 230, 175);
        switch (strike.type) {
            case CIRCLE, TRACKING -> drawCircle(strike.center, strike.type == StrikeType.CIRCLE ? 4.0 : 2.3, danger, 36);
            case RING -> {
                drawCircle(strike.center, 4, Color.WHITE, 36);
                drawCircle(strike.center, 7, Color.WHITE, 48);
            }
            case LINE -> drawLine(strike.center.clone().subtract(strike.direction.clone().multiply(10)),
                    strike.center.clone().add(strike.direction.clone().multiply(10)), danger, .45);
            case CROSS -> {
                drawLine(strike.center.clone().add(-10, 0, 0), strike.center.clone().add(10, 0, 0), danger, .5);
                drawLine(strike.center.clone().add(0, 0, -10), strike.center.clone().add(0, 0, 10), danger, .5);
            }
        }
    }

    private void resolveStrike(SevenStrike strike) {
        strike.center.getWorld().playSound(strike.center, Sound.ENTITY_EVOKER_CAST_SPELL, 1.1f, .58f);
        for (Player player : combatPlayers()) {
            boolean hit = switch (strike.type) {
                case CIRCLE -> horizontalDistance(player.getLocation(), strike.center) <= 4;
                case TRACKING -> horizontalDistance(player.getLocation(), strike.center) <= 2.3;
                case RING -> {
                    double d = horizontalDistance(player.getLocation(), strike.center);
                    yield Math.abs(d - 4) < 1.3 || Math.abs(d - 7) < 1.3;
                }
                case LINE -> distancePointToSegment(player.getLocation().toVector(),
                        strike.center.toVector().subtract(strike.direction.clone().multiply(12)),
                        strike.center.toVector().add(strike.direction.clone().multiply(12))) <= 1.2;
                case CROSS -> Math.abs(player.getX() - strike.center.getX()) <= 1.2
                        || Math.abs(player.getZ() - strike.center.getZ()) <= 1.2;
            };
            if (!hit) continue;
            sevenHitPlayers.add(player.getUniqueId());
            hurt(player, cfg("abilities.seven-ends.damage", 700.0), randomGaugeGain(), 1);
        }
    }

    private void spawnInitialCrystals() {
        int count = phase == Phase.P2 ? 4 : Math.max(2, cfgInt("end-gauge.initial-crystals", 3));
        for (int index = crystals.size(); index < count; index++) {
            double angle = Math.PI * 2 * index / count;
            spawnCrystal(battlefieldCenter.clone().add(Math.cos(angle) * 13, 0, Math.sin(angle) * 13));
        }
    }

    private void spawnCrystal(Location location) {
        if (location.getWorld() == null || crystals.size() >= cfgInt("end-gauge.maximum-crystals", 12)) return;
        Location grounded = groundedCrystalLocation(location);
        EnderCrystal entity = grounded.getWorld().spawn(grounded, EnderCrystal.class, crystal -> {
            crystal.setShowingBottom(false);
            crystal.setBeamTarget(null);
            crystal.setInvulnerable(true);
            crystal.setPersistent(true);
            crystal.getPersistentDataContainer().set(plugin.bossTypeKey(), PersistentDataType.STRING,
                    "end_witch_crystal");
        });
        crystals.add(new Crystal(entity));
    }

    private void destroyCrystal(Crystal crystal, Location impact) {
        if (crystal == null || !crystals.remove(crystal)) return;
        crystal.entity.remove();
        endGauge = Math.max(0.0, endGauge - plugin.endWitchSettings().crystalReduction());
        impact.getWorld().spawnParticle(Particle.EXPLOSION, impact, 2);
        ParticleCompat.spawnDust(impact.getWorld(), impact, 25, .8, 1, .8, .08,
                Color.fromRGB(30, 245, 180), 1.5f);
        impact.getWorld().playSound(impact, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 1.8f, .65f);
        announce("boss.end-witch.crystal-broken", "gauge", Math.round(endGauge));
    }

    public boolean ownsAuxiliary(Entity entity) {
        UUID id = entity.getUniqueId();
        return auxiliaries.containsKey(id) || crystals.stream().anyMatch(crystal -> crystal.entity.getUniqueId().equals(id));
    }

    public boolean handleAuxiliaryDamage(EntityDamageEvent event) {
        Crystal crystal = crystals.stream().filter(c -> c.entity.getUniqueId().equals(event.getEntity().getUniqueId()))
                .findFirst().orElse(null);
        if (crystal != null) {
            event.setCancelled(true); // Only a boss spell may resolve and destroy a crystal.
            if (damagePlayer(event) != null) damagePlayer(event).sendActionBar(
                    plugin.messages().component("boss.end-witch.crystal-boss-only"));
            return true;
        }
        Auxiliary aux = auxiliaries.get(event.getEntity().getUniqueId());
        if (aux == null) return false;
        event.setCancelled(true);
        Player attacker = damagePlayer(event);
        if (attacker == null) return true;
        aux.health -= Math.max(1.0, event.getFinalDamage());
        aux.entity.getWorld().playSound(aux.entity.getLocation(), Sound.BLOCK_AMETHYST_BLOCK_HIT, 1.0f, .9f);
        if (aux.health <= 0.0) resolveAuxiliary(aux, attacker);
        return true;
    }

    public void handleAuxiliaryDeath(EntityDeathEvent event) {
        Auxiliary aux = auxiliaries.remove(event.getEntity().getUniqueId());
        if (aux != null) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    private void resolveAuxiliary(Auxiliary aux, Player breaker) {
        if (aux.resolved) return;
        aux.resolved = true;
        auxiliaries.remove(aux.entity.getUniqueId());
        Location at = aux.entity.getLocation();
        aux.entity.remove();
        switch (aux.type) {
            case SOUL -> {
                Player owner = player(aux.owner);
                if (owner != null) owner.setHealth(Math.min(owner.getMaxHealth(), owner.getHealth() + aux.storedHealth));
            }
            case FLOWER -> healZones.add(new HealZone(at, age + seconds(8)));
            case CAGE -> {
                // Breaking one pillar opens a real escape route: remove its two neighbours too.
                auxiliaries.values().stream().filter(other -> other.type == AuxType.CAGE
                                && other.entity.getLocation().distanceSquared(at) <= 12.0)
                        .toList().forEach(other -> { other.entity.remove(); auxiliaries.remove(other.entity.getUniqueId()); });
            }
            default -> { }
        }
        at.getWorld().spawnParticle(Particle.BLOCK, at.add(0, 1, 0), 15, .5, .7, .5, .08,
                new ItemStack(Material.AMETHYST_BLOCK).getType().createBlockData());
    }

    private Auxiliary spawnAux(AuxType type, Location location, Material helmet, double health,
                               long expiresAt, UUID owner, double storedHealth) {
        ArmorStand stand = location.getWorld().spawn(location, ArmorStand.class, entity -> {
            entity.setInvisible(true);
            entity.setGravity(false);
            entity.setBasePlate(false);
            entity.setArms(false);
            entity.setSmall(false);
            entity.setPersistent(true);
            entity.getEquipment().setHelmet(new ItemStack(helmet));
            entity.customName(legacyComponent(auxiliaryName(type)));
            entity.setCustomNameVisible(true);
            entity.setGlowing(false);
            entity.getPersistentDataContainer().set(plugin.bossTypeKey(), PersistentDataType.STRING,
                    "end_witch_auxiliary");
        });
        Auxiliary aux = new Auxiliary(type, stand, Math.max(1.0, health), expiresAt);
        aux.owner = owner;
        aux.storedHealth = storedHealth;
        auxiliaries.put(stand.getUniqueId(), aux);
        return aux;
    }

    private String auxiliaryName(AuxType type) {
        return plugin.messages().raw("boss.end-witch.aux."
                + type.name().toLowerCase(Locale.ROOT));
    }

    /** Keeps party members independent after disabling Bukkit's victim-wide invulnerability frames. */
    public boolean rejectRateLimitedPlayerDamage(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getUniqueId().equals(controller.getUniqueId())) return false;
        // A scripted lock is complete immunity: cancel before downstream on-hit
        // plugins can spend procs or cooldowns on a hit that cannot hurt the boss.
        if (controllerDamageLocked()) return true;
        Player attacker = damagePlayer(event);
        if (attacker == null) return false;
        if (isLinkedPlayerDamage(event, attacker)
                || PlayerDamageEventSupport.isYknRpgSyntheticDamage()) {
            preApprovedPlayerDamageEvents.add(event);
            return false;
        }
        if (event.getDamage() <= 0.0) return false;
        if (!acceptPlayerHit(attacker)) return true;
        preApprovedPlayerDamageEvents.add(event);
        return false;
    }

    public void clearPlayerDamageApproval(EntityDamageEvent event) {
        preApprovedPlayerDamageEvents.remove(event);
    }

    public boolean handleIncomingDamage(EntityDamageEvent event) {
        boolean preApproved = preApprovedPlayerDamageEvents.contains(event);
        if (dying || pendingPhase != null || entranceActive || age <= phaseInvulnerableUntil || phaseHealthLockUntil > 0L
                || skill == Skill.PHASE_TRANSITION) {
            event.setCancelled(true);
            return true;
        }
        Player attacker = damagePlayer(event);
        if (attacker != null && event.getDamage() > 0.0 && !preApproved
                && !acceptPlayerHit(attacker)) {
            event.setCancelled(true);
            return true;
        }
        if (attacker != null) {
            double outgoing = plugin.slayer().outgoingDamage(attacker, event.getDamage(),
                    phase == Phase.P3 && health() <= maxHealth() * .2);
            if (age < rebelUntil.getOrDefault(attacker.getUniqueId(), 0L)) outgoing *= 1.4;
            if (age < vulnerableUntil) outgoing *= 1.3;
            event.setDamage(outgoing);
        }
        // The 500k cap represents the configured effective phase pool for every
        // damage source, including EcoPets whose damager is not a Player entity.
        if (skill != Skill.WORLD_END) {
            event.setDamage(event.getDamage() * phaseHealthCapDamageMultiplier());
        }
        double damage = Math.max(0.0, event.getFinalDamage());
        if (skill == Skill.WORLD_END) {
            event.setCancelled(true);
            if (damage > 0 && attacker != null) {
                worldEndShield = Math.max(0, worldEndShield - damage);
                threat.merge(attacker.getUniqueId(), damage, Double::sum);
                if (worldEndShield <= 0) breakWorldEndShield();
            }
            return true;
        }
        double applied = Math.min(health(), damage);
        if (attacker != null && applied > 0) threat.merge(attacker.getUniqueId(), applied, Double::sum);
        if (damage >= health()) {
            event.setCancelled(true);
            event.setDamage(0);
            phaseHealthRemaining = Math.min(1.0, phaseHealth());
            controller.setHealth(Math.min(1.0, phaseHealth()));
            if (phase == Phase.P1) queuePhaseTransition(Phase.P2);
            else if (phase == Phase.P2) queuePhaseTransition(Phase.P3);
            else finishDeath();
            return true;
        }
        phaseHealthRemaining = Math.max(0, phaseHealthRemaining - applied);
        if (applied > 0) {
            playHitFeedback(applied);
            playHurtReaction(attacker);
            registerReactiveTeleport(attacker);
        }
        return false;
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
            transitionTo(next);
        });
    }

    public void handleAttack(EntityDamageByEntityEvent event) {
        if (dealingSkillDamage) return;
        if (!(event.getEntity() instanceof Player player)) return;
        double damage = plugin.endWitchSettings().attackDamage()
                * (1.0 + marks.getOrDefault(player.getUniqueId(), 0)
                * plugin.endWitchSettings().markDamagePerStack());
        event.setDamage(plugin.slayer().incomingDamage(player, scaledDamage(damage)));
        disableShield(player);
    }

    public void handleHealing(Player player, EntityRegainHealthEvent event) {
        if (!validTarget(player, plugin.endWitchSettings().bossbarRange())) return;
        recentlyHealed.put(player.getUniqueId(), age);
        if (age >= inverseHealingUntil.getOrDefault(player.getUniqueId(), 0L)
                || event.getAmount() < cfg("abilities.negate-healing.trigger-amount", 4.0)) return;
        double damage = event.getAmount()
                * Math.max(0.0, cfg("abilities.negate-healing.damage-multiplier", 1.0));
        event.setCancelled(true);
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && !player.isDead()) hurt(player, damage, 2, 0);
        });
    }

    private void transitionTo(Phase next) {
        reactiveHitCount = 0;
        phase = next;
        persistPhase();
        long transition = Math.max(PHASE_ANIMATION_TICKS,
                plugin.endWitchSettings().transitionInvulnerabilityTicks());
        phaseInvulnerableUntil = age + transition;
        phaseHealthLockUntil = phaseInvulnerableUntil;
        refillCurrentPhaseHealth();
        if (next == Phase.P2) {
            for (Crystal crystal : List.copyOf(crystals)) crystal.entity.remove();
            crystals.clear();
            for (int index = 0; index < 4; index++) {
                double angle = index * Math.PI / 2;
                spawnCrystal(battlefieldCenter.clone().add(Math.cos(angle) * 14, 0, Math.sin(angle) * 14));
            }
            announce("boss.end-witch.phase-two");
        } else {
            nextForcedWorldEndAt = age + plugin.endWitchSettings().phaseThreeUltimateTimeoutTicks();
            announce("boss.end-witch.phase-three");
        }
        worldEndPending = false;
        forcedNextSkill = null;
        clearChantModifiers();
        skill = Skill.PHASE_TRANSITION;
        skillStartedAt = age;
        resetSkillActionClock();
        controller.setAI(false);
        controller.setInvulnerable(true);
        if (model != null) model.playOnce("phase_transition", "idle");
        updateBars();
    }

    private void tickPhaseProtection() {
        if (phaseHealthLockUntil > 0) {
            if (age <= phaseHealthLockUntil) refillCurrentPhaseHealth();
            else {
                refillCurrentPhaseHealth();
                phaseHealthLockUntil = 0;
            }
        }
        controller.setInvulnerable(pendingPhase != null || phaseHealthLockUntil > 0 || age <= phaseInvulnerableUntil
                || skill == Skill.PHASE_TRANSITION || entranceActive || dying);
    }

    private void refillCurrentPhaseHealth() {
        double maximum = phaseHealth();
        setBase(Attribute.MAX_HEALTH, maximum);
        phaseHealthRemaining = maximum;
        synchronizeControllerHealth();
    }

    private double phaseHealth() {
        return Math.min(BossHealthLimits.MAXIMUM, desiredPhaseHealth());
    }

    private double desiredPhaseHealth() {
        EndWitchSettings settings = plugin.endWitchSettings();
        double base = switch (phase) {
            case P1 -> settings.phaseOneHealth();
            case P2 -> settings.phaseTwoHealth();
            case P3 -> settings.phaseThreeHealth();
        };
        return Math.max(1.0, base * encounterHealthMultiplier);
    }

    private double phaseHealthCapDamageMultiplier() {
        if (!plugin.getConfig().getBoolean(
                "end-witch.damage-intake.preserve-effective-health-above-cap", true)) return 1.0;
        double desired = desiredPhaseHealth();
        if (desired <= BossHealthLimits.MAXIMUM) return 1.0;
        return BossHealthLimits.MAXIMUM / desired;
    }

    private void synchronizeControllerHealth() {
        if (!controller.isValid() || controller.isDead()) return;
        double health = Math.max(1.0, Math.min(phaseHealth(), phaseHealthRemaining));
        if (Math.abs(controller.getHealth() - health) > 1.0E-6) controller.setHealth(health);
    }

    public void applySettings(boolean preserveHealthFraction) {
        EndWitchSettings settings = plugin.endWitchSettings();
        double oldMaximum = Math.max(1.0, phaseHealth());
        double fraction = preserveHealthFraction ? Math.min(1.0, health() / oldMaximum) : 1.0;
        setBase(Attribute.MAX_HEALTH, phaseHealth());
        setBase(Attribute.ATTACK_DAMAGE, settings.attackDamage());
        setBase(Attribute.ARMOR, settings.armor());
        setBase(Attribute.FOLLOW_RANGE, settings.followRange());
        setBase(Attribute.KNOCKBACK_RESISTANCE, settings.knockbackResistance());
        applyMovementSpeed();
        controller.setInvisible(true);
        controller.setMaximumNoDamageTicks(0);
        controller.setNoDamageTicks(0);
        controller.customName(legacyComponent(settings.displayName()));
        controller.setCustomNameVisible(false);
        phaseHealthRemaining = Math.max(1.0, Math.min(phaseHealth(), phaseHealth() * fraction));
        synchronizeControllerHealth();
        ensureModel();
    }

    private void applyMovementSpeed() {
        double speed = plugin.endWitchSettings().movementSpeed()
                * actionSpeedMultiplier() * (p3SpeedBoost ? 1.25 : 1.0);
        setBase(Attribute.MOVEMENT_SPEED, Math.min(1.0, speed));
    }

    private void setBase(Attribute attribute, double value) {
        AttributeInstance instance = controller.getAttribute(attribute);
        if (instance != null) instance.setBaseValue(value);
    }

    private void ensureModel() {
        if (model != null && model.valid()) return;
        closeModel();
        EndWitchSettings settings = plugin.endWitchSettings();
        model = plugin.betterModel().attachStable(settings.modelId(), controller,
                settings.modelScale(), "idle", false);
        if (model != null) model.setGlow(true, GLOW_COLOR);
    }

    public void recoverModelAfterSpectralArrow() {
        controller.removePotionEffect(PotionEffectType.GLOWING);
        controller.setGlowing(false);
        controller.setInvisible(true);
        ensureModel();
        if (model != null) {
            model.refresh();
            model.setGlow(true, GLOW_COLOR);
            if (dying) model.playOnceAndHold("death");
            else if (entranceActive) model.playOnceAndHold("spawn");
            else model.playLoop(skill == Skill.NONE ? (walking ? "walk" : "idle") : "cast");
        }
    }

    private void acquireTarget() {
        Player best = null;
        double bestThreat = -1;
        double bestDistance = Double.MAX_VALUE;
        for (Player player : combatPlayers()) {
            double currentThreat = threat.getOrDefault(player.getUniqueId(), 0.0);
            double distance = player.getLocation().distanceSquared(controller.getLocation());
            if (currentThreat > bestThreat || currentThreat == bestThreat && distance < bestDistance) {
                best = player;
                bestThreat = currentThreat;
                bestDistance = distance;
            }
        }
        controller.setTarget(best);
    }

    private void tickChase(Player target) {
        if (target == null) { playMovement(false); return; }
        controller.setAI(true);
        controller.setTarget(target);
        Vector toward = target.getLocation().toVector().subtract(controller.getLocation().toVector()).setY(0);
        boolean moving = toward.lengthSquared() > 36.0;
        playMovement(moving);
        if (!moving) return;
        double actionSpeed = actionSpeedMultiplier();
        Vector desired = toward.normalize().multiply(Math.min(.34,
                (.05 + plugin.endWitchSettings().movementSpeed() * .42) * actionSpeed));
        Vector current = controller.getVelocity();
        controller.setVelocity(new Vector(current.getX() * .52 + desired.getX() * .48,
                current.getY(), current.getZ() * .52 + desired.getZ() * .48));
    }

    private void playMovement(boolean moving) {
        if (moving == walking) return;
        walking = moving;
        if (model != null) model.playLoop(moving ? "walk" : "idle");
    }

    private void finishSkillWithCooldown(double fallbackSeconds) {
        cooldowns.put(skill, age + seconds(cfg("abilities."
                + configSkillName(skill) + ".cooldown-seconds", fallbackSeconds)));
        finishSkill();
    }

    private void finishSkill() {
        boolean chant = skill == Skill.SILENT_CHANT;
        Skill previous = skill;
        Skill chained = null;
        if (nextSkillChains && !executingChainedSkill && !chant
                && skill != Skill.WORLD_END && !worldEndPending) {
            chained = skillRotation.choose(skillPool(), candidate -> candidate != Skill.SILENT_CHANT
                    && candidate != previous && skillReady(candidate));
        }
        boolean startsChain = chained != null;
        if (nextSkillHeals && !chant && !startsChain) {
            healBoss(maxHealth() * Math.max(0.0,
                    cfg("abilities.silent-chant.green-heal-percent", 4.0)) / 100.0);
        }
        if (!chant && !startsChain) clearChantModifiers();
        skill = Skill.NONE;
        skillStartedAt = 0;
        skillTargetId = null;
        recordedLocations.clear();
        controller.setAI(true);
        acquireTarget();
        if (model != null) model.playLoop("idle");
        double castDelay = phase == Phase.P3
                ? cfg("abilities.cast-timing.phase-three-delay-seconds", 0.9)
                : cfg("abilities.cast-timing.phase-one-two-delay-seconds", 1.4);
        nextSkillAt = age + actionDelayTicks(castDelay);
        if (startsChain) {
            forcedNextSkill = chained;
            executingChainedSkill = true;
            nextSkillAt = age + actionDelayTicks(cfg("abilities.cast-timing.chain-skill-delay-seconds",
                    cfg("abilities.cast-timing.repeated-skill-delay-seconds", 0.4)));
        }
    }

    private void clearChantModifiers() {
        nextSkillDamageBonus = 0;
        nextSkillHeals = false;
        nextSkillChains = false;
        executingChainedSkill = false;
    }

    private void finishDeath() {
        if (dying) return;
        dying = true;
        deathStartedAt = age;
        entranceActive = false;
        walking = false;
        skill = Skill.NONE;
        worldEndPending = false;
        forcedNextSkill = null;
        clearChantModifiers();
        plugin.endWitchArenaManager().onBossDeathStarted(this);
        clearPlayerHealthBars();
        clearAuxiliaries();
        for (Crystal crystal : crystals) crystal.entity.remove();
        crystals.clear();
        bolts.clear();
        healZones.clear();
        sevenStrikes.clear();
        phaseHealthRemaining = Math.min(1.0, phaseHealth());
        synchronizeControllerHealth();
        controller.setAI(false);
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        if (model != null) model.playOnceAndHold("death");
        Location center = controller.getLocation().clone().add(0, 1.0, 0);
        controller.getWorld().playSound(center, Sound.ENTITY_ILLUSIONER_DEATH, 2.4f, 0.58f);
        controller.getWorld().spawnParticle(Particle.SOUL, center,
                55, 1.0, 1.4, 1.0, 0.06);
    }

    private void tickDeathSequence() {
        long elapsed = age - deathStartedAt;
        Location base = controller.getLocation();
        Location center = base.clone().add(0, 1.0, 0);
        controller.setAI(false);
        controller.setInvulnerable(true);
        controller.setVelocity(new Vector());
        if (elapsed % 2L == 0L) {
            double progress = Math.min(1.0, elapsed / (double) DEATH_ANIMATION_TICKS);
            drawMagicCircle(base, Math.max(1.3, 5.2 - progress * 3.9), -age * 0.16);
            controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center,
                    14, 0.9, 1.25, 0.9, 0.11);
            controller.getWorld().spawnParticle(Particle.SOUL, center,
                    9, 0.75, 1.0, 0.75, 0.045);
        }
        if (elapsed == 18L || elapsed == 38L) {
            controller.getWorld().playSound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE,
                    1.8f, elapsed == 18L ? 0.62f : 0.48f);
        }
        if (elapsed < DEATH_ANIMATION_TICKS) return;

        controller.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
        controller.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, center,
                90, 1.4, 1.7, 1.4, 0.09);
        controller.getWorld().spawnParticle(Particle.REVERSE_PORTAL, center,
                150, 1.8, 2.0, 1.8, 0.16);
        controller.getWorld().playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE,
                2.8f, 0.48f);
        closeModel();
        controller.setInvulnerable(false);
        controller.setHealth(0.0);
    }

    public void onDeath(EntityDeathEvent event) {
        event.getDrops().clear();
        event.setDroppedExp(plugin.endWitchSettings().experience());
        plugin.endWitchArenaManager().onBossDefeated(this);
        detach();
    }

    public void remove() {
        detach();
        if (controller.isValid()) controller.remove();
    }

    public void detach() {
        nextAcceptedPlayerHit.clear();
        preApprovedPlayerDamageEvents.clear();
        closeModel();
        clearPlayerHealthBars();
        clearAuxiliaries();
        for (Crystal crystal : crystals) if (crystal.entity.isValid()) crystal.entity.remove();
        crystals.clear();
    }

    private void clearAuxiliaries() {
        auxiliaries.values().forEach(aux -> { if (aux.entity.isValid()) aux.entity.remove(); });
        auxiliaries.clear();
    }

    private void closeModel() {
        if (model != null) model.close();
        model = null;
    }

    private void updateBars() {
        double maximum = Math.max(1.0, phaseHealth());
        double gaugeMaximum = plugin.endWitchSettings().gaugeMaximum();
        double progress = Math.max(0, Math.min(1, phaseHealthRemaining / maximum));
        double limit = plugin.endWitchSettings().bossbarRange() * plugin.endWitchSettings().bossbarRange();
        Set<UUID> visiblePlayers = new HashSet<>();
        for (Player player : controller.getWorld().getPlayers()) {
            if (player.getLocation().distanceSquared(controller.getLocation()) <= limit
                    && player.getGameMode() != GameMode.SPECTATOR) {
                visiblePlayers.add(player.getUniqueId());
                BossBar bar = playerHealthBars.computeIfAbsent(player.getUniqueId(), ignored -> {
                    BossBar created = Bukkit.createBossBar("", BarColor.GREEN, BarStyle.SOLID,
                            BarFlag.DARKEN_SKY);
                    created.setVisible(true);
                    created.addPlayer(player);
                    return created;
                });
                String title = plugin.messages().raw("boss.end-witch.bossbar",
                        "name", plugin.endWitchSettings().displayName(), "phase", phase.name())
                        + plugin.messages().raw("boss.end-witch.bossbar-status",
                        "mark", marks.getOrDefault(player.getUniqueId(), 0),
                        "gauge", Math.round(endGauge), "max", Math.round(gaugeMaximum));
                bar.setProgress(progress);
                bar.setTitle(legacyString(title));
            }
        }
        Iterator<Map.Entry<UUID, BossBar>> iterator = playerHealthBars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BossBar> entry = iterator.next();
            if (visiblePlayers.contains(entry.getKey())) continue;
            entry.getValue().removeAll();
            iterator.remove();
        }
    }

    private void clearPlayerHealthBars() {
        playerHealthBars.values().forEach(BossBar::removeAll);
        playerHealthBars.clear();
    }

    private void hurt(Player player, double baseDamage, double gaugeGain, int markGain) {
        if (player == null || player.isDead()) return;
        double modifier = 1.0 + marks.getOrDefault(player.getUniqueId(), 0)
                * plugin.endWitchSettings().markDamagePerStack();
        double chantMultiplier = nextSkillDamageBonus > 0
                ? 1.0 + Math.max(0.0,
                cfg("abilities.silent-chant.red-damage-bonus-percent", 25.0)) / 100.0
                : 1.0;
        double damage = scaledDamage(baseDamage * modifier * chantMultiplier);
        damage = plugin.slayer().incomingDamage(player, damage);
        dealSkillDamage(player, damage);
        disableShield(player);
        addGauge(gaugeGain);
        if (markGain > 0) addMarks(player, markGain);
    }

    private void addMarks(Player player, int amount) {
        int previous = marks.getOrDefault(player.getUniqueId(), 0);
        int next = Math.min(3, previous + amount);
        marks.put(player.getUniqueId(), next);
        if (next >= 3 && previous < 3) {
            player.setSprinting(false);
            player.getWorld().spawnParticle(Particle.EXPLOSION, player.getLocation(), 1);
            double damage = scaledDamage(cfg("marks.third-stack-explosion-damage", 290.0));
            dealSkillDamage(player, plugin.slayer().incomingDamage(player, damage));
            disableShield(player);
        }
    }

    private void dealSkillDamage(Player player, double damage) {
        dealingSkillDamage = true;
        try {
            plugin.slayer().dealSkillDamage(player, Math.max(0.0, damage), skillDamageSource);
        } finally {
            dealingSkillDamage = false;
        }
    }

    private double scaledDamage(double baseDamage) {
        EndWitchSettings settings = plugin.endWitchSettings();
        double multiplier = switch (phase) {
            case P1 -> 1.0;
            case P2 -> 1.0 + settings.phaseTwoDamageBonus();
            case P3 -> 1.0 + settings.phaseThreeDamageBonus();
        };
        return Math.max(0.0, baseDamage) * multiplier;
    }

    private void setMark(Player player, int value) {
        if (value <= 0) marks.remove(player.getUniqueId());
        else marks.put(player.getUniqueId(), Math.min(3, value));
    }

    private void addGauge(double amount) {
        double normalized = amount / encounterParticipantCount();
        endGauge = Math.max(0, Math.min(plugin.endWitchSettings().gaugeMaximum(), endGauge + normalized));
    }

    private int randomGaugeGain() {
        int minimum = cfgInt("end-gauge.skill-hit-minimum", 2);
        int maximum = Math.max(minimum, cfgInt("end-gauge.skill-hit-maximum", 5));
        return ThreadLocalRandom.current().nextInt(minimum, maximum + 1);
    }

    private void healBoss(double amount) {
        phaseHealthRemaining = Math.min(maxHealth(), phaseHealthRemaining + Math.max(0, amount));
        synchronizeControllerHealth();
    }

    private void disableShield(Player player) {
        if (!player.isBlocking()) return;
        player.setCooldown(Material.SHIELD, seconds(cfg("abilities.shield-disable-seconds", 5.0)) > Integer.MAX_VALUE
                ? Integer.MAX_VALUE : (int) seconds(cfg("abilities.shield-disable-seconds", 5.0)));
        player.getWorld().playSound(player.getLocation(), Sound.ITEM_SHIELD_BREAK, 1.0f, .8f);
    }

    private void playHitFeedback(double damage) {
        if (age < nextHitFeedbackAt) return;
        nextHitFeedbackAt = age + 4;
        Location at = controller.getLocation().add(0, 1.1, 0);
        at.getWorld().playSound(at, Sound.ENTITY_ILLUSIONER_HURT, 1.1f, .8f);
        at.getWorld().spawnParticle(Particle.CRIT, at, 7, .4, .6, .4, .08);
    }

    private void playHurtReaction(Player attacker) {
        if (attacker == null || skill != Skill.NONE || dying || entranceActive
                || age <= phaseInvulnerableUntil || phaseHealthLockUntil > 0L
                || age < nextHurtReactionAt) return;
        nextHurtReactionAt = age + Math.max(1L,
                seconds(cfg("combat-feedback.hurt-animation-cooldown-seconds", 0.2)));
        Vector away = controller.getLocation().toVector()
                .subtract(attacker.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 0.001) {
            away = controller.getLocation().getDirection().setY(0.0).multiply(-1.0);
        }
        if (away.lengthSquared() > 0.001) {
            double horizontal = Math.max(0.0,
                    cfg("combat-feedback.hurt-knockback-horizontal", 0.18));
            double vertical = Math.max(0.0,
                    cfg("combat-feedback.hurt-knockback-vertical", 0.06));
            Vector recoil = away.normalize().multiply(horizontal);
            recoil.setY(Math.max(controller.getVelocity().getY(), vertical));
            controller.setVelocity(recoil);
        }
        if (model != null) model.playOnce("hurt", walking ? "walk" : "idle");
    }

    private void registerReactiveTeleport(Player attacker) {
        if (attacker == null || skill == Skill.WORLD_END || dying || entranceActive) return;
        reactiveHitCount++;
        long scaledHits = (long) Math.max(1, cfgInt("reactive-teleport.hit-count", 3))
                * encounterParticipantCount();
        int requiredHits = (int) Math.min(Integer.MAX_VALUE, scaledHits);
        if (reactiveHitCount < requiredHits) return;
        reactiveHitCount = 0;
        teleportAtRange(attacker);
    }

    private void teleportAtRange(Player attacker) {
        Location destination = findReactiveTeleportPosition(attacker);
        if (destination == null) return;
        Location origin = controller.getLocation().clone();
        Vector towardPlayer = attacker.getLocation().toVector()
                .subtract(destination.toVector()).setY(0.0);
        if (towardPlayer.lengthSquared() > 0.001) destination.setDirection(towardPlayer);
        controller.teleport(destination);
        controller.setVelocity(new Vector());
        origin.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                origin.clone().add(0, 1.0, 0), 34, .5, .85, .5, .08);
        destination.getWorld().spawnParticle(Particle.REVERSE_PORTAL,
                destination.clone().add(0, 1.0, 0), 34, .5, .85, .5, .08);
        destination.getWorld().playSound(destination,
                Sound.ENTITY_ENDERMAN_TELEPORT, 1.6f, 1.18f);
    }

    private Location findReactiveTeleportPosition(Player attacker) {
        double distance = Math.max(1.0, cfg("reactive-teleport.distance", 8.0));
        Vector away = controller.getLocation().toVector()
                .subtract(attacker.getLocation().toVector()).setY(0.0);
        if (away.lengthSquared() < 0.001) {
            away = attacker.getLocation().getDirection().setY(0.0).multiply(-1.0);
        }
        if (away.lengthSquared() < 0.001) away = new Vector(0, 0, 1);
        away.normalize().multiply(distance);
        double[] angles = {0.0, Math.PI / 6.0, -Math.PI / 6.0,
                Math.PI / 3.0, -Math.PI / 3.0, Math.PI / 2.0, -Math.PI / 2.0, Math.PI};
        int[] verticalOffsets = {0, -1, 1, -2, 2};
        for (double angle : angles) {
            Location base = attacker.getLocation().clone().add(away.clone().rotateAroundY(angle));
            for (int verticalOffset : verticalOffsets) {
                Location candidate = base.clone();
                candidate.setY(Math.floor(attacker.getY()) + verticalOffset + 0.05);
                if (candidate.getBlock().isPassable()
                        && candidate.clone().add(0, 1, 0).getBlock().isPassable()
                        && candidate.clone().add(0, -1, 0).getBlock().getType().isSolid()) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private List<Player> combatPlayers() {
        double range = plugin.endWitchSettings().bossbarRange();
        return controller.getWorld().getPlayers().stream().filter(player -> validTarget(player, range)).toList();
    }

    private int encounterParticipantCount() {
        int participants = plugin.endWitchArenaManager()
                .challengeParticipantCount(id(), controller.getWorld());
        return participants > 0 ? participants : Math.max(1, combatPlayers().size());
    }

    private boolean validTarget(Player player, double range) {
        return player != null && player.isOnline() && !player.isDead()
                && player.getWorld() == controller.getWorld()
                && player.getGameMode() != GameMode.CREATIVE
                && player.getGameMode() != GameMode.SPECTATOR
                && player.getLocation().distanceSquared(controller.getLocation()) <= range * range;
    }

    private Player targetPlayer() {
        return controller.getTarget() instanceof Player player
                && validTarget(player, plugin.endWitchSettings().followRange()) ? player : null;
    }

    private Player player(UUID id) {
        if (id == null) return null;
        Player player = Bukkit.getPlayer(id);
        return validTarget(player, plugin.endWitchSettings().bossbarRange()) ? player : null;
    }

    private Player damagePlayer(EntityDamageEvent event) {
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return null;
        Entity damager = byEntity.getDamager();
        if (damager instanceof Player player) return player;
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player player) return player;
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

    private boolean acceptPlayerHit(Player attacker) {
        UUID attackerId = attacker.getUniqueId();
        if (age < nextAcceptedPlayerHit.getOrDefault(attackerId, Long.MIN_VALUE)) return false;
        nextAcceptedPlayerHit.put(attackerId,
                age + plugin.endWitchSettings().playerHitCooldownTicks());
        return true;
    }

    private void face(Location location) {
        Location own = controller.getLocation();
        Vector direction = location.toVector().subtract(own.toVector());
        if (direction.lengthSquared() < .01) return;
        own.setDirection(direction);
        controller.teleport(own);
    }

    private java.util.Optional<Crystal> nearestCrystal(Location location, double radius) {
        double limit = radius * radius;
        return crystals.stream().filter(c -> c.entity.isValid()
                        && crystalCenter(c).distanceSquared(location) <= limit)
                .min(Comparator.comparingDouble(c -> crystalCenter(c).distanceSquared(location)));
    }

    private java.util.Optional<Crystal> randomCrystal() {
        List<Crystal> valid = crystals.stream().filter(crystal -> crystal.entity.isValid()).toList();
        return valid.isEmpty() ? java.util.Optional.empty()
                : java.util.Optional.of(valid.get(ThreadLocalRandom.current().nextInt(valid.size())));
    }

    private Crystal crystalAt(Location location, double radius) {
        return crystals.stream().filter(c -> c.entity.isValid()
                        && c.entity.getWorld() == location.getWorld()
                        && c.entity.getBoundingBox().expand(Math.max(0.0, radius))
                        .contains(location.toVector()))
                .findFirst().orElse(null);
    }

    private Location crystalCenter(Crystal crystal) {
        var box = crystal.entity.getBoundingBox();
        return new Location(crystal.entity.getWorld(),
                (box.getMinX() + box.getMaxX()) * 0.5,
                (box.getMinY() + box.getMaxY()) * 0.5,
                (box.getMinZ() + box.getMaxZ()) * 0.5);
    }

    private Location groundedCrystalLocation(Location requested) {
        World world = requested.getWorld();
        if (world == null) return requested.clone();
        int blockX = requested.getBlockX();
        int blockZ = requested.getBlockZ();
        int start = Math.min(world.getMaxHeight() - 2,
                Math.max(world.getMinHeight() + 1, requested.getBlockY() + 1));
        int bottom = Math.max(world.getMinHeight() + 1, start - 16);
        Location result = requested.clone();
        for (int feetY = start; feetY >= bottom; feetY--) {
            if (!world.getBlockAt(blockX, feetY - 1, blockZ).getType().isSolid()) continue;
            result.setY(feetY);
            result.setYaw(0.0f);
            result.setPitch(0.0f);
            return result;
        }
        result.setY(battlefieldCenter.getY());
        result.setYaw(0.0f);
        result.setPitch(0.0f);
        return result;
    }

    private Location entityLocation(UUID id) {
        if (id == null) return null;
        Entity entity = Bukkit.getEntity(id);
        return entity != null && entity.isValid() && entity.getWorld() == controller.getWorld()
                ? entity.getLocation() : null;
    }

    private boolean hasAux(AuxType type) {
        return auxiliaries.values().stream().anyMatch(aux -> aux.type == type && aux.entity.isValid());
    }

    private boolean isRune(AuxType type) {
        return type == AuxType.RUNE_RED || type == AuxType.RUNE_GREEN || type == AuxType.RUNE_CYAN;
    }

    private void clearRunes() {
        auxiliaries.values().stream().filter(aux -> isRune(aux.type)).toList().forEach(aux -> {
            aux.entity.remove();
            auxiliaries.remove(aux.entity.getUniqueId());
        });
    }

    private void drawCircle(Location center, double radius, Color color, int points) {
        for (int index = 0; index < points; index++) {
            double angle = Math.PI * 2 * index / points;
            Location point = center.clone().add(Math.cos(angle) * radius, .05, Math.sin(angle) * radius);
            ParticleCompat.spawnDust(point.getWorld(), point, 1, 0, 0, 0, 0, color, 1.15f);
        }
    }

    private void drawMagicCircle(Location center, double radius, double rotation) {
        Color color = Color.fromRGB(25, 230, 170);
        drawCircle(center.clone().add(0, .08, 0), radius, color, 64);
        drawCircle(center.clone().add(0, .08, 0), radius * .55, color, 40);
        for (int arm = 0; arm < 6; arm++) {
            double angle = rotation + arm * Math.PI / 3;
            Location end = center.clone().add(Math.cos(angle) * radius, .08, Math.sin(angle) * radius);
            drawLine(center.clone().add(0, .08, 0), end, color, .5);
        }
    }

    private void drawLine(Location start, Location end, Color color, double spacing) {
        if (start.getWorld() != end.getWorld()) return;
        Vector delta = end.toVector().subtract(start.toVector());
        double length = delta.length();
        if (length < .01) return;
        Vector step = delta.normalize().multiply(Math.max(.2, spacing));
        Location point = start.clone();
        for (double distance = 0; distance <= length; distance += step.length()) {
            ParticleCompat.spawnDust(point.getWorld(), point, 1, 0, 0, 0, 0, color, 1.05f);
            point.add(step);
        }
    }

    private void announce(String key, Object... replacements) {
        for (Player player : combatPlayers()) plugin.messages().send(player, key, replacements);
    }

    private double cfg(String path, double fallback) {
        return plugin.getConfig().getDouble("end-witch." + path, fallback);
    }

    private double actionSpeedMultiplier() {
        return Math.max(0.5, Math.min(2.0, cfg("action-speed-multiplier", 1.25)));
    }

    private long actionDelayTicks(double seconds) {
        return Math.max(1L, Math.round(seconds * 20.0 / actionSpeedMultiplier()));
    }

    private int cfgInt(String path, int fallback) {
        return plugin.getConfig().getInt("end-witch." + path, fallback);
    }

    private static long seconds(double seconds) {
        return Math.max(1L, Math.round(seconds * 20.0));
    }

    private static double horizontalDistance(Location first, Location second) {
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private static double distancePointToSegment(Vector point, Vector start, Vector end) {
        Vector segment = end.clone().subtract(start);
        double lengthSquared = segment.lengthSquared();
        if (lengthSquared < 1.0E-9) return point.distance(start);
        double t = Math.max(0, Math.min(1, point.clone().subtract(start).dot(segment) / lengthSquared));
        return point.distance(start.clone().add(segment.multiply(t)));
    }

    private static String configSkillName(Skill skill) {
        return skill.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    private static double normalizeHealthMultiplier(Double multiplier) {
        if (multiplier == null || !Double.isFinite(multiplier)) return 1.0;
        return Math.max(1.0, Math.min(10.0, multiplier));
    }

    private void persistPhase() {
        controller.getPersistentDataContainer().set(plugin.endWitchPhaseKey(), PersistentDataType.INTEGER,
                phase == Phase.P1 ? 1 : phase == Phase.P2 ? 2 : 3);
    }

    private static Component legacyComponent(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value);
    }

    private static String legacyString(String value) {
        return LegacyComponentSerializer.legacySection().serialize(legacyComponent(value));
    }
}
