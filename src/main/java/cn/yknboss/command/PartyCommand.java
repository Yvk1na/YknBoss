package cn.yknboss.command;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.party.PartyManager;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class PartyCommand implements CommandExecutor, TabCompleter {
    private final YknBossPlugin plugin;

    public PartyCommand(YknBossPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "party.command-player-only");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        try {
            switch (action) {
                case "invite", "i" -> {
                    ensureMutable(player);
                    requireArgs(args, 2, "party.usage.invite");
                    Player target = onlinePlayer(args[1]);
                    ensureMutable(target);
                    plugin.partyManager().invite(player, target);
                }
                case "accept", "join" -> {
                    ensureMutable(player);
                    plugin.partyManager().accept(player);
                }
                case "decline", "deny" -> plugin.partyManager().decline(player);
                case "remove", "kick" -> {
                    ensureMutable(player);
                    requireArgs(args, 2, "party.usage.remove");
                    UUID member = partyMember(player, args[1]);
                    if (plugin.arenaManager().isPlayerInSession(member)
                            || plugin.endsunArenaManager().isPlayerInSession(member)) {
                        throw new IllegalStateException(plugin.messages().plain("party.error.member-in-instance"));
                    }
                    plugin.partyManager().remove(player, member);
                }
                case "transfer", "leader", "t" -> {
                    ensureMutable(player);
                    requireArgs(args, 2, "party.usage.transfer");
                    UUID member = partyMember(player, args[1]);
                    plugin.partyManager().transfer(player, member);
                }
                case "leave", "l" -> {
                    ensureMutable(player);
                    plugin.partyManager().leave(player);
                }
                case "disband", "delete", "d" -> {
                    ensureMutable(player);
                    plugin.partyManager().disband(player);
                }
                case "list", "info" -> showParty(player);
                case "chat", "c" -> {
                    requireArgs(args, 2, "party.usage.chat");
                    plugin.partyManager().chat(player, String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
                }
                default -> help(player);
            }
        } catch (IllegalStateException exception) {
            plugin.messages().send(player, "command.error", "message", exception.getMessage());
        }
        return true;
    }

    private void showParty(Player player) {
        PartyManager.PartyView party = plugin.partyManager().partyOf(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException(plugin.messages().plain("party.error.no-party")));
        plugin.messages().send(player, "party.list.header",
                "current", party.members().size(), "max", plugin.partyManager().maxMembers());
        for (UUID member : party.members()) {
            String name = Bukkit.getOfflinePlayer(member).getName();
            boolean online = Bukkit.getPlayer(member) != null;
            String prefix = member.equals(party.leader())
                    ? plugin.messages().raw("party.list.leader-prefix")
                    : plugin.messages().raw("party.list.member-prefix");
            plugin.messages().send(player, online ? "party.list.online" : "party.list.offline",
                    "prefix", prefix, "player", name == null ? member : name);
        }
    }

    private void ensureMutable(Player player) {
        if (plugin.arenaManager().isPlayerInSession(player.getUniqueId())
                || plugin.endsunArenaManager().isPlayerInSession(player.getUniqueId())) {
            throw new IllegalStateException(plugin.messages().plain("party.error.instance-locked"));
        }
    }

    private UUID partyMember(Player player, String value) {
        PartyManager.PartyView party = plugin.partyManager().partyOf(player.getUniqueId())
                .orElseThrow(() -> new IllegalStateException(plugin.messages().plain("party.error.no-party")));
        for (UUID member : party.members()) {
            String name = Bukkit.getOfflinePlayer(member).getName();
            if (member.toString().equalsIgnoreCase(value) || name != null && name.equalsIgnoreCase(value)) return member;
        }
        throw new IllegalStateException(plugin.messages().plain("party.error.member-not-found", "player", value));
    }

    private Player onlinePlayer(String value) {
        Player player = Bukkit.getPlayerExact(value);
        if (player == null) {
            try {
                player = Bukkit.getPlayer(UUID.fromString(value));
            } catch (IllegalArgumentException ignored) {
                // The value was a player name rather than a UUID.
            }
        }
        if (player == null) {
            throw new IllegalStateException(plugin.messages().plain("party.error.player-not-online", "player", value));
        }
        return player;
    }

    private void requireArgs(String[] args, int length, String messageKey) {
        if (args.length < length) {
            throw new IllegalStateException(plugin.messages().plain(messageKey));
        }
    }

    private void help(Player player) {
        plugin.messages().sendList(player, "party.help");
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                                 @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return filter(List.of("invite", "i", "accept", "decline", "remove", "transfer", "t",
                    "leave", "l", "disband", "d", "list", "chat", "help"), args[0]);
        }
        if (!(sender instanceof Player player) || args.length != 2) return List.of();
        if (args[0].equalsIgnoreCase("invite") || args[0].equalsIgnoreCase("i")) {
            List<String> names = Bukkit.getOnlinePlayers().stream()
                    .filter(other -> !other.equals(player))
                    .map(Player::getName).toList();
            return filter(names, args[1]);
        }
        if (args[0].equalsIgnoreCase("remove") || args[0].equalsIgnoreCase("kick")
                || args[0].equalsIgnoreCase("transfer") || args[0].equalsIgnoreCase("t")) {
            var party = plugin.partyManager().partyOf(player.getUniqueId());
            if (party.isEmpty()) return List.of();
            List<String> names = new ArrayList<>();
            for (UUID member : party.get().members()) {
                if (member.equals(player.getUniqueId())) continue;
                String name = Bukkit.getOfflinePlayer(member).getName();
                if (name != null) names.add(name);
            }
            return filter(names, args[1]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> values, String prefix) {
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return values.stream().filter(value -> value.toLowerCase(Locale.ROOT).startsWith(normalized)).toList();
    }
}
