package cn.yknboss.ui;

import cn.yknboss.YknBossPlugin;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.RenderType;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scoreboard.Team;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the temporary per-raid sidebar and restores the exact scoreboard object
 * a player had before entering the instance. One board is shared by every
 * online viewer in the same raid, so health and DPS always agree for the party.
 */
public final class RaidSidebarService {
    private static final int MAX_LINES = 15;
    private static final int MAX_MEMBERS = 6;
    private static final String OBJECTIVE_NAME = "ykn_raid";
    private static final LegacyComponentSerializer LEGACY_SECTION =
            LegacyComponentSerializer.legacySection();

    private final YknBossPlugin plugin;
    private final Map<UUID, RaidView> sessions = new HashMap<>();
    private final Map<UUID, UUID> sessionByViewer = new HashMap<>();
    private SidebarSettings settings;
    private int nextRefreshTick;

    public RaidSidebarService(YknBossPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        settings = SidebarSettings.load(plugin);
        nextRefreshTick = Bukkit.getCurrentTick();
        if (!settings.enabled()) {
            closeAll();
            return;
        }
        for (RaidView view : sessions.values()) {
            render(view);
        }
    }

    /** Called by the plugin's existing one-tick scheduler. */
    public void tick() {
        if (!settings.enabled() || sessions.isEmpty()) return;
        int currentTick = Bukkit.getCurrentTick();
        if (currentTick < nextRefreshTick) return;
        nextRefreshTick = currentTick + settings.updateIntervalTicks();
        for (RaidView view : sessions.values()) render(view);
    }

    public void attach(UUID sessionId, Collection<UUID> participants, Collection<UUID> downPlayers, Player player,
                       int initialDurationTicks) {
        if (!settings.enabled() || sessionId == null || player == null || !player.isOnline()) return;
        UUID playerId = player.getUniqueId();
        UUID previousSessionId = sessionByViewer.get(playerId);
        if (previousSessionId != null && !previousSessionId.equals(sessionId)) detach(player);

        Scoreboard originalBoard = player.getScoreboard();
        RaidView view = sessions.computeIfAbsent(sessionId,
                ignored -> createView(sessionId, participants, initialDurationTicks, originalBoard));
        view.participants.clear();
        view.participants.addAll(participants);
        view.downPlayers.clear();
        view.downPlayers.addAll(downPlayers);
        if (view.state == State.WAITING) view.remainingTicks = Math.max(0, initialDurationTicks);

        if (!view.originalBoards.containsKey(playerId)) {
            view.originalBoards.put(playerId, originalBoard);
        }
        sessionByViewer.put(playerId, sessionId);
        if (player.getScoreboard() != view.scoreboard) player.setScoreboard(view.scoreboard);
        render(view);
    }

    public void setDown(UUID sessionId, UUID playerId, boolean down) {
        RaidView view = sessions.get(sessionId);
        if (view == null || playerId == null) return;
        if (down) view.downPlayers.add(playerId);
        else view.downPlayers.remove(playerId);
        render(view);
    }

    public void start(UUID sessionId, int durationTicks, Map<UUID, Double> cumulativeDamage) {
        RaidView view = sessions.get(sessionId);
        if (view == null) return;
        view.state = State.ACTIVE;
        view.remainingTicks = Math.max(0, durationTicks);
        view.samples.clear();
        view.dps.clear();
        sampleDamage(view, cumulativeDamage);
        render(view);
    }

    public void update(UUID sessionId, int remainingTicks, Map<UUID, Double> cumulativeDamage) {
        RaidView view = sessions.get(sessionId);
        if (view == null) return;
        if (view.state == State.WAITING) view.state = State.ACTIVE;
        view.remainingTicks = Math.max(0, remainingTicks);
        sampleDamage(view, cumulativeDamage);
        render(view);
    }

    public void settling(UUID sessionId, Map<UUID, Double> cumulativeDamage) {
        setFinishedState(sessionId, State.SETTLING, cumulativeDamage);
    }

    public void finish(UUID sessionId, boolean victory, Map<UUID, Double> cumulativeDamage) {
        setFinishedState(sessionId, victory ? State.VICTORY : State.FAILED, cumulativeDamage);
    }

    private void setFinishedState(UUID sessionId, State state, Map<UUID, Double> cumulativeDamage) {
        RaidView view = sessions.get(sessionId);
        if (view == null) return;
        sampleDamage(view, cumulativeDamage);
        view.state = state;
        render(view);
    }

