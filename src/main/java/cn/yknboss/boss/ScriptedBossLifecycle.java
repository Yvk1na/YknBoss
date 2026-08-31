package cn.yknboss.boss;

import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;

import java.util.UUID;

/**
 * Shared settlement guard for every scripted, multi-phase boss.
 *
 * <p>A controller entity dying is not, by itself, proof that the encounter was
 * completed.  Implementations must first enter their authored final-death sequence;
 * managers must call {@link #authorizeDeathSettlement(EntityDeathEvent)} before
 * awarding a victory.  New bosses use the same contract so a phase hand-off can
 * never be mistaken for a kill.</p>
 */
public interface ScriptedBossLifecycle {
    boolean finalDeathSequenceActive();

    /** True while Bukkit damage must never reach the hidden controller entity. */
    boolean controllerDamageLocked();

    /**
     * Replaces a controller that an external damage implementation killed without
     * allowing the scripted phase hand-off to run.  Implementations advance to the
     * next independent health pool (or begin the authored final-death animation) and
     * return the replacement controller id.
     */
    UUID recoverUnexpectedControllerDeath();

    default boolean enforceControllerDamageLock(EntityDamageEvent event) {
        if (!controllerDamageLocked()) return false;
        event.setCancelled(true);
        event.setDamage(0.0);
        return true;
    }

    default boolean authorizeDeathSettlement(EntityDeathEvent event) {
        if (finalDeathSequenceActive()) return true;
        event.getDrops().clear();
        event.setDroppedExp(0);
        return false;
    }
}
