package cn.yknboss.slayer;

import cn.yknboss.YknBossPlugin;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

public interface SlayerService {

    double outgoingDamage(Player player, double damage, boolean executeWindow);

    double incomingDamage(Player player, double damage);

    int respawnDelayTicks(Player player, int baseDelayTicks);

    void awardVictory(String raid, Set<UUID> eligibleParticipants, Map<UUID, Double> damageContributions);

    static SlayerService create(YknBossPlugin plugin) {
        if (!plugin.getServer().getPluginManager().isPluginEnabled("AuraSkills")) {
            plugin.getLogger().warning("未检测到 AuraSkills，讨伐职业与副本经验已停用。");
            return disabled();
        }
        try {
            return new AuraSlayerService(plugin);
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().severe("AuraSkills 讨伐职业初始化失败：" + exception.getMessage());
            return disabled();
        }
    }

    static SlayerService disabled() {
        return new SlayerService() {
            @Override
            public double outgoingDamage(Player player, double damage, boolean executeWindow) {
                return damage;
            }

            @Override
            public double incomingDamage(Player player, double damage) {
                return damage;
            }

            @Override
            public int respawnDelayTicks(Player player, int baseDelayTicks) {
                return baseDelayTicks;
            }

            @Override
            public void awardVictory(String raid, Set<UUID> eligibleParticipants,
                                     Map<UUID, Double> damageContributions) {
            }
        };
    }
}
