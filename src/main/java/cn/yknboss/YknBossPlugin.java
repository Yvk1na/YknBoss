package cn.yknboss;

import cn.yknboss.arena.ArenaWorldStorage;
import cn.yknboss.arena.BoneDragonArenaManager;
import cn.yknboss.arena.BoneDragonArenaSettings;
import cn.yknboss.arena.EndsunGuardArenaManager;
import cn.yknboss.arena.EndsunGuardArenaSettings;
import cn.yknboss.arena.EndWitchArenaManager;
import cn.yknboss.arena.EndWitchArenaSettings;
import cn.yknboss.boss.BoneDragonManager;
import cn.yknboss.boss.BoneDragonSettings;
import cn.yknboss.boss.BossHealthLimits;
import cn.yknboss.boss.EndsunGuardManager;
import cn.yknboss.boss.EndsunGuardSettings;
import cn.yknboss.boss.EndWitchManager;
import cn.yknboss.boss.EndWitchSettings;
import cn.yknboss.command.YknBossCommand;
import cn.yknboss.command.PartyCommand;
import cn.yknboss.listener.BossListener;
import cn.yknboss.listener.BoneDragonArenaListener;
import cn.yknboss.model.BetterModelSupport;
import cn.yknboss.message.MessageService;
import cn.yknboss.party.PartyManager;
import cn.yknboss.party.PartySettings;
import cn.yknboss.special.SpecialItemManager;
import cn.yknboss.slayer.SlayerService;
import cn.yknboss.ui.RaidSidebarService;
import org.bukkit.NamespacedKey;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public final class YknBossPlugin extends JavaPlugin {
    private NamespacedKey bossTypeKey;
    private NamespacedKey specialItemKey;
    private NamespacedKey boneDragonPhaseKey;
    private NamespacedKey boneDragonAbsorbedKey;
    private NamespacedKey boneDragonDeathSummonMaskKey;
    private NamespacedKey endsunPhaseKey;
    private NamespacedKey endWitchPhaseKey;
    private NamespacedKey encounterHealthMultiplierKey;
    private BoneDragonSettings boneDragonSettings;
    private EndsunGuardSettings endsunGuardSettings;
    private EndWitchSettings endWitchSettings;
    private BoneDragonManager boneDragonManager;
    private EndsunGuardManager endsunGuardManager;
    private EndWitchManager endWitchManager;
    private BoneDragonArenaSettings arenaSettings;
    private BoneDragonArenaManager arenaManager;
    private EndsunGuardArenaSettings endsunArenaSettings;
    private EndsunGuardArenaManager endsunArenaManager;
    private EndWitchArenaSettings endWitchArenaSettings;
    private EndWitchArenaManager endWitchArenaManager;
    private PartyManager partyManager;
    private PartySettings partySettings;
    private MessageService messages;
    private SpecialItemManager specialItemManager;
    private BetterModelSupport betterModel;
    private SlayerService slayerService;
    private RaidSidebarService raidSidebar;

    @Override
    public void onEnable() {
        repairMalformedEndWitchMessageIndentation();
        saveDefaultConfig();
        boolean migrated = migrateLegacyConfig();
        int addedDefaults = mergeMissingDefaults();
        if (migrated || addedDefaults > 0) saveConfig();
        messages = new MessageService(this);
        bossTypeKey = new NamespacedKey(this, "boss_type");
        specialItemKey = new NamespacedKey(this, "special_item");
        boneDragonPhaseKey = new NamespacedKey(this, "bone_dragon_phase");
        boneDragonAbsorbedKey = new NamespacedKey(this, "bone_dragon_absorbed");
        boneDragonDeathSummonMaskKey = new NamespacedKey(this, "bone_dragon_death_summon_mask");
        endsunPhaseKey = new NamespacedKey(this, "endsun_phase");
        endWitchPhaseKey = new NamespacedKey(this, "end_witch_phase");
        encounterHealthMultiplierKey = new NamespacedKey(this, "encounter_health_multiplier");
        warnAboutClampedDragonHealth();
        boneDragonSettings = BoneDragonSettings.load(getConfig());
        endsunGuardSettings = EndsunGuardSettings.load(getConfig());
        endWitchSettings = EndWitchSettings.load(getConfig());
        arenaSettings = BoneDragonArenaSettings.load(getConfig());
        endsunArenaSettings = EndsunGuardArenaSettings.load(getConfig());
        endWitchArenaSettings = EndWitchArenaSettings.load(getConfig());
        partySettings = PartySettings.load(getConfig());
        betterModel = new BetterModelSupport(this);
        boneDragonManager = new BoneDragonManager(this);
        endsunGuardManager = new EndsunGuardManager(this);
        endWitchManager = new EndWitchManager(this);
        partyManager = new PartyManager(this, partySettings);
        raidSidebar = new RaidSidebarService(this);
        arenaManager = new BoneDragonArenaManager(this, arenaSettings);
        endsunArenaManager = new EndsunGuardArenaManager(this, endsunArenaSettings);
        endWitchArenaManager = new EndWitchArenaManager(this, endWitchArenaSettings);
        ArenaWorldStorage.ensureRootDirectory();
        migrateLegacyArenaWorlds();
        specialItemManager = new SpecialItemManager(this);
        slayerService = SlayerService.create(this);

        getServer().getPluginManager().registerEvents(new BossListener(this), this);
        getServer().getPluginManager().registerEvents(new BoneDragonArenaListener(this), this);
        getServer().getPluginManager().registerEvents(specialItemManager, this);
        YknBossCommand command = new YknBossCommand(this);
        PluginCommand pluginCommand = getCommand("yknboss");
        if (pluginCommand == null) {
            throw new IllegalStateException("plugin.yml 中缺少 yknboss 指令");
        }
        pluginCommand.setExecutor(command);
        pluginCommand.setTabCompleter(command);
        PartyCommand partyCommand = new PartyCommand(this);
        PluginCommand partyPluginCommand = getCommand("party");
        if (partyPluginCommand == null) {
            throw new IllegalStateException("plugin.yml 中缺少 party 指令");
        }
        partyPluginCommand.setExecutor(partyCommand);
        partyPluginCommand.setTabCompleter(partyCommand);

        getServer().getScheduler().runTaskTimer(this, () -> {
            boneDragonManager.tick();
            endsunGuardManager.tick();
            endWitchManager.tick();
            specialItemManager.tick();
            raidSidebar.tick();
        }, 1L, 1L);
        getServer().getScheduler().runTask(this, () -> {
            boneDragonManager.discoverLoadedBosses();
            endsunGuardManager.discoverLoadedBosses();
            endWitchManager.discoverLoadedBosses();
            if (arenaSettings.enabled() && (arenaSettings.autoCreate() || arenaManager.loadedWorld() != null)) {
                try {
                    if (arenaSettings.autoCreate()) arenaManager.createAll();
                    else arenaManager.createOrLoad();
                } catch (RuntimeException exception) {
                    getLogger().severe("创建骨龙竞技场失败: " + exception.getMessage());
                }
            }
            if (endsunArenaSettings.enabled()
                    && (endsunArenaSettings.autoCreate() || endsunArenaManager.loadedWorld() != null)) {
                try {
                    if (endsunArenaSettings.autoCreate()) endsunArenaManager.createAll();
                    else endsunArenaManager.createOrLoad();
                } catch (RuntimeException exception) {
                    getLogger().severe("创建阿努赫竞技场失败: " + exception.getMessage());
                }
            }
            if (endWitchArenaSettings.enabled()
                    && (endWitchArenaSettings.autoCreate() || endWitchArenaManager.loadedWorld() != null)) {
                try {
                    if (endWitchArenaSettings.autoCreate()) endWitchArenaManager.createAll();
                    else endWitchArenaManager.createOrLoad();
                } catch (RuntimeException exception) {
                    getLogger().severe("创建伊芙蕾雅竞技场失败: " + exception.getMessage());
                }
            }
        });

        getLogger().info("YknBoss 已启用。Paper 1.21.10 / Java " + Runtime.version().feature());
        if (!betterModel.available()) {
            getLogger().warning("未检测到兼容的 BetterModel 3.x（当前：" + betterModel.detectedVersion()
                    + "）；骨龙、烬日冥卫·阿努赫与终焉之魔女的生成指令将被拒绝。");
        } else if (!betterModel.hasModel(boneDragonSettings.modelId())) {
            getLogger().warning("BetterModel 3.x 已连接，但尚未加载模型 " + boneDragonSettings.modelId()
                    + "。请安装 BetterModel 模型包并执行 /bettermodel reload。");
        }
        if (betterModel.available() && !betterModel.hasModel(endsunGuardSettings.modelId())) {
            getLogger().warning("BetterModel 3.x 已连接，但尚未加载模型 " + endsunGuardSettings.modelId()
                    + "。请安装 BetterModel 模型包并执行 /bettermodel reload。");
        }
        if (betterModel.available() && !betterModel.hasModel(endWitchSettings.modelId())) {
            getLogger().warning("BetterModel 3.x 已连接，但尚未加载模型 " + endWitchSettings.modelId()
                    + "。请安装 BetterModel 模型包并执行 /bettermodel reload。");
        }
    }

    /**
     * Repairs the single malformed message block shipped by 2.0.5 before Bukkit
     * attempts to parse the external configuration. User content is backed up and
     * every other line remains untouched.
     */
    private void repairMalformedEndWitchMessageIndentation() {
        var configPath = getDataFolder().toPath().resolve("config.yml");
        if (!Files.isRegularFile(configPath)) return;
        try {
            var lines = Files.readAllLines(configPath, StandardCharsets.UTF_8);
            boolean repaired = false;
            for (int index = 0; index + 1 < lines.size(); index++) {
                if (!"  end-witch:".equals(lines.get(index))
                        || !lines.get(index + 1).startsWith("    bossbar:")) continue;
                lines.set(index, "    end-witch:");
                for (int child = index + 1; child < Math.min(lines.size(), index + 4); child++) {
                    String line = lines.get(child);
                    if (line.startsWith("    ") && !line.startsWith("      ")) {
                        lines.set(child, "  " + line);
                    }
                }
                repaired = true;
                break;
            }
            if (!repaired) return;
            Files.createDirectories(getDataFolder().toPath());
            var backup = getDataFolder().toPath().resolve("config.yml.pre-2.0.6-yaml-fix.bak");
            if (!Files.exists(backup)) {
                Files.copy(configPath, backup, StandardCopyOption.COPY_ATTRIBUTES);
            }
            Files.write(configPath, lines, StandardCharsets.UTF_8);
            getLogger().warning("已自动修复 2.0.5 配置中的终焉魔女消息缩进；原文件已备份为 "
                    + backup.getFileName());
        } catch (IOException exception) {
            getLogger().warning("自动修复 2.0.5 配置缩进失败：" + exception.getMessage());
        }
    }

    @Override
    public void onDisable() {
        if (raidSidebar != null) raidSidebar.shutdown();
        if (specialItemManager != null) specialItemManager.shutdown();
        if (arenaManager != null) arenaManager.shutdown();
        if (endsunArenaManager != null) endsunArenaManager.shutdown();
        if (endWitchArenaManager != null) endWitchArenaManager.shutdown();
        if (boneDragonManager != null) boneDragonManager.shutdown();
        if (endsunGuardManager != null) endsunGuardManager.shutdown();
        if (endWitchManager != null) endWitchManager.shutdown();
    }

    public int reloadPluginConfig() {
        reloadConfig();
        boolean migrated = migrateLegacyConfig();
        int addedDefaults = mergeMissingDefaults();
        if (migrated || addedDefaults > 0) saveConfig();

        BoneDragonSettings reloadedBoneDragonSettings = BoneDragonSettings.load(getConfig());
        EndsunGuardSettings reloadedEndsunGuardSettings = EndsunGuardSettings.load(getConfig());
        EndWitchSettings reloadedEndWitchSettings = EndWitchSettings.load(getConfig());
        BoneDragonArenaSettings reloadedArenaSettings = BoneDragonArenaSettings.load(getConfig());
        EndsunGuardArenaSettings reloadedEndsunArenaSettings = EndsunGuardArenaSettings.load(getConfig());
        EndWitchArenaSettings reloadedEndWitchArenaSettings = EndWitchArenaSettings.load(getConfig());
        PartySettings reloadedPartySettings = PartySettings.load(getConfig());

        warnAboutClampedDragonHealth();
        boneDragonSettings = reloadedBoneDragonSettings;
        endsunGuardSettings = reloadedEndsunGuardSettings;
        endWitchSettings = reloadedEndWitchSettings;
        arenaSettings = reloadedArenaSettings;
        endsunArenaSettings = reloadedEndsunArenaSettings;
        endWitchArenaSettings = reloadedEndWitchArenaSettings;
        partySettings = reloadedPartySettings;
        arenaManager.reload(arenaSettings);
        endsunArenaManager.reload(endsunArenaSettings);
        endWitchArenaManager.reload(endWitchArenaSettings);
        partyManager.reload(partySettings);
        raidSidebar.reload();
        for (var player : getServer().getOnlinePlayers()) {
            if (arenaManager.syncRaidSidebar(player)
                    || endsunArenaManager.syncRaidSidebar(player)
                    || endWitchArenaManager.syncRaidSidebar(player)) continue;
            raidSidebar.detach(player);
        }
        boneDragonManager.applySettingsToActiveBosses();
        endsunGuardManager.applySettingsToActiveBosses();
        endWitchManager.applySettingsToActiveBosses();
        getLogger().info("配置已热重载；自动补齐 " + addedDefaults + " 个缺失配置项。无需重启服务器。");
        return addedDefaults;
    }

    private int mergeMissingDefaults() {
        YamlConfiguration bundledDefaults;
        InputStream resource = getResource("config.yml");
        if (resource == null) throw new IllegalStateException("插件包内缺少 config.yml");
        try (InputStreamReader reader = new InputStreamReader(resource, StandardCharsets.UTF_8)) {
            bundledDefaults = YamlConfiguration.loadConfiguration(reader);
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取插件包内的默认配置", exception);
        }

        FileConfiguration config = getConfig();
        int added = 0;
        for (String path : bundledDefaults.getKeys(true)) {
            if (bundledDefaults.isConfigurationSection(path) || config.contains(path, true)) continue;
            config.set(path, bundledDefaults.get(path));
            added++;
        }
        config.options().copyDefaults(true);
        return added;
    }

    private void migrateLegacyArenaWorlds() {
        try {
            arenaManager.migrateLegacyWorlds();
        } catch (RuntimeException exception) {
            getLogger().severe("迁移旧骨龙副本到 bossmap 失败: " + exception.getMessage());
        }
        try {
            endsunArenaManager.migrateLegacyWorlds();
        } catch (RuntimeException exception) {
            getLogger().severe("迁移旧阿努赫副本到 bossmap 失败: " + exception.getMessage());
        }
        try {
            endWitchArenaManager.migrateLegacyWorlds();
        } catch (RuntimeException exception) {
            getLogger().severe("迁移旧伊芙蕾雅副本到 bossmap 失败: " + exception.getMessage());
        }
    }

    private boolean migrateLegacyConfig() {
        int version = getConfig().contains("config-version", true)
                ? getConfig().getInt("config-version") : 1;
        if (version < 5) {
            if (version == 4) {
                String yawPath = "bone-dragon.model.yaw-offset-degrees";
                double oldYaw = getConfig().contains(yawPath, true) ? getConfig().getDouble(yawPath) : 180.0;
                double flippedYaw = (oldYaw + 180.0) % 360.0;
                if (flippedYaw < 0.0) flippedYaw += 360.0;
                getConfig().set(yawPath, flippedYaw);
            } else {
                getConfig().set("bone-dragon.model.yaw-offset-degrees", 0.0);
            }
        }
        if (version < 10) {
            if (version < 7) getConfig().set("bone-dragon.fear-aura", null);
            if (getConfig().getInt("bone-dragon-arena.radius", 200) == 200) {
                getConfig().set("bone-dragon-arena.radius", 100);
            }
            if (!getConfig().isSet("bone-dragon-arena.summon-duration-seconds")) {
                getConfig().set("bone-dragon-arena.summon-duration-seconds", 3);
            }
        }
        if (version < 11) {
            getConfig().set("bone-dragon-arena.instance-count", 10);
            getConfig().set("bone-dragon-arena.return-delay-seconds", 30);
            getConfig().set("bone-dragon.model.scale", 1.0);
        }
        if (version < 12) {
            if (!getConfig().isSet("bone-dragon-arena.respawn-delay-seconds")) {
                getConfig().set("bone-dragon-arena.respawn-delay-seconds", 10);
            }
            if (!getConfig().isSet("party.max-members")) getConfig().set("party.max-members", 6);
            if (!getConfig().isSet("party.invite-expire-seconds")) getConfig().set("party.invite-expire-seconds", 60);
        }
        if (version < 15) {
            String lorePath = "messages.special.rainbow.lore";
            var lore = new java.util.ArrayList<>(getConfig().getStringList(lorePath));
            for (int index = 0; index < lore.size(); index++) {
                if ("&7每秒变换一次染色玻璃颜色。".equals(lore.get(index))) {
                    lore.set(index, "&7每 0.5 秒变换一次染色玻璃颜色。");
                }
            }
            if (!lore.isEmpty()) getConfig().set(lorePath, lore);
        }
        if (version < 17) {
            double oldMaximum = getConfig().getDouble("bone-dragon.max-health", 1024.0);
            double p2Ratio = getConfig().getDouble("bone-dragon.phases.p2-health-percent", 60.0) / 100.0;
            double p3Ratio = getConfig().getDouble("bone-dragon.phases.p3-health-percent", 30.0) / 100.0;
            if (!getConfig().isSet("bone-dragon.phases.p1-health")) {
                getConfig().set("bone-dragon.phases.p1-health", oldMaximum * Math.max(0.01, 1.0 - p2Ratio));
            }
            if (!getConfig().isSet("bone-dragon.phases.p2-health")) {
                getConfig().set("bone-dragon.phases.p2-health", oldMaximum * Math.max(0.01, p2Ratio - p3Ratio));
            }
            if (!getConfig().isSet("bone-dragon.phases.p3-health")) {
                getConfig().set("bone-dragon.phases.p3-health", oldMaximum * Math.max(0.01, p3Ratio));
            }
            getConfig().set("bone-dragon.max-health", null);
            getConfig().set("bone-dragon.phases.p2-health-percent", null);
            getConfig().set("bone-dragon.phases.p3-health-percent", null);
        }
        if (version < 18 && !getConfig().isSet("bone-dragon.minions.max-alive")) {
            getConfig().set("bone-dragon.minions.max-alive", 15);
        }
        if (version < 19) {
            String stunDurationPath = "bone-dragon.stun.duration-seconds";
            double oldStunDuration = getConfig().getDouble(stunDurationPath, 5.0);
            if (Math.abs(oldStunDuration - 5.0) < 0.0001) getConfig().set(stunDurationPath, 8.0);
        }
        if (version < 20) {
            String deathSummonRoot = "bone-dragon.abilities.death-summon.";
            if (!getConfig().isSet(deathSummonRoot + "min-count")) getConfig().set(deathSummonRoot + "min-count", 1);
            if (!getConfig().isSet(deathSummonRoot + "max-count")) getConfig().set(deathSummonRoot + "max-count", 3);
            if (!getConfig().isSet(deathSummonRoot + "heal-interval-seconds")) {
                getConfig().set(deathSummonRoot + "heal-interval-seconds", 10.0);
            }
            if (!getConfig().isSet(deathSummonRoot + "missing-health-heal-percent")) {
                getConfig().set(deathSummonRoot + "missing-health-heal-percent", 5.0);
            }
            if (!getConfig().isSet(deathSummonRoot + "ruler-health")) getConfig().set(deathSummonRoot + "ruler-health", 60.0);
            if (!getConfig().isSet(deathSummonRoot + "ruler-damage")) getConfig().set(deathSummonRoot + "ruler-damage", 12.0);
        }
        if (version < 21) {
            String absorbRoot = "bone-dragon.abilities.skeleton-absorb.";
            if (!getConfig().isSet(absorbRoot + "max-count")) getConfig().set(absorbRoot + "max-count", 10);
            if (!getConfig().isSet(absorbRoot + "damage-increase-percent-per-skeleton")) {
                getConfig().set(absorbRoot + "damage-increase-percent-per-skeleton", 1.0);
            }
        }
        if (version < 22) {
            String spikesRoot = "bone-dragon.abilities.bone-spikes.";
            if (!getConfig().isSet(spikesRoot + "cooldown-seconds")) getConfig().set(spikesRoot + "cooldown-seconds", 14.0);
            if (!getConfig().isSet(spikesRoot + "damage")) getConfig().set(spikesRoot + "damage", 22.0);
            if (!getConfig().isSet(spikesRoot + "radius")) getConfig().set(spikesRoot + "radius", 12.0);
            if (!getConfig().isSet(spikesRoot + "rings")) getConfig().set(spikesRoot + "rings", 4);
            if (!getConfig().isSet(spikesRoot + "spikes-per-ring")) getConfig().set(spikesRoot + "spikes-per-ring", 18);
            if (!getConfig().isSet(spikesRoot + "hit-radius")) getConfig().set(spikesRoot + "hit-radius", 1.75);
        }
        if (version >= 60) return false;
        if (version < 23) {
            String absorbRoot = "bone-dragon.abilities.skeleton-absorb.";
            if (!getConfig().isSet(absorbRoot + "max-damage-increase-percent")) {
                getConfig().set(absorbRoot + "max-damage-increase-percent", 30.0);
            }
        }
        String rainbowLorePath = "messages.special.rainbow.lore";
        var rainbowLore = new java.util.ArrayList<>(getConfig().getStringList(rainbowLorePath));
        for (int index = 0; index < rainbowLore.size(); index++) {
            String line = rainbowLore.get(index);
            if (!"&7每秒变换一次染色玻璃颜色。".equals(line)
                    && !"&7每 0.5 秒变换一次染色玻璃颜色。".equals(line)) continue;
            rainbowLore.set(index, "&7动态纹理每 0.5 秒变换一次颜色。");
            if (!rainbowLore.contains("&8固定物品与 NBT，仅改变显示颜色")) {
                rainbowLore.add(index + 1, "&8固定物品与 NBT，仅改变显示颜色");
            }
            break;
        }
        if (!rainbowLore.isEmpty()) getConfig().set(rainbowLorePath, rainbowLore);
        if (version < 25) {
            String maxAlivePath = "bone-dragon.minions.max-alive";
            if (getConfig().getInt(maxAlivePath, 15) == 15) getConfig().set(maxAlivePath, 20);
            String summonCountPath = "bone-dragon.abilities.summon-skeletons.count";
            if (getConfig().getInt(summonCountPath, 5) == 5) getConfig().set(summonCountPath, 8);
        }
        if (version < 26) {
            getConfig().set("messages.arena.session-id", null);
            getConfig().set("messages.command.spec-usage", "&e用法：/yknboss spec <玩家ID|leave>");
            getConfig().set("messages.command.help-spec", java.util.List.of(
                    "&b/yknboss spec <玩家ID> &7- 观战该玩家正在挑战的骨龙副本",
                    "&b/yknboss spec leave &7- 退出观战并返回原位置"));
            getConfig().set("messages.arena.spectate.entered",
                    "&b已进入玩家 &f{player}&b 所在的骨龙副本观战。");
            getConfig().set("messages.arena.error.spectate-not-found",
                    "指定玩家未在可观战的骨龙副本中：{player}");
        }
        if (version < 27 && !getConfig().isSet("bone-dragon-arena.challenge-duration-seconds")) {
            getConfig().set("bone-dragon-arena.challenge-duration-seconds", 600);
        }
        if (version < 28) {
            String absorbRoot = "bone-dragon.abilities.skeleton-absorb.";
            if (!getConfig().isSet(absorbRoot + "min-remaining")) {
                getConfig().set(absorbRoot + "min-remaining", 5);
            }
            if (!getConfig().isSet(absorbRoot + "post-cap-max-count")) {
                getConfig().set(absorbRoot + "post-cap-max-count", 5);
            }
            String healthLorePath = "messages.special.rainbow.lore";
            var lore = new java.util.ArrayList<>(getConfig().getStringList(healthLorePath));
            String healthLine = "&6佩戴在头部时：最大生命值 +100";
            if (!lore.isEmpty() && !lore.contains(healthLine)) lore.add(1, healthLine);
            if (!lore.isEmpty()) getConfig().set(healthLorePath, lore);
        }
        if (version < 29) {
            if (!getConfig().isSet("bone-dragon.abilities.shield-disable-seconds")) {
                getConfig().set("bone-dragon.abilities.shield-disable-seconds", 5.0);
            }
            if (!getConfig().isSet("messages.arena.spectate.gamemode-locked")) {
                getConfig().set("messages.arena.spectate.gamemode-locked",
                        "&c观战骨龙副本期间无法切换游戏模式。");
            }
        }
        if (version < 30) {
            getConfig().set("messages.command.admin-usage", "&e用法：/yknboss admin <special|bypass>");
            if (!getConfig().isSet("messages.command.admin-bypass-enabled")) {
                getConfig().set("messages.command.admin-bypass-enabled",
                        "&a已开启观战模式切换绕过；现在可以在观战副本中切换游戏模式。");
            }
            if (!getConfig().isSet("messages.command.admin-bypass-disabled")) {
                getConfig().set("messages.command.admin-bypass-disabled", "&e已关闭观战模式切换绕过。");
            }
            var adminHelp = new java.util.ArrayList<>(getConfig().getStringList("messages.command.help-admin"));
            String bypassHelp = "&d/yknboss admin bypass &7- 开关观战副本游戏模式限制绕过";
            if (!adminHelp.contains(bypassHelp)) adminHelp.add(bypassHelp);
            getConfig().set("messages.command.help-admin", adminHelp);
        }
        if (version < 31) {
            getConfig().set("undead-king", null);
            getConfig().set("messages.command.spawned-undead-king", null);
            getConfig().set("messages.command.info.undead-count", null);
            getConfig().set("messages.command.info.undead-line", null);
            getConfig().set("messages.boss.undead-king", null);
            getConfig().set("messages.entity.undead-guard", null);
            getConfig().set("messages.item.undead-king-model", null);
            getConfig().set("messages.item.undead-soul-name", null);
            getConfig().set("messages.item.undead-soul-lore-one", null);
            getConfig().set("messages.item.undead-soul-lore-two", null);
            var adminHelp = new java.util.ArrayList<>(getConfig().getStringList("messages.command.help-admin"));
            adminHelp.removeIf(line -> line.toLowerCase(java.util.Locale.ROOT).contains("undead_king")
                    || line.contains("不死之王"));
            getConfig().set("messages.command.help-admin", adminHelp);
        }
        if (version < 33 && !getConfig().isSet("bone-dragon.phases.transition-invulnerability-seconds")) {
            getConfig().set("bone-dragon.phases.transition-invulnerability-seconds", 0.5);
        }
        if (version < 34) {
            getConfig().set("messages.command.info.endsun-line",
                    "&7- {id} | HP {health}/{max-health} | {phase} | {skill} | 日蚀 {eclipse} | {world} {x} {y} {z}");
        }
        if (version < 35) {
            double oldMaximum = getConfig().getDouble("endsun-guard.max-health", 800.0);
            if (!getConfig().isSet("endsun-guard.phases.p1-health")) {
                getConfig().set("endsun-guard.phases.p1-health", oldMaximum);
            }
            if (!getConfig().isSet("endsun-guard.phases.p2-health")) {
                getConfig().set("endsun-guard.phases.p2-health", oldMaximum * 0.875);
            }
            if (!getConfig().isSet("endsun-guard.phases.p3-health")) {
                getConfig().set("endsun-guard.phases.p3-health", oldMaximum * 0.75);
            }
            getConfig().set("endsun-guard.max-health", null);
            getConfig().set("endsun-guard.phases.phase-two-health-percent", null);
            getConfig().set("endsun-guard.phases.phase-three-health-percent", null);
            if (Math.abs(getConfig().getDouble("endsun-guard.movement-speed", 0.25) - 0.25) < 0.0001) {
                getConfig().set("endsun-guard.movement-speed", 0.32);
            }
            if (!getConfig().isSet("endsun-guard.attack-speed-multiplier")) {
                getConfig().set("endsun-guard.attack-speed-multiplier", 1.2);
            }
            migrateDefaultNumber("endsun-guard.abilities.scythe-return.cooldown-seconds", 8.0, 6.5);
            migrateDefaultNumber("endsun-guard.abilities.amethyst-judgement.cooldown-seconds", 18.0, 14.0);
            migrateDefaultNumber("endsun-guard.abilities.soul-crown.cooldown-seconds", 24.0, 18.0);
            migrateDefaultNumber("endsun-guard.abilities.solar-shield.cooldown-seconds", 16.0, 12.0);
            migrateDefaultNumber("endsun-guard.abilities.heart-furnace.drain-cooldown-seconds", 20.0, 15.0);
            migrateDefaultNumber("endsun-guard.abilities.black-sun.fragment-cost", 3.0, 6.0);
            getConfig().set("messages.boss.endsun-guard.fragment-lore", "&7拾取后自动计入团队进度，集齐后全队获得遮光屏障。");
            getConfig().set("messages.boss.endsun-guard.fragment-progress", "&e团队盾牌残片：{value}/{cost}");
            getConfig().set("messages.boss.endsun-guard.barrier-built", "&8&l盾牌残片已组成团队遮光屏障，全队将免疫黑日处决！");
        }
        if (version < 38) {
            getConfig().set("bone-dragon.stun.dive-hit-count", null);
        }
        if (version < 39) {
            getConfig().set("party.invite-expire-seconds", 60);
            getConfig().set("messages.command.enter-usage",
                    "&e用法：/yknboss enter <bone_dragon|endsun_guard>");
            getConfig().set("messages.command.unknown-raid",
                    "&c未知副本：{raid}，可用 bone_dragon 或 endsun_guard");
            getConfig().set("messages.command.arena-usage",
                    "&e用法：/yknboss arena <create|spawn|reset|info>");
            getConfig().set("messages.party.error.invite-repeat",
                    "你需要等待 {seconds} 秒后才能再次邀请该玩家");
            getConfig().set("messages.party.error.no-party",
                    "你当前没有队伍，请先使用 /p invite <玩家ID> 自动创建队伍");
            getConfig().set("messages.party.error.unknown-raid", null);

            var playerHelp = new java.util.ArrayList<>(getConfig().getStringList("messages.command.help-player"));
            playerHelp.removeIf(line -> {
                String lower = line.toLowerCase(java.util.Locale.ROOT);
                return lower.contains("/party enter") || lower.contains("/p enter")
                        || lower.contains("/yknboss arena tp");
            });
            String enterHelp = "&b/yknboss enter <bone_dragon|endsun_guard> &7- 进入对应 Boss 副本";
            if (playerHelp.stream().noneMatch(line -> line.toLowerCase(java.util.Locale.ROOT)
                    .contains("/yknboss enter"))) playerHelp.add(0, enterHelp);
            getConfig().set("messages.command.help-player", playerHelp);

            var adminHelp = new java.util.ArrayList<>(getConfig().getStringList("messages.command.help-admin"));
            for (int index = 0; index < adminHelp.size(); index++) {
                if (adminHelp.get(index).toLowerCase(java.util.Locale.ROOT).contains("/yknboss arena")) {
                    adminHelp.set(index, "&b/yknboss arena <create|spawn|reset|info>");
                }
            }
            getConfig().set("messages.command.help-admin", adminHelp);
            getConfig().set("messages.party.help", java.util.List.of(
                    "&6队伍指令：",
                    "&e/p i <玩家ID> &7（/party invite，无队伍时自动创建）&e | accept | decline",
                    "&e/party remove <玩家ID> | /p t <玩家ID>",
                    "&b/yknboss enter <bone_dragon|endsun_guard> &7（队长创建对应团本）",
                    "&e/p l | /p d | /party list | /party chat <消息>"));
        }
        if (version < 40) {
            getConfig().set("endsun-guard.phases.p1-health", 409.6);
            getConfig().set("endsun-guard.phases.p2-health", 307.2);
            getConfig().set("endsun-guard.phases.p3-health", 307.2);
            if (!getConfig().isSet("endsun-guard.phases.phase-two-damage-bonus-percent")) {
                getConfig().set("endsun-guard.phases.phase-two-damage-bonus-percent", 15.0);
            }
            if (!getConfig().isSet("endsun-guard.phases.phase-three-damage-bonus-percent")) {
                getConfig().set("endsun-guard.phases.phase-three-damage-bonus-percent", 30.0);
            }
        }
        if (version < 41) {
            for (String arenaRoot : java.util.List.of("bone-dragon-arena", "endsun-guard-arena")) {
                getConfig().set(arenaRoot + ".challenge-duration-seconds", 900);
                if (!getConfig().isSet(arenaRoot + ".challenge-duration-reduction-per-extra-player-seconds")) {
                    getConfig().set(arenaRoot + ".challenge-duration-reduction-per-extra-player-seconds", 60);
                }
                if (!getConfig().isSet(arenaRoot + ".minimum-challenge-duration-seconds")) {
                    getConfig().set(arenaRoot + ".minimum-challenge-duration-seconds", 60);
                }
                if (!getConfig().isSet(arenaRoot + ".boss-health-increase-per-extra-player-percent")) {
                    getConfig().set(arenaRoot + ".boss-health-increase-per-extra-player-percent", 10.0);
                }
            }
        }
        if (version < 44) {
            migrateDefaultNumber("end-witch.reactive-teleport.distance", 5.0, 8.0);
            if (!getConfig().isSet("end-witch.phases.phase-two-damage-bonus-percent")) {
                getConfig().set("end-witch.phases.phase-two-damage-bonus-percent", 20.0);
            }
            if (!getConfig().isSet("end-witch.phases.phase-three-damage-bonus-percent")) {
                getConfig().set("end-witch.phases.phase-three-damage-bonus-percent", 40.0);
            }
            for (String arenaRoot : java.util.List.of(
                    "bone-dragon-arena", "endsun-guard-arena", "end-witch-arena")) {
                if (!getConfig().isSet(arenaRoot + ".challenge-duration-seconds")) {
                    getConfig().set(arenaRoot + ".challenge-duration-seconds", 900);
                }
                if (!getConfig().isSet(arenaRoot + ".challenge-duration-reduction-per-extra-player-seconds")) {
                    getConfig().set(arenaRoot + ".challenge-duration-reduction-per-extra-player-seconds", 60);
                }
                if (!getConfig().isSet(arenaRoot + ".minimum-challenge-duration-seconds")) {
                    getConfig().set(arenaRoot + ".minimum-challenge-duration-seconds", 60);
                }
                if (!getConfig().isSet(arenaRoot + ".boss-health-increase-per-extra-player-percent")) {
                    getConfig().set(arenaRoot + ".boss-health-increase-per-extra-player-percent", 10.0);
                }
            }
        }
        if (version < 45) {
            migrateDefaultNumber("end-witch.abilities.world-end.channel-seconds", 8.0, 12.0);
            if (!getConfig().isSet("end-witch.abilities.world-end.players-per-channel-reduction")) {
                getConfig().set("end-witch.abilities.world-end.players-per-channel-reduction", 2);
            }
            if (!getConfig().isSet("end-witch.abilities.world-end.channel-reduction-seconds")) {
                getConfig().set("end-witch.abilities.world-end.channel-reduction-seconds", 1.0);
            }
            if (!getConfig().isSet("end-witch.abilities.world-end.minimum-channel-seconds")) {
                getConfig().set("end-witch.abilities.world-end.minimum-channel-seconds", 1.0);
            }
            String messagePath = "messages.boss.end-witch.world-end-start";
            String oldMessage = "&0&l必杀——世界终焉！&a在 8 秒内击破终焉护盾！";
            if (oldMessage.equals(getConfig().getString(messagePath, oldMessage))) {
                getConfig().set(messagePath, "&0&l必杀——世界终焉！&a在 {seconds} 秒内击破终焉护盾！");
            }
        }
        if (version < 46 && !getConfig().isSet("messages.boss.end-witch.bossbar-status")) {
            getConfig().set("messages.boss.end-witch.bossbar-status",
                    " &2蚀痕 &f{mark}&7/3 &5终焉刻度 &f{gauge}&7/&f{max}");
        }
        if (version < 48) getConfig().set("messages.boss.endsun-guard.eclipse-meter", null);
        if (version < 49) migrateCombatBalanceV49();
        if (version < 50) migrateCombatBalanceV50();
        if (version < 51) migrateEndsunEmpiricalBalanceV51();
        if (version < 53) migrateCastingFlowV53();
        if (version < 54) migrateUniformSevenMinuteBalanceV54();
        if (version < 56) {
            String titlePath = "raid-sidebar.title";
            String legacyTitle = "&b&l✦ 副本挑战 ✦";
            if (!getConfig().isSet(titlePath)
                    || legacyTitle.equals(getConfig().getString(titlePath))) {
                getConfig().set(titlePath, "&b&lSKYBLOCK");
            }
        }
        if (version < 57 && getConfig().getInt("party.max-members", 6) > 6) {
            getConfig().set("party.max-members", 6);
        }
        if (version < 58) {
            getConfig().set("slayer.skill", null);
            getConfig().set("slayer.abilities", null);
            getConfig().set("boss-skill-damage", null);
        }
        if (version < 59) migrateAuraSkillsBalanceV59();
        if (version < 60) migrateLeaveCommandV60();
        getConfig().set("config-version", 60);
        getLogger().info("Boss 配置已迁移至 v60（副本退出指令改为 /yknboss leave）。");
        return true;
    }

    private void migrateLeaveCommandV60() {
        String helpPath = "messages.command.help-player";
        var help = new java.util.ArrayList<>(getConfig().getStringList(helpPath));
        boolean changed = false;
        for (int index = 0; index < help.size(); index++) {
            String line = help.get(index);
            if (!line.toLowerCase(java.util.Locale.ROOT).contains("/yknboss giveup")) continue;
            help.set(index, line.replaceAll("(?i)/yknboss giveup", "/yknboss leave"));
            changed = true;
        }
        if (changed) getConfig().set(helpPath, help);
    }

    private void migrateCastingFlowV53() {
        setExplicitDefault("endsun-guard.reactive-teleport.hit-count", 5);
        setExplicitDefault("endsun-guard.abilities.cast-timing.initial-delay-seconds", 1.5);
        setExplicitDefault("endsun-guard.abilities.cast-timing.retry-delay-seconds", 0.5);
        setExplicitDefault("endsun-guard.abilities.cast-timing.phase-one-two-delay-seconds", 1.2);
        setExplicitDefault("endsun-guard.abilities.cast-timing.phase-three-delay-seconds", 0.8);
        setExplicitDefault("bone-dragon.abilities.cast-timing.retry-delay-seconds", 0.5);
        setExplicitDefault("bone-dragon.abilities.cast-timing.phase-one-delay-seconds", 1.0);
        setExplicitDefault("bone-dragon.abilities.cast-timing.phase-two-three-delay-seconds", 0.8);
        setExplicitDefault("end-witch.abilities.world-end.gauge-after-shield-break", 30.0);

        String chainPath = "end-witch.abilities.cast-timing.chain-skill-delay-seconds";
        if (!getConfig().contains(chainPath, true)) {
            String legacyPath = "end-witch.abilities.cast-timing.repeated-skill-delay-seconds";
            double delay = getConfig().contains(legacyPath, true)
                    ? getConfig().getDouble(legacyPath) : 0.55;
            getConfig().set(chainPath, delay);
        }

        String brokenPath = "messages.boss.end-witch.world-end-broken";
        String oldBroken = "&a&l终焉护盾破碎！伊芙蕾雅虚弱 8 秒，刻度回退至 80。";
        if (oldBroken.equals(getConfig().getString(brokenPath))) {
            getConfig().set(brokenPath,
                    "&a&l终焉护盾破碎！伊芙蕾雅虚弱 8 秒，刻度回退至 {gauge}。");
        }
        String cyanPath = "messages.boss.end-witch.aux.rune_cyan";
        if ("&b复诵文字".equals(getConfig().getString(cyanPath))) {
            getConfig().set(cyanPath, "&b连缀文字");
        }
    }

    private void migrateUniformSevenMinuteBalanceV54() {
        java.util.List<Double> oldMultipliers = java.util.List.of(
                1.00, 1.45, 1.90, 2.30, 2.70, 2.85, 2.98, 3.10, 3.20, 3.30);
        java.util.List<Double> standardMultipliers = java.util.List.of(
                1.00, 1.50, 2.25, 3.00, 3.75, 4.50, 5.25, 6.00, 6.75, 7.50);
        java.util.List<Double> endsunMultipliers = java.util.List.of(
                1.00, 2.00, 3.00, 4.00, 5.00, 6.00, 7.00, 8.00, 9.00, 10.00);

        for (String arenaRoot : java.util.List.of(
                "bone-dragon-arena", "endsun-guard-arena", "end-witch-arena")) {
            migrateKnownDefaultNumber(arenaRoot + ".challenge-duration-seconds", 600.0, 900.0);
            migrateKnownDefaultNumber(arenaRoot
                    + ".challenge-duration-reduction-per-extra-player-seconds", 0.0, 60.0);
            migrateKnownDefaultNumber(arenaRoot + ".minimum-challenge-duration-seconds", 600.0, 60.0);
        }
        migrateKnownDefaultNumberList("bone-dragon-arena.boss-health-multipliers-by-player-count",
                standardMultipliers, oldMultipliers);
        migrateKnownDefaultNumberList("endsun-guard-arena.boss-health-multipliers-by-player-count",
                endsunMultipliers, oldMultipliers);
        migrateKnownDefaultNumberList("end-witch-arena.boss-health-multipliers-by-player-count",
                standardMultipliers, oldMultipliers);
        migrateKnownDefaultNumber("bone-dragon-arena.boss-health-increase-per-extra-player-percent",
                75.0, 10.0);
        migrateKnownDefaultNumber("endsun-guard-arena.boss-health-increase-per-extra-player-percent",
                100.0, 10.0);
        migrateKnownDefaultNumber("end-witch-arena.boss-health-increase-per-extra-player-percent",
                75.0, 10.0);

        migrateKnownDefaultNumber("bone-dragon.phases.p1-health", 33_871.0, 55_000.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p2-health", 30_792.0, 50_000.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p3-health", 27_713.0, 45_000.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.guard-health", 493.0, 800.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.summon-skeletons.health", 739.0, 1_200.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.death-summon.ruler-health", 1_540.0, 2_500.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p1-health", 38_061.0, 78_750.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p2-health", 33_832.0, 70_000.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p3-health", 29_603.0, 61_250.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.soul-crown.shield-heal-on-burning-hit",
                97.0, 200.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.solar-shield.maximum-energy", 1_208.0, 2_500.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.heart-furnace.reflection-health", 725.0, 1_500.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.heart-furnace.shield-restore-per-soul", 12.0, 25.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.black-sun.furnace-health", 967.0, 2_000.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sunfall.armor-break-damage", 1_208.0, 2_500.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.twin-eclipse.body-health", 1_450.0, 3_000.0);
        migrateKnownDefaultNumber("end-witch.phases.p1-health", 83_574.0, 135_708.0);
        migrateKnownDefaultNumber("end-witch.phases.p2-health", 80_479.0, 130_682.0);
        migrateKnownDefaultNumber("end-witch.phases.p3-health", 77_383.0, 125_655.0);
        migrateKnownDefaultNumber("end-witch.abilities.world-end.shield-health", 2_463.0, 4_000.0);
        migrateKnownDefaultNumber("end-witch.abilities.world-end.shield-player-scaling", 0.75, 0.65);
        migrateKnownDefaultNumber("end-witch.abilities.world-end.channel-reduction-seconds", 0.0, 1.0);
        migrateKnownDefaultNumber("end-witch.abilities.world-end.minimum-channel-seconds", 12.0, 1.0);
        setExplicitDefault("bone-dragon.damage-intake.player-hit-cooldown-ticks", 10);
        setExplicitDefault("end-witch.damage-intake.player-hit-cooldown-ticks", 10);
        setExplicitDefault("end-witch.damage-intake.preserve-effective-health-above-cap", true);
    }

    private void migrateAuraSkillsBalanceV59() {
        java.util.List<Double> oldStandardMultipliers = java.util.List.of(
                1.00, 1.50, 2.25, 3.00, 3.75, 4.50, 5.25, 6.00, 6.75, 7.50);
        java.util.List<Double> linearMultipliers = java.util.List.of(
                1.00, 2.00, 3.00, 4.00, 5.00, 6.00, 7.00, 8.00, 9.00, 10.00);
        migrateKnownDefaultNumberList("bone-dragon-arena.boss-health-multipliers-by-player-count",
                linearMultipliers, oldStandardMultipliers);
        migrateKnownDefaultNumberList("end-witch-arena.boss-health-multipliers-by-player-count",
                linearMultipliers, oldStandardMultipliers);
        migrateKnownDefaultNumber("bone-dragon-arena.boss-health-increase-per-extra-player-percent",
                100.0, 75.0);
        migrateKnownDefaultNumber("end-witch-arena.boss-health-increase-per-extra-player-percent",
                100.0, 75.0);

        migrateKnownDefaultNumber("bone-dragon.phases.p1-health", 21_400.0, 33_871.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p2-health", 19_500.0, 30_792.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p3-health", 17_500.0, 27_713.0);
        migrateKnownDefaultNumber("bone-dragon.attack-damage", 150.0, 180.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.tail-sweep.damage", 215.0, 260.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.grab.capture-damage", 100.0, 120.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.damage", 135.0, 160.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.guard-health", 360.0, 493.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.guard-damage", 90.0, 110.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.fire-breath.damage", 50.0, 55.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.summon-skeletons.health", 540.0, 739.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.summon-skeletons.damage", 90.0, 110.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.death-summon.ruler-health", 1_125.0, 1_540.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.meteor-rain.damage", 350.0, 420.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.bone-prison.damage", 135.0, 160.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.bone-spikes.damage", 250.0, 300.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.ground-charge.damage", 280.0, 340.0);

        migrateKnownDefaultNumber("endsun-guard.phases.p1-health", 38_100.0, 38_061.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p2-health", 33_800.0, 33_832.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p3-health", 29_600.0, 29_603.0);
        migrateKnownDefaultNumber("endsun-guard.attack-damage", 275.0, 400.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.scythe-return.outward-damage", 240.0, 350.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.scythe-return.return-damage", 485.0, 700.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.amethyst-judgement.damage", 620.0, 900.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.soul-crown.flame-damage", 110.0, 160.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.black-sun.collapse-damage", 345.0, 500.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sand-funeral.damage", 85.0, 120.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.scythe-echo.damage", 380.0, 550.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.nether-boundary.return-damage", 205.0, 300.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sunfall.impact-damage", 380.0, 550.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sunfall.radiation-damage", 70.0, 100.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.reflect-damage", 170.0, 250.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.movement-damage", 380.0, 550.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.petrify-damage", 415.0, 600.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.group-damage", 275.0, 400.0);

        migrateKnownDefaultNumber("end-witch.phases.p1-health", 83_300.0, 83_574.0);
        migrateKnownDefaultNumber("end-witch.phases.p2-health", 80_500.0, 80_479.0);
        migrateKnownDefaultNumber("end-witch.phases.p3-health", 77_400.0, 77_383.0);
        migrateKnownDefaultNumber("end-witch.attack-damage", 580.0, 1_000.0);
        migrateKnownDefaultNumber("end-witch.marks.third-stack-explosion-damage", 290.0, 500.0);
        migrateKnownDefaultNumber("end-witch.abilities.magic-arrows.damage", 405.0, 700.0);
        migrateKnownDefaultNumber("end-witch.abilities.black-skirt.damage", 380.0, 650.0);
        migrateKnownDefaultNumber("end-witch.abilities.fate-gaze.damage", 640.0, 1_100.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-blink.damage", 490.0, 850.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-blink.echo-damage", 435.0, 750.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-scissors.damage", 930.0, 1_600.0);
        migrateKnownDefaultNumber("end-witch.abilities.funeral-flowers.drain-damage", 130.0, 200.0);
        migrateKnownDefaultNumber("end-witch.abilities.time-funeral.damage", 700.0, 1_200.0);
        migrateKnownDefaultNumber("end-witch.abilities.doom-link.damage", 290.0, 500.0);
        migrateKnownDefaultNumber("end-witch.abilities.night-meteors.damage", 580.0, 1_000.0);
        migrateKnownDefaultNumber("end-witch.abilities.shadow-cage.explosion-damage", 700.0, 1_200.0);
        migrateKnownDefaultNumber("end-witch.abilities.heaven-inversion.damage", 985.0, 1_700.0);
        migrateKnownDefaultNumber("end-witch.abilities.seven-ends.damage", 700.0, 1_200.0);
    }

    private void setExplicitDefault(String path, Object value) {
        if (!getConfig().contains(path, true)) getConfig().set(path, value);
    }

    private void migrateCombatBalanceV49() {
        java.util.List<Double> healthMultipliers = java.util.List.of(
                1.00, 1.45, 1.90, 2.30, 2.70, 2.85, 2.98, 3.10, 3.20, 3.30);
        for (String arenaRoot : java.util.List.of(
                "bone-dragon-arena", "endsun-guard-arena", "end-witch-arena")) {
            String path = arenaRoot + ".boss-health-multipliers-by-player-count";
            if (!getConfig().isSet(path)) getConfig().set(path, healthMultipliers);
        }

        migrateKnownDefaultNumber("bone-dragon.attack-damage", 180.0, 32.0, 70.0);
        migrateKnownDefaultNumber("bone-dragon.armor", 18.0, 22.0, 50.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p1-health", 55_000.0, 409.6, 800.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p2-health", 50_000.0, 307.2, 500.0);
        migrateKnownDefaultNumber("bone-dragon.phases.p3-health", 45_000.0, 307.2, 400.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.tail-sweep.damage", 260.0, 28.0, 40.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.grab.capture-damage", 120.0, 10.0, 15.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.damage", 160.0, 12.0, 20.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.guard-health", 800.0, 36.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.skull-barrage.guard-damage", 110.0, 9.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.fire-breath.damage", 55.0, 8.0, 20.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.summon-skeletons.health", 1_200.0, 40.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.summon-skeletons.damage", 110.0, 10.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.death-summon.ruler-health", 2_500.0, 60.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.meteor-rain.damage", 420.0, 24.0, 80.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.bone-prison.damage", 160.0, 10.0, 20.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.bone-spikes.damage", 300.0, 22.0, 50.0);
        migrateKnownDefaultNumber("bone-dragon.abilities.ground-charge.damage", 340.0, 26.0, 50.0);

        migrateKnownDefaultNumber("endsun-guard.attack-damage", 400.0, 26.0, 100.0);
        migrateKnownDefaultNumber("endsun-guard.armor", 24.0, 22.0, 50.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p1-health", 90_000.0, 409.6, 1_000.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p2-health", 80_000.0, 307.2, 800.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p3-health", 70_000.0, 307.2, 600.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.scythe-return.outward-damage", 350.0, 18.0, 40.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.scythe-return.return-damage", 700.0, 36.0, 80.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.amethyst-judgement.damage", 900.0, 34.0, 68.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.soul-crown.flame-damage", 160.0, 8.0, 20.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.soul-crown.shield-heal-on-burning-hit", 200.0, 20.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.solar-shield.maximum-energy", 2_500.0, 200.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.heart-furnace.reflection-health", 1_500.0, 24.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.black-sun.collapse-damage", 500.0, 12.0, 30.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.black-sun.furnace-health", 3_000.0, 160.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sand-funeral.damage", 120.0, 8.0, 20.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.scythe-echo.damage", 550.0, 24.0, 50.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.nether-boundary.return-damage", 300.0, 12.0, 30.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sunfall.impact-damage", 550.0, 22.0, 50.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sunfall.radiation-damage", 100.0, 5.0, 10.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.sunfall.armor-break-damage", 2_500.0, 45.0, 90.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.reflect-damage", 250.0, 10.0, 20.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.movement-damage", 550.0, 16.0, 40.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.petrify-damage", 600.0, 18.0, 40.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.pharaoh-edict.group-damage", 400.0, 14.0, 30.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.twin-eclipse.body-health", 4_500.0, 180.0, 400.0);

        migrateKnownDefaultNumber("end-witch.attack-damage", 1_000.0, 18.0, 120.0);
        migrateKnownDefaultNumber("end-witch.armor", 30.0, 14.0, 30.0);
        migrateKnownDefaultNumber("end-witch.phases.p1-health", 135_000.0, 420.0, 1_200.0);
        migrateKnownDefaultNumber("end-witch.phases.p2-health", 130_000.0, 420.0, 1_000.0);
        migrateKnownDefaultNumber("end-witch.phases.p3-health", 125_000.0, 420.0, 850.0);
        migrateKnownDefaultNumber("end-witch.marks.third-stack-explosion-damage", 500.0, 10.0, 20.0);
        migrateKnownDefaultNumber("end-witch.abilities.magic-arrows.damage", 700.0, 16.0, 40.0);
        migrateKnownDefaultNumber("end-witch.abilities.black-skirt.damage", 650.0, 14.0, 30.0);
        migrateKnownDefaultNumber("end-witch.abilities.fate-gaze.damage", 1_100.0, 24.0, 50.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-blink.damage", 850.0, 18.0, 50.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-blink.echo-damage", 750.0, 20.0, 40.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-scissors.health", 3_500.0, 35.0);
        migrateKnownDefaultNumber("end-witch.abilities.witch-scissors.damage", 1_600.0, 34.0, 80.0);
        migrateKnownDefaultNumber("end-witch.abilities.silent-chant.rune-health", 1_800.0, 18.0);
        migrateKnownDefaultNumber("end-witch.abilities.funeral-flowers.health", 2_400.0, 24.0);
        migrateKnownDefaultNumber("end-witch.abilities.funeral-flowers.drain-damage", 200.0, 5.0, 10.0);
        migrateKnownDefaultNumber("end-witch.abilities.funeral-flowers.boss-heal", 800.0, 12.0, 30.0);
        migrateKnownDefaultNumber("end-witch.abilities.soul-pawn.crystal-health", 3_200.0, 32.0);
        migrateKnownDefaultNumber("end-witch.abilities.time-funeral.damage", 1_200.0, 28.0, 60.0);
        migrateKnownDefaultNumber("end-witch.abilities.doom-link.damage", 500.0, 10.0, 20.0);
        migrateKnownDefaultNumber("end-witch.abilities.night-meteors.damage", 1_000.0, 26.0, 50.0);
        migrateKnownDefaultNumber("end-witch.abilities.shadow-cage.pillar-health", 2_500.0, 20.0);
        migrateKnownDefaultNumber("end-witch.abilities.shadow-cage.explosion-damage", 1_200.0, 25.0, 50.0);
        migrateKnownDefaultNumber("end-witch.abilities.heaven-inversion.damage", 1_700.0, 35.0, 70.0);
        migrateKnownDefaultNumber("end-witch.abilities.seven-ends.damage", 1_200.0, 24.0, 50.0);
        migrateKnownDefaultNumber("end-witch.abilities.world-end.shield-health", 4_000.0, 240.0);
    }

    private void migrateCombatBalanceV50() {
        migrateKnownDefaultNumber("endsun-guard.phases.p1-health", 89_489.0, 90_000.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p2-health", 79_545.0, 80_000.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p3-health", 69_602.0, 70_000.0);
        migrateKnownDefaultNumber("end-witch.phases.p1-health", 135_708.0, 135_000.0);
        migrateKnownDefaultNumber("end-witch.phases.p2-health", 130_682.0, 130_000.0);
        migrateKnownDefaultNumber("end-witch.phases.p3-health", 125_655.0, 125_000.0);
    }

    private void migrateEndsunEmpiricalBalanceV51() {
        migrateKnownDefaultNumber("endsun-guard.phases.p1-health", 78_750.0, 89_489.0, 90_000.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p2-health", 70_000.0, 79_545.0, 80_000.0);
        migrateKnownDefaultNumber("endsun-guard.phases.p3-health", 61_250.0, 69_602.0, 70_000.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.black-sun.furnace-health", 2_000.0, 3_000.0);
        migrateKnownDefaultNumber("endsun-guard.abilities.twin-eclipse.body-health", 3_000.0, 4_500.0);
        if (!getConfig().isSet("endsun-guard.damage-intake.player-hit-cooldown-ticks")) {
            getConfig().set("endsun-guard.damage-intake.player-hit-cooldown-ticks", 10);
        }
    }

    private void migrateDefaultNumber(String path, double oldValue, double newValue) {
        if (Math.abs(getConfig().getDouble(path, oldValue) - oldValue) < 0.0001) {
            getConfig().set(path, newValue);
        }
    }

    private void migrateKnownDefaultNumber(String path, double newValue, double... oldValues) {
        Object configured = getConfig().get(path);
        if (!(configured instanceof Number number)) return;
        double current = number.doubleValue();
        for (double oldValue : oldValues) {
            if (Math.abs(current - oldValue) >= 0.0001) continue;
            getConfig().set(path, newValue);
            return;
        }
    }

    private void migrateKnownDefaultNumberList(String path, java.util.List<Double> newValues,
                                               java.util.List<Double> oldValues) {
        java.util.List<?> configured = getConfig().getList(path);
        if (configured == null || configured.size() != oldValues.size()) return;
        for (int index = 0; index < configured.size(); index++) {
            Object value = configured.get(index);
            if (!(value instanceof Number number)
                    || Math.abs(number.doubleValue() - oldValues.get(index)) >= 0.0001) return;
        }
        getConfig().set(path, newValues);
    }

    private void warnAboutClampedDragonHealth() {
        for (String phase : new String[] {"p1", "p2", "p3"}) {
            String path = "bone-dragon.phases." + phase + "-health";
            double dragonHealth = getConfig().getDouble(path, BossHealthLimits.MAXIMUM);
            if (dragonHealth > BossHealthLimits.MAXIMUM || dragonHealth < 1.0) {
                getLogger().warning(path + "=" + dragonHealth + " 超出服务器允许范围 1-"
                        + (long) BossHealthLimits.MAXIMUM + "，已自动钳制。");
            }
        }
    }

    public NamespacedKey bossTypeKey() {
        return bossTypeKey;
    }

    public NamespacedKey specialItemKey() {
        return specialItemKey;
    }

    public NamespacedKey boneDragonPhaseKey() {
        return boneDragonPhaseKey;
    }

    public NamespacedKey boneDragonAbsorbedKey() {
        return boneDragonAbsorbedKey;
    }

    public NamespacedKey boneDragonDeathSummonMaskKey() {
        return boneDragonDeathSummonMaskKey;
    }

    public NamespacedKey endsunPhaseKey() {
        return endsunPhaseKey;
    }

    public NamespacedKey endWitchPhaseKey() {
        return endWitchPhaseKey;
    }

    public NamespacedKey encounterHealthMultiplierKey() {
        return encounterHealthMultiplierKey;
    }

    public BoneDragonSettings boneDragonSettings() {
        return boneDragonSettings;
    }

    public EndsunGuardSettings endsunGuardSettings() {
        return endsunGuardSettings;
    }

    public EndWitchSettings endWitchSettings() {
        return endWitchSettings;
    }

    public BoneDragonManager boneDragonManager() {
        return boneDragonManager;
    }

    public EndsunGuardManager endsunGuardManager() {
        return endsunGuardManager;
    }

    public EndWitchManager endWitchManager() {
        return endWitchManager;
    }

    public BoneDragonArenaManager arenaManager() {
        return arenaManager;
    }

    public EndsunGuardArenaManager endsunArenaManager() {
        return endsunArenaManager;
    }

    public EndWitchArenaManager endWitchArenaManager() {
        return endWitchArenaManager;
    }

    public boolean isArenaWorld(org.bukkit.World world) {
        return arenaManager != null && arenaManager.isArenaWorld(world)
                || endsunArenaManager != null && endsunArenaManager.isArenaWorld(world)
                || endWitchArenaManager != null && endWitchArenaManager.isArenaWorld(world);
    }

    public PartyManager partyManager() {
        return partyManager;
    }

    public MessageService messages() {
        return messages;
    }

    public RaidSidebarService raidSidebar() {
        return raidSidebar;
    }

    public SpecialItemManager specialItemManager() {
        return specialItemManager;
    }

    public BetterModelSupport betterModel() {
        return betterModel;
    }

    public SlayerService slayer() {
        return slayerService;
    }

    @Override
    public @Nullable ChunkGenerator getDefaultWorldGenerator(String worldName, String id) {
        if (arenaSettings == null || endsunArenaSettings == null || endWitchArenaSettings == null) return null;
        if ("end_witch_arena".equalsIgnoreCase(id)
                || (id == null || id.isBlank()) && worldName.startsWith(endWitchArenaSettings.worldName())) {
            return new cn.yknboss.arena.EndWitchArenaGenerator(
                    endWitchArenaSettings.radius(), endWitchArenaSettings.floorY());
        }
        if ("endsun_guard_arena".equalsIgnoreCase(id)
                || (id == null || id.isBlank()) && worldName.startsWith(endsunArenaSettings.worldName())) {
            return new cn.yknboss.arena.EndsunGuardArenaGenerator(
                    endsunArenaSettings.radius(), endsunArenaSettings.floorY());
        }
        if (id != null && !id.isBlank()
                && !"bone_dragon_arena".equalsIgnoreCase(id)
                && !arenaSettings.worldName().equalsIgnoreCase(worldName)) return null;
        return new cn.yknboss.arena.BoneDragonArenaGenerator(arenaSettings.radius(), arenaSettings.floorY());
    }
}
