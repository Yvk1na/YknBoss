package cn.yknboss.boss;

import java.util.ArrayDeque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Small shuffle-bag style selector shared by the scripted bosses. It remembers
 * recent casts, walks the whole ready pool before reusing one, and never chooses
 * the immediately preceding skill merely because every other skill is cooling
 * down.
 */
final class SkillRotation<S> {
    private final int memorySize;
    private final ArrayDeque<S> recent = new ArrayDeque<>();
    private int cursor;

    SkillRotation(int memorySize, int initialCursor) {
        this.memorySize = Math.max(1, memorySize);
        this.cursor = initialCursor;
    }

    S choose(List<S> options, Predicate<S> ready) {
        if (options.isEmpty()) return null;
        S selected = find(options, ready, true);
        if (selected == null) selected = find(options, ready, false);
        if (selected != null) remember(selected);
        return selected;
    }

    S last() {
        return recent.peekLast();
    }

    boolean recentlyUsed(S skill) {
        return skill != null && recent.contains(skill);
    }

    void remember(S skill) {
        if (skill == null) return;
        recent.remove(skill);
        recent.addLast(skill);
        while (recent.size() > memorySize) recent.removeFirst();
    }

    private S find(List<S> options, Predicate<S> ready, boolean requireFresh) {
        S last = last();
        int start = Math.floorMod(cursor, options.size());
        for (int offset = 0; offset < options.size(); offset++) {
            int index = (start + offset) % options.size();
            S candidate = options.get(index);
            if (!ready.test(candidate) || candidate.equals(last)) continue;
            if (requireFresh && recent.contains(candidate)) continue;
            cursor = (index + 1) % options.size();
            return candidate;
        }
        return null;
    }
}
