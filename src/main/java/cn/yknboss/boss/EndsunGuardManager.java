package cn.yknboss.boss;

import cn.yknboss.YknBossPlugin;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Husk;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class EndsunGuardManager {
    public static final String TYPE_ID = "endsun_guard";
    public static final String TOTEM_TYPE_ID = "endsun_fire_totem";
    public static final String SOUL_TYPE_ID = "endsun_soul_reflection";
    public static final String FRAGMENT_ITEM_ID = "endsun_shield_fragment";

    private final YknBossPlugin plugin;
    private final Map<UUID, EndsunGuard> bosses = new HashMap<>();
    private final Map<UUID, Long> nextTickErrorLog = new HashMap<>();
    private long tickCounter;

    public EndsunGuardManager(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    public EndsunGuard spawn(Location location) {
        return spawn(location, 1.0);
    }

    public EndsunGuard spawn(Location location, double healthMultiplier) {
        requireModel();
        EndsunGuard boss = EndsunGuard.spawn(plugin, location.clone(), healthMultiplier);
        bosses.put(boss.id(), boss);
        return boss;
    }

    private void requireModel() {
        if (!plugin.betterModel().available()) {
            throw new IllegalStateException("烬日冥卫·阿努赫需要 BetterModel 3.x；检测到："
                    + plugin.betterModel().detectedVersion());
        }
        if (!plugin.betterModel().hasModel(plugin.endsunGuardSettings().modelId())) {
            throw new IllegalStateException("BetterModel 缺少 " + plugin.endsunGuardSettings().modelId()
                    + "；安装 YknBoss-BetterModel-Pack 后执行 /bettermodel reload");
        }
    }

    public void tick() {
        tickCounter++;
        var iterator = bosses.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, EndsunGuard> entry = iterator.next();
            EndsunGuard boss = entry.getValue();
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
                            "阿努赫 " + entry.getKey() + " tick 异常；已隔离，本 tick 不会阻断其他 Boss", exception);
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
        if (!(entity instanceof Husk husk) || !isBoss(husk) || bosses.containsKey(husk.getUniqueId())) return;
        if (!plugin.betterModel().available() || !plugin.betterModel().hasModel(plugin.endsunGuardSettings().modelId())) {
            plugin.getLogger().warning("发现未恢复的烬日冥卫·阿努赫 " + husk.getUniqueId()
                    + "：BetterModel 3.x 或模型尚未加载");
            return;
        }
        EndsunGuard boss = EndsunGuard.restore(plugin, husk);
        bosses.put(boss.id(), boss);
        plugin.getLogger().info("已恢复烬日冥卫·阿努赫实体 " + boss.id());
    }

    public boolean isBoss(Entity entity) {
        return TYPE_ID.equals(entity.getPersistentDataContainer()
                .get(plugin.bossTypeKey(), PersistentDataType.STRING));
    }

    public void handleDeath(EntityDeathEvent event) {
        UUID deadControllerId = event.getEntity().getUniqueId();
        EndsunGuard boss = bosses.get(deadControllerId);
        if (boss != null && !boss.authorizeDeathSettlement(event)) {
            try {
                UUID replacementId = boss.recoverUnexpectedControllerDeath();
                bosses.remove(deadControllerId);
                bosses.put(replacementId, boss);
                nextTickErrorLog.remove(deadControllerId);
                plugin.endsunArenaManager().replaceActiveBoss(deadControllerId, replacementId,
                        event.getEntity().getWorld());
                plugin.getLogger().warning("阿努赫控制实体被外部伤害链提前杀死；已重建实体并推进脚本阶段："
                        + deadControllerId + " -> " + replacementId);
            } catch (RuntimeException | LinkageError exception) {
                plugin.getLogger().log(Level.SEVERE,
                        "阿努赫控制实体异常死亡后的阶段恢复失败；未进行胜利结算。", exception);
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
        EndsunGuard boss = bosses.get(event.getDamager().getUniqueId());
        if (boss != null) boss.handleAttack(event);
    }

    public boolean rejectRateLimitedPlayerDamage(EntityDamageByEntityEvent event) {
        EndsunGuard controllerBoss = bosses.get(event.getEntity().getUniqueId());
        if (controllerBoss != null) return controllerBoss.rejectRateLimitedPlayerDamage(event);
        for (EndsunGuard boss : bosses.values()) {
            if (boss.rejectRateLimitedPlayerDamage(event)) return true;
        }
        return false;
    }

    public void clearPlayerDamageApproval(EntityDamageEvent event) {
        EndsunGuard controllerBoss = bosses.get(event.getEntity().getUniqueId());
        if (controllerBoss != null) {
            controllerBoss.clearPlayerDamageApproval(event);
            return;
        }
        for (EndsunGuard boss : bosses.values()) boss.clearPlayerDamageApproval(event);
    }

    public boolean handleIncomingDamage(EntityDamageEvent event) {
        EndsunGuard boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.handleIncomingDamage(event);
    }

    public boolean enforceControllerDamageLock(EntityDamageEvent event) {
        EndsunGuard boss = bosses.get(event.getEntity().getUniqueId());
        return boss != null && boss.enforceControllerDamageLock(event);
    }

    public boolean handleAuxiliaryDamage(EntityDamageEvent event) {
        for (EndsunGuard boss : bosses.values()) {
            if (boss.ownsAuxiliary(event.getEntity())) return boss.handleAuxiliaryDamage(event);
        }
        return false;
    }

    public boolean isAuxiliary(Entity entity) {
        for (EndsunGuard boss : bosses.values()) {
            if (boss.ownsAuxiliary(entity)) return true;
        }
        return false;
    }

    public boolean handleTotemInteract(Player player, Entity entity) {
        for (EndsunGuard boss : bosses.values()) {
            if (boss.handleTotemInteract(entity, player)) return true;
        }
        return false;
    }

    public boolean handleAuxiliaryDeath(EntityDeathEvent event) {
        for (EndsunGuard boss : bosses.values()) {
            if (!boss.ownsAuxiliary(event.getEntity())) continue;
            boss.handleAuxiliaryDeath(event);
            return true;
        }
        return false;
    }

    public void handlePlayerFireDamage(Player player, double damage) {
        nearest(player.getLocation(), plugin.endsunGuardSettings().bossbarRange())
                .ifPresent(boss -> boss.handlePlayerFireDamage(player, damage));
    }

    public boolean tryBuildBarrier(Player player) {
        for (EndsunGuard boss : bosses.values()) {
            if (boss.location().getWorld() == player.getWorld() && boss.tryBuildBarrier(player)) return true;
        }
        return false;
    }

    public boolean collectShieldFragment(Player player, Item item) {
        for (EndsunGuard boss : bosses.values()) {
            if (boss.collectShieldFragment(player, item)) return true;
        }
        return false;
    }

    public boolean isShieldFragment(ItemStack stack) {
        return stack != null && stack.hasItemMeta()
                && FRAGMENT_ITEM_ID.equals(stack.getItemMeta().getPersistentDataContainer()
                .get(plugin.specialItemKey(), PersistentDataType.STRING));
    }

    public void recoverModelAfterSpectralArrow(Entity entity) {
        EndsunGuard boss = bosses.get(entity.getUniqueId());
        if (boss != null) boss.recoverModelAfterSpectralArrow();
    }

    public Optional<EndsunGuard> nearest(Location location, double radius) {
        double limit = radius * radius;
        EndsunGuard nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (EndsunGuard boss : bosses.values()) {
            if (boss.location().getWorld() != location.getWorld()) continue;
            double distance = boss.location().distanceSquared(location);
            if (distance <= limit && distance < nearestDistance) {
                nearest = boss;
                nearestDistance = distance;
            }
        }
        return Optional.ofNullable(nearest);
    }

    public Collection<EndsunGuard> activeBosses() {
        return Collections.unmodifiableCollection(bosses.values());
    }

    public int killAll() {
        int count = bosses.size();
        for (EndsunGuard boss : new ArrayList<>(bosses.values())) boss.remove();
        bosses.clear();
        return count;
    }

    public void applySettingsToActiveBosses() {
        for (EndsunGuard boss : bosses.values()) boss.applySettings(true);
    }

    public void shutdown() {
        for (EndsunGuard boss : bosses.values()) boss.detach();
        bosses.clear();
    }
}
