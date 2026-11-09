package cn.yknboss.command;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.boss.BoneDragon;
import cn.yknboss.boss.EndsunGuard;
import cn.yknboss.boss.EndWitch;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class YknBossCommand implements CommandExecutor, TabCompleter {
    private final YknBossPlugin plugin;

    public YknBossCommand(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            help(sender);
            return true;
        }
        String action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("leave")) return leave(sender);
        if (action.equals("enter")) return enter(sender, args);
        if (action.equals("spec") || action.equals("spectate")) return spectate(sender, args);
        if (!sender.hasPermission("yknboss.admin")) {
            plugin.messages().send(sender, "command.no-permission");
            return true;
        }
        return switch (action) {
            case "spawn" -> spawn(sender, args);
            case "kill" -> kill(sender, args);
            case "info" -> info(sender);
            case "reload" -> reload(sender);
            case "arena" -> arena(sender, args);
            case "admin" -> admin(sender, args);
            default -> {
                help(sender);
                yield true;
            }
        };
    }

    private boolean leave(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.player-only");
            return true;
        }
        try {
            if (plugin.endWitchArenaManager().isPlayerInSession(player.getUniqueId())) {
                plugin.endWitchArenaManager().giveUp(player);
            } else if (plugin.endsunArenaManager().isPlayerInSession(player.getUniqueId())) {
                plugin.endsunArenaManager().giveUp(player);
            } else {
                plugin.arenaManager().giveUp(player);
            }
        } catch (IllegalStateException exception) {
            plugin.messages().send(sender, "command.error", "message", exception.getMessage());
        }
        return true;
    }

    private boolean enter(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.player-only");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "command.enter-usage");
            return true;
        }
        String raid = args[1].toLowerCase(Locale.ROOT);
        try {
            int raidType = switch (raid) {
                case "bone", "bone_dragon", "dragon" -> 0;
                case "endsun", "endsun_guard", "anuhe" -> 1;
                case "end_witch", "witch", "eifreya", "ifuleiya" -> 2;
                default -> throw new IllegalStateException(
                        plugin.messages().plain("command.unknown-raid", "raid", raid));
            };
            leaveSpectateBeforeEnter(player);
            if (raidType == 0) plugin.arenaManager().enter(player);
            else if (raidType == 1) plugin.endsunArenaManager().enter(player);
            else plugin.endWitchArenaManager().enter(player);
        } catch (IllegalStateException exception) {
            plugin.messages().send(sender, "command.error", "message", exception.getMessage());
        }
        return true;
    }

    private void leaveSpectateBeforeEnter(Player player) {
        if (plugin.endWitchArenaManager().isSpectating(player)) {
            plugin.endWitchArenaManager().leaveSpectate(player);
        } else if (plugin.endsunArenaManager().isSpectating(player)) {
            plugin.endsunArenaManager().leaveSpectate(player);
        } else if (plugin.arenaManager().isSpectating(player)) {
            plugin.arenaManager().leaveSpectate(player);
        }
    }

    private boolean spawn(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.spawn-player-only");
            return true;
        }
        String requestedType = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "bone_dragon";
        String type = switch (requestedType) {
            case "bone_dragon" -> "bone_dragon";
            case "endsun_guard", "endsun", "anuhe" -> "endsun_guard";
            case "end_witch", "witch", "ifuleiya", "eifreya" -> "end_witch";
            default -> null;
        };
        if (type == null) {
            plugin.messages().send(sender, "command.unknown-boss", "type", requestedType);
            return true;
        }
        Vector forward = player.getLocation().getDirection().setY(0.0);
        if (forward.lengthSquared() < 0.01) forward = new Vector(0, 0, 1);
        Location location = player.getLocation().add(forward.normalize()
                .multiply(type.equals("bone_dragon") ? 14.0 : 7.0));
        location.setYaw(player.getLocation().getYaw() + 180.0f);
        try {
            if (type.equals("bone_dragon")) {
                BoneDragon boss = plugin.boneDragonManager().spawn(location);
                plugin.messages().send(sender, "command.spawned-bone-dragon", "id", boss.id());
            } else if (type.equals("endsun_guard")) {
                EndsunGuard boss = plugin.endsunGuardManager().spawn(location);
                plugin.messages().send(sender, "command.spawned-endsun-guard", "id", boss.id());
            } else {
                EndWitch boss = plugin.endWitchManager().spawn(location);
                plugin.messages().send(sender, "command.spawned-end-witch", "id", boss.id());
            }
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().severe("生成 " + type + " 失败: " + exception.getMessage());
            plugin.messages().send(sender, "command.spawn-failed", "message", exception.getMessage());
        }
        return true;
    }

    private boolean kill(CommandSender sender, String[] args) {
        if (args.length >= 2 && args[1].equalsIgnoreCase("all")) {
            int count = plugin.boneDragonManager().killAll() + plugin.endsunGuardManager().killAll()
                    + plugin.endWitchManager().killAll();
            plugin.messages().send(sender, "command.removed-all", "count", count);
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.console-kill-usage");
            return true;
        }
        var nearestDragon = plugin.boneDragonManager().nearest(player.getLocation(), 96.0);
        var nearestEndsun = plugin.endsunGuardManager().nearest(player.getLocation(), 96.0);
        var nearestWitch = plugin.endWitchManager().nearest(player.getLocation(), 96.0);
        if (nearestDragon.isEmpty() && nearestEndsun.isEmpty() && nearestWitch.isEmpty()) {
            plugin.messages().send(sender, "command.no-nearby-boss", "range", 96);
            return true;
        }
        double dragonDistance = nearestDragon.map(boss -> boss.location().distanceSquared(player.getLocation()))
                .orElse(Double.MAX_VALUE);
        double endsunDistance = nearestEndsun.map(boss -> boss.location().distanceSquared(player.getLocation()))
                .orElse(Double.MAX_VALUE);
        double witchDistance = nearestWitch.map(boss -> boss.location().distanceSquared(player.getLocation()))
                .orElse(Double.MAX_VALUE);
        if (dragonDistance <= endsunDistance && dragonDistance <= witchDistance) nearestDragon.orElseThrow().remove();
        else if (endsunDistance <= witchDistance) nearestEndsun.orElseThrow().remove();
        else nearestWitch.orElseThrow().remove();
        plugin.messages().send(sender, "command.removed-nearest");
        return true;
    }

    private boolean info(CommandSender sender) {
        plugin.messages().send(sender, "command.info.dragon-count", "count", plugin.boneDragonManager().activeBosses().size());
        for (BoneDragon boss : plugin.boneDragonManager().activeBosses()) {
            Location location = boss.location();
            plugin.messages().send(sender, "command.info.dragon-line",
                    "id", boss.id(), "health", format(boss.health()), "max-health", format(boss.maxHealth()),
                    "phase", boss.phaseName(), "skill", boss.currentSkill(), "world", location.getWorld().getName(),
                    "x", format(location.getX()), "y", format(location.getY()), "z", format(location.getZ()));
        }
        plugin.messages().send(sender, "command.info.endsun-count",
                "count", plugin.endsunGuardManager().activeBosses().size());
        for (EndsunGuard boss : plugin.endsunGuardManager().activeBosses()) {
            Location location = boss.location();
            plugin.messages().send(sender, "command.info.endsun-line",
                    "id", boss.id(), "health", format(boss.health()), "max-health", format(boss.maxHealth()),
                    "phase", boss.phaseName(), "skill", boss.currentSkill(),
                    "eclipse", format(boss.highestEclipse()),
                    "world", location.getWorld().getName(),
                    "x", format(location.getX()), "y", format(location.getY()), "z", format(location.getZ()));
        }
        plugin.messages().send(sender, "command.info.witch-count",
                "count", plugin.endWitchManager().activeBosses().size());
        for (EndWitch boss : plugin.endWitchManager().activeBosses()) {
            Location location = boss.location();
            plugin.messages().send(sender, "command.info.witch-line",
                    "id", boss.id(), "health", format(boss.health()), "max-health", format(boss.maxHealth()),
                    "phase", boss.phaseName(), "skill", boss.currentSkill(), "gauge", format(boss.endGauge()),
                    "world", location.getWorld().getName(), "x", format(location.getX()),
                    "y", format(location.getY()), "z", format(location.getZ()));
        }
        return true;
    }

    private boolean reload(CommandSender sender) {
        try {
            int addedDefaults = plugin.reloadPluginConfig();
            plugin.messages().send(sender, "command.reloaded", "count", addedDefaults);
        } catch (RuntimeException exception) {
            plugin.getLogger().severe("重载配置失败: " + exception.getMessage());
            plugin.messages().send(sender, "command.reload-failed", "message", exception.getMessage());
        }
        return true;
    }

    private boolean spectate(CommandSender sender, String[] args) {
        if (!sender.hasPermission("yknboss.admin") && !sender.hasPermission("yknboss.spec")) {
            plugin.messages().send(sender, "command.no-permission");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.player-only");
            return true;
        }
        if (args.length < 2) {
            plugin.messages().send(sender, "command.spec-usage");
            return true;
        }
        try {
            if (args[1].equalsIgnoreCase("leave") || args[1].equalsIgnoreCase("exit")) {
                if (plugin.endWitchArenaManager().isSpectating(player)) plugin.endWitchArenaManager().leaveSpectate(player);
                else if (plugin.endsunArenaManager().isSpectating(player)) plugin.endsunArenaManager().leaveSpectate(player);
                else plugin.arenaManager().leaveSpectate(player);
            } else {
                Player target = org.bukkit.Bukkit.getPlayerExact(args[1]);
                if (target != null && plugin.endWitchArenaManager().isPlayerInSession(target.getUniqueId())) {
                    plugin.endWitchArenaManager().spectate(player, args[1]);
                } else if (target != null && plugin.endsunArenaManager().isPlayerInSession(target.getUniqueId())) {
                    plugin.endsunArenaManager().spectate(player, args[1]);
                } else {
                    plugin.arenaManager().spectate(player, args[1]);
                }
            }
        } catch (IllegalStateException exception) {
            plugin.messages().send(sender, "command.error", "message", exception.getMessage());
        }
        return true;
    }

    private boolean arena(CommandSender sender, String[] args) {
        String action = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "info";
        try {
            return switch (action) {
                case "create" -> {
                    plugin.arenaManager().createAll();
                    plugin.messages().send(sender, "command.arena-created",
                            "count", plugin.arenaManager().settings().instanceCount());
                    yield true;
                }
                case "spawn" -> {
                    World world = plugin.arenaManager().createOrLoad();
                    BoneDragon boss = plugin.arenaManager().spawnArenaBoss(world);
                    plugin.messages().send(sender, "command.arena-boss-spawned", "id", boss.id());
                    yield true;
                }
                case "reset" -> {
                    World world = plugin.arenaManager().regenerateNow();
                    plugin.messages().send(sender, "command.arena-reset", "world", world.getName());
                    yield true;
                }
                case "info" -> {
                    var settings = plugin.arenaManager().settings();
                    World world = plugin.arenaManager().loadedWorld();
                    plugin.messages().send(sender, "command.arena-info",
                            "world", settings.worldName(), "active", plugin.arenaManager().activeInstanceCount(),
                            "instances", settings.instanceCount(), "loaded", plugin.arenaManager().loadedInstanceCount(),
                            "radius", settings.radius(), "floor-y", settings.floorY(),
                            "state", plugin.messages().plain(world == null
                                    ? "command.arena-state-unloaded" : "command.arena-state-loaded"));
                    yield true;
                }
                default -> {
                    plugin.messages().send(sender, "command.arena-usage");
                    yield true;
                }
            };
        } catch (RuntimeException | LinkageError exception) {
            plugin.getLogger().severe("骨龙竞技场操作失败: " + exception.getMessage());
            plugin.messages().send(sender, "command.arena-failed", "message", exception.getMessage());
            return true;
        }
    }

    private boolean admin(CommandSender sender, String[] args) {
        if (args.length < 2
                || (!args[1].equalsIgnoreCase("special") && !args[1].equalsIgnoreCase("bypass"))) {
            plugin.messages().send(sender, "command.admin-usage");
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "command.player-only");
            return true;
        }
        if (args[1].equalsIgnoreCase("bypass")) {
            boolean enabled = plugin.endWitchArenaManager().isSpectating(player)
                    ? plugin.endWitchArenaManager().toggleSpectatorGameModeBypass(player)
                    : plugin.endsunArenaManager().isSpectating(player)
                    ? plugin.endsunArenaManager().toggleSpectatorGameModeBypass(player)
                    : plugin.arenaManager().toggleSpectatorGameModeBypass(player);
            plugin.messages().send(player, enabled
                    ? "command.admin-bypass-enabled"
                    : "command.admin-bypass-disabled");
            return true;
        }
        plugin.specialItemManager().openCatalog(player);
        return true;
    }

    private void help(CommandSender sender) {
        plugin.messages().sendList(sender, "command.help-player");
        if (sender.hasPermission("yknboss.admin") || sender.hasPermission("yknboss.spec")) {
            plugin.messages().sendList(sender, "command.help-spec");
        }
        if (!sender.hasPermission("yknboss.admin")) return;
        plugin.messages().sendList(sender, "command.help-admin");
    }

    private static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            java.util.ArrayList<String> actions = new java.util.ArrayList<>();
            actions.add("leave");
            actions.add("enter");
            if (sender.hasPermission("yknboss.admin") || sender.hasPermission("yknboss.spec")) actions.add("spec");
            if (sender.hasPermission("yknboss.admin")) {
                actions.addAll(List.of("spawn", "kill", "info", "reload", "arena", "admin"));
            }
            return filter(actions, args[0]);
        }
        if ((args[0].equalsIgnoreCase("spec") || args[0].equalsIgnoreCase("spectate")) && args.length == 2
                && (sender.hasPermission("yknboss.admin") || sender.hasPermission("yknboss.spec"))) {
            java.util.ArrayList<String> targets = new java.util.ArrayList<>(plugin.arenaManager().activeParticipantNames());
            targets.addAll(plugin.endsunArenaManager().activeParticipantNames());
            targets.addAll(plugin.endWitchArenaManager().activeParticipantNames());
            targets.add("leave");
            return filter(targets, args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("enter")) {
            return filter(List.of("bone_dragon", "endsun_guard", "end_witch"), args[1]);
        }
        if (!args[0].equalsIgnoreCase("leave") && !args[0].equalsIgnoreCase("enter")
                && !sender.hasPermission("yknboss.admin")) return List.of();
        if (args.length == 2 && args[0].equalsIgnoreCase("spawn")) {
            return filter(List.of("bone_dragon", "endsun_guard", "end_witch"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("kill")) {
            return filter(List.of("all"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("arena")) {
            return filter(List.of("create", "spawn", "reset", "info"), args[1]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("admin")) {
            return filter(List.of("special", "bypass"), args[1]);
        }
        return List.of();
    }

    private List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream()
                .filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized))
                .toList();
    }
}
