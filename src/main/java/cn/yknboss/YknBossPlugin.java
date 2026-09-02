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
            if (!getConfig().isSet("party.max-members")) getConfig().set("party.max-members", 10);
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
        if (version >= 48) return false;
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
        getConfig().set("config-version", 48);
        getLogger().info("Boss 配置已迁移至 v48（移除物品栏上方的阿努赫日蚀刻度提示）。");
        return true;
    }

    private void migrateDefaultNumber(String path, double oldValue, double newValue) {
        if (Math.abs(getConfig().getDouble(path, oldValue) - oldValue) < 0.0001) {
            getConfig().set(path, newValue);
        }
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
