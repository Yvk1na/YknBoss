package cn.yknboss.boss;

import cn.yknboss.YknBossPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.bukkit.block.Block;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class BoneDragonManager {
    public static final String TYPE_ID = "bone_dragon";
    public static final String MINION_TYPE_ID = "bone_dragon_minion";

    private final YknBossPlugin plugin;
    private final Map<UUID, BoneDragon> bosses = new HashMap<>();
    private final Map<UUID, Long> nextTickErrorLog = new HashMap<>();
    private long tickCounter;

    public BoneDragonManager(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    public BoneDragon spawn(Location location) {
        return spawn(location, 1.0);
    }

    public BoneDragon spawn(Location location, double healthMultiplier) {
        requireModel();
        BoneDragon boss = BoneDragon.spawn(plugin, location.clone(), healthMultiplier);
        bosses.put(boss.id(), boss);
        return boss;
    }

    private void requireModel() {
        if (!plugin.betterModel().available()) {
            throw new IllegalStateException("骨龙需要 BetterModel 3.x；检测到：" + plugin.betterModel().detectedVersion());
        }
        if (!plugin.betterModel().hasModel(plugin.boneDragonSettings().modelId())) {
            throw new IllegalStateException("BetterModel 缺少 " + plugin.boneDragonSettings().modelId()
                    + "；安装 YknBoss-BetterModel-Pack 后执行 /bettermodel reload");
        }
    }

    public void tick() {
        tickCounter++;
        var iterator = bosses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BoneDragon> entry = iterator.next();
            BoneDragon boss = entry.getValue();
            try {
                if (!boss.tick()) {
                    boss.detach();
                    nextTickErrorLog.remove(entry.getKey());
                    iterator.remove();
                }
            } catch (RuntimeException | LinkageError exception) {
                if (tickCounter >= nextTickErrorLog.getOrDefault(entry.getKey(), 0L)) {
                    nextTickErrorLog.put(entry.getKey(), tickCounter + 100L);
                    plugin.getLogger().log(Level.SEVERE,
                            "骨龙 " + entry.getKey() + " tick 异常；已隔离，本 tick 不会阻断其他 Boss", exception);
                }
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
        if (!(entity instanceof Zombie zombie) || !isBoss(zombie) || bosses.containsKey(zombie.getUniqueId())) return;
        if (!plugin.betterModel().available() || !plugin.betterModel().hasModel(plugin.boneDragonSettings().modelId())) {
            plugin.getLogger().warning("发现未恢复的骨龙 " + zombie.getUniqueId() + "：BetterModel 3.x 或模型尚未加载");
            return;
        }
        BoneDragon boss = BoneDragon.restore(plugin, zombie);
        bosses.put(boss.id(), boss);
        plugin.getLogger().info("已恢复骨龙实体 " + boss.id());
    }

    public boolean isBoss(Entity entity) {
        return TYPE_ID.equals(typeOf(entity));
    }

    public boolean isMinion(Entity entity) {
        return MINION_TYPE_ID.equals(typeOf(entity));
    }

    private String typeOf(Entity entity) {
        return entity.getPersistentDataContainer().get(plugin.bossTypeKey(), PersistentDataType.STRING);
    }

    public void handleDeath(EntityDeathEvent event) {
        UUID deadControllerId = event.getEntity().getUniqueId();
        BoneDragon boss = bosses.get(deadControllerId);
        if (boss == null && event.getEntity() instanceof Zombie zombie && isBoss(zombie)
                && plugin.betterModel().available()
                && plugin.betterModel().hasModel(plugin.boneDragonSettings().modelId())) {
            boss = BoneDragon.restore(plugin, zombie);
        }
        if (boss != null && !boss.authorizeDeathSettlement(event)) {
            try {
                UUID replacementId = boss.recoverUnexpectedControllerDeath();
                bosses.remove(deadControllerId);
                bosses.put(replacementId, boss);
                nextTickErrorLog.remove(deadControllerId);
                plugin.arenaManager().replaceActiveBoss(deadControllerId, replacementId,
                        event.getEntity().getWorld());
                plugin.getLogger().warning("骨龙控制实体被外部伤害链提前杀死；已重建实体并推进脚本阶段："
                        + deadControllerId + " -> " + replacementId);
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "骨龙控制实体异常死亡后的阶段恢复失败；未进行胜利结算。", exception);
            }
            return;
        }
        bosses.remove(deadControllerId);
        if (boss != null) boss.onDeath(event);
        else {
            event.getDrops().clear();
            event.setDroppedExp(0);
        }
    }

    public boolean handleIncomingDamage(EntityDamageEvent event) {
        BoneDragon boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.handleIncomingDamage(event);
    }

    public boolean rejectRateLimitedPlayerDamage(EntityDamageByEntityEvent event) {
        BoneDragon controllerBoss = bosses.get(event.getEntity().getUniqueId());
        if (controllerBoss != null) return controllerBoss.rejectRateLimitedPlayerDamage(event);
        for (BoneDragon boss : bosses.values()) {
            if (boss.rejectRateLimitedPlayerDamage(event)) return true;
        }
        return false;
    }

    public void clearPlayerDamageApproval(EntityDamageEvent event) {
        BoneDragon controllerBoss = bosses.get(event.getEntity().getUniqueId());
        if (controllerBoss != null) {
            controllerBoss.clearPlayerDamageApproval(event);
            return;
        }
        for (BoneDragon boss : bosses.values()) boss.clearPlayerDamageApproval(event);
    }

    public boolean enforceControllerDamageLock(EntityDamageEvent event) {
        BoneDragon boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.enforceControllerDamageLock(event);
    }

    public void recoverModelAfterSpectralArrow(Entity entity) {
        BoneDragon boss = bosses.get(entity.getUniqueId());
        if (boss != null) boss.recoverModelAfterSpectralArrow();
    }

    public boolean breakPrisonBlock(Block block) {
        for (BoneDragon boss : bosses.values()) {
            if (boss.breakPrisonBlock(block)) return true;
        }
        return false;
    }

    public Optional<BoneDragon> nearest(Location location, double radius) {
        double limit = radius * radius;
        BoneDragon nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (BoneDragon boss : bosses.values()) {
            if (boss.location().getWorld() != location.getWorld()) continue;
            double distance = boss.location().distanceSquared(location);
            if (distance <= limit && distance < nearestDistance) {
                nearest = boss;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Collection<BoneDragon> activeBosses() {
        return Collections.unmodifiableCollection(bosses.values());
    }

    public int killAll() {
        int count = bosses.size();
        for (BoneDragon boss : new ArrayList<>(bosses.values())) boss.remove();
        bosses.clear();
        return count;
    }

    public void applySettingsToActiveBosses() {
        for (BoneDragon boss : bosses.values()) boss.applySettings(true);
    }

    public void shutdown() {
        for (BoneDragon boss : bosses.values()) boss.detach();
        bosses.clear();
    }
}
