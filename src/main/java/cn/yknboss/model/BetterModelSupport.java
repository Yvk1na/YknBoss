package cn.yknboss.model;

import kr.toxicity.model.api.BetterModel;
import kr.toxicity.model.api.animation.AnimationIterator;
import kr.toxicity.model.api.animation.AnimationModifier;
import kr.toxicity.model.api.bukkit.platform.BukkitAdapter;
import kr.toxicity.model.api.data.renderer.ModelRenderer;
import kr.toxicity.model.api.event.hitbox.HitBoxDamagedEvent;
import kr.toxicity.model.api.tracker.EntityTracker;
import kr.toxicity.model.api.tracker.ModelScaler;
import kr.toxicity.model.api.tracker.TrackerUpdateAction;
import kr.toxicity.model.api.util.function.BonePredicate;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

public final class BetterModelSupport {
    @FunctionalInterface
    public interface HitBoxDamageModifier {
        float modify(String boneName, UUID causingEntityId, boolean indirect, Location impact, float damage);
    }

    public interface Instance {
        void playLoop(String animation);
        void playOnce(String animation);
        void playOnce(String animation, String fallbackAnimation);
        void playOnceAndHold(String animation);
        void setWingsVisible(boolean visible);
        void setRedGlow(boolean enabled);
        void setGlow(boolean enabled, int color);
        void stabilizeScale();
        void setHitBoxDamageModifier(HitBoxDamageModifier modifier);
        void refresh();
        void close();
        boolean valid();
    }

    private final JavaPlugin owner;
    private final Plugin betterModelPlugin;
    private final boolean compatible;

    public BetterModelSupport(JavaPlugin owner) {
        this.owner = owner;
        this.betterModelPlugin = owner.getServer().getPluginManager().getPlugin("BetterModel");
        this.compatible = betterModelPlugin != null
                && betterModelPlugin.isEnabled()
                && majorVersion(betterModelPlugin.getPluginMeta().getVersion()) >= 3;
    }

    public boolean available() {
        return compatible;
    }

    public String detectedVersion() {
        return betterModelPlugin == null ? "未安装" : betterModelPlugin.getPluginMeta().getVersion();
    }

    public boolean hasModel(String modelId) {
        if (!compatible) return false;
        try {
            return BetterModel.modelOrNull(modelId) != null;
        } catch (LinkageError | RuntimeException exception) {
            owner.getLogger().warning("读取 BetterModel 模型失败: " + exception.getMessage());
            return false;
        }
    }

    public Instance attach(String modelId, Entity entity, float scale) {
        return attach(modelId, entity, scale, "idle_fly", true);
    }

    public Instance attach(String modelId, Entity entity, float scale,
                           String initialAnimation, boolean enableMagicEffects) {
        return attach(modelId, entity, scale, initialAnimation, enableMagicEffects, true);
    }

    public Instance attachStable(String modelId, Entity entity, float scale,
                                 String initialAnimation, boolean enableMagicEffects) {
        return attach(modelId, entity, scale, initialAnimation, enableMagicEffects, false);
    }

    private Instance attach(String modelId, Entity entity, float scale,
                            String initialAnimation, boolean enableMagicEffects,
                            boolean forceInitialScaleUpdate) {
        if (!compatible) {
            throw new IllegalStateException("该 Boss 需要 BetterModel 3.x；当前检测结果：" + detectedVersion());
        }
        ModelRenderer renderer = BetterModel.modelOrNull(modelId);
        if (renderer == null) {
            throw new IllegalStateException("BetterModel 未加载模型 " + modelId
                    + "；请安装 YknBoss-BetterModel-Pack 后执行 /bettermodel reload");
        }
        EntityTracker tracker = renderer.getOrCreate(BukkitAdapter.adapt(entity));
        ModelScaler fixedScaler = ModelScaler.value(scale);
        tracker.scaler(fixedScaler);
        if (forceInitialScaleUpdate) tracker.forceUpdate(true);
        TrackerInstance instance = new TrackerInstance(owner, tracker, fixedScaler);
        if (enableMagicEffects) instance.enableMagicEffects();
        instance.playLoop(initialAnimation);
        return instance;
    }

    private static int majorVersion(String value) {
        try {
            return Integer.parseInt(value.split("[.-]", 2)[0]);
        } catch (RuntimeException ignored) {
            return -1;
        }
    }

