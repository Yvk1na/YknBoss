package cn.yknboss.party;

import cn.yknboss.YknBossPlugin;
import cn.yknboss.message.MessageService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.UUID;

public final class PartyManager {
    private final MessageService messages;
    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, UUID> partyByMember = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();
    private final Map<InviteKey, Long> inviteCooldowns = new HashMap<>();
    private PartySettings settings;

    public PartyManager(YknBossPlugin plugin, PartySettings settings) {
        this.messages = plugin.messages();
        this.settings = settings;
    }

    public void reload(PartySettings settings) {
        this.settings = settings;
    }

    public int maxMembers() {
        return settings.maxMembers();
    }

    public void invite(Player sender, Player target) {
        UUID senderId = sender.getUniqueId();
        UUID targetId = target.getUniqueId();
        if (senderId.equals(targetId)) {
            throw new IllegalStateException(messages.plain("party.error.invite-self"));
        }
        UUID existingPartyId = partyByMember.get(senderId);
        Party party = existingPartyId == null ? null : requireLeader(senderId);
        requireNoParty(targetId);
        if (party != null && party.members.size() >= settings.maxMembers()) {
            throw new IllegalStateException(messages.plain("party.error.full", "max", settings.maxMembers()));
        }

        long now = System.currentTimeMillis();
        inviteCooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        InviteKey inviteKey = new InviteKey(senderId, targetId);
        long cooldownUntil = inviteCooldowns.getOrDefault(inviteKey, 0L);
        if (cooldownUntil > now) {
            long seconds = Math.max(1L, (cooldownUntil - now + 999L) / 1000L);
            throw new IllegalStateException(messages.plain("party.error.invite-repeat", "seconds", seconds));
        }

        if (party == null) {
            party = createParty(senderId);
            sender.sendMessage(messages.component("party.created"));
        }
        long expiresAt = now + settings.inviteLifetimeMillis();
        invites.put(targetId, new Invite(party.id, expiresAt));
        inviteCooldowns.put(inviteKey, expiresAt);
        target.sendMessage(messages.component("party.invite.received", "player", sender.getName()));
        Component acceptButton = messages.component("party.invite.accept-button")
                .clickEvent(ClickEvent.runCommand("/party accept"))
                .hoverEvent(HoverEvent.showText(messages.component("party.invite.accept-hover")));
        target.sendMessage(messages.component("party.invite.accept-hint",
                        "seconds", settings.inviteLifetimeMillis() / 1000L)
                .append(Component.space())
                .append(acceptButton));
        sender.sendMessage(messages.component("party.invite.sent", "player", target.getName()));
    }