    /** Restores one player without ending the raid, used for quit/manual teleport. */
    public void detach(Player player) {
        if (player == null) return;
        UUID playerId = player.getUniqueId();
        UUID sessionId = sessionByViewer.remove(playerId);
        if (sessionId == null) return;
        RaidView view = sessions.get(sessionId);
        if (view == null) return;
        Scoreboard original = view.originalBoards.remove(playerId);
        if (original != null && player.getScoreboard() == view.scoreboard) {
            player.setScoreboard(original);
        }
    }

    /** Ends a raid view and restores every online participant still using it. */
    public void close(UUID sessionId) {
        if (sessionId == null) return;
        RaidView view = sessions.remove(sessionId);
        if (view == null) return;
        for (Map.Entry<UUID, Scoreboard> entry : view.originalBoards.entrySet()) {
            sessionByViewer.remove(entry.getKey(), sessionId);
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && player.getScoreboard() == view.scoreboard) {
                player.setScoreboard(entry.getValue());
            }
        }
        view.originalBoards.clear();
        view.objective.unregister();
    }

    public void shutdown() {
        closeAll();
    }

    private void closeAll() {
        for (UUID sessionId : new ArrayList<>(sessions.keySet())) close(sessionId);
        sessionByViewer.clear();
    }

    private RaidView createView(UUID sessionId, Collection<UUID> participants, int initialDurationTicks,
                                Scoreboard chromeSource) {
        ScoreboardManager manager = Bukkit.getScoreboardManager();
        if (manager == null) throw new IllegalStateException("服务器计分板管理器尚未就绪");
        Scoreboard scoreboard = manager.getNewScoreboard();
        SidebarChrome chrome = captureChrome(chromeSource);
        Objective objective = scoreboard.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY,
                chrome.title(), RenderType.INTEGER);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        objective.numberFormat(NumberFormat.blank());
        return new RaidView(sessionId, scoreboard, objective, participants, initialDurationTicks, chrome);
    }

    private void sampleDamage(RaidView view, Map<UUID, Double> cumulativeDamage) {
        long now = System.nanoTime();
        Map<UUID, Double> sanitized = new HashMap<>();
        for (UUID participant : view.participants) {
            sanitized.put(participant, Math.max(0.0, cumulativeDamage.getOrDefault(participant, 0.0)));
        }
        view.samples.addLast(new DamageSample(now, Map.copyOf(sanitized)));

        long cutoff = now - settings.dpsWindowNanos();
        while (view.samples.size() > 2) {
            DamageSample oldest = view.samples.removeFirst();
            DamageSample next = view.samples.peekFirst();
            if (next != null && next.nanoTime() > cutoff) {
                view.samples.addFirst(oldest);
                break;
            }
        }

        DamageSample baseline = view.samples.peekFirst();
        DamageSample current = view.samples.peekLast();
        if (baseline == null || current == null) return;
        double elapsedSeconds = (current.nanoTime() - baseline.nanoTime()) / 1_000_000_000.0;
        if (elapsedSeconds < 0.25) return;
        for (UUID participant : view.participants) {
            double delta = current.damage().getOrDefault(participant, 0.0)
                    - baseline.damage().getOrDefault(participant, 0.0);
            // A recovered/replaced encounter may reset a cumulative source. Never
            // turn that reset into negative DPS on the player's sidebar.
            view.dps.put(participant, Math.max(0.0, delta / elapsedSeconds));
        }
    }

    private void render(RaidView view) {
        SidebarChrome chrome = view.chrome;
        view.objective.displayName(chrome.title());
        List<Component> lines = new ArrayList<>(MAX_LINES);
        int memberCount = Math.min(MAX_MEMBERS, view.participants.size());
        int decorativeSlots = Math.max(0, MAX_LINES - (5 + memberCount));
        lines.add(chrome.topLine());
        if (decorativeSlots-- > 0) lines.add(component(settings.separator()));
        lines.add(component(replace(settings.timeLine(), "time", displayedTime(view))));

        double teamDps = 0.0;
        for (UUID participant : view.participants) teamDps += view.dps.getOrDefault(participant, 0.0);
        lines.add(component(replace(settings.teamDpsLine(), "dps", compact(teamDps))));
        if (decorativeSlots-- > 0) lines.add(component(settings.blankLine()));
        lines.add(component(settings.teamHeading()));

        int count = 0;
        for (UUID participantId : view.participants) {
            if (count++ >= MAX_MEMBERS) break;
            Player participant = Bukkit.getPlayer(participantId);
            String name = shortenedName(participant == null ? Bukkit.getOfflinePlayer(participantId).getName()
                    : participant.getName());
            if (participant == null || !participant.isOnline()) {
                lines.add(component(replace(settings.offlineMemberLine(), "name", name)));
                continue;
            }
            if (view.downPlayers.contains(participantId)) {
                lines.add(component(replace(settings.downMemberLine(),
                        "name", name,
                        "dps", compact(view.dps.getOrDefault(participantId, 0.0)))));
                continue;
            }
            AttributeInstance maximumAttribute = participant.getAttribute(Attribute.MAX_HEALTH);
            double maximumHealth = maximumAttribute == null ? 20.0 : Math.max(1.0, maximumAttribute.getValue());
            double health = participant.isDead() ? 0.0
                    : Math.max(0.0, Math.min(maximumHealth, participant.getHealth()));
            double ratio = health / maximumHealth;
            String status = ratio > 0.6 ? "&a●" : ratio > 0.3 ? "&e●" : "&c●";
            String line = replace(settings.memberLine(),
                    "status", status,
                    "name", name,
                    "health", compact(health),
                    "max-health", compact(maximumHealth),
                    "dps", compact(view.dps.getOrDefault(participantId, 0.0)));
            lines.add(component(line));
        }
        if (decorativeSlots > 0) lines.add(component(settings.separator()));
        lines.add(chrome.footerLine());

        int visible = Math.min(MAX_LINES, lines.size());
        for (int index = 0; index < MAX_LINES; index++) {
            Score score = view.objective.getScore("ykn_" + index);
            if (index < visible) {
                score.customName(lines.get(index));
                score.setScore(MAX_LINES - index);
            } else if (score.isScoreSet()) {
                score.resetScore();
            }
        }

        for (UUID playerId : view.originalBoards.keySet()) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline() && player.getScoreboard() != view.scoreboard) {
                player.setScoreboard(view.scoreboard);
            }
        }
    }

    private String displayedTime(RaidView view) {
        return switch (view.state) {
            case WAITING -> settings.waitingTime();
            case ACTIVE -> formatDuration((view.remainingTicks + 19) / 20);
            case SETTLING -> settings.settlingTime();
            case VICTORY -> settings.victoryTime();
            case FAILED -> settings.failedTime();
        };
    }

    private Component component(String legacyText) {
        return plugin.messages().componentLiteral(legacyText);
    }

    private SidebarChrome captureChrome(Scoreboard source) {
        Component fallbackTitle = component(settings.title());
        Component fallbackTop = component(settings.topLine());
        Component fallbackFooter = component(settings.footerLine());
        if (source == null) return new SidebarChrome(fallbackTitle, fallbackTop, fallbackFooter);

        try {
            Objective original = source.getObjective(DisplaySlot.SIDEBAR);
            if (original == null) return new SidebarChrome(fallbackTitle, fallbackTop, fallbackFooter);

            List<Score> scores = source.getEntries().stream()
                    .map(original::getScore)
                    .filter(Score::isScoreSet)
                    .sorted(Comparator.comparingInt(Score::getScore).reversed()
                            .thenComparing(Score::getEntry))
                    .toList();
            Component title = original.displayName();
            Component top = scores.isEmpty() ? fallbackTop : renderedLine(source, scores.getFirst());
            Component footer = scores.isEmpty() ? fallbackFooter : renderedLine(source, scores.getLast());
            return new SidebarChrome(title, top, footer);
        } catch (IllegalStateException ignored) {
            return new SidebarChrome(fallbackTitle, fallbackTop, fallbackFooter);
        }
    }

    private static Component renderedLine(Scoreboard scoreboard, Score score) {
        Component customName = score.customName();
        if (customName != null) return customName;
        Component entry = LEGACY_SECTION.deserialize(score.getEntry());
        Team team = scoreboard.getEntryTeam(score.getEntry());
        if (team == null) return entry;
        return team.prefix().append(entry).append(team.suffix());
    }

    private String shortenedName(String name) {
        String safe = name == null || name.isBlank() ? "未知玩家" : name;
        int maximum = settings.nameMaxLength();
        int codePoints = safe.codePointCount(0, safe.length());
        if (codePoints <= maximum) return safe;
        int end = safe.offsetByCodePoints(0, Math.max(1, maximum - 1));
        return safe.substring(0, end) + "…";
    }

    private static String replace(String template, Object... replacements) {
        String value = template == null ? "" : template;
        for (int index = 0; index + 1 < replacements.length; index += 2) {
            value = value.replace("{" + replacements[index] + "}", String.valueOf(replacements[index + 1]));
        }
        return value;
    }

    private static String formatDuration(int seconds) {
        int clamped = Math.max(0, seconds);
        return String.format(Locale.ROOT, "%d:%02d", clamped / 60, clamped % 60);
    }

    private static String compact(double value) {
        double safe = Math.max(0.0, value);
        if (safe < 1_000.0) return Long.toString(Math.round(safe));
        if (safe < 1_000_000.0) return scaled(safe / 1_000.0, "k");
        if (safe < 1_000_000_000.0) return scaled(safe / 1_000_000.0, "m");
        return scaled(safe / 1_000_000_000.0, "b");
    }

    private static String scaled(double value, String suffix) {
        return (value < 10.0 ? String.format(Locale.ROOT, "%.1f", value)
                : Long.toString(Math.round(value))) + suffix;
    }

    private enum State { WAITING, ACTIVE, SETTLING, VICTORY, FAILED }

    private record DamageSample(long nanoTime, Map<UUID, Double> damage) { }

    private record SidebarChrome(Component title, Component topLine, Component footerLine) { }

    private static final class RaidView {
        private final UUID sessionId;
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final SidebarChrome chrome;
        private final LinkedHashSet<UUID> participants = new LinkedHashSet<>();
        private final LinkedHashSet<UUID> downPlayers = new LinkedHashSet<>();
        private final Map<UUID, Scoreboard> originalBoards = new HashMap<>();
        private final Deque<DamageSample> samples = new ArrayDeque<>();
        private final Map<UUID, Double> dps = new HashMap<>();
        private State state = State.WAITING;
        private int remainingTicks;

        private RaidView(UUID sessionId, Scoreboard scoreboard, Objective objective,
                         Collection<UUID> participants, int remainingTicks, SidebarChrome chrome) {
            this.sessionId = sessionId;
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.chrome = chrome;
            this.participants.addAll(participants);
            this.remainingTicks = Math.max(0, remainingTicks);
        }
    }

    private record SidebarSettings(boolean enabled, int updateIntervalTicks, long dpsWindowNanos,
                                   int nameMaxLength, String title, String topLine, String footerLine,
                                   String separator,
                                   String timeLine, String teamDpsLine, String blankLine,
                                   String teamHeading, String memberLine, String downMemberLine,
                                   String offlineMemberLine,
                                   String waitingTime, String settlingTime, String victoryTime,
                                   String failedTime) {
        private static SidebarSettings load(YknBossPlugin plugin) {
            var root = plugin.getConfig().getConfigurationSection("raid-sidebar");
            boolean enabled = root == null || root.getBoolean("enabled", true);
            int updateTicks = root == null ? 10 : Math.max(2, Math.min(100,
                    root.getInt("update-interval-ticks", 10)));
            double dpsWindowSeconds = root == null ? 10.0 : Math.max(1.0, Math.min(60.0,
                    root.getDouble("dps-window-seconds", 10.0)));
            int nameLength = root == null ? 10 : Math.max(3, Math.min(16,
                    root.getInt("name-max-length", 10)));
            return new SidebarSettings(enabled, updateTicks,
                    Math.max(1L, Math.round(dpsWindowSeconds * 1_000_000_000.0)), nameLength,
                    text(root, "title", "&b&lSKYBLOCK"),
                    text(root, "top-line", "&7副本挑战"),
                    text(root, "footer-line", "&emc.infnetwork.net"),
                    text(root, "separator", "&8&m--------------------"),
                    text(root, "time-line", "&b⌛ &f挑战剩余 &b&l{time}"),
                    text(root, "team-dps-line", "&6⚔ &f团队 DPS &e&l{dps}"),
                    text(root, "blank-line", "&r"),
                    text(root, "team-heading", "&d❤ &f队伍状态"),
                    text(root, "member-line", "{status} &f{name} &c{health}&7/{max-health}❤ &8│ &e{dps}⚔"),
                    text(root, "down-member-line", "&c✖ &f{name} &c倒地 &8│ &e{dps}⚔"),
                    text(root, "offline-member-line", "&8○ &7{name} &8离线"),
                    text(root, "waiting-time", "&e等待召唤"),
                    text(root, "settling-time", "&e结算中"),
                    text(root, "victory-time", "&6挑战完成"),
                    text(root, "failed-time", "&c挑战失败"));
        }

        private static String text(org.bukkit.configuration.ConfigurationSection root,
                                   String path, String fallback) {
            return root == null ? fallback : root.getString(path, fallback);
        }
    }
}
