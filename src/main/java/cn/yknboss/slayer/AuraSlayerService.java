package cn.yknboss.slayer;

import cn.yknboss.YknBossPlugin;
import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.ability.CustomAbility;
import dev.aurelium.auraskills.api.item.ItemContext;
import dev.aurelium.auraskills.api.registry.NamespacedId;
import dev.aurelium.auraskills.api.registry.NamespacedRegistry;
import dev.aurelium.auraskills.api.skill.CustomSkill;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;

import java.io.File;
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
    private final CustomAbility huntingInstinct;
    private final CustomAbility bossResistance;
    private final CustomAbility lastStand;
    private final CustomAbility regroup;
    private final CustomAbility finalInsight;
    private final CustomSkill slayer;

    AuraSlayerService(YknBossPlugin plugin) {
        this.plugin = plugin;
        saveContentFiles();
        api = AuraSkillsApi.get();
        File contentDirectory = new File(plugin.getDataFolder(), "auraskills-content");
        NamespacedRegistry registry = api.useRegistry(NAMESPACE, contentDirectory);

        huntingInstinct = ability("hunting_instinct", "hunting-instinct", 0.4, 0.4, 1);
        bossResistance = ability("boss_resistance", "boss-resistance", 0.5, 0.5, 2);
        lastStand = ability("last_stand", "last-stand", 0.6, 0.6, 3);
        regroup = ability("regroup", "regroup", 0.2, 0.2, 4);
        finalInsight = ability("final_insight", "final-insight", 0.4, 0.4, 5);
        registry.registerAbility(huntingInstinct);
        registry.registerAbility(bossResistance);
        registry.registerAbility(lastStand);
        registry.registerAbility(regroup);
        registry.registerAbility(finalInsight);

        slayer = CustomSkill.builder(id("slayer"))
                .item(ItemContext.builder().material("nether_star").group("first_row").order(1).build())
                .abilities(huntingInstinct, bossResistance, lastStand, regroup, finalInsight)
                .displayName(text("slayer.skill.name"))
                .description(text("slayer.skill.description"))
                .build();
        registry.registerSkill(slayer);
        plugin.getLogger().info("已向 AuraSkills 注册讨伐职业（最高 100 级）。");
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
    public int respawnDelayTicks(Player player, int baseDelayTicks) {
        if (!isOfficialCombatant(player)) return baseDelayTicks;
        int reductionTicks = (int) Math.round(value(player, regroup) * 20.0);
        return Math.max(120, baseDelayTicks - reductionTicks);
    }

    @Override
    public void awardVictory(String raid, Set<UUID> eligibleParticipants,
                             Map<UUID, Double> damageContributions) {
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

    private CustomAbility ability(String id, String configKey, double base, double perLevel, int unlock) {
        return CustomAbility.builder(id(id))
                .baseValue(base)
                .valuePerLevel(perLevel)
                .unlock(unlock)
                .levelUp(5)
                .maxLevel(20)
                .displayName(text("slayer.abilities." + configKey + ".name"))
                .description(text("slayer.abilities." + configKey + ".description"))
                .info(text("slayer.abilities." + configKey + ".info"))
                .build();
    }

    private double value(Player player, CustomAbility ability) {
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

    private String text(String path) {
        return plugin.getConfig().getString(path, path);
    }

    private void saveContentFiles() {
        for (String path : new String[] {
                "auraskills-content/skills.yml",
                "auraskills-content/abilities.yml",
                "auraskills-content/xp_requirements.yml",
                "auraskills-content/sources/slayer.yml"
        }) {
            plugin.saveResource(path, false);
        }
    }
}