    public PartyView accept(Player player) {
        requireNoParty(player.getUniqueId());
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || invite.expiresAt < System.currentTimeMillis()) {
            throw new IllegalStateException(messages.plain("party.error.no-invite"));
        }
        Party party = parties.get(invite.partyId);
        if (party == null) throw new IllegalStateException(messages.plain("party.error.invite-disbanded"));
        if (party.members.size() >= settings.maxMembers()) {
            throw new IllegalStateException(messages.plain("party.error.full", "max", settings.maxMembers()));
        }
        party.members.add(player.getUniqueId());
        partyByMember.put(player.getUniqueId(), party.id);
        broadcast(party, messages.component("party.member.joined", "player", player.getName()));
        return party.view();
    }

    public void decline(Player player) {
        Invite invite = invites.remove(player.getUniqueId());
        if (invite == null || invite.expiresAt < System.currentTimeMillis()) {
            throw new IllegalStateException(messages.plain("party.error.no-invite"));
        }
        player.sendMessage(messages.component("party.invite.declined"));
    }

    public void remove(Player leader, UUID memberId) {
        Party party = requireLeader(leader.getUniqueId());
        if (party.leader.equals(memberId)) {
            throw new IllegalStateException(messages.plain("party.error.remove-self"));
        }
        if (!party.members.remove(memberId)) throw new IllegalStateException(messages.plain("party.error.not-member"));
        partyByMember.remove(memberId);
        String name = playerName(memberId);
        broadcast(party, messages.component("party.member.removed", "player", name));
        Player removed = Bukkit.getPlayer(memberId);
        if (removed != null) removed.sendMessage(messages.component("party.member.you-removed"));
    }

    public void transfer(Player leader, UUID newLeaderId) {
        Party party = requireLeader(leader.getUniqueId());
        if (party.leader.equals(newLeaderId)) throw new IllegalStateException(messages.plain("party.error.already-leader"));
        if (!party.members.contains(newLeaderId)) throw new IllegalStateException(messages.plain("party.error.not-member"));
        party.leader = newLeaderId;
        broadcast(party, messages.component("party.member.new-leader", "player", playerName(newLeaderId)));
    }

    public void leave(Player player) {
        Party party = requireParty(player.getUniqueId());
        UUID playerId = player.getUniqueId();
        if (party.members.size() == 1) {
            disbandInternal(party, messages.component("party.disbanded-empty"));
            return;
        }
        party.members.remove(playerId);
        partyByMember.remove(playerId);
        if (party.leader.equals(playerId)) {
            party.leader = party.members.iterator().next();
            broadcast(party, messages.component("party.member.new-leader", "player", playerName(party.leader)));
        }
        broadcast(party, messages.component("party.member.left", "player", player.getName()));
        player.sendMessage(messages.component("party.member.you-left"));
    }

    public void disband(Player leader) {
        Party party = requireLeader(leader.getUniqueId());
        disbandInternal(party, messages.component("party.disbanded"));
    }

    public Optional<PartyView> partyOf(UUID playerId) {
        UUID partyId = partyByMember.get(playerId);
        Party party = partyId == null ? null : parties.get(partyId);
        return party == null ? Optional.empty() : Optional.of(party.view());
    }

    public Optional<PartyView> partyById(UUID partyId) {
        Party party = parties.get(partyId);
        return party == null ? Optional.empty() : Optional.of(party.view());
    }

    public void chat(Player sender, String message) {
        Party party = requireParty(sender.getUniqueId());
        Component formatted = messages.component("party.chat", "player", sender.getName(), "message", message);
        broadcast(party, formatted);
    }

    public List<String> memberNames(PartyView party) {
        List<String> result = new ArrayList<>();
        for (UUID member : party.members()) result.add(playerName(member));
        return result;
    }

    private Party requireParty(UUID playerId) {
        UUID partyId = partyByMember.get(playerId);
        Party party = partyId == null ? null : parties.get(partyId);
        if (party == null) throw new IllegalStateException(messages.plain("party.error.no-party"));
        return party;
    }

    private Party requireLeader(UUID playerId) {
        Party party = requireParty(playerId);
        if (!party.leader.equals(playerId)) throw new IllegalStateException(messages.plain("party.error.leader-only"));
        return party;
    }

    private void requireNoParty(UUID playerId) {
        if (partyByMember.containsKey(playerId)) throw new IllegalStateException(messages.plain("party.error.already-in-party"));
    }

    private Party createParty(UUID creatorId) {
        Party party = new Party(UUID.randomUUID(), creatorId);
        parties.put(party.id, party);
        partyByMember.put(creatorId, party.id);
        return party;
    }

    private void disbandInternal(Party party, Component message) {
        broadcast(party, message);
        for (UUID member : party.members) partyByMember.remove(member);
        parties.remove(party.id);
        invites.entrySet().removeIf(entry -> entry.getValue().partyId.equals(party.id));
    }

    private void broadcast(Party party, Component message) {
        for (UUID member : party.members) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) player.sendMessage(message);
        }
    }

    private static String playerName(UUID playerId) {
        OfflinePlayer player = Bukkit.getOfflinePlayer(playerId);
        return player.getName() == null ? playerId.toString() : player.getName();
    }

    private static final class Party {
        private final UUID id;
        private UUID leader;
        private final LinkedHashSet<UUID> members = new LinkedHashSet<>();

        private Party(UUID id, UUID leader) {
            this.id = id;
            this.leader = leader;
            members.add(leader);
        }

        private PartyView view() {
            return new PartyView(id, leader, List.copyOf(members));
        }
    }

    private record Invite(UUID partyId, long expiresAt) { }

    private record InviteKey(UUID senderId, UUID targetId) { }

    public record PartyView(UUID id, UUID leader, List<UUID> members) {
        public boolean isLeader(UUID playerId) {
            return leader.equals(playerId);
        }

        public boolean contains(UUID playerId) {
            return members.contains(playerId);
        }
    }
}
