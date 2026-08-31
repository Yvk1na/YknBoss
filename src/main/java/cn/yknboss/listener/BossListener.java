package cn.yknboss.listener;

import cn.yknboss.YknBossPlugin;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.event.entity.EntityTransformEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.entity.SpectralArrow;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class BossListener implements Listener {
    private final YknBossPlugin plugin;

    public BossListener(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(EntityDeathEvent event) {
        if (plugin.endWitchManager().handleAuxiliaryDeath(event)
                || plugin.endsunGuardManager().handleAuxiliaryDeath(event)) {
            return;
        } else if (plugin.boneDragonManager().isBoss(event.getEntity())) {
            plugin.boneDragonManager().handleDeath(event);
        } else if (plugin.endsunGuardManager().isBoss(event.getEntity())) {
            plugin.endsunGuardManager().handleDeath(event);
        } else if (plugin.endWitchManager().isBoss(event.getEntity())) {
            plugin.endWitchManager().handleDeath(event);
        } else if (plugin.boneDragonManager().isMinion(event.getEntity())) {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEndsunAuxiliaryDamage(EntityDamageEvent event) {
        if (plugin.endWitchManager().handleAuxiliaryDamage(event)) return;
        plugin.endsunGuardManager().handleAuxiliaryDamage(event);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onEndsunTotemInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (plugin.endsunGuardManager().handleTotemInteract(event.getPlayer(), event.getRightClicked())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onShieldFragmentPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)
                || !plugin.endsunGuardManager().isShieldFragment(event.getItem().getItemStack())) return;
        if (plugin.endsunGuardManager().collectShieldFragment(player, event.getItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCombust(EntityCombustEvent event) {
        if (isProtectedEntity(event.getEntity())) {
            event.setCancelled(true);
            event.getEntity().setFireTicks(0);
            event.getEntity().setVisualFire(false);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onBossAttack(EntityDamageByEntityEvent event) {
        if (plugin.endsunGuardManager().isBoss(event.getDamager())) {
            plugin.endsunGuardManager().handleAttack(event);
        } else if (plugin.endWitchManager().isBoss(event.getDamager())) {
            plugin.endWitchManager().handleAttack(event);
        }
        if (!(event.getDamager() instanceof SpectralArrow) || !isBoss(event.getEntity())) return;
        var bossEntity = event.getEntity();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!bossEntity.isValid()) return;
            plugin.boneDragonManager().recoverModelAfterSpectralArrow(bossEntity);
            plugin.endsunGuardManager().recoverModelAfterSpectralArrow(bossEntity);
            plugin.endWitchManager().recoverModelAfterSpectralArrow(bossEntity);
        });
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBossPotionEffect(EntityPotionEffectEvent event) {
        if (!isBoss(event.getEntity())) return;
        PotionEffect effect = event.getNewEffect();
        if (effect != null && effect.getType().equals(PotionEffectType.GLOWING)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onTransform(EntityTransformEvent event) {
        if (isBoss(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onEnvironmentalDamage(EntityDamageEvent event) {
        if (!isBoss(event.getEntity())) return;
        switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA -> {
                event.setCancelled(true);
                event.getEntity().setFireTicks(0);
                event.getEntity().setVisualFire(false);
            }
            case HOT_FLOOR -> {
                if (plugin.endsunGuardManager().isBoss(event.getEntity())) {
                    event.setCancelled(true);
                    event.getEntity().setFireTicks(0);
                    event.getEntity().setVisualFire(false);
                }
            }
            case FALL, SUFFOCATION, DROWNING, CRAMMING -> event.setCancelled(true);
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBoneDragonDamage(EntityDamageEvent event) {
        if (plugin.boneDragonManager().isBoss(event.getEntity())) {
            plugin.boneDragonManager().handleIncomingDamage(event);
        } else if (plugin.endsunGuardManager().isBoss(event.getEntity())) {
            plugin.endsunGuardManager().handleIncomingDamage(event);
        } else if (plugin.endWitchManager().isBoss(event.getEntity())) {
            plugin.endWitchManager().handleIncomingDamage(event);
        }
    }

    /**
     * Reassert scripted phase/death locks after every other normal damage listener.
     * This protects the one-tick phase hand-off even when another plugin changes the
     * cancellation state after our main HIGHEST handler.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void enforceScriptedBossDamageLock(EntityDamageEvent event) {
        if (plugin.boneDragonManager().enforceControllerDamageLock(event)) return;
        if (plugin.endsunGuardManager().enforceControllerDamageLock(event)) return;
        plugin.endWitchManager().enforceControllerDamageLock(event);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerHealing(EntityRegainHealthEvent event) {
        plugin.endWitchManager().handleHealing(event);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerFireDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        switch (event.getCause()) {
            case FIRE, FIRE_TICK, LAVA, HOT_FLOOR ->
                    plugin.endsunGuardManager().handlePlayerFireDamage(player, event.getFinalDamage());
            default -> { }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShieldFragmentUse(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND
                || (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK)
                || !plugin.endsunGuardManager().isShieldFragment(event.getItem())) return;
        if (plugin.endsunGuardManager().tryBuildBarrier(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        plugin.boneDragonManager().discover(event.getChunk());
        plugin.endsunGuardManager().discover(event.getChunk());
        plugin.endWitchManager().discover(event.getChunk());
    }

    private boolean isBoss(org.bukkit.entity.Entity entity) {
        return plugin.boneDragonManager().isBoss(entity) || plugin.endsunGuardManager().isBoss(entity)
                || plugin.endWitchManager().isBoss(entity);
    }

    private boolean isProtectedEntity(org.bukkit.entity.Entity entity) {
        return isBoss(entity) || plugin.boneDragonManager().isMinion(entity)
                || plugin.endsunGuardManager().isAuxiliary(entity)
                || plugin.endWitchManager().isAuxiliary(entity);
    }
}
