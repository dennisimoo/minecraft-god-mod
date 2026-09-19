package dev.aigod;

import com.google.gson.JsonObject;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Runs the one communal goal per Minecraft day. At dawn the god is asked to set
 * today's goal; every player's contributions pool into it until sundown. Failed
 * API calls retry once a minute. Quarter-progress milestones are announced, and
 * completion or sundown failure is handed back to the god.
 */
final class DailyChallengeManager {
    private static final int RETRY_TICKS = 1_200;
    private static final long WORLD_EVENT_INTERVAL_MILLIS = 3_600_000;

    private final MinecraftServer server;
    private final GodService god;
    private final DailyStore store;
    private final DailyStore.State state;
    private final ServerBossEvent bossBar = new ServerBossEvent(
            UUID.nameUUIDFromBytes("ai-god-daily-goal".getBytes(StandardCharsets.UTF_8)),
            Component.literal("server goal"), BossEvent.BossBarColor.YELLOW, BossEvent.BossBarOverlay.PROGRESS);
    private boolean pending;
    private int lastAttemptTick = -RETRY_TICKS;
    private int lastQuarter;
    private int ticks;

    DailyChallengeManager(MinecraftServer server, GodService god, DailyStore store) {
        this.server = server;
        this.god = god;
        this.store = store;
        this.state = store.load();
        ServerGoal goal = state.activeGoal;
        this.lastQuarter = goal == null || goal.amount() == 0 ? 0 : goal.progress() * 4 / goal.amount();
    }

    private record Chapter(String name, String milestone, String hint) {}

    private static final Chapter[] CHAPTERS = {
        new Chapter("Foothold", "minecraft:story/smelt_iron",
                "the server needs iron tools and armor; steer goals toward mining and smelting"),
        new Chapter("Deep Delving", "minecraft:story/mine_diamond",
                "push the server toward diamonds and enchanting; deeper mining and gear"),
        new Chapter("The Nether", "minecraft:nether/obtain_blaze_rod",
                "drive the server into the Nether for blaze rods and nether loot"),
        new Chapter("The Hunt", "minecraft:story/follow_ender_eye",
                "gather ender pearls and blaze powder, craft eyes of ender, find the stronghold"),
        new Chapter("The End", "minecraft:end/kill_dragon",
                "prepare the raid on the End and the Ender Dragon"),
        new Chapter("Beyond", "minecraft:nether/summon_wither",
                "the dragon is dead; invent ever harder endgame arcs (wither, elytra, beacons)"),
    };

    private Chapter chapter() {
        return CHAPTERS[Math.min(state.chapter - 1, CHAPTERS.length - 1)];
    }

    int chapterNumber() {
        return state.chapter;
    }

    String chapterName() {
        return chapter().name();
    }

    /** Chapter framing handed to the god at dawn. */
    String chapterBrief() {
        Chapter chapter = chapter();
        return "The server stands in Chapter %d of its saga: %s, with a %d-day win streak. %s. Higher chapters mean harder goals, so scale today's count and rarity to Chapter %d, clearly tougher than earlier chapters."
                .formatted(state.chapter, chapter.name(), state.winStreak,
                        chapter.hint(), state.chapter);
    }

    /** Catches an existing world up and advances when the current milestone is completed. */
    boolean syncAdvancements(Set<String> advancementIds) {
        int previous = state.chapter;
        while (state.chapter < CHAPTERS.length
                && advancementIds.contains(chapter().milestone())) {
            state.chapter++;
        }
        if (state.chapter == previous) return false;
        store.save(state);
        god.chapterAdvanced(state.chapter, chapter().name(), List.copyOf(state.relics));
        return true;
    }

    boolean claimWorldEvent(long nowMillis) {
        if (state.nextWorldEventAtMillis == 0) {
            state.nextWorldEventAtMillis = nowMillis + WORLD_EVENT_INTERVAL_MILLIS;
            store.save(state);
            return false;
        }
        if (nowMillis < state.nextWorldEventAtMillis) return false;
        state.nextWorldEventAtMillis = nowMillis + WORLD_EVENT_INTERVAL_MILLIS;
        store.save(state);
        return true;
    }

    /** Records a relic name the god forged at a chapter boundary. */
    void addRelic(String name) {
        state.relics.add(name);
        while (state.relics.size() > 24) state.relics.remove(0);
        store.save(state);
    }