    private static final class TrackerInstance implements Instance {
        private static final BonePredicate WING_BONES = BonePredicate.name("leftWing")
                .or(BonePredicate.name("rightWing"))
                .withChildren();
        private static final BonePredicate MAGIC_EFFECT_BONES = BonePredicate.name("yknfx_ground_outer")
                .or(BonePredicate.name("yknfx_ground_middle"))
                .or(BonePredicate.name("yknfx_ground_core"))
                .or(BonePredicate.name("yknfx_mouth_outer"))
                .or(BonePredicate.name("yknfx_mouth_core"))
                .or(BonePredicate.name("yknfx_sky_outer"))
                .or(BonePredicate.name("yknfx_sky_core"))
                .or(BonePredicate.name("yknfx_soul_orbit"))
                .or(BonePredicate.name("yknfx_bone_seal"))
                .withChildren();

        private final JavaPlugin owner;
        private final EntityTracker tracker;
        private final ModelScaler fixedScaler;
        private boolean hitBoxDamageModifierRegistered;
        private volatile String currentAnimation;
        private final AtomicLong animationRequestGeneration = new AtomicLong();

        private TrackerInstance(JavaPlugin owner, EntityTracker tracker, ModelScaler fixedScaler) {
            this.owner = owner;
            this.tracker = tracker;
            this.fixedScaler = fixedScaler;
        }

        @Override
        public void playLoop(String animation) {
            if (!valid()) return;
            String resolved = resolveAnimation(animation);
            if (resolved == null) {
                warnMissingAnimation(animation);
                return;
            }
            AnimationModifier modifier = AnimationModifier.DEFAULT.toBuilder()
                    .speed(animationSpeed(resolved))
                    .build();
            switchAnimation(resolved, modifier, null);
        }

        @Override
        public void playOnce(String animation) {
            playOnce(animation, "idle_fly");
        }

        @Override
        public void playOnce(String animation, String fallbackAnimation) {
            if (!valid()) return;
            String resolved = resolveAnimation(animation);
            if (resolved == null) {
                warnMissingAnimation(animation);
                return;
            }
            AnimationModifier modifier = AnimationModifier.DEFAULT_WITH_PLAY_ONCE.toBuilder()
                    .speed(animationSpeed(resolved))
                    .build();
            switchAnimation(resolved, modifier, () -> playLoop(fallbackAnimation));
        }

        @Override
        public void playOnceAndHold(String animation) {
            if (!valid()) return;
            String resolved = resolveAnimation(animation);
            if (resolved == null) {
                warnMissingAnimation(animation);
                return;
            }
            AnimationModifier modifier = AnimationModifier.DEFAULT_WITH_PLAY_ONCE.toBuilder()
                    .type(AnimationIterator.Type.HOLD_ON_LAST)
                    .speed(animationSpeed(resolved))
                    .build();
            switchAnimation(resolved, modifier, null);
        }

        private void switchAnimation(String animation, AnimationModifier modifier, Runnable completion) {
            if (!valid()) return;
            long requestGeneration = animationRequestGeneration.incrementAndGet();
            // First leave any BetterModel completion callback, then serialize the actual
            // state mutation through the tracker's own task queue below.
            Bukkit.getScheduler().runTask(owner, () -> {
                if (!valid() || requestGeneration != animationRequestGeneration.get()) return;
                // BetterModel owns AnimationStateHandler on its tracker worker. Queue the
                // whole replacement there so a render tick never observes half-switched bones.
                tracker.task(() -> {
                    if (!valid() || requestGeneration != animationRequestGeneration.get()) return;
                    String previous = currentAnimation;
                    if (previous != null) tracker.stopAnimation(previous);
                    Runnable guardedCompletion = completion == null ? null : () -> {
                        if (!valid() || requestGeneration != animationRequestGeneration.get()
                                || !animation.equals(currentAnimation)) return;
                        completion.run();
                    };
                    boolean started = guardedCompletion == null
                            ? tracker.animate(animation, modifier)
                            : tracker.animate(animation, modifier, guardedCompletion);
                    if (started) currentAnimation = animation;
                });
            });
        }

        private static float animationSpeed(String animation) {
            String name = animation.startsWith("animation.")
                    ? animation.substring("animation.".length())
                    : animation;
            return switch (name) {
                case "idle_fly" -> 1.35f;
                case "ground_idle" -> 1.45f;
                case "ground_walk" -> 1.15f;
                case "idle" -> 1.15f;
                case "walk" -> 1.35f;
                case "cast", "float_cast", "phase_transition", "silenced", "world_end" -> 1.08f;
                case "blink_slash" -> 1.22f;
                case "hurt" -> 1.25f;
                case "scythe_slash" -> 1.18f;
                case "scythe_recall", "amethyst_judgement", "crown_ignite", "solar_shield",
                     "shield_stagger", "heart_furnace_open", "soul_drain", "eclipse_transform",
                     "black_sun_ritual", "final_stance", "scythe_echo", "nether_boundary",
                     "sunfall", "pharaoh_edict" -> 1.15f;
                case "sand_funeral", "twin_eclipse" -> 1.05f;
                case "entrance" -> 1.0f;
                case "tail_sweep" -> 1.27f;
                case "dive_grab", "grab" -> 1.20f;
                case "summon", "death_summon", "spawn", "wing_break" -> 1.20f;
                case "skeleton_absorb" -> 1.0f;
                case "skull_barrage", "bone_prison" -> 1.25f;
                case "bone_spikes" -> 1.0f;
                case "meteor" -> 1.15f;
                case "death" -> 1.30f;
                default -> 1.0f;
            };
        }

