package dev.aigod;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.tree.CommandNode;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.PermissionSet;
import net.minecraft.stats.Stats;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class GodService implements AutoCloseable {
    private final MinecraftServer server;
    private final String godName;
    private final OpenAiGodClient client;
    private final QuestManager quests;
    private final DailyChallengeManager daily;
    private final ConversationStore conversationStore;
    private final ScheduleStore scheduleStore;
    private final ArrayDeque<ChatTurn> queue = new ArrayDeque<>();
    private final List<DeferredCommand> deferredCommands = new ArrayList<>();
    private final List<ScheduledEvent> scheduledEvents = new ArrayList<>();
    private final Map<UUID, Set<String>> completedAdvancements = new HashMap<>();
    private final Set<UUID> lowHealthPlayers = new HashSet<>();
    private final Map<UUID, Integer> chatCounts = new HashMap<>();
    private String conversationId;
    private boolean processing;
    private int ticks;
    private volatile String adminState = "{\"players\":[],\"scheduled_events\":[]}";

    GodService(MinecraftServer server, String apiKey, String model, String godName, int compactThreshold,
               QuestStore questStore, DailyStore dailyStore, ConversationStore conversationStore,
               ScheduleStore scheduleStore) {
        this.server = server;
        this.godName = godName;
        this.client = new OpenAiGodClient(apiKey, model, godName, compactThreshold);
        this.quests = new QuestManager(server, questStore);
        this.daily = new DailyChallengeManager(server, this, dailyStore);
        this.conversationStore = conversationStore;
        this.scheduleStore = scheduleStore;
        this.scheduledEvents.addAll(scheduleStore.load());
        this.conversationId = conversationStore.load();
    }

    void hear(ServerPlayer player, String message) {
        long nowMillis = System.currentTimeMillis();
        chatCounts.merge(player.getUUID(), 1, Integer::sum);
        broadcastChat(Component.literal("<" + player.getGameProfile().name() + "> " + message));
        ChatTurn pending = queue.peekLast();
        if (pending == null || pending.systemEvent || pending.started
                || !pending.playerId.equals(player.getUUID()) || !pending.isRecent(nowMillis)) {
            queue.addLast(new ChatTurn(player.getUUID(), message, nowMillis));
        } else {
            pending.appendMessage(message, nowMillis);
        }
        processNext();
    }

    void recordKill(ServerPlayer player, String entityId, String victimName) {
        quests.recordKill(player, entityId, victimName);
        daily.recordKill(player.getUUID(), entityId);
    }

    void recordMine(ServerPlayer player, String blockId) {
        quests.recordMine(player, blockId);
        daily.recordMine(player.getUUID(), blockId);
    }

    void requestDailyGoal(ServerPlayer speaker, long deadlineDayTime, long day, boolean trial,
                          String chapterBrief, List<String> pastGoals, Runnable onIssued, Runnable onFailed) {
        String history = pastGoals.isEmpty()
                ? "This is the server's first daily goal ever; make it a memorable initiation."
                : "Recent daily goals, oldest first, which you must NOT repeat or closely echo:\n- "
                        + String.join("\n- ", pastGoals);
        String brief = trial
                ? """
                  Today is a TRIAL DAY, the seventh-day reckoning. First STAGE the trial with
                  run_command: summon a themed boss encounter near the players (a wither, or elite
                  waves of custom-named mobs) that fits the world's current arc. Then set the goal
                  with create_daily_goal using a KILL objective matching what you summoned. Trials
                  deserve a far richer reward and a far harsher failure than an ordinary day.
                  """
                : """
                  Set today's ONE server-wide goal with create_daily_goal now. Make it a clear next
                  step in the world's long survival arc toward defeating the Ender Dragon, sized
                  for everyone online, fun, hard, and achievable before sundown. Base it on the
                  players' actual biomes, dimensions, equipment, and nearby world shown in live
                  state.
                  """;
        ChatTurn turn = new ChatTurn(speaker.getUUID(), """
                A new Minecraft day dawns (server day %d, %d players online). %s
                %s
                The native boss bar will keep the goal visible, so do not announce it again or
                repeat it in your reply.
                %s
                """.formatted(day, server.getPlayerCount(), chapterBrief.strip(), brief.strip(), history));
        turn.systemEvent = true;
        turn.silent = true;
        turn.goalDeadline = deadlineDayTime;
        turn.goalDay = day;
        turn.goalTrial = trial;
        turn.onSuccess = onIssued;
        turn.onFailure = onFailed;
        queue.addLast(turn);
        processNext();
    }

    void chapterAdvanced(int chapter, String name, java.util.List<String> relics) {
        ServerPlayer speaker = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (speaker == null) return;
        quests.runOperatorCommand("title @a times 20 90 30", speaker);
        quests.runOperatorCommand("title @a subtitle {\"text\":\"Chapter " + chapter + ": " + name
                + "\",\"color\":\"aqua\"}", speaker);
        quests.runOperatorCommand("title @a title {\"text\":\"ASCENSION\",\"color\":\"gold\",\"bold\":true}", speaker);
        quests.runOperatorCommand("playsound minecraft:ui.toast.challenge_complete master @a", speaker);
        String owned = relics.isEmpty() ? "none yet" : String.join(", ", relics);
        ChatTurn turn = new ChatTurn(speaker.getUUID(), ("""
                The server has ASCENDED to Chapter %d: %s. Mark it with the forge_relic tool: give
                every online player one renamed, enchanted trophy worthy of this chapter, with a
                unique name. Relics already forged: %s. Do not repeat a relic name.
                """).formatted(chapter, name, owned));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    void goalCompleted(ServerGoal goal, int winStreak) {
        ServerPlayer speaker = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (speaker == null) return;
        String milestone = winStreak % 3 == 0
                ? " This is a %d-day win streak milestone. Grant one fitting communal boon with run_command.".formatted(winStreak)
                : " The server's win streak is now " + winStreak + ".";
        ChatTurn turn = new ChatTurn(speaker.getUUID(), ("""
                The server COMPLETED today's goal: "%s" (%d %s). The stored reward already ran for
                every online player. Celebrate briefly in your voice; a little spectacle (particles,
                a triumphant sound) is welcome. Do not repeat the reward.%s%s
                """).formatted(goal.challenge(), goal.amount(), goal.target(), milestone,
                        daily.contributionLine(goal)));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    void goalFailed(ServerGoal goal) {
        ServerPlayer speaker = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (speaker == null) return;
        ChatTurn turn = new ChatTurn(speaker.getUUID(), """
                The sun has set and the server FAILED today's goal: "%s" (progress %d/%d %s).
                Deliver a fitting consequence to EVERYONE online right now using run_command,
                matched to the goal they failed. Then tell them what happened naturally in one
                short sentence. Do not use fantasy narration, proclamations, or judgment language.
                """.formatted(goal.challenge(), goal.progress(), goal.amount(), goal.target()));
        turn.systemEvent = true;
        turn.onFailure = () -> {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                quests.runOperatorCommand(goal.punishmentCommand(), player);
            }
        };
        queue.addLast(turn);
        processNext();
    }

    void playerDied(ServerPlayer player, String deathMessage) {
        ChatTurn turn = new ChatTurn(player.getUUID(), """
                %s just died: "%s". React as you see fit: mock them, mourn them, avenge them,
                punish whatever killed them, or stay_silent if this death bores you.
                """.formatted(player.getGameProfile().name(), deathMessage));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    void playerJoined(ServerPlayer player) {
        Set<String> advancements = currentAdvancements(player);
        completedAdvancements.put(player.getUUID(), advancements);
        daily.syncAdvancements(advancements);
        boolean firstJoin = player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) == 0;
        ChatTurn turn = new ChatTurn(player.getUUID(), firstJoin
                ? """
                  %s has joined this world for the first time. Give them a short, tailored,
                  in-character introduction. In at most two sentences, make clear that they can
                  speak normally and that you can act on the world with every server command.
                  """.formatted(player.getGameProfile().name())
                : """
                  %s has returned to the world. Welcome them back in one short, contextual,
                  in-character sentence using your shared memory and the live state.
                  """.formatted(player.getGameProfile().name()));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    void tick() {
        quests.tick();
        daily.tick();
        ticks++;
        runDeferredCommands();
        runScheduledEvents();
        processNext();
        if (ticks % 20 == 0) {
            detectAdvancements();
            detectLowHealth();
            if (server.getPlayerCount() > 0 && daily.claimWorldEvent(System.currentTimeMillis())) {
                requestWorldEvent();
            }
            refreshAdminState();
        }
    }

    String adminState() {
        return adminState;
    }

    QuestManager quests() {
        return quests;
    }

    private void processNext() {
        if (processing) return;
        ChatTurn turn = queue.peekFirst();
        if (turn == null) return;
        if (!turn.systemEvent && !turn.ready(System.currentTimeMillis())) return;
        ServerPlayer player = server.getPlayerList().getPlayer(turn.playerId);
        if (player == null) {
            if (turn.onFailure != null) turn.onFailure.run();
            queue.removeFirst();
            processNext();
            return;
        }
        processing = true;
        turn.started = true;
        turn.baseConversationId = conversationId;
        String input = snapshot(player, turn);
        client.respond(player.getUUID(), input, conversationId)
                .whenComplete((response, error) -> server.execute(() -> handle(turn, response, error)));
    }

    private void handle(ChatTurn turn, OpenAiGodClient.ResponseTurn response, Throwable error) {
        ServerPlayer player = server.getPlayerList().getPlayer(turn.playerId);
        if (error != null || player == null) {
            conversationId = turn.baseConversationId;
            if (player != null) {
                Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                if (!turn.systemEvent) {
                    player.sendSystemMessage(Component.literal("§cThe AI God cannot answer: "
                            + (cause == null ? "speaker vanished" : cause.getMessage())));
                }
            }
            if (turn.onFailure != null) turn.onFailure.run();
            finishTurn();
            return;
        }

        conversationId = response.conversationId();
        conversationStore.save(conversationId);
        boolean requestsSilence = response.toolCalls().stream()
                .anyMatch(call -> call.name().equals("stay_silent"));
        boolean createsQuest = response.toolCalls().stream()
                .anyMatch(call -> call.name().equals("create_challenge") || call.name().equals("create_daily_goal"));
        if (!response.message().isBlank() && !turn.silent && !requestsSilence && !createsQuest) {
            say(response.message());
        }

        if (response.toolCalls().isEmpty()) {
            if (turn.onSuccess != null) turn.onSuccess.run();
            finishTurn();
            return;
        }

        List<OpenAiGodClient.ToolResult> results = new ArrayList<>();
        for (OpenAiGodClient.ToolCall call : response.toolCalls()) {
            results.add(new OpenAiGodClient.ToolResult(call.callId(), execute(turn, player, call)));
        }
        client.continueWithTools(player.getUUID(), conversationId, results)
                .whenComplete((next, nextError) -> server.execute(() -> handle(turn, next, nextError)));
    }

    private String execute(ChatTurn turn, ServerPlayer player, OpenAiGodClient.ToolCall call) {
        try {
            return switch (call.name()) {
                case "run_command" -> runOperatorCommand(call.arguments(), player);
                case "command_help" -> commandHelp(call.arguments(), player);
                case "show_text" -> showText(call.arguments(), player);
                case "inspect_view" -> inspectView(player);
                case "schedule_event" -> scheduleEvent(call.arguments(), player);
                case "cancel_scheduled_event" -> cancelScheduledEvent(call.arguments());
                case "create_challenge" -> createQuest(turn, call.arguments(), player);
                case "create_daily_goal" -> createDailyGoal(turn, call.arguments(), player);
                case "complete_challenge" -> completeChallenge(call.arguments());
                case "forge_relic" -> forgeRelic(call.arguments(), player);
                case "cancel_challenge" -> cancelQuest(call.arguments());
                case "stay_silent" -> {
                    turn.silent = true;
                    yield "Silence selected. No chat message will be posted for this turn.";
                }
                default -> "error: unknown tool " + call.name();
            };
        } catch (RuntimeException exception) {
            return "error: " + exception.getMessage();
        }
    }

    private String runOperatorCommand(JsonObject arguments, ServerPlayer player) {
        String command = arguments.get("command").getAsString().strip();
        if (command.startsWith("/")) command = command.substring(1);
        command = command.replace("{player}", player.getGameProfile().name());
        CommandOutcome outcome = runCommand(command, player);
        if (!outcome.known()) return "ok: command accepted for execution: " + command;
        return outcome.succeeded()
                ? "ok: command returned %d: %s".formatted(outcome.value(), command)
                : "error: Minecraft reported command failure: " + command;
    }

    private String commandHelp(JsonObject arguments, ServerPlayer player) {
        String name = arguments.get("command").getAsString().strip();
        CommandDispatcher<CommandSourceStack> dispatcher = server.getCommands().getDispatcher();
        CommandSourceStack source = operatorSource(player);
        if (name.isEmpty()) {
            return dispatcher.getRoot().getChildren().stream()
                    .filter(node -> node.canUse(source))
                    .map(CommandNode::getName)
                    .sorted()
                    .toList()
                    .toString();
        }
        CommandNode<CommandSourceStack> node = dispatcher.getRoot().getChild(name);
        if (node == null || !node.canUse(source)) return "error: no available root command named " + name;
        String[] usage = dispatcher.getAllUsage(node, source, true);
        if (usage.length == 0) return name;
        return name + " " + String.join("\n" + name + " ", usage);
    }

    private String showText(JsonObject arguments, ServerPlayer player) {
        String text = MinecraftChatText.fromModel(arguments.get("text").getAsString());
        if (text.isBlank()) return "error: text cannot be blank";
        String color = arguments.get("color").getAsString();
        String tag = "ai_god_text_" + UUID.randomUUID().toString().substring(0, 8);
        runCommand("kill @e[type=minecraft:text_display,tag=ai_god_text,distance=..16]", player);
        String command = "execute anchored eyes positioned ^ ^1 ^5 run summon minecraft:text_display ~ ~ ~ "
                + "{billboard:\"center\",text:{text:%s,color:\"%s\"},shadow:false,background:0,"
                + "see_through:true,line_width:160,Tags:[\"ai_god_text\",\"%s\"]}"
                .formatted(new JsonPrimitive(text).toString(), color, tag);
        CommandOutcome outcome = runCommand(command, player);
        if (!outcome.known() || outcome.succeeded()) {
            deferredCommands.add(new DeferredCommand(ticks + 20 * 12, "kill @e[tag=" + tag + "]"));
        }
        return !outcome.known() || outcome.succeeded()
                ? "ok: subtle floating text created; it disappears automatically after 12 seconds"
                : "error: Minecraft could not create the text display";
    }

    private String inspectView(ServerPlayer player) {
        HitResult hit = player.pick(32, 1, false);
        String lookingAt = "nothing within 32 blocks";
        if (hit instanceof BlockHitResult blockHit && hit.getType() == HitResult.Type.BLOCK) {
            var position = blockHit.getBlockPos();
            lookingAt = BuiltInRegistries.BLOCK.getKey(player.level().getBlockState(position).getBlock())
                    + " at " + position.getX() + " " + position.getY() + " " + position.getZ();
        }
        List<String> nearby = player.level().getEntities(player, player.getBoundingBox().inflate(16)).stream()
                .limit(20)
                .map(entity -> BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()) + " at "
                        + "%.0f %.0f %.0f".formatted(entity.getX(), entity.getY(), entity.getZ()))
                .toList();
        return "looking_at=" + lookingAt + "; nearby_entities=" + nearby;
    }

    private String scheduleEvent(JsonObject arguments, ServerPlayer player) {
        if (scheduledEvents.size() >= 64) return "error: the server already has 64 scheduled events";
        int delay = arguments.get("delay_seconds").getAsInt();
        int repeat = arguments.get("repeat_seconds").getAsInt();
        if (delay < 1 || delay > 86_400) return "error: delay_seconds must be between 1 and 86400";
        if (repeat != 0 && (repeat < 10 || repeat > 86_400)) {
            return "error: repeat_seconds must be 0 or between 10 and 86400";
        }
        String instruction = arguments.get("instruction").getAsString().strip();
        if (instruction.isBlank()) return "error: instruction cannot be blank";
        String id = UUID.randomUUID().toString().substring(0, 8);
        scheduledEvents.add(new ScheduledEvent(id, player.getUUID(), player.getGameProfile().name(), instruction,
                System.currentTimeMillis() + delay * 1_000L, repeat * 1_000L));
        saveScheduledEvents();
        refreshAdminState();
        return "ok: scheduled event " + id + (repeat == 0 ? " once" : " repeating every " + repeat + " seconds");
    }

    private String cancelScheduledEvent(JsonObject arguments) {
        String id = arguments.get("event_id").getAsString();
        boolean removed = scheduledEvents.removeIf(event -> event.id().equals(id));
        if (removed) saveScheduledEvents();
        refreshAdminState();
        return removed ? "ok: cancelled scheduled event " + id : "error: no scheduled event named " + id;
    }

    private void runDeferredCommands() {
        Iterator<DeferredCommand> iterator = deferredCommands.iterator();
        while (iterator.hasNext()) {
            DeferredCommand command = iterator.next();
            if (command.dueTick > ticks) continue;
            runServerCommand(command.command);
            iterator.remove();
        }
    }

    private void runServerCommand(String command) {
        CommandSourceStack source = server.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
        try {
            Commands.validateParseResults(server.getCommands().getDispatcher().parse(command, source));
            server.getCommands().performPrefixedCommand(source, command);
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException ignored) {
            // A cleanup command can safely expire if its entity no longer exists.
        }
    }

    private void runScheduledEvents() {
        long now = System.currentTimeMillis();
        List<ScheduledEvent> due = scheduledEvents.stream().filter(event -> event.dueAtMillis() <= now).toList();
        boolean handled = false;
        for (ScheduledEvent event : due) {
            ServerPlayer player = server.getPlayerList().getPlayer(event.playerId());
            if (player == null) continue;
            handled = true;
            ChatTurn turn = new ChatTurn(event.playerId(), "Scheduled event " + event.id()
                    + " is due now: " + event.instruction());
            turn.systemEvent = true;
            queue.addLast(turn);
            scheduledEvents.remove(event);
            if (event.repeatMillis() > 0) scheduledEvents.add(event.next(now));
        }
        if (handled) {
            saveScheduledEvents();
            refreshAdminState();
            processNext();
        }
    }

    private void saveScheduledEvents() {
        scheduleStore.save(scheduledEvents);
    }

    private void detectAdvancements() {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            Set<String> now = currentAdvancements(player);
            Set<String> known = completedAdvancements.computeIfAbsent(player.getUUID(), ignored -> new HashSet<>(now));
            List<String> unlocked = new ArrayList<>();
            for (String advancement : now) {
                if (known.add(advancement)) unlocked.add(advancement);
            }
            if (unlocked.isEmpty() || daily.syncAdvancements(now)) continue;
            for (String advancement : unlocked) {
                ChatTurn turn = new ChatTurn(player.getUUID(), player.getGameProfile().name()
                        + " just unlocked advancement " + advancement
                        + ". React briefly if it is interesting. You may celebrate with particles or sound, or stay silent.");
                turn.systemEvent = true;
                queue.addLast(turn);
            }
        }
        processNext();
    }

    private void requestWorldEvent() {
        ServerPlayer speaker = server.getPlayerList().getPlayers().stream().findFirst().orElse(null);
        if (speaker == null) return;
        ChatTurn turn = new ChatTurn(speaker.getUUID(), """
                An hour has passed in Chapter %d: %s. Decide whether this moment deserves one
                coherent world event. You may change the weather, reveal a small structure or
                landmark away from player builds, stage a creature encounter, leave a discovery,
                or use sound and particles. Fit it to the current chapter and live state. Keep it
                playable, do not start a challenge or daily goal, and use stay_silent if nothing
                would improve the game right now.
                """.formatted(daily.chapterNumber(), daily.chapterName()));
        turn.systemEvent = true;
        queue.addLast(turn);
        processNext();
    }

    private void detectLowHealth() {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID id = player.getUUID();
            online.add(id);
            float hearts = player.getHealth() / 2.0F;
            if (hearts > 4.0F) lowHealthPlayers.remove(id);
            if (!shouldTriggerLowHealth(hearts, player.isAlive(), lowHealthPlayers.contains(id))) continue;
            lowHealthPlayers.add(id);
            ChatTurn turn = new ChatTurn(id, """
                    %s just fell to %.1f hearts. This is a one-time near-death moment, not a chat
                    request. React or act only if it improves the moment: rescue them, warn them,
                    make the danger worse, or stay_silent. Do not create a challenge.
                    """.formatted(player.getGameProfile().name(), hearts));
            turn.systemEvent = true;
            queue.addLast(turn);
        }
        lowHealthPlayers.retainAll(online);
        processNext();
    }

    static boolean shouldTriggerLowHealth(float hearts, boolean alive, boolean tracked) {
        return alive && hearts <= 2.0F && !tracked;
    }

    private Set<String> currentAdvancements(ServerPlayer player) {
        Set<String> completed = new HashSet<>();
        for (var advancement : server.getAdvancements().getAllAdvancements()) {
            if (advancement.value().display().isPresent()
                    && player.getAdvancements().getOrStartProgress(advancement).isDone()) {
                completed.add(advancement.id().toString());
            }
        }
        return completed;
    }

    private void refreshAdminState() {
        JsonObject state = new JsonObject();
        state.addProperty("daytime_ticks", server.overworld().getOverworldClockTime());
        state.addProperty("raining", server.overworld().isRaining());
        state.addProperty("thundering", server.overworld().isThundering());
        state.addProperty("queue_depth", queue.size());
        state.addProperty("processing", processing);
        state.addProperty("server_goal", daily.statusLine());
        state.add("goal", daily.adminState());
        JsonArray players = new JsonArray();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            JsonObject value = new JsonObject();
            value.addProperty("name", player.getGameProfile().name());
            value.addProperty("uuid", player.getUUID().toString());
            value.addProperty("health", player.getHealth() / 2.0F);
            value.addProperty("max_health", player.getMaxHealth() / 2.0F);
            value.addProperty("hunger", player.getFoodData().getFoodLevel());
            value.addProperty("x", Math.round(player.getX()));
            value.addProperty("y", Math.round(player.getY()));
            value.addProperty("z", Math.round(player.getZ()));
            value.addProperty("dimension", player.level().dimension().identifier().toString());
            value.addProperty("biome", player.level().getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.identifier().toString()).orElse("unknown"));
            value.addProperty("holding", heldItem(player));
            value.addProperty("challenge", quests.status(player));
            value.addProperty("chat_visibility", player.getChatVisibility().name().toLowerCase());
            value.addProperty("xp_level", player.experienceLevel);
            value.addProperty("gamemode", player.gameMode.getGameModeForPlayer().getName());
            value.addProperty("chats", chatCounts.getOrDefault(player.getUUID(), 0));
            value.addProperty("playtime_minutes",
                    player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) / 1_200);
            value.addProperty("deaths", player.getStats().getValue(Stats.CUSTOM.get(Stats.DEATHS)));
            value.addProperty("mob_kills", player.getStats().getValue(Stats.CUSTOM.get(Stats.MOB_KILLS)));
            value.addProperty("player_kills", player.getStats().getValue(Stats.CUSTOM.get(Stats.PLAYER_KILLS)));
            value.addProperty("jumps", player.getStats().getValue(Stats.CUSTOM.get(Stats.JUMP)));
            value.addProperty("blocks_walked",
                    player.getStats().getValue(Stats.CUSTOM.get(Stats.WALK_ONE_CM)) / 100);
            value.add("inventory", inventoryJson(player));
            players.add(value);
        }
        state.add("players", players);
        JsonArray schedules = new JsonArray();
        for (ScheduledEvent event : scheduledEvents) {
            JsonObject value = new JsonObject();
            value.addProperty("id", event.id());
            value.addProperty("player", event.playerName());
            value.addProperty("instruction", event.instruction());
            value.addProperty("due_in_seconds", Math.max(0,
                    (event.dueAtMillis() - System.currentTimeMillis()) / 1_000));
            value.addProperty("repeat_seconds", event.repeatMillis() / 1_000);
            schedules.add(value);
        }
        state.add("scheduled_events", schedules);
        adminState = state.toString();
    }

    private CommandOutcome runCommand(String command, ServerPlayer player) {
        CommandSourceStack source = operatorSource(player);
        try {
            Commands.validateParseResults(server.getCommands().getDispatcher().parse(command, source));
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException exception) {
            return new CommandOutcome(true, false, 0);
        }
        boolean[] called = {false};
        boolean[] succeeded = {false};
        int[] value = {0};
        server.getCommands().performPrefixedCommand(source.withCallback((success, result) -> {
            called[0] = true;
            succeeded[0] = success;
            value[0] = result;
        }), command);
        return new CommandOutcome(called[0], succeeded[0], value[0]);
    }

    private static CommandSourceStack operatorSource(ServerPlayer player) {
        return player.createCommandSourceStack()
                .withPermission(PermissionSet.ALL_PERMISSIONS)
                .withSuppressedOutput();
    }

    private String createQuest(ChatTurn turn, JsonObject arguments, ServerPlayer player) {
        Quest quest = quests.create(player, arguments);
        turn.silent = true;
        say(quest.challenge());
        return "ok: challenge created for %s: %s (%s %s x%d)".formatted(
                player.getGameProfile().name(), quest.challenge(),
                quest.objective(), quest.target(), quest.amount());
    }

    private String createDailyGoal(ChatTurn turn, JsonObject arguments, ServerPlayer player) {
        if (turn.goalDeadline == null) {
            throw new IllegalArgumentException(
                    "No daily goal was requested; today's goal already exists or it is past sundown.");
        }
        ServerGoal goal = daily.createGoal(arguments, turn.goalDeadline, turn.goalDay, turn.goalTrial);
        turn.goalDeadline = null;
        turn.silent = true;
        say(goal.challenge());
        goalFanfare(player, goal);
        return "ok: server goal set for day %d: %s (%s %s x%d shared by all players; deadline is sundown)"
                .formatted(goal.day(), goal.challenge(), goal.objective(), goal.target(), goal.amount());
    }

    private String forgeRelic(JsonObject arguments, ServerPlayer player) {
        String name = arguments.get("name").getAsString().strip();
        String give = arguments.get("give_command").getAsString().strip();
        if (name.isEmpty() || give.isEmpty()) throw new IllegalArgumentException("relic needs a name and give_command");
        JsonObject command = new JsonObject();
        command.addProperty("command", give);
        String result = runOperatorCommand(command, player);
        if (result.startsWith("error:")) return result;
        daily.addRelic(name);
        say("a new relic enters the world: " + name);
        return "ok: relic \"" + name + "\" forged and granted";
    }

    private String completeChallenge(JsonObject arguments) {
        String name = arguments.get("player_name").getAsString();
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException("No online player named " + name);
        return quests.forceComplete(target);
    }

    private String cancelQuest(JsonObject arguments) {
        String name = arguments.get("player_name").getAsString();
        ServerPlayer target = server.getPlayerList().getPlayerByName(name);
        if (target == null) throw new IllegalArgumentException("No online player named " + name);
        quests.cancel(target);
        return "ok: challenge for %s cancelled with no reward or punishment".formatted(name);
    }

    private void finishTurn() {
        queue.removeFirst();
        processing = false;
        processNext();
    }

    /** A short imperative for the on-screen title, e.g. "Collect 24 iron ingot". */
    private static String shortObjective(ServerGoal goal) {
        String verb = switch (goal.objective()) {
            case KILL -> "Kill";
            case MINE -> "Mine";
            case COLLECT -> "Collect";
            case STAT -> "Reach";
        };
        return "%s %d %s".formatted(verb, goal.amount(), QuestManager.prettyTarget(goal.target()));
    }

    private void goalFanfare(ServerPlayer player, ServerGoal goal) {
        String heading = goal.trial() ? "TRIAL DAY" : "Today's Goal";
        String color = goal.trial() ? "dark_red" : "red";
        String sound = goal.trial() ? "minecraft:entity.wither.spawn" : "minecraft:entity.ender_dragon.growl";
        quests.runOperatorCommand("title @a times 10 70 20", player);
        quests.runOperatorCommand("title @a subtitle {\"text\":"
                + new JsonPrimitive(shortObjective(goal)) + ",\"color\":\"gold\"}", player);
        quests.runOperatorCommand("title @a title {\"text\":\"" + heading
                + "\",\"color\":\"" + color + "\",\"bold\":true}", player);
        quests.runOperatorCommand("playsound " + sound + " master @a", player);
    }

    private void say(String message) {
        broadcastChat(Component.literal("§d[%s] §f".formatted(godName)
                + MinecraftChatText.fromModel(message)));
    }

    private void broadcastChat(Component message) {
        server.sendSystemMessage(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.connection.send(new ClientboundSystemChatPacket(message, false));
        }
    }

    private String snapshot(ServerPlayer speaker, ChatTurn turn) {
        ServerLevel level = (ServerLevel) speaker.level();
        StringBuilder players = new StringBuilder();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!players.isEmpty()) players.append('\n');
            players.append("- ").append(player.getGameProfile().name())
                    .append(" health=").append("%.1f/%.1f hearts".formatted(
                            player.getHealth() / 2.0F, player.getMaxHealth() / 2.0F))
                    .append(" hunger=").append(player.getFoodData().getFoodLevel()).append("/20")
                    .append(" xp=").append(player.experienceLevel)
                    .append(" position=").append("%.0f %.0f %.0f".formatted(
                            player.getX(), player.getY(), player.getZ()))
                    .append(" rotation=").append("%.1f %.1f".formatted(
                            player.getYRot(), player.getXRot()))
                    .append(" dimension=").append(player.level().dimension().identifier())
                    .append(" biome=").append(player.level().getBiome(player.blockPosition()).unwrapKey()
                            .map(key -> key.identifier().toString()).orElse("unknown"))
                    .append(" holding=[").append(heldItem(player)).append(']')
                    .append(" inventory=[").append(inventory(player)).append(']')
                    .append(" challenge=[").append(quests.status(player)).append(']');
        }
        String lead = turn.systemEvent
                ? "Automatic server event concerning %s: %s"
                : """
                  New public server chat turn.
                  current_speaker=%s
                  current_speaker_uuid=%s
                  identity_rule=In this turn, I/me/my/you/your refer only to current_speaker unless another player is explicitly named.
                  current_speaker_challenge=%s
                  sent_at_epoch_ms=%d
                  message=%s
                  """;
        String schedules = scheduledEvents.isEmpty() ? "" : "\nActive scheduled events:\n"
                + scheduledEvents.stream()
                .map(event -> "- " + event.id() + " for " + event.playerName() + ": "
                        + event.instruction() + " (due in " + Math.max(0,
                        (event.dueAtMillis() - System.currentTimeMillis()) / 1_000) + "s; repeat "
                        + event.repeatMillis() / 1_000 + "s)")
                .collect(java.util.stream.Collectors.joining("\n"));
        return """
                %s

                Live server state:
                difficulty=%s, day=%d, sky=%s, daytime_ticks=%d, raining=%s, thundering=%s
                chapter=%d (%s)
                online_players=%d
                server_goal=[%s]
                current_speaker_view=[%s]
                %s%s
                """.formatted(
                turn.systemEvent
                        ? lead.formatted(speaker.getGameProfile().name(), turn.message())
                        : lead.formatted(speaker.getGameProfile().name(), speaker.getUUID(),
                                quests.status(speaker), turn.lastMessageAtMillis, turn.message()),
                server.getWorldData().getDifficulty(), DayCycle.day(level.getOverworldClockTime()),
                DayCycle.phase(level.getOverworldClockTime()), level.getOverworldClockTime(),
                level.isRaining(), level.isThundering(),
                daily.chapterNumber(), daily.chapterName(), server.getPlayerCount(), daily.statusLine(),
                inspectView(speaker), players, schedules);
    }

    private static JsonArray inventoryJson(ServerPlayer player) {
        JsonArray items = new JsonArray();
        var inv = player.getInventory();
        for (int slot = 0; slot < inv.getContainerSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack.isEmpty()) continue;
            JsonObject entry = new JsonObject();
            entry.addProperty("id", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            entry.addProperty("name", QuestManager.prettyTarget(
                    BuiltInRegistries.ITEM.getKey(stack.getItem()).toString()));
            entry.addProperty("count", stack.getCount());
            items.add(entry);
        }
        return items;
    }

    private static String heldItem(ServerPlayer player) {
        ItemStack held = player.getMainHandItem();
        if (held.isEmpty()) return "nothing";
        return BuiltInRegistries.ITEM.getKey(held.getItem()) + " x" + held.getCount();
    }

    private static String inventory(ServerPlayer player) {
        StringBuilder inventory = new StringBuilder();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.isEmpty()) continue;
            if (!inventory.isEmpty()) inventory.append(", ");
            inventory.append(BuiltInRegistries.ITEM.getKey(stack.getItem()))
                    .append(" x").append(stack.getCount());
        }
        return inventory.isEmpty() ? "empty" : inventory.toString();
    }

    @Override
    public void close() {
        client.close();
    }

    static final class ChatTurn {
        private static final long BURST_WINDOW_MILLIS = 750;

        private final UUID playerId;
        private final List<String> messages = new ArrayList<>();
        private long lastMessageAtMillis;
        private boolean started;
        private String baseConversationId;
        private boolean silent;
        private boolean systemEvent;
        private Long goalDeadline;
        private long goalDay;
        private boolean goalTrial;
        private Runnable onSuccess;
        private Runnable onFailure;

        ChatTurn(UUID playerId, String message) {
            this(playerId, message, System.currentTimeMillis());
        }

        ChatTurn(UUID playerId, String message, long atMillis) {
            this.playerId = playerId;
            this.lastMessageAtMillis = atMillis;
            this.messages.add(message);
        }

        void appendMessage(String message, long atMillis) {
            lastMessageAtMillis = atMillis;
            if (!messages.getLast().equals(message)) messages.add(message);
        }

        boolean isRecent(long nowMillis) {
            return nowMillis - lastMessageAtMillis <= BURST_WINDOW_MILLIS;
        }

        boolean ready(long nowMillis) {
            return nowMillis - lastMessageAtMillis >= BURST_WINDOW_MILLIS;
        }

        String message() {
            return String.join("\n", messages);
        }
    }

    private record CommandOutcome(boolean known, boolean succeeded, int value) {}
    private record DeferredCommand(long dueTick, String command) {}

}
