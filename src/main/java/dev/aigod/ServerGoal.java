package dev.aigod;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One communal objective for the whole server. Every player's kills, mining,
 * gathering, or stats count toward the same total. COLLECT and STAT progress is
 * measured against a per-player baseline captured the first time that player is
 * seen while the goal is active, then summed across players.
 */
final class ServerGoal {
    private final String challenge;
    private final Quest.Objective objective;
    private final String target;
    private final int amount;
    private final long day;
    private final long deadlineDayTime;
    private final String rewardCommand;
    private final String punishmentCommand;
    private final boolean trial;
    private int eventProgress;
    private Map<UUID, Integer> baselines = new HashMap<>();
    private Map<UUID, Integer> latest = new HashMap<>();
    private Map<UUID, Integer> eventCredits = new HashMap<>();

    ServerGoal(String challenge, Quest.Objective objective, String target, int amount,
               long day, long deadlineDayTime, String rewardCommand, String punishmentCommand,
               boolean trial) {
        this.challenge = challenge;
        this.objective = objective;
        this.target = target;
        this.amount = amount;
        this.day = day;
        this.deadlineDayTime = deadlineDayTime;
        this.rewardCommand = rewardCommand;
        this.punishmentCommand = punishmentCommand;
        this.trial = trial;
    }

    /**
     * Restores any map that a save file predating it left null. Gson allocates the goal
     * without running field initializers, so a goal already in flight when the server
     * updates would otherwise carry nulls into the first kill of the day.
     */
    void normalize() {
        if (baselines == null) baselines = new HashMap<>();
        if (latest == null) latest = new HashMap<>();
        if (eventCredits == null) eventCredits = new HashMap<>();
    }

    /** KILL and MINE contributions from any player. Returns true when progress moved. */
    boolean recordEvent(UUID playerId, Quest.Objective event, String eventTarget) {
        if (objective != event || !target.equals(eventTarget) || complete()) return false;
        eventProgress++;
        if (playerId != null) eventCredits.merge(playerId, 1, Integer::sum);
        return true;
    }

    /** COLLECT and STAT running totals per player. Returns true when progress moved. */
    boolean updateTotal(UUID playerId, int currentCount) {
        if (objective != Quest.Objective.COLLECT && objective != Quest.Objective.STAT) return false;
        baselines.putIfAbsent(playerId, currentCount);
        int before = progress();
        latest.put(playerId, currentCount);
        return progress() != before;
    }

    /**
     * How much each player contributed to the current progress, whatever the objective
     * type. KILL and MINE credit the player who caused the event; COLLECT and STAT use
     * each player's gain over their own baseline.
     */
    Map<UUID, Integer> contributions() {
        Map<UUID, Integer> result = new HashMap<>();
        if (objective == Quest.Objective.KILL || objective == Quest.Objective.MINE) {
            result.putAll(eventCredits);
            return result;
        }
        for (Map.Entry<UUID, Integer> entry : latest.entrySet()) {
            int gained = Math.max(0, entry.getValue() - baselines.getOrDefault(entry.getKey(), 0));
            if (gained > 0) result.put(entry.getKey(), gained);
        }
        return result;
    }

    /** Contributors ordered by how much they added, largest first. Ties break on UUID so the order is stable. */
    List<Map.Entry<UUID, Integer>> leaderboard() {
        List<Map.Entry<UUID, Integer>> entries = new ArrayList<>(contributions().entrySet());
        entries.sort(Comparator
                .comparingInt(Map.Entry<UUID, Integer>::getValue).reversed()
                .thenComparing(entry -> entry.getKey().toString()));
        return entries;
    }

    int progress() {
        if (objective == Quest.Objective.KILL || objective == Quest.Objective.MINE) {
            return Math.min(amount, eventProgress);
        }
        int sum = 0;
        for (Map.Entry<UUID, Integer> entry : latest.entrySet()) {
            sum += Math.max(0, entry.getValue() - baselines.getOrDefault(entry.getKey(), 0));
        }
        return Math.min(amount, sum);
    }

    boolean complete() {
        return progress() >= amount;
    }

    boolean expired(long nowDayTime) {
        return !complete() && nowDayTime >= deadlineDayTime;
    }

    String challenge() { return challenge; }
    Quest.Objective objective() { return objective; }
    String target() { return target; }
    int amount() { return amount; }
    long day() { return day; }
    long deadlineDayTime() { return deadlineDayTime; }
    boolean trial() { return trial; }
    String rewardCommand() { return rewardCommand; }
    String punishmentCommand() { return punishmentCommand; }
}
