package cn.yknboss.slayer;

import cn.yknboss.YknBossPlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.ability.Ability;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.skill.Skill;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.damage.DamageSource;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class AuraSlayerService implements SlayerService {

    private static final String NAMESPACE = "yknboss";
    private static final String BONE_DRAGON = "bone_dragon";
    private static final String ENDSUN_GUARD = "endsun_guard";
    private static final String END_WITCH = "end_witch";

    private final YknBossPlugin plugin;
    private final AuraSkillsApi api;
    private final Ability huntingInstinct;
    private final Ability bossResistance;
    private final Ability lastStand;
    private final Ability regroup;
    private final Ability finalInsight;
    private final Skill slayer;

    AuraSlayerService(YknBossPlugin plugin) {
        this.plugin = plugin;
        api = AuraSkillsApi.get();
        slayer = requiredSkill("slayer");
        huntingInstinct = requiredAbility("hunting_instinct");
        bossResistance = requiredAbility("boss_resistance");
        lastStand = requiredAbility("last_stand");
        regroup = requiredAbility("regroup");
        finalInsight = requiredAbility("final_insight");
        plugin.getLogger().info("已连接 AuraSkills 内建讨伐职业。");
    }

    @Override
    public double outgoingDamage(Player player, double damage, boolean executeWindow) {
        if (damage <= 0.0 || !isOfficialCombatant(player)) return damage;
        double bonus = value(player, huntingInstinct);
        if (healthRatio(player) <= 0.35) bonus += value(player, lastStand);
        if (executeWindow) bonus += value(player, finalInsight);
        return damage * (1.0 + bonus / 100.0);
    }

    @Override
    public double incomingDamage(Player player, double damage) {
        if (damage <= 0.0 || !isOfficialCombatant(player)) return damage;
        return damage * Math.max(0.0, 1.0 - value(player, bossResistance) / 100.0);
    }

    @Override
    public void dealSkillDamage(Player player, double damage, DamageSource source) {
        player.damage(Math.max(0.0, damage), source);
    }

    @Override
    public int respawnDelayTicks(Player player, int baseDelayTicks) {
        if (!isOfficialCombatant(player)) return baseDelayTicks;
        int reductionTicks = (int) Math.round(value(player, regroup) * 20.0);
        return Math.max(120, baseDelayTicks - reductionTicks);
    }

    @Override
    public void awardVictory(String raid, Set<UUID> eligibleParticipants,
                             Map<UUID, Double> damageContributions) {
        if (!slayer.isEnabled()) return;
        double totalDamage = damageContributions.values().stream()
                .filter(value -> value != null && value > 0.0)
                .mapToDouble(Double::doubleValue)
                .sum();
        if (totalDamage <= 0.0) return;
        double minimumShare = Math.max(0.0,
                plugin.getConfig().getDouble("slayer.rewards.minimum-damage-share", 0.01));
        double minimumDamage = totalDamage * minimumShare;
        int xp = switch (raid) {
            case END_WITCH -> plugin.getConfig().getInt("slayer.rewards.end-witch-xp", 2200);
            case ENDSUN_GUARD -> plugin.getConfig().getInt("slayer.rewards.endsun-guard-xp", 1800);
            default -> plugin.getConfig().getInt("slayer.rewards.bone-dragon-xp", 1200);
        };
        if (xp <= 0) return;

        for (UUID playerId : eligibleParticipants) {
            if (damageContributions.getOrDefault(playerId, 0.0) + 0.0001 < minimumDamage) continue;
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            SkillsUser user = api.getUser(playerId);
            if (!user.isLoaded()) continue;
            user.addSkillXp(slayer, xp);
            player.sendMessage(plugin.messages().component("slayer.xp-awarded", "xp", xp));
        }
    }

    private double value(Player player, Ability ability) {
        if (!slayer.isEnabled() || !ability.isEnabled()) return 0.0;
        SkillsUser user = api.getUser(player.getUniqueId());
        if (!user.isLoaded()) return 0.0;
        int level = user.getAbilityLevel(ability);
        return level <= 0 ? 0.0 : ability.getValue(level);
    }

    private boolean isOfficialCombatant(Player player) {
        return plugin.arenaManager().isActiveCombatant(player)
                || plugin.endsunArenaManager().isActiveCombatant(player)
                || plugin.endWitchArenaManager().isActiveCombatant(player);
    }

    private double healthRatio(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MAX_HEALTH);
        double maximum = attribute == null ? 20.0 : Math.max(1.0, attribute.getValue());
        return Math.max(0.0, player.getHealth()) / maximum;
    }

    private NamespacedId id(String key) {
        return NamespacedId.of(NAMESPACE, key);
    }

    private Skill requiredSkill(String key) {
        NamespacedId id = id(key);
        Skill skill = api.getGlobalRegistry().getSkill(id);
        if (skill == null) {
            throw new IllegalStateException("AuraSkills 未注册讨伐职业 " + id);
        }
        return skill;
    }

    private Ability requiredAbility(String key) {
        NamespacedId id = id(key);
        Ability ability = api.getGlobalRegistry().getAbility(id);
        if (ability == null) {
            throw new IllegalStateException("AuraSkills 未注册讨伐能力 " + id);
        }
        return ability;
    }
}