    void tick() {
        if (++ticks % 20 != 0) return;
        long now = server.overworld().getOverworldClockTime();
        ServerGoal goal = state.activeGoal;
        if (goal != null) {
            boolean changed = pollTotals(goal);
            if (goal.complete()) {
                finish(goal, true);
            } else if (goal.expired(now)) {
                finish(goal, false);
            } else {
                updateBossBar(goal, now);
                if (changed) {
                    announceMilestone(goal);
                    store.save(state);
                }
            }
            return;
        }
        long today = DayCycle.day(now);
        if (!DayCycle.beforeSundown(now) || state.lastIssuedDay >= today || pending) return;
        if (ticks - lastAttemptTick < RETRY_TICKS) return;
        ServerPlayer speaker = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (speaker == null) return;
        lastAttemptTick = ticks;
        pending = true;
        god.requestDailyGoal(speaker, DayCycle.sundownOf(now), today, today > 0 && today % 7 == 0,
                chapterBrief(), List.copyOf(state.pastGoals),
                () -> pending = false,
                () -> pending = false);
    }

    /** Called by GodService when the god sets today's goal via create_daily_goal. */
    ServerGoal createGoal(JsonObject arguments, long deadlineDayTime, long day, boolean trial) {
        if (state.activeGoal != null) {
            throw new IllegalArgumentException("Today's server goal already exists.");
        }
        Quest.Objective objective = Quest.Objective.valueOf(required(arguments, "objective"));
        String target = objective == Quest.Objective.STAT
                ? required(arguments, "target")
                : QuestManager.normalizedId(required(arguments, "target"));
        QuestManager.validateTarget(objective, target);
        int amount = arguments.get("amount").getAsInt();
        if (amount < 1) throw new IllegalArgumentException("amount must be positive");
        ServerGoal goal = new ServerGoal(
                required(arguments, "challenge"), objective, target, amount, day, deadlineDayTime,
                command(arguments, "reward_command"), command(arguments, "punishment_command"), trial);
        pollTotals(goal);
        state.activeGoal = goal;
        state.remember(day, goal.challenge());
        lastQuarter = 0;
        store.save(state);
        return goal;
    }

    void recordKill(UUID playerId, String entityId) {
        recordEvent(playerId, Quest.Objective.KILL, entityId);
    }

    void recordMine(UUID playerId, String blockId) {
        recordEvent(playerId, Quest.Objective.MINE, blockId);
    }

    String statusLine() {
        ServerGoal goal = state.activeGoal;
        if (goal == null) return "no server goal is active right now";
        long ticksLeft = Math.max(0, goal.deadlineDayTime() - server.overworld().getOverworldClockTime());
        return "%s — %d/%d %s; %d game ticks until sundown; every player's contributions count together"
                .formatted(goal.challenge(), goal.progress(), goal.amount(),
                        QuestManager.prettyTarget(goal.target()), ticksLeft);
    }

    JsonObject adminState() {
        JsonObject value = new JsonObject();
        ServerGoal goal = state.activeGoal;
        value.addProperty("active", goal != null);
        value.addProperty("chapter", state.chapter);
        value.addProperty("chapter_name", chapter().name());
        value.addProperty("win_streak", state.winStreak);
        value.addProperty("next_world_event_in_seconds", state.nextWorldEventAtMillis == 0 ? 3_600
                : Math.max(0, (state.nextWorldEventAtMillis - System.currentTimeMillis()) / 1_000));
        if (goal == null) return value;
        value.addProperty("challenge", goal.challenge());
        value.addProperty("objective", goal.objective().name().toLowerCase());
        value.addProperty("target", QuestManager.prettyTarget(goal.target()));
        value.addProperty("progress", goal.progress());
        value.addProperty("amount", goal.amount());
        value.addProperty("ticks_left", Math.max(0,
                goal.deadlineDayTime() - server.overworld().getOverworldClockTime()));
        com.google.gson.JsonArray contributors = new com.google.gson.JsonArray();
        for (java.util.Map.Entry<UUID, Integer> entry : goal.leaderboard()) {
            String name = playerName(entry.getKey());
            if (name == null) continue;
            JsonObject row = new JsonObject();
            row.addProperty("player", name);
            row.addProperty("amount", entry.getValue());
            contributors.add(row);
        }
        value.add("contributors", contributors);
        return value;
    }

