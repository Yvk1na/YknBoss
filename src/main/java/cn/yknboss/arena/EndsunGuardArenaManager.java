package cn.yknboss.arena;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.boss.EndsunGuard;
import cn.yknboss.party.PartyManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Difficulty;
import org.bukkit.GameRule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.WorldType;
import org.bukkit.block.Block;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import org.mvplugins.multiverse.core.MultiverseCoreApi;
import org.mvplugins.multiverse.core.world.LoadedMultiverseWorld;
import org.mvplugins.multiverse.core.world.WorldManager;
import org.mvplugins.multiverse.core.world.options.CreateWorldOptions;
import org.mvplugins.multiverse.core.world.options.DeleteWorldOptions;
import org.mvplugins.multiverse.core.world.options.ImportWorldOptions;
import org.mvplugins.multiverse.core.world.options.LoadWorldOptions;
import org.mvplugins.multiverse.core.world.options.RegenWorldOptions;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class EndsunGuardArenaManager {
    private static final String GENERATOR_ID = "endsun_guard_arena";
    private static final int GENERATION_VERSION = 1;

    private final YknBossPlugin plugin;
    private final NamespacedKey generationKey;
    private final List<ArenaInstance> instances = new ArrayList<>();
    private final Map<UUID, ArenaInstance> instanceByPlayer = new HashMap<>();
    private final Map<UUID, ArenaInstance> spectatorByPlayer = new HashMap<>();
    private final Set<UUID> spectatorGameModeBypass = new HashSet<>();
    private final Map<UUID, ReturnPoint> pendingOfflineReturns = new HashMap<>();
    private final Map<UUID, GameMode> pendingReturnGameModes = new HashMap<>();
    private EndsunGuardArenaSettings settings;

    public EndsunGuardArenaManager(YknBossPlugin plugin, EndsunGuardArenaSettings settings) {
        this.plugin = plugin;
        this.generationKey = new NamespacedKey(plugin, "endsun_arena_generation");
        this.settings = settings;
        rebuildInstanceList();
    }

    public void reload(EndsunGuardArenaSettings settings) {
        boolean active = instances.stream().anyMatch(ArenaInstance::occupied);
        this.settings = settings;
        boolean namesChanged = instances.size() != settings.instanceCount();
        if (!namesChanged) {
            for (int index = 0; index < instances.size(); index++) {
                if (!instances.get(index).worldName.equals(instanceWorldName(index + 1))) {
                    namesChanged = true;
                    break;
                }
            }
        }
        if (!active && namesChanged) rebuildInstanceList();
        for (ArenaInstance instance : instances) {
            World world = Bukkit.getWorld(instance.worldName);
            if (world != null) configureWorld(world);
        }
    }

    private void rebuildInstanceList() {
        instances.clear();
        for (int slot = 1; slot <= settings.instanceCount(); slot++) {
            instances.add(new ArenaInstance(slot, instanceWorldName(slot)));
        }
    }

    private String instanceWorldName(int slot) {
        return ArenaWorldStorage.instanceWorldName(settings.worldName(), slot);
    }

    private String legacyInstanceWorldName(int slot) {
        return ArenaWorldStorage.legacyInstanceWorldName(settings.worldName(), slot);
    }

    public void createAll() {
        for (ArenaInstance instance : instances) createOrLoad(instance);
    }

    public void migrateLegacyWorlds() {
        if (!settings.enabled()) return;
        WorldManager manager = multiverseWorldManager();
        for (ArenaInstance instance : instances) {
            ArenaWorldStorage.prepareInstance(manager, plugin.getLogger(), instance.worldName,
                    legacyInstanceWorldName(instance.slot), "烬日冥卫");
        }
    }

    public World createOrLoad() {
        return createOrLoad(instances.getFirst());
    }

    private World createOrLoad(ArenaInstance instance) {
        if (!settings.enabled()) throw new IllegalStateException("烬日冥卫竞技场已在配置中禁用");
        WorldManager manager = multiverseWorldManager();
        ArenaWorldStorage.prepareInstance(manager, plugin.getLogger(), instance.worldName,
                legacyInstanceWorldName(instance.slot), "烬日冥卫");
        LoadedMultiverseWorld multiverseWorld = manager.getLoadedWorld(instance.worldName).getOrNull();
        boolean createdNow = false;

        if (multiverseWorld == null && manager.isWorld(instance.worldName)) {
            var knownWorld = manager.getWorld(instance.worldName).getOrNull();
            if (knownWorld == null) throw new IllegalStateException("Multiverse 找不到已登记的烬日冥卫副本 " + instance.worldName);
            var load = manager.loadWorld(LoadWorldOptions.world(knownWorld));
            if (load.isFailure()) {
                throw new IllegalStateException("Multiverse 无法加载烬日冥卫副本 " + instance.worldName + "：" + load.getFailureReason());
            }
            multiverseWorld = load.getOrNull();
        }

        if (multiverseWorld == null) {
            File worldFolder = new File(Bukkit.getWorldContainer(), instance.worldName);
            if (Bukkit.getWorld(instance.worldName) != null || worldFolder.isDirectory()) {
                World bukkitWorld = Bukkit.getWorld(instance.worldName);
                World.Environment environment = bukkitWorld == null ? World.Environment.NORMAL : bukkitWorld.getEnvironment();
                var imported = manager.importWorld(ImportWorldOptions.worldName(instance.worldName)
                        .environment(environment)
                        .generator(generatorName())
                        .useSpawnAdjust(false)
                        .doFolderCheck(false));
                if (imported.isFailure()) {
                    throw new IllegalStateException("Multiverse 无法接管烬日冥卫副本 " + instance.worldName + "：" + imported.getFailureReason());
                }
                multiverseWorld = imported.getOrNull();
            } else {
                multiverseWorld = createManagedWorld(manager, instance.worldName);
                createdNow = true;
                plugin.getLogger().info("已创建烬日冥卫副本 " + instance.worldName + "，场地半径 " + settings.radius());
            }
        }

        if (multiverseWorld == null) throw new IllegalStateException("Multiverse 未返回烬日冥卫副本 " + instance.worldName);
        World world = multiverseWorld.getBukkitWorld().getOrNull();
        if (world == null) throw new IllegalStateException("烬日冥卫副本尚未加载：" + instance.worldName);
        if (world.getEnvironment() == World.Environment.THE_END) {
            if (!world.getPlayers().isEmpty()) throw new IllegalStateException("旧末地副本中仍有玩家，无法自动重建");
            world = recreateAsNormalVoid(manager, world, instance.worldName);
            createdNow = true;
        }

        configureWorld(world);
        world.getChunkAt(0, 0).load(true);
        if (createdNow) {
            markCurrentGeneration(world);
        } else {
            Integer generation = world.getPersistentDataContainer().get(generationKey, PersistentDataType.INTEGER);
            if (generation == null || generation != GENERATION_VERSION) {
                if (!world.getPlayers().isEmpty()) throw new IllegalStateException("旧版烬日冥卫副本中仍有玩家，无法升级");
                world = regenerate(instance, world);
            }
        }
        return world;
    }

    private LoadedMultiverseWorld createManagedWorld(WorldManager manager, String worldName) {
        var created = manager.createWorld(CreateWorldOptions.worldName(worldName)
                .environment(World.Environment.NORMAL)
                .worldType(WorldType.NORMAL)
                .seed(settings.seed())
                .generateStructures(false)
                .generator(generatorName())
                .useSpawnAdjust(false));
        if (created.isFailure()) {
            throw new IllegalStateException("Multiverse 无法创建烬日冥卫副本 " + worldName + "：" + created.getFailureReason());
        }
        LoadedMultiverseWorld world = created.getOrNull();
        if (world == null) throw new IllegalStateException("Multiverse 创建副本后未返回结果：" + worldName);
        return world;
    }

    private World recreateAsNormalVoid(WorldManager manager, World oldWorld, String worldName) {
        LoadedMultiverseWorld loaded = manager.getLoadedWorld(oldWorld).getOrNull();
        if (loaded == null) throw new IllegalStateException("旧烬日冥卫副本未被 Multiverse-Core 管理");
        var deleted = manager.deleteWorld(DeleteWorldOptions.world(loaded));
        if (deleted.isFailure()) throw new IllegalStateException("删除旧末地副本失败：" + deleted.getFailureReason());
        LoadedMultiverseWorld recreated = createManagedWorld(manager, worldName);
        World world = recreated.getBukkitWorld().getOrNull();
        if (world == null) throw new IllegalStateException("重建普通虚空副本后未加载它");
        return world;
    }

    public void enter(Player requester) {
        PartyManager.PartyView party = plugin.partyManager().partyOf(requester.getUniqueId()).orElse(null);
        UUID sessionKey = party == null ? requester.getUniqueId() : party.id();
        ArenaInstance existing = findBySessionKey(sessionKey);
        if (existing != null) {
            if (!existing.participants.contains(requester.getUniqueId())) {
                throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.not-session-party"));
            }
            enterPlayer(existing, requester);
            return;
        }
        if (instanceByPlayer.containsKey(requester.getUniqueId())) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.already-in-instance"));
        }
        if (plugin.arenaManager().isPlayerInSession(requester.getUniqueId())
                || plugin.endWitchArenaManager().isPlayerInSession(requester.getUniqueId())) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.already-in-instance"));
        }
        if (party != null && !party.isLeader(requester.getUniqueId())) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.create-leader-only"));
        }
        List<UUID> members = party == null ? List.of(requester.getUniqueId()) : party.members();
        int maxMembers = plugin.partyManager().maxMembers();
        if (members.size() > maxMembers) {
            throw new IllegalStateException(plugin.messages().plain(
                    "endsun-arena.error.party-too-large", "max", maxMembers));
        }
        if (members.stream().anyMatch(member -> plugin.arenaManager().isPlayerInSession(member)
                || plugin.endWitchArenaManager().isPlayerInSession(member))) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.party-member-in-other-instance"));
        }

        ArenaInstance instance = instances.stream().filter(value -> !value.occupied()).findFirst()
                .orElseThrow(() -> new IllegalStateException(plugin.messages().plain("endsun-arena.error.all-busy",
                        "count", instances.size())));
        World world = prepareInstanceForSession(instance, requester);
        instance.sessionKey = sessionKey;
        instance.participants.addAll(members);
        for (UUID member : members) instanceByPlayer.put(member, instance);

        int entered = 0;
        for (UUID member : members) {
            Player online = Bukkit.getPlayer(member);
            if (online != null) {
                enterPlayer(instance, online, world);
                entered++;
            }
        }
        broadcast(instance, plugin.messages().component("endsun-arena.entered",
                "slot", instance.slot, "instances", instances.size(), "online", entered, "members", members.size()));
    }

    private World prepareInstanceForSession(ArenaInstance instance, Player requester) {
        requester.sendMessage(plugin.messages().component("endsun-arena.preparing",
                "slot", instance.slot, "instances", instances.size()));
        World world = createOrLoad(instance);
        if (!world.getPlayers().isEmpty()) {
            throw new IllegalStateException("烬日冥卫副本 " + instance.worldName + " 中仍有玩家，无法在开场前重置");
        }
        instance.resetting = true;
        try {
            EndsunGuard staleBoss = arenaBoss(instance);
            if (staleBoss != null) staleBoss.remove();
            instance.activeBossId = null;
            World regenerated = regenerate(instance, world);
            plugin.getLogger().info("烬日冥卫副本 " + instance.worldName + " 已在挑战开始前恢复到初始状态");
            return regenerated;
        } finally {
            instance.resetting = false;
        }
    }

    public void spectate(Player player, String targetPlayerName) {
        UUID playerId = player.getUniqueId();
        if (instanceByPlayer.containsKey(playerId)) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.spectate-participant"));
        }
        if (spectatorByPlayer.containsKey(playerId)) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.already-spectating"));
        }
        Player targetPlayer = resolveSpectatePlayer(targetPlayerName);
        ArenaInstance instance = targetPlayer == null ? null : instanceByPlayer.get(targetPlayer.getUniqueId());
        if (instance == null || instance.sessionKey == null || instance.resetting) {
            throw new IllegalStateException(plugin.messages().plain(
                    "endsun-arena.error.spectate-not-found", "player", targetPlayerName));
        }

        World world = createOrLoad(instance);
        ReturnPoint returnPoint = ReturnPoint.of(player.getLocation());
        GameMode previousMode = player.getGameMode();
        instance.spectators.add(playerId);
        instance.spectatorReturnPoints.put(playerId, returnPoint);
        instance.spectatorGameModes.put(playerId, previousMode);
        spectatorByPlayer.put(playerId, instance);
        player.setGameMode(GameMode.SPECTATOR);
        Location destination = new Location(world, 0.5, settings.floorY() + 16.0, 0.5, 0.0f, 45.0f);
        if (!player.teleport(destination)) {
            detachSpectator(playerId);
            player.setGameMode(previousMode);
            throw new IllegalStateException(plugin.messages().plain(
                    "endsun-arena.error.spectate-not-found", "player", targetPlayerName));
        }
        player.sendMessage(plugin.messages().component(
                "endsun-arena.spectate.entered", "player", targetPlayer.getName()));
    }

    public void leaveSpectate(Player player) {
        SpectatorExit exit = detachSpectator(player.getUniqueId());
        if (exit == null) throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.not-spectating"));
        restoreSpectator(player, exit, true);
    }

    public boolean isSpectating(Player player) {
        return spectatorByPlayer.containsKey(player.getUniqueId());
    }

    public boolean canBypassSpectatorGameModeLock(Player player) {
        return player.hasPermission("yknboss.admin")
                && spectatorGameModeBypass.contains(player.getUniqueId());
    }

    public boolean toggleSpectatorGameModeBypass(Player player) {
        UUID playerId = player.getUniqueId();
        if (spectatorGameModeBypass.remove(playerId)) {
            if (isSpectating(player) && player.getGameMode() != GameMode.SPECTATOR) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            return false;
        }
        spectatorGameModeBypass.add(playerId);
        return true;
    }

    public List<String> activeParticipantNames() {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (ArenaInstance instance : instances) {
            if (instance.sessionKey == null || instance.resetting) continue;
            for (UUID participantId : instance.participants) {
                Player participant = Bukkit.getPlayer(participantId);
                if (participant != null) result.add(participant.getName());
            }
        }
        return result.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private Player resolveSpectatePlayer(String playerName) {
        Player exact = Bukkit.getPlayerExact(playerName);
        if (exact != null) return exact;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getName().equalsIgnoreCase(playerName)) return online;
        }
        return null;
    }

    private void enterPlayer(ArenaInstance instance, Player player) {
        enterPlayer(instance, player, createOrLoad(instance));
    }

    private void enterPlayer(ArenaInstance instance, Player player, World world) {
        if (!isArenaWorld(player.getWorld())) {
            instance.returnPoints.putIfAbsent(player.getUniqueId(), ReturnPoint.of(player.getLocation()));
        }
        instanceByPlayer.put(player.getUniqueId(), instance);
        if (player.teleport(playerSpawn(world))) syncRaidSidebar(player);
    }

    public boolean allowTeleport(Player player, Location from, Location to) {
        if (to.getWorld() == null) return true;
        ArenaInstance destination = instanceForWorld(to.getWorld());
        ArenaInstance spectating = spectatorByPlayer.get(player.getUniqueId());
        if (destination == null) {
            if (spectating != null && instanceForWorld(from.getWorld()) == spectating) {
                SpectatorExit exit = detachSpectator(player.getUniqueId());
                if (exit != null) {
                    player.setGameMode(exit.gameMode);
                    player.sendMessage(plugin.messages().component("endsun-arena.spectate.left"));
                }
            }
            return true;
        }
        ArenaInstance assigned = instanceByPlayer.get(player.getUniqueId());
        if (assigned != destination && spectating != destination) {
            player.sendMessage(plugin.messages().component("endsun-arena.teleport-denied"));
            return false;
        }
        if (from.getWorld() == null || instanceForWorld(from.getWorld()) != destination) {
            destination.returnPoints.putIfAbsent(player.getUniqueId(), ReturnPoint.of(from));
        }
        return true;
    }

    public void handleJoin(Player player) {
        ReturnPoint pending = pendingOfflineReturns.remove(player.getUniqueId());
        if (pending != null) {
            GameMode gameMode = pendingReturnGameModes.remove(player.getUniqueId());
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (gameMode != null) player.setGameMode(gameMode);
                returnPlayer(player, pending);
            });
            return;
        }
        ArenaInstance currentWorld = instanceForWorld(player.getWorld());
        if (currentWorld != null && instanceByPlayer.get(player.getUniqueId()) != currentWorld
                && spectatorByPlayer.get(player.getUniqueId()) != currentWorld) {
            Bukkit.getScheduler().runTask(plugin, () -> player.teleport(fallbackReturnLocation()));
        }
    }

    public void handleQuit(Player player) {
        spectatorGameModeBypass.remove(player.getUniqueId());
        SpectatorExit exit = detachSpectator(player.getUniqueId());
        if (exit != null) restoreSpectator(player, exit, false);
    }

    public boolean handlePlayerDeath(Player player) {
        ArenaInstance instance = instanceByPlayer.get(player.getUniqueId());
        if (instance == null || instanceForWorld(player.getWorld()) != instance || instance.resetting) return false;
        UUID playerId = player.getUniqueId();
        int respawnDelayTicks = plugin.slayer().respawnDelayTicks(player, settings.respawnDelayTicks());
        instance.deadPlayers.add(playerId);
        plugin.raidSidebar().setDown(instance.sessionKey, playerId, true);
        instance.previousGameModes.putIfAbsent(playerId, player.getGameMode());
        instance.respawnReadyAt.put(playerId,
                System.currentTimeMillis() + respawnDelayTicks * 50L);
        BukkitTask previous = instance.respawnTasks.remove(playerId);
        if (previous != null) previous.cancel();
        int seconds = respawnDelayTicks / 20;
        player.sendMessage(plugin.messages().component("endsun-arena.respawn.wait", "seconds", seconds));
        broadcast(instance, plugin.messages().component("endsun-arena.player-down",
                "player", player.getName(), "seconds", seconds));
        instance.respawnTasks.put(playerId, Bukkit.getScheduler().runTaskTimer(plugin,
                () -> tickRespawn(instance, playerId), 20L, 20L));
        Bukkit.getScheduler().runTask(plugin, () -> evaluatePartyWipe(instance));
        return true;
    }

    public void handlePlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        ArenaInstance instance = instanceByPlayer.get(player.getUniqueId());
        if (instance == null) return;
        if (instance.resetting) {
            ReturnPoint point = instance.returnPoints.get(player.getUniqueId());
            Location returnLocation = point == null ? fallbackReturnLocation() : point.resolve();
            if (returnLocation == null || isArenaWorld(returnLocation.getWorld())) returnLocation = fallbackReturnLocation();
            event.setRespawnLocation(returnLocation);
            return;
        }
        World world = Bukkit.getWorld(instance.worldName);
        event.setRespawnLocation(world == null ? fallbackReturnLocation() : playerSpawn(world));
        if (!instance.deadPlayers.contains(player.getUniqueId())) return;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (instance.deadPlayers.contains(player.getUniqueId()) && player.isOnline()) {
                player.setGameMode(GameMode.SPECTATOR);
            }
        });
    }

    private void tickRespawn(ArenaInstance instance, UUID playerId) {
        if (instance.resetting || !instance.deadPlayers.contains(playerId)) {
            cancelRespawnTask(instance, playerId);
            return;
        }
        long readyAt = instance.respawnReadyAt.getOrDefault(playerId, 0L);
        long remainingMillis = readyAt - System.currentTimeMillis();
        if (remainingMillis <= 0L) {
            revivePlayer(instance, playerId);
            return;
        }
        Player player = Bukkit.getPlayer(playerId);
        if (player != null && !player.isDead()) {
            player.setGameMode(GameMode.SPECTATOR);
            long seconds = Math.max(1L, (remainingMillis + 999L) / 1000L);
            player.sendActionBar(plugin.messages().component("endsun-arena.respawn.actionbar", "seconds", seconds));
        }
    }

    private void revivePlayer(ArenaInstance instance, UUID playerId) {
        cancelRespawnTask(instance, playerId);
        instance.deadPlayers.remove(playerId);
        plugin.raidSidebar().setDown(instance.sessionKey, playerId, false);
        instance.respawnReadyAt.remove(playerId);
        GameMode gameMode = instance.previousGameModes.remove(playerId);
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        if (player.isDead()) player.spigot().respawn();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (instance.resetting || instanceByPlayer.get(playerId) != instance || !player.isOnline()) return;
            player.setGameMode(gameMode == null ? GameMode.SURVIVAL : gameMode);
            World world = Bukkit.getWorld(instance.worldName);
            if (world != null) player.teleport(playerSpawn(world));
            player.sendMessage(plugin.messages().component("endsun-arena.respawn.complete"));
        });
    }

    private void evaluatePartyWipe(ArenaInstance instance) {
        if (instance.resetting || instance.returnTask != null || instance.participants.isEmpty()) return;
        boolean foundCombatant = false;
        boolean foundLivingCombatant = false;
        for (UUID participant : instance.participants) {
            Player player = Bukkit.getPlayer(participant);
            if (player == null || instanceForWorld(player.getWorld()) != instance) continue;
            foundCombatant = true;
            if (!instance.deadPlayers.contains(participant) && !player.isDead()) {
                foundLivingCombatant = true;
                break;
            }
        }
        if (foundCombatant && !foundLivingCombatant) {
            failInstance(instance, plugin.messages().component("endsun-arena.challenge-failed.wipe"));
        }
    }

    public void giveUp(Player player) {
        ArenaInstance instance = instanceByPlayer.get(player.getUniqueId());
        if (instance == null || instanceForWorld(player.getWorld()) != instance) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.not-in-instance"));
        }
        PartyManager.PartyView party = plugin.partyManager().partyOf(player.getUniqueId()).orElse(null);
        if (party != null && !party.isLeader(player.getUniqueId())) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.giveup-leader-only"));
        }
        if (instance.returnTask != null) {
            throw new IllegalStateException(plugin.messages().plain("endsun-arena.error.already-completed"));
        }
        failInstance(instance, plugin.messages().component("endsun-arena.challenge-failed.giveup",
                "player", player.getName()));
    }

    private void failInstance(ArenaInstance instance, Component reason) {
        if (instance.resetting) return;
        broadcast(instance, reason);
        cancelChallengeTimer(instance);
        if (instance.summonTask != null) instance.summonTask.cancel();
        if (instance.returnTask != null) instance.returnTask.cancel();
        instance.summonTask = null;
        instance.returnTask = null;
        instance.summoning = false;
        EndsunGuard boss = arenaBoss(instance);
        plugin.raidSidebar().finish(instance.sessionKey, false,
                boss == null ? Map.of() : boss.damageContributions());
        if (boss != null) boss.remove();
        instance.activeBossId = null;
        finishSession(instance);
    }

    public boolean handleMonumentInteract(Player player, Block block) {
        if (!isMonumentBlock(block)) return false;
        ArenaInstance instance = instanceForWorld(block.getWorld());
        if (instance == null) return true;
        if (instanceByPlayer.get(player.getUniqueId()) != instance) {
            player.sendMessage(plugin.messages().component("endsun-arena.not-member"));
            return true;
        }
        if (instance.resetting || instance.returnTask != null) {
            player.sendMessage(plugin.messages().component("endsun-arena.resetting"));
            return true;
        }
        if (instance.summoning) {
            player.sendMessage(plugin.messages().component("endsun-arena.summoning"));
            return true;
        }
        EndsunGuard existing = arenaBoss(instance);
        if (existing != null) {
            instance.activeBossId = existing.id();
            player.sendMessage(plugin.messages().component("endsun-arena.boss-already-active"));
            return true;
        }
        startSummoning(instance, player, block.getWorld());
        return true;
    }

    private void startSummoning(ArenaInstance instance, Player summoner, World world) {
        instance.summoning = true;
        Location focus = monumentFocus(world);
        BukkitRunnable ritual = new BukkitRunnable() {
            private int elapsed;

            @Override
            public void run() {
                if (!instance.summoning || Bukkit.getWorld(instance.worldName) != world) {
                    finish();
                    return;
                }
                double progress = Math.min(1.0, elapsed / (double) settings.summonDurationTicks());
                double radius = 4.5 - progress * 3.2;
                for (int index = 0; index < 20; index++) {
                    double angle = Math.PI * 2.0 * index / 20.0 + elapsed * 0.12;
                    Location particle = focus.clone().add(Math.cos(angle) * radius,
                            progress * 8.0 + Math.sin(angle * 2.0) * 0.35, Math.sin(angle) * radius);
                    world.spawnParticle(index % 3 == 0 ? Particle.SOUL_FIRE_FLAME : Particle.FLAME,
                            particle, 1, 0, 0, 0, 0);
                }
                world.spawnParticle(Particle.FLAME, focus.clone().add(0, progress * 8.0, 0),
                        8, 1.2, 0.8, 1.2, 0.03);
                if (elapsed % 20 == 0) {
                    world.playSound(focus, Sound.BLOCK_BEACON_POWER_SELECT, 2.5f,
                            0.55f + (float) progress * 0.65f);
                }
                if (elapsed < settings.summonDurationTicks()) {
                    elapsed += 2;
                    return;
                }
                try {
                    spawnArenaBoss(world);
                    world.spawnParticle(Particle.EXPLOSION, focus, 8, 2.0, 4.0, 2.0, 0.05);
                    world.spawnParticle(Particle.FLAME, focus, 180, 3.5, 5.0, 3.5, 0.11);
                    world.playSound(focus, Sound.ENTITY_BLAZE_AMBIENT, 4.0f, 0.55f);
                    for (Player player : world.getPlayers()) {
                        player.showTitle(Title.title(
                                plugin.messages().component("endsun-arena.summon-title.title"),
                                plugin.messages().component("endsun-arena.summon-title.subtitle",
                                        "seconds", settings.returnDelayTicks() / 20),
                                Title.Times.times(Duration.ofMillis(250), Duration.ofSeconds(2), Duration.ofMillis(500))));
                    }
                    finish();
                } catch (RuntimeException | LinkageError exception) {
                    plugin.getLogger().severe("烬日祭坛召唤失败：" + exception.getMessage());
                    summoner.sendMessage(plugin.messages().component("endsun-arena.summon-failed",
                            "message", exception.getMessage()));
                    finish();
                }
            }

            private void finish() {
                instance.summoning = false;
                instance.summonTask = null;
                cancel();
            }
        };
        instance.summonTask = ritual.runTaskTimer(plugin, 0L, 2L);
    }

    public void registerArenaBoss(EndsunGuard boss) {
        ArenaInstance instance = instanceForWorld(boss.location().getWorld());
        if (instance == null) return;
        if (instance.challengeDurationTicks <= 0) prepareChallengeScaling(instance, boss.location().getWorld());
        instance.activeBossId = boss.id();
        removeMonument(boss.location().getWorld());
        startChallengeTimer(instance);
    }

    public EndsunGuard spawnArenaBoss(World world) {
        ArenaInstance instance = instanceForWorld(world);
        if (instance == null) throw new IllegalArgumentException("只能在烬日冥卫竞技场中召唤副本烬日冥卫·阿努赫");
        if (arenaBoss(instance) != null) throw new IllegalStateException("该副本中已经存在烬日冥卫·阿努赫");
        removeMonument(world);
        try {
            prepareChallengeScaling(instance, world);
            EndsunGuard boss = plugin.endsunGuardManager().spawn(
                    bossSpawn(world), instance.bossHealthMultiplier);
            instance.activeBossId = boss.id();
            startChallengeTimer(instance);
            return boss;
        } catch (RuntimeException | LinkageError exception) {
            restoreMonument(world);
            throw exception;
        }
    }

    public void replaceActiveBoss(UUID previousBossId, UUID replacementBossId, World world) {
        ArenaInstance instance = instanceForWorld(world);
        if (instance == null || previousBossId == null || replacementBossId == null
                || !previousBossId.equals(instance.activeBossId)) return;
        instance.activeBossId = replacementBossId;
    }

    public void onBossDefeated(EndsunGuard boss) {
        ArenaInstance instance = instanceForWorld(boss.location().getWorld());
        if (instance == null) return;
        if (instance.activeBossId != null && !instance.activeBossId.equals(boss.id())) return;
        if (instance.returnTask != null || instance.resetting) return;
        cancelChallengeTimer(instance);
        if (!instance.xpAwarded) {
            instance.xpAwarded = true;
            plugin.slayer().awardVictory("endsun_guard", Set.copyOf(instance.eligibleParticipants),
                    boss.damageContributions());
            markClearTime(instance);
            plugin.raidLoot().spawnChest("endsun_guard", instance.sessionKey,
                    monumentFocus(boss.location().getWorld()), instance.clearTicks,
                    Set.copyOf(instance.eligibleParticipants), boss.damageContributions());
        }
        plugin.raidSidebar().finish(instance.sessionKey, true, boss.damageContributions());
        instance.activeBossId = null;
        int totalSeconds = settings.returnDelayTicks() / 20;
        broadcast(instance, plugin.messages().component("endsun-arena.victory", "seconds", totalSeconds));
        final int[] remaining = {settings.returnDelayTicks()};
        instance.returnTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            remaining[0] -= 20;
            if (remaining[0] <= 0) {
                BukkitTask task = instance.returnTask;
                instance.returnTask = null;
                if (task != null) task.cancel();
                finishSession(instance);
                return;
            }
            int seconds = remaining[0] / 20;
            if (seconds == 20 || seconds == 10 || seconds <= 5) {
                broadcast(instance, plugin.messages().component("endsun-arena.return-countdown", "seconds", seconds));
            }
        }, 20L, 20L);
    }

    private void finishSession(ArenaInstance instance) {
        if (instance.resetting) return;
        cancelChallengeTimer(instance);
        instance.resetting = true;
        prepareDeadPlayersForExit(instance);
        Bukkit.getScheduler().runTaskLater(plugin, () -> completeSessionClose(instance), 2L);
    }

    private void prepareDeadPlayersForExit(ArenaInstance instance) {
        for (BukkitTask task : instance.respawnTasks.values()) task.cancel();
        instance.respawnTasks.clear();
        for (UUID playerId : List.copyOf(instance.deadPlayers)) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isDead()) player.spigot().respawn();
        }
        instance.deadPlayers.clear();
        instance.respawnReadyAt.clear();
    }

    private void completeSessionClose(ArenaInstance instance) {
        World arena = Bukkit.getWorld(instance.worldName);

        boolean waitingForRespawn = false;
        for (UUID playerId : instance.participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isDead()) {
                player.spigot().respawn();
                waitingForRespawn = true;
            }
        }
        if (waitingForRespawn) {
            Bukkit.getScheduler().runTaskLater(plugin, () -> completeSessionClose(instance), 2L);
            return;
        }

        UUID completedSessionId = instance.sessionKey;
        for (UUID playerId : List.copyOf(instance.participants)) {
            instanceByPlayer.remove(playerId, instance);
            ReturnPoint point = instance.returnPoints.remove(playerId);
            Player player = Bukkit.getPlayer(playerId);
            if (player != null) {
                GameMode previous = instance.previousGameModes.remove(playerId);
                if (previous != null) player.setGameMode(previous);
                returnPlayer(player, point);
            } else if (point != null) {
                pendingOfflineReturns.put(playerId, point);
            }
        }
        plugin.raidSidebar().close(completedSessionId);
        plugin.raidLoot().close(completedSessionId);
        instance.participants.clear();
        instance.sessionKey = null;

        for (UUID spectatorId : List.copyOf(instance.spectators)) {
            SpectatorExit exit = detachSpectator(spectatorId);
            if (exit == null) continue;
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (spectator != null) {
                restoreSpectator(spectator, exit, true);
            } else if (exit.returnPoint != null) {
                pendingOfflineReturns.put(spectatorId, exit.returnPoint);
                pendingReturnGameModes.put(spectatorId, exit.gameMode);
            }
        }

        if (arena == null) {
            resetState(instance);
            return;
        }
        for (Player player : List.copyOf(arena.getPlayers())) player.teleport(fallbackReturnLocation());
        if (!arena.getPlayers().isEmpty()) {
            plugin.getLogger().warning(instance.worldName + " 仍有玩家，20 tick 后重试结束副本");
            Bukkit.getScheduler().runTaskLater(plugin, () -> completeSessionClose(instance), 20L);
            return;
        }
        plugin.getLogger().info("烬日冥卫副本 " + instance.worldName + " 已结束；场地将在下次挑战开始前重置");
        resetState(instance);
    }

    private void returnPlayer(Player player, ReturnPoint point) {
        Location destination = point == null ? fallbackReturnLocation() : point.resolve();
        if (destination == null || isArenaWorld(destination.getWorld())) destination = fallbackReturnLocation();
        player.teleport(destination);
        player.sendMessage(plugin.messages().component("endsun-arena.returned"));
    }

    private SpectatorExit detachSpectator(UUID playerId) {
        ArenaInstance instance = spectatorByPlayer.remove(playerId);
        if (instance == null) return null;
        instance.spectators.remove(playerId);
        ReturnPoint point = instance.spectatorReturnPoints.remove(playerId);
        GameMode gameMode = instance.spectatorGameModes.remove(playerId);
        return new SpectatorExit(point, gameMode == null ? GameMode.SURVIVAL : gameMode);
    }

    private void restoreSpectator(Player player, SpectatorExit exit, boolean notify) {
        player.setGameMode(exit.gameMode);
        Location destination = exit.returnPoint == null ? fallbackReturnLocation() : exit.returnPoint.resolve();
        if (destination == null || isArenaWorld(destination.getWorld())) destination = fallbackReturnLocation();
        player.teleport(destination);
        if (notify) player.sendMessage(plugin.messages().component("endsun-arena.spectate.left"));
    }

    public World regenerateNow() {
        ArenaInstance instance = instances.stream()
                .filter(value -> !value.occupied())
                .filter(value -> {
                    World world = Bukkit.getWorld(value.worldName);
                    return world == null || world.getPlayers().isEmpty();
                })
                .findFirst().orElseThrow(() -> new IllegalStateException("没有空闲且无玩家的烬日冥卫副本可供重置"));
        World world = createOrLoad(instance);
        instance.resetting = true;
        try {
            return regenerate(instance, world);
        } finally {
            resetState(instance);
        }
    }

    private World regenerate(ArenaInstance instance, World world) {
        WorldManager manager = multiverseWorldManager();
        LoadedMultiverseWorld loaded = manager.getLoadedWorld(world).getOrNull();
        if (loaded == null) throw new IllegalStateException("烬日冥卫副本未被 Multiverse-Core 管理：" + instance.worldName);
        var result = manager.regenWorld(RegenWorldOptions.world(loaded)
                .seed(settings.seed())
                .randomSeed(false)
                .keepGameRule(false)
                .keepWorldBorder(false)
                .keepWorldConfig(true));
        if (result.isFailure()) throw new IllegalStateException("Multiverse 再生世界失败：" + result.getFailureReason());
        LoadedMultiverseWorld regenerated = result.getOrNull();
        World newWorld = regenerated == null ? null : regenerated.getBukkitWorld().getOrNull();
        if (newWorld == null) throw new IllegalStateException("Multiverse 再生后未加载世界");
        configureWorld(newWorld);
        newWorld.getChunkAt(0, 0).load(true);
        markCurrentGeneration(newWorld);
        return newWorld;
    }

    public boolean isMonumentBlock(Block block) {
        if (!isArenaWorld(block.getWorld())) return false;
        int y = block.getY();
        return Math.abs(block.getX()) <= 4 && Math.abs(block.getZ()) <= 2
                && y >= settings.floorY() + 4 && y <= settings.floorY() + 14;
    }

    public boolean isArenaWorld(World world) {
        return instanceForWorld(world) != null;
    }

    public boolean isPlayerInSession(UUID playerId) {
        return instanceByPlayer.containsKey(playerId);
    }

    /** Synchronizes the temporary sidebar only while the assigned player is physically in this instance. */
    public boolean syncRaidSidebar(Player player) {
        ArenaInstance instance = instanceByPlayer.get(player.getUniqueId());
        if (instance == null || instance.sessionKey == null
                || instanceForWorld(player.getWorld()) != instance) return false;
        int initialDuration = instance.challengeDurationTicks > 0
                ? instance.challengeDurationTicks
                : settings.challengeDurationTicks(Math.max(1, instance.participants.size()));
        plugin.raidSidebar().attach(instance.sessionKey, instance.participants,
                instance.deadPlayers, player, initialDuration);
        return true;
    }

    public World loadedWorld() {
        for (ArenaInstance instance : instances) {
            World world = Bukkit.getWorld(instance.worldName);
            if (world != null) return world;
        }
        return null;
    }

    public int loadedInstanceCount() {
        int result = 0;
        for (ArenaInstance instance : instances) if (Bukkit.getWorld(instance.worldName) != null) result++;
        return result;
    }

    public int activeInstanceCount() {
        int result = 0;
        for (ArenaInstance instance : instances) if (instance.occupied()) result++;
        return result;
    }

    public Location playerSpawn(World world) {
        return new Location(world, 0.5, settings.floorY() + 2.0, settings.radius() - 45.5, 180.0f, 0.0f);
    }

    public Location bossSpawn(World world) {
        return new Location(world, 0.5, settings.floorY() + 4.0, 0.5, 0.0f, 0.0f);
    }

    public EndsunGuardArenaSettings settings() {
        return settings;
    }

    public void shutdown() {
        spectatorGameModeBypass.clear();
        for (UUID spectatorId : List.copyOf(spectatorByPlayer.keySet())) {
            SpectatorExit exit = detachSpectator(spectatorId);
            Player spectator = Bukkit.getPlayer(spectatorId);
            if (exit != null && spectator != null) restoreSpectator(spectator, exit, false);
        }
        for (ArenaInstance instance : instances) {
            instance.summoning = false;
            cancelChallengeTimer(instance);
            if (instance.summonTask != null) instance.summonTask.cancel();
            if (instance.returnTask != null) instance.returnTask.cancel();
            instance.summonTask = null;
            instance.returnTask = null;
            for (BukkitTask task : instance.respawnTasks.values()) task.cancel();
            instance.respawnTasks.clear();
        }
    }

    private ArenaInstance instanceForWorld(World world) {
        if (world == null) return null;
        for (ArenaInstance instance : instances) {
            if (instance.worldName.equalsIgnoreCase(world.getName())) return instance;
        }
        return null;
    }

    private ArenaInstance findBySessionKey(UUID sessionKey) {
        for (ArenaInstance instance : instances) {
            if (sessionKey.equals(instance.sessionKey)) return instance;
        }
        return null;
    }

    private EndsunGuard arenaBoss(ArenaInstance instance) {
        for (EndsunGuard boss : plugin.endsunGuardManager().activeBosses()) {
            World world = boss.location().getWorld();
            if (world != null && world.getName().equalsIgnoreCase(instance.worldName)) return boss;
        }
        return null;
    }

    private void removeMonument(World world) {
        for (int x = -4; x <= 4; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = settings.floorY() + 4; y <= settings.floorY() + 14; y++) {
                    world.getBlockAt(x, y, z).setType(Material.AIR, false);
                }
            }
        }
    }

    private void restoreMonument(World world) {
        int baseY = settings.floorY() + 4;
        for (int x = -3; x <= 3; x++) for (int z = -2; z <= 2; z++) {
            world.getBlockAt(x, baseY, z).setType(Material.CHISELED_POLISHED_BLACKSTONE, false);
        }
        for (int x = -2; x <= 2; x++) for (int z = -1; z <= 1; z++) {
            world.getBlockAt(x, baseY + 1, z).setType(Material.POLISHED_BASALT, false);
        }
        for (int x = -2; x <= 2; x++) for (int offset = 2; offset <= 8; offset++) {
            boolean edge = Math.abs(x) == 2 || offset == 2 || offset == 8;
            Material material = edge ? Material.GILDED_BLACKSTONE
                    : (offset == 5 && x == 0 ? Material.CRYING_OBSIDIAN : Material.POLISHED_BLACKSTONE);
            world.getBlockAt(x, baseY + offset, 0).setType(material, false);
        }
        for (int x : new int[]{-3, 3}) {
            world.getBlockAt(x, baseY + 5, 0).setType(Material.MAGMA_BLOCK, false);
            world.getBlockAt(x, baseY + 8, 0).setType(Material.GILDED_BLACKSTONE, false);
        }
        for (int x : new int[]{-4, 4}) {
            world.getBlockAt(x, baseY + 9, 0).setType(Material.GILDED_BLACKSTONE, false);
            world.getBlockAt(x, baseY + 10, 0).setType(Material.GILDED_BLACKSTONE, false);
        }
        for (int x = -1; x <= 1; x++) world.getBlockAt(x, baseY + 9, 0).setType(Material.GILDED_BLACKSTONE, false);
        world.getBlockAt(0, baseY + 10, 0).setType(Material.SHROOMLIGHT, false);
    }

    private Location monumentFocus(World world) {
        return new Location(world, 0.5, settings.floorY() + 8.0, 0.5);
    }

    private void broadcast(ArenaInstance instance, Component message) {
        World world = Bukkit.getWorld(instance.worldName);
        if (world == null) return;
        for (Player player : world.getPlayers()) player.sendMessage(message);
    }

    private WorldManager multiverseWorldManager() {
        if (!MultiverseCoreApi.isLoaded()) {
            throw new IllegalStateException("需要先安装并启用 Multiverse-Core 5.7.3+");
        }
        return MultiverseCoreApi.get().getWorldManager();
    }

    private String generatorName() {
        return plugin.getName() + ":" + GENERATOR_ID;
    }

    private void configureWorld(World world) {
        for (EnderDragon dragon : world.getEntitiesByClass(EnderDragon.class)) dragon.remove();
        world.setSpawnLocation(playerSpawn(world));
        world.setTime(13000L);
        world.setStorm(false);
        world.setThundering(false);
        world.setPVP(false);
        world.setDifficulty(Difficulty.HARD);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_FIRE_TICK, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        WorldBorder border = world.getWorldBorder();
        border.setCenter(0.5, 0.5);
        border.setSize((settings.radius() - 1.0) * 2.0);
        border.setWarningDistance(6);
    }

    private void markCurrentGeneration(World world) {
        world.getPersistentDataContainer().set(generationKey, PersistentDataType.INTEGER, GENERATION_VERSION);
        world.save();
    }

    private void resetState(ArenaInstance instance) {
        cancelChallengeTimer(instance);
        instance.activeBossId = null;
        instance.summoning = false;
        instance.resetting = false;
        instance.summonTask = null;
        instance.returnTask = null;
        instance.challengeDurationTicks = 0;
        instance.bossHealthMultiplier = 1.0;
        instance.eligibleParticipants.clear();
        instance.xpAwarded = false;
        instance.challengeStartTick = -1;
        instance.clearTicks = -1;
        for (BukkitTask task : instance.respawnTasks.values()) task.cancel();
        instance.respawnTasks.clear();
        instance.deadPlayers.clear();
        instance.respawnReadyAt.clear();
        instance.previousGameModes.clear();
        for (UUID spectatorId : List.copyOf(instance.spectators)) spectatorByPlayer.remove(spectatorId, instance);
        instance.spectators.clear();
        instance.spectatorReturnPoints.clear();
        instance.spectatorGameModes.clear();
    }

    private void cancelRespawnTask(ArenaInstance instance, UUID playerId) {
        BukkitTask task = instance.respawnTasks.remove(playerId);
        if (task != null) task.cancel();
    }

    public void onBossDeathStarted(EndsunGuard boss) {
        ArenaInstance instance = instanceForWorld(boss.location().getWorld());
        if (instance == null || instance.activeBossId == null || !instance.activeBossId.equals(boss.id())) return;
        cancelChallengeTimer(instance);
        markClearTime(instance);
        plugin.raidSidebar().settling(instance.sessionKey, boss.damageContributions());
    }

    private void startChallengeTimer(ArenaInstance instance) {
        cancelChallengeTimer(instance);
        if (instance.sessionKey == null || instance.participants.isEmpty() || instance.activeBossId == null) return;
        if (instance.challengeStartTick < 0) instance.challengeStartTick = Bukkit.getCurrentTick();
        int durationTicks = instance.challengeDurationTicks > 0
                ? instance.challengeDurationTicks
                : settings.challengeDurationTicks(activeParticipantCount(instance, Bukkit.getWorld(instance.worldName)));
        instance.challengeDurationTicks = durationTicks;
        final int[] remainingTicks = {durationTicks};
        EndsunGuard activeBoss = arenaBoss(instance);
        plugin.raidSidebar().start(instance.sessionKey, durationTicks,
                activeBoss == null ? Map.of() : activeBoss.damageContributions());
        broadcast(instance, plugin.messages().component(
                "endsun-arena.challenge-timer.started", "time", formatDuration(durationTicks / 20)));
        instance.challengeTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (instance.resetting || instance.returnTask != null || instance.activeBossId == null) {
                cancelChallengeTimer(instance);
                return;
            }
            remainingTicks[0] -= 20;
            EndsunGuard currentBoss = arenaBoss(instance);
            plugin.raidSidebar().update(instance.sessionKey, remainingTicks[0],
                    currentBoss == null ? Map.of() : currentBoss.damageContributions());
            if (remainingTicks[0] <= 0) {
                cancelChallengeTimer(instance);
                failInstance(instance, plugin.messages().component(
                        "endsun-arena.challenge-failed.timeout",
                        "time", formatDuration(durationTicks / 20)));
                return;
            }
            int seconds = (remainingTicks[0] + 19) / 20;
            if (seconds == 300 || seconds == 60 || seconds == 30 || seconds == 10 || seconds <= 5) {
                broadcast(instance, plugin.messages().component(
                        "endsun-arena.challenge-timer.remaining", "time", formatDuration(seconds)));
            }
        }, 20L, 20L);
    }

    private void prepareChallengeScaling(ArenaInstance instance, World world) {
        instance.eligibleParticipants.clear();
        if (world != null) {
            for (UUID participantId : instance.participants) {
                Player player = Bukkit.getPlayer(participantId);
                if (player != null && player.isOnline() && player.getWorld().equals(world)) {
                    instance.eligibleParticipants.add(participantId);
                }
            }
        }
        int participantCount = activeParticipantCount(instance, world);
        instance.challengeDurationTicks = settings.challengeDurationTicks(participantCount);
        instance.bossHealthMultiplier = settings.bossHealthMultiplier(participantCount);
    }

    private int activeParticipantCount(ArenaInstance instance, World world) {
        if (world == null) return 1;
        int count = 0;
        for (UUID participantId : instance.participants) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline() && player.getWorld().equals(world)) count++;
        }
        return Math.max(1, count);
    }

    public int challengeParticipantCount(UUID bossId, World world) {
        ArenaInstance instance = instanceForWorld(world);
        if (instance == null || bossId == null || !bossId.equals(instance.activeBossId)) return 0;
        return Math.max(1, instance.eligibleParticipants.size());
    }

    public boolean isActiveCombatant(Player player) {
        ArenaInstance instance = instanceByPlayer.get(player.getUniqueId());
        return instance != null
                && instance.activeBossId != null
                && !instance.resetting
                && instance.eligibleParticipants.contains(player.getUniqueId())
                && instanceForWorld(player.getWorld()) == instance;
    }

    private void markClearTime(ArenaInstance instance) {
        if (instance.clearTicks >= 0) return;
        instance.clearTicks = instance.challengeStartTick < 0
                ? Math.max(0, instance.challengeDurationTicks)
                : Math.max(0, Bukkit.getCurrentTick() - instance.challengeStartTick);
    }

    private void cancelChallengeTimer(ArenaInstance instance) {
        BukkitTask task = instance.challengeTask;
        instance.challengeTask = null;
        if (task != null) task.cancel();
    }

    private static String formatDuration(int totalSeconds) {
        int clamped = Math.max(0, totalSeconds);
        return String.format(java.util.Locale.ROOT, "%d:%02d", clamped / 60, clamped % 60);
    }

    private Location fallbackReturnLocation() {
        for (World world : Bukkit.getWorlds()) {
            if (!plugin.isArenaWorld(world)) return world.getSpawnLocation().clone().add(0.5, 0.0, 0.5);
        }
        throw new IllegalStateException("没有可用的返回世界");
    }

    private static final class ArenaInstance {
        private final int slot;
        private final String worldName;
        private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> eligibleParticipants = new LinkedHashSet<>();
        private final Map<UUID, ReturnPoint> returnPoints = new HashMap<>();
        private final LinkedHashSet<UUID> deadPlayers = new LinkedHashSet<>();
        private final Map<UUID, Long> respawnReadyAt = new HashMap<>();
        private final Map<UUID, GameMode> previousGameModes = new HashMap<>();
        private final Map<UUID, BukkitTask> respawnTasks = new HashMap<>();
        private final LinkedHashSet<UUID> spectators = new LinkedHashSet<>();
        private final Map<UUID, ReturnPoint> spectatorReturnPoints = new HashMap<>();
        private final Map<UUID, GameMode> spectatorGameModes = new HashMap<>();
        private UUID sessionKey;
        private UUID activeBossId;
        private boolean summoning;
        private boolean resetting;
        private boolean xpAwarded;
        private BukkitTask summonTask;
        private BukkitTask returnTask;
        private BukkitTask challengeTask;
        private int challengeDurationTicks;
        private double bossHealthMultiplier = 1.0;
        private int challengeStartTick = -1;
        private int clearTicks = -1;

        private ArenaInstance(int slot, String worldName) {
            this.slot = slot;
            this.worldName = worldName;
        }

        private boolean occupied() {
            return sessionKey != null || summoning || resetting || returnTask != null || activeBossId != null;
        }
    }

    private record SpectatorExit(ReturnPoint returnPoint, GameMode gameMode) { }

    private record ReturnPoint(UUID worldId, String worldName, double x, double y, double z,
                               float yaw, float pitch) {
        private static ReturnPoint of(Location location) {
            World world = location.getWorld();
            if (world == null) throw new IllegalArgumentException("返回位置没有世界");
            return new ReturnPoint(world.getUID(), world.getName(), location.getX(), location.getY(),
                    location.getZ(), location.getYaw(), location.getPitch());
        }

        private Location resolve() {
            World world = Bukkit.getWorld(worldId);
            if (world == null) world = Bukkit.getWorld(worldName);
            return world == null ? null : new Location(world, x, y, z, yaw, pitch);
        }
    }
}