        private void enableMagicEffects() {
            if (!valid()) return;
            enqueue(() -> tracker.update(
                    TrackerUpdateAction.brightness(15, 15),
                    MAGIC_EFFECT_BONES));
        }

        @Override
        public void setWingsVisible(boolean visible) {
            if (!valid()) return;
            enqueue(() -> tracker.update(TrackerUpdateAction.togglePart(visible), WING_BONES));
        }

        @Override
        public void setRedGlow(boolean enabled) {
            if (!valid()) return;
            if (enabled) {
                enqueue(() -> tracker.update(TrackerUpdateAction.composite(
                        TrackerUpdateAction.tint(0xFF3030),
                        TrackerUpdateAction.glow(true),
                        TrackerUpdateAction.glowColor(0xB00000),
                        TrackerUpdateAction.brightness(15, 15))));
            } else {
                enqueue(() -> tracker.update(TrackerUpdateAction.composite(
                        TrackerUpdateAction.previousTint(),
                        TrackerUpdateAction.glow(false),
                        TrackerUpdateAction.brightness(-1, -1))));
            }
        }

        @Override
        public void setGlow(boolean enabled, int color) {
            if (!valid()) return;
            enqueue(() -> tracker.update(enabled
                    ? TrackerUpdateAction.composite(
                            TrackerUpdateAction.glow(true),
                            TrackerUpdateAction.glowColor(color & 0xFFFFFF))
                    : TrackerUpdateAction.glow(false)));
        }

        @Override
        public void stabilizeScale() {
            if (!valid()) return;
            enqueue(this::reapplyFixedScale);
        }

        private void reapplyFixedScale() {
            if (!valid()) return;
            tracker.scaler(fixedScaler);
        }

        @Override
        public void setHitBoxDamageModifier(HitBoxDamageModifier modifier) {
            if (!valid() || modifier == null || hitBoxDamageModifierRegistered) return;
            hitBoxDamageModifierRegistered = true;
            tracker.listenHitBox(HitBoxDamagedEvent.class, event -> {
                String boneName = event.getHitBox().positionSource().name().rawName();
                var causingEntity = event.getSource().getCausingEntity();
                UUID causingEntityId = causingEntity == null ? null : causingEntity.uuid();
                Location impact = null;
                Entity source = Bukkit.getEntity(event.getHitBox().source().uuid());
                if (source != null) {
                    var offset = event.getHitBox().positionSource().hitBoxPosition();
                    impact = source.getLocation().add(offset.x, offset.y, offset.z);
                }
                float modified = modifier.modify(boneName, causingEntityId, event.getSource().isIndirect(),
                        impact, event.getDamage());
                if (Float.isFinite(modified) && modified >= 0.0f) event.setDamage(modified);
            });
        }

        @Override
        public void refresh() {
            if (!valid()) return;
            enqueue(() -> {
                tracker.refresh();
                reapplyFixedScale();
            });
        }

        private void enqueue(Runnable action) {
            if (!valid()) return;
            tracker.task(() -> {
                if (valid()) action.run();
            });
        }

        private String resolveAnimation(String animation) {
            if (tracker.renderer().animation(animation).isPresent()) return animation;

            String prefixed = "animation." + animation;
            if (tracker.renderer().animation(prefixed).isPresent()) return prefixed;

            if (animation.startsWith("animation.")) {
                String unprefixed = animation.substring("animation.".length());
                if (tracker.renderer().animation(unprefixed).isPresent()) return unprefixed;
            }
            return null;
        }

        private void warnMissingAnimation(String animation) {
            owner.getLogger().warning("BetterModel animation not found or failed to start: "
                    + animation + "; available=" + tracker.renderer().animations().keySet());
        }

        @Override
        public void close() {
            animationRequestGeneration.incrementAndGet();
            currentAnimation = null;
            if (!tracker.isClosed()) tracker.close();
        }

        @Override
        public boolean valid() {
            return !tracker.isClosed();
        }
    }
}