    /**
     * One short clause naming who contributed most, for the goal-completion prompt.
     * Empty when nobody is attributable, so goals finished before this shipped, or by
     * players who have since logged off, simply read as they did before.
     */
    String contributionLine(ServerGoal goal) {
        List<java.util.Map.Entry<UUID, Integer>> board = goal.leaderboard();
        if (board.isEmpty()) return "";
        java.util.Map.Entry<UUID, Integer> top = board.get(0);
        String name = playerName(top.getKey());
        if (name == null) return "";
        if (board.size() > 1 && board.get(1).getValue().equals(top.getValue())) {
            return " %s and %s tied for the most contributed (%d %s each); mention that in one short clause."
                    .formatted(name, playerName(board.get(1).getKey()) == null ? "another player"
                            : playerName(board.get(1).getKey()), top.getValue(),
                            QuestManager.prettyTarget(goal.target()));
        }
        return " %s contributed the most (%d of %d %s); mention that in one short clause, no extra ceremony."
                .formatted(name, top.getValue(), goal.amount(), QuestManager.prettyTarget(goal.target()));
    }

    private String playerName(UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        return player == null ? null : player.getName().getString();
    }

    private void recordEvent(UUID playerId, Quest.Objective objective, String target) {
        ServerGoal goal = state.activeGoal;
        if (goal != null && goal.recordEvent(playerId, objective, target)) {
            announceMilestone(goal);
            store.save(state);
        }
    }

    private boolean pollTotals(ServerGoal goal) {
        if (goal.objective() != Quest.Objective.COLLECT && goal.objective() != Quest.Objective.STAT) {
            return false;
        }
        boolean changed = false;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            int current = goal.objective() == Quest.Objective.COLLECT
                    ? QuestManager.count(player, goal.target())
                    : player.getStats().getValue(QuestManager.resolveStat(goal.target()));
            changed |= goal.updateTotal(player.getUUID(), current);
        }
        return changed;
    }

    private void announceMilestone(ServerGoal goal) {
        if (goal.amount() == 0 || goal.complete()) return;
        int quarter = goal.progress() * 4 / goal.amount();
        if (quarter <= lastQuarter) return;
        lastQuarter = quarter;
        server.getPlayerList().broadcastSystemMessage(Component.literal(
                "§eserver goal: %d/%d %s".formatted(
                        goal.progress(), goal.amount(), QuestManager.prettyTarget(goal.target()))), false);
    }

    static String bossBarLabel(ServerGoal goal) {
        String action = switch (goal.objective()) {
            case KILL -> "Kill";
            case MINE -> "Mine";
            case COLLECT -> "Collect";
            case STAT -> "Reach";
        };
        return "%s%s  •  %d/%d".formatted(goal.trial() ? "Trial: " : "",
                action + " " + QuestManager.prettyTarget(goal.target()),
                goal.progress(), goal.amount());
    }

    /** Keeps the HUD boss bar naming the goal, tracking progress, and reddening toward sundown. */
    private void updateBossBar(ServerGoal goal, long now) {
        bossBar.setName(Component.literal(bossBarLabel(goal)));
        bossBar.setProgress(goal.amount() == 0 ? 0.0F
                : Math.min(1.0F, (float) goal.progress() / goal.amount()));
        long ticksLeft = Math.max(0, goal.deadlineDayTime() - now);
        bossBar.setColor(ticksLeft > 6_000 ? BossEvent.BossBarColor.GREEN
                : ticksLeft > 2_000 ? BossEvent.BossBarColor.YELLOW
                : BossEvent.BossBarColor.RED);
        for (ServerPlayer player : new ArrayList<>(bossBar.getPlayers())) {
            if (player.hasDisconnected()) bossBar.removePlayer(player);
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            bossBar.addPlayer(player);
        }
    }

    private void finish(ServerGoal goal, boolean succeeded) {
        state.activeGoal = null;
        state.winStreak = succeeded ? state.winStreak + 1 : 0;
        store.save(state);
        bossBar.removeAllPlayers();
        if (succeeded) {
            server.getPlayerList().broadcastSystemMessage(
                    Component.literal("§6server goal complete. the reward is granted to all"), false);
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                god.quests().runOperatorCommand(goal.rewardCommand(), player);
            }
            god.goalCompleted(goal, state.winStreak);
        } else {
            god.goalFailed(goal);
        }
    }

    private static String required(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).getAsString().isBlank()) {
            throw new IllegalArgumentException("Missing " + key);
        }
        return object.get(key).getAsString();
    }

    private static String command(JsonObject object, String key) {
        if (!object.has(key)) return "";
        String command = object.get(key).getAsString().strip();
        return command.startsWith("/") ? command.substring(1) : command;
    }
}
