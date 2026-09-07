package cn.yknboss.boss;

import cn.yknboss.YknBossPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityRegainHealthEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class EndWitchManager {
    public static final String TYPE_ID = "end_witch";

    private final YknBossPlugin plugin;
    private final Map<UUID, EndWitch> bosses = new HashMap<>();
    private final Map<UUID, Long> nextErrorLog = new HashMap<>();
    private long ticks;

    public EndWitchManager(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    public EndWitch spawn(Location location) {
        return spawn(location, 1.0);
    }

    public EndWitch spawn(Location location, double healthMultiplier) {
        requireModel();
        EndWitch boss = EndWitch.spawn(plugin, location.clone(), healthMultiplier);
        bosses.put(boss.id(), boss);
        return boss;
    }

    private void requireModel() {
        if (!plugin.betterModel().available()) {
            throw new IllegalStateException("终焉之魔女需要 BetterModel 3.x；检测到："
                    + plugin.betterModel().detectedVersion());
        }
        if (!plugin.betterModel().hasModel(plugin.endWitchSettings().modelId())) {
            throw new IllegalStateException("BetterModel 缺少 " + plugin.endWitchSettings().modelId()
                    + "；安装 YknBoss-BetterModel-Pack 后执行 /bettermodel reload");
        }
    }

    public void tick() {
        ticks++;
        var iterator = bosses.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            try {
                if (!entry.getValue().tick()) {
                    entry.getValue().detach();
                    nextErrorLog.remove(entry.getKey());
                    iterator.remove();
                }
            } catch (RuntimeException | LinkageError exception) {
                if (ticks < nextErrorLog.getOrDefault(entry.getKey(), 0L)) continue;
                nextErrorLog.put(entry.getKey(), ticks + 100L);
                plugin.getLogger().log(Level.SEVERE, "终焉之魔女 " + entry.getKey()
                        + " tick 异常；已隔离，本 tick 不会阻断其他 Boss", exception);
            }
        }
    }

    public void discoverLoadedBosses() {
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) discover(entity);
        }
    }

    public void discover(Chunk chunk) {
        for (Entity entity : chunk.getEntities()) discover(entity);
    }

    private void discover(Entity entity) {
        if (!(entity instanceof Husk husk) || !isBoss(husk) || bosses.containsKey(husk.getUniqueId())) return;
        if (!plugin.betterModel().available() || !plugin.betterModel().hasModel(plugin.endWitchSettings().modelId())) {
            plugin.getLogger().warning("发现未恢复的终焉之魔女 " + husk.getUniqueId()
                    + "：BetterModel 或 end_witch 模型尚未加载");
            return;
        }
        EndWitch boss = EndWitch.restore(plugin, husk);
        bosses.put(boss.id(), boss);
    }

    public boolean isBoss(Entity entity) {
        return TYPE_ID.equals(entity.getPersistentDataContainer()
                .get(plugin.bossTypeKey(), PersistentDataType.STRING));
    }

    public boolean isAuxiliary(Entity entity) {
        return bosses.values().stream().anyMatch(boss -> boss.ownsAuxiliary(entity));
    }

    public boolean handleAuxiliaryDamage(EntityDamageEvent event) {
        for (EndWitch boss : bosses.values()) {
            if (boss.ownsAuxiliary(event.getEntity())) return boss.handleAuxiliaryDamage(event);
        }
        return false;
    }

    public boolean handleAuxiliaryDeath(EntityDeathEvent event) {
        for (EndWitch boss : bosses.values()) {
            if (!boss.ownsAuxiliary(event.getEntity())) continue;
            boss.handleAuxiliaryDeath(event);
            return true;
        }
        return false;
    }

    public void handleDeath(EntityDeathEvent event) {
        UUID deadControllerId = event.getEntity().getUniqueId();
        EndWitch boss = bosses.get(deadControllerId);
        if (boss != null && !boss.authorizeDeathSettlement(event)) {
            try {
                UUID replacementId = boss.recoverUnexpectedControllerDeath();
                bosses.remove(deadControllerId);
                bosses.put(replacementId, boss);
                nextErrorLog.remove(deadControllerId);
                plugin.endWitchArenaManager().replaceActiveBoss(deadControllerId, replacementId,
                        event.getEntity().getWorld());
                plugin.getLogger().warning("终焉之魔女控制实体被外部伤害链提前杀死；已重建实体并推进脚本阶段："
                        + deadControllerId + " -> " + replacementId);
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "终焉之魔女控制实体异常死亡后的阶段恢复失败；未进行胜利结算。", exception);
            }
            return;
        }
        boss = bosses.remove(deadControllerId);
        if (boss != null) boss.onDeath(event);
        else {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    public void handleAttack(EntityDamageByEntityEvent event) {
        EndWitch boss = bosses.get(event.getDamager().getUniqueId());
        if (boss != null) boss.handleAttack(event);
    }

    public boolean handleIncomingDamage(EntityDamageEvent event) {
        EndWitch boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.handleIncomingDamage(event);
    }

    public boolean rejectRateLimitedPlayerDamage(EntityDamageByEntityEvent event) {
        EndWitch boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.rejectRateLimitedPlayerDamage(event);
    }

    public void clearPlayerDamageApproval(EntityDamageEvent event) {
        EndWitch boss = bosses.get(event.getEntity().getUniqueId());
        if (boss != null) boss.clearPlayerDamageApproval(event);
    }

    public boolean enforceControllerDamageLock(EntityDamageEvent event) {
        EndWitch boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.enforceControllerDamageLock(event);
    }

    public void handleHealing(EntityRegainHealthEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        for (EndWitch boss : bosses.values()) boss.handleHealing(player, event);
    }

    public void recoverModelAfterSpectralArrow(Entity entity) {
        EndWitch boss = bosses.get(entity.getUniqueId());
        if (boss != null) boss.recoverModelAfterSpectralArrow();
    }

    public Optional<EndWitch> nearest(Location location, double radius) {
        double limit = radius * radius;
        EndWitch result = null;
        double distance = Double.MAX_VALUE;
        for (EndWitch boss : bosses.values()) {
            if (boss.location().getWorld() != location.getWorld()) continue;
            double candidate = boss.location().distanceSquared(location);
            if (candidate <= limit && candidate < distance) {
                result = boss;
                distance = candidate;
            }
        }
        return Optional.ofNullable(result);
    }

    public Collection<EndWitch> activeBosses() {
        return Collections.unmodifiableCollection(bosses.values());
    }

    public int killAll() {
        int count = bosses.size();
        for (EndWitch boss : new ArrayList<>(bosses.values())) boss.remove();
        bosses.clear();
        return count;
    }

    public void applySettingsToActiveBosses() {
        bosses.values().forEach(boss -> boss.applySettings(true));
    }

    public void shutdown() {
        bosses.values().forEach(EndWitch::detach);
        bosses.clear();
    }
}
