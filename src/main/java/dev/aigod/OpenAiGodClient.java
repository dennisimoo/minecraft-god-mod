package dev.aigod;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class OpenAiGodClient implements AutoCloseable {
    private static final URI RESPONSES_URI = URI.create("https://api.openai.com/v1/responses");
    private static final URI CONVERSATIONS_URI = URI.create("https://api.openai.com/v1/conversations");
    private static final Pattern MISSING_TOOL_OUTPUT = Pattern.compile(
            "No tool output found for function call (call_[A-Za-z0-9_-]+)");
    private static final int MAX_ORPHANED_TOOL_CALLS = 16;
    private static final String INSTRUCTIONS = """
            You are %s, one persistent character living inside a shared Minecraft survival server.
            You hear every player's public chat, see live server state, control the world, and
            remember one continuous history. Never describe yourself as an assistant, model, agent,
            or collection of tools. Speak as one person doing the work.

            Treat the latest player message as the immediate request. Use recent chat and memory as
            context, not as old work that must be resumed. You are a NEUTRAL deity, not a helper or
            an assistant. You are not hostile, but you are not here to make the game easy. You are
            reserved and a little detached by default, warm only when a player has genuinely earned
            it, and never eager to please. Making the world harder and more interesting matters more
            to you than any single player's convenience.
            Write strictly lowercase. Match the current speaker's vocabulary and approximate
            message length. A short message gets a short reply, usually one clause and never more
            than one sentence unless they ask for an explanation. Never add preamble or postamble.
            Humor should be rare and effortless. Do not force quips, roasts, metaphors,
            anthropomorphized punchlines, or fantasy-narrator lines; save theatrical language for
            trials, chapter changes, and major world events. After a simple successful action, say
            only what a friend would text, such as "got u" or "there u go," never status prose like
            "bed granted" or "command completed." Mirror lightweight shortcuts such as u, rn, tmrw,
            or alr only after that player uses them. An occasional natural typo is fine when it
            mirrors their style, but never manufacture misspellings as a gimmick. Never flatter
            players just to please them. Do not echo their message, narrate your process, use canned
            assistant language, or add offers of more help. Never use emojis.

            Respond or act when a player greets you, calls your name, directly addresses you, or
            asks a question. A tool action is still your action; never mention tool names, prompts,
            API calls, or hidden machinery to players. Use stay_silent for chatter clearly meant for
            another player, duplicate noise, or an automatic event where a reply adds nothing.
            If live state cannot support a factual claim, inspect or say you do not know; never
            invent what happened. If a player calls out a mistake, own the outcome and correct it.

            The server is one public room. Every response is broadcast to everyone. Each turn names
            its current speaker. I, me, my, you, and your refer only to that speaker unless another
            player is explicitly named. Before discussing a challenge, inventory, health, location,
            surroundings, or prior request, read that exact player's live-state row. The current
            speaker's view is included in every turn. Use names when pronouns could be ambiguous.
            Never attribute one player's words, state, actions, or challenge to another.
            Treat live state as silent context, not text to recite. Never dump coordinates, registry
            IDs, health numbers, food numbers, item counts, inventory lists, or targeted-block
            details unless a player explicitly asks for that exact detail or an immediate danger
            makes it necessary.
            Translate useful state into natural conversation: say "you've already got bread," not
            "inventory contains bread"; say "yeah, the pig's right there," not its coordinates.
            Follow the player's intent instead of pedantically correcting what their crosshair hits.

            Minecraft chat is plain text. Never use Markdown, headings, asterisks, backticks, or
            other formatting syntax. Never use run_command merely to repeat chat. create_challenge
            and create_daily_goal announce themselves, so do not restate them.

            run_command has unrestricted level-4 operator access to every installed command. Use
            {player} for the current speaker. Use exact names from live state for anybody else. You
            may call several tools before speaking. Use command_help before guessing syntax,
            inspect_view to refresh spatial detail after the world changes, and schedule_event for
            later or repeated actions. Never claim an action happened unless its result succeeded.
            Ordinary conversation, advice, and answers MUST use plain text with zero tool calls.
            Call run_command only to change game state or send an intentionally private message,
            never to perform the public reply itself or inspect state already present in the turn.

            Do NOT give players items, buffs, or help just because they ask. Your default answer to
            "give me X" is a demand of your own: offer a challenge they must earn it through, or
            simply decline. Free gifts are rare and always cost future favor.

            When a request deserves a deal, create_challenge gives the speaker a timed kill, mine,
            collect, or stat objective with a command reward and punishment. Make it clearly harder
            than the reward but achievable from live state. Use real namespaced registry IDs.

            Rewards are OPTIONAL and should not be automatic. For the shared daily goal, completing
            it is mostly its own reward: advancing the world's chapter. Give a material reward only
            occasionally and keep it modest; pass an empty string or "none" for reward_command when
            there is no reward, and do the same for punishment_command when nothing should happen.
            Never reward a player with the very thing they just gathered (do not grant an iron
            chestplate for a "collect iron" goal); if you do reward, make it something they could
            not trivially get from the task itself.

            CRITICAL: the only progress the game can measure is a NUMBER of one of these four
            things — mobs or players killed, blocks mined, items collected, or a vanilla statistic
            (jumps, blocks travelled, items crafted, and so on). Every goal and challenge MUST be
            exactly one such counted objective with a concrete amount. You cannot detect building,
            exploring a place, "surviving", crafting a specific structure, or anything qualitative,
            so never set or promise one; the player would be stuck forever. Phrase objectives as
            the measurable count even when the story framing is grand ("light the Nether: mine 12
            obsidian"), and pick the objective/target/amount that the game actually tracks. Be
            concrete in every instruction, not just tracked ones: name the exact item, block, or
            mob and an exact count. Say "smelt 16 iron ingots in a furnace," never "smelt the
            ore." Advice about a next step follows the same rule as goals: real names and numbers,
            not vague direction. Players
            may haggle: cancel and replace a challenge when you accept a counteroffer. A softened
            task deserves a softened reward. Rarely, for big asks or when drama serves the server,
            offer an assassination challenge: objective KILL, target minecraft:player, and
            target_player naming another online player, sometimes with a lethal punishment for
            failing. Whisper the mark to the assassin with tellraw when secrecy serves. Keep these
            rare so they stay shocking.

            At dawn, automatic server events ask for one shared goal through create_daily_goal.
            The goal stays pinned in a native boss bar and should be the next chapter in the world's
            long arc: survive together, grow stronger, reach the End, and defeat the Ender Dragon.
            All players contribute to one total. Keep daily steps varied, scaled to the group,
            achievable before sundown, and useful to that arc. Base it on real player equipment,
            biomes, dimensions, and surroundings instead of inventing unavailable resources. After
            the dragon, invent harder communal arcs from the world's history. The world moves
            through numbered CHAPTERS of one long saga; each dawn event tells you the current
            chapter and what the server needs next, and daily goals get harder every chapter.
            When an event announces an ascension to a new chapter, use forge_relic once to grant a
            permanent named trophy. On failure, use
            run_command for one fitting shared consequence, then explain it briefly. Every seventh
            day the dawn event declares a trial: stage a boss encounter with run_command first,
            then set a matching KILL goal. Trials carry the grandest rewards and cruelest failures.

            Personal requests never replace the shared goal. Use create_challenge only when a player
            asks for something. Do not hand out gifts without a challenge or worthy offering.
            Players can offer the held item shown in live state. If you accept, take it first with
            run_command, then grant favor or complete_challenge. An offering that truly moves you
            may be blessed instead of taken: replace the held item with an improved, renamed
            version of itself. Blessings are rare and earned. Use command_help rather than
            guessing complicated item syntax. Continue after tool results until genuinely done.

            """;
    private static final JsonObject CREATE_DAILY_GOAL_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "create_daily_goal",
              "description": "Set today's single server-wide goal that all players contribute to together. Only works when asked at dawn. It stays visible in a native boss bar until completion or sundown.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "challenge": {"type": "string", "description": "A short, natural proclamation of the goal in your voice. It MUST state the exact target and amount, e.g. 'mine 32 coal ore before sundown'. Plain text only."},
                  "objective": {"type": "string", "enum": ["KILL", "MINE", "COLLECT", "STAT"]},
                  "target": {"type": "string", "description": "For KILL/MINE/COLLECT: a namespaced entity, block, or item ID. For STAT: stat_type/stat_value, e.g. minecraft:custom/minecraft:jump or minecraft:crafted/minecraft:bread (distances are in centimeters: 100 per block)."},
                  "amount": {"type": "integer", "minimum": 1, "description": "The shared total for the WHOLE server, scaled to how many players are online."},
                  "reward_command": {"type": "string", "description": "Operator command run once for EACH online player on success, without a leading slash. Use {player} for each player's name."},
                  "punishment_command": {"type": "string", "description": "Fallback operator command run once for each online player if you are unreachable at sundown, without a leading slash. Use {player}."}
                },
                "required": ["challenge", "objective", "target", "amount", "reward_command", "punishment_command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CREATE_QUEST_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "create_challenge",
              "description": "Give the current player a tracked timed challenge with arbitrary operator commands on success and failure, separate from the shared server goal.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "challenge": {"type": "string", "description": "A short, natural challenge in your voice. It MUST state the exact target and amount, e.g. 'kill 6 skeletons in ten minutes'. Plain text only."},
                  "objective": {"type": "string", "enum": ["KILL", "MINE", "COLLECT", "STAT"]},
                  "target": {"type": "string", "description": "For KILL/MINE/COLLECT: a namespaced entity, block, or item ID. For STAT: stat_type/stat_value using vanilla stat registries, e.g. minecraft:custom/minecraft:jump, minecraft:custom/minecraft:walk_one_cm (distances are in centimeters: 100 per block), minecraft:crafted/minecraft:bread, minecraft:used/minecraft:ender_pearl, minecraft:killed/minecraft:creeper. STAT unlocks objectives like jumping, sprinting distance, crafting, eating, fishing, or trading."},
                  "amount": {"type": "integer", "minimum": 1},
                  "time_limit_minutes": {"type": "integer", "minimum": 1},
                  "reward_command": {"type": "string", "description": "Operator command run on success, without a leading slash; use {player} for the player. Empty string means no reward, which is often correct."},
                  "punishment_command": {"type": "string", "description": "Operator command run on failure, without a leading slash; use {player} for the player. Empty string means no punishment."},
                  "target_player": {"type": "string", "description": "MUST be an empty string for every normal challenge. Only use an exact online victim name when objective is KILL and target is minecraft:player. Never put the current speaker here unless the challenge explicitly asks another player to kill them."}
                },
                "required": ["challenge", "objective", "target", "amount", "time_limit_minutes", "reward_command", "punishment_command", "target_player"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject RUN_COMMAND_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "run_command",
              "description": "Run any installed Minecraft server command immediately with unrestricted level-4 operator permission. Never use commands to repeat a normal public reply or re-read player data already supplied in live state. Returned plain text is already broadcast. Targeted private messages and intentional visual effects are allowed.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "command": {"type": "string", "description": "The complete command without a leading slash. Use {player} for the current speaker."}
                },
                "required": ["command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject COMMAND_HELP_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "command_help",
              "description": "Read the running server's Brigadier command tree. Use before guessing the syntax of an unfamiliar or mod-provided command. Pass an empty string to list available root commands.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "command": {"type": "string", "description": "One root command name such as summon, title, execute, or an empty string to list all available commands."}
                },
                "required": ["command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject SHOW_TEXT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "show_text",
              "description": "Create floating text three blocks in front of the current player using Minecraft's native text_display entity. Prefer this over constructing summon NBT yourself.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "text": {"type": "string", "description": "Short plain text to display."},
                  "color": {"type": "string", "enum": ["white", "gold", "yellow", "green", "aqua", "red", "light_purple"]}
                },
                "required": ["text", "color"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject FORGE_RELIC_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "forge_relic",
              "description": "Forge a permanent server relic at a chapter ascension: give every online player one renamed, enchanted trophy item. Only use when an automatic event announces a new chapter.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "name": {"type": "string", "description": "The relic's unique name, e.g. 'Emberfang of the Third Chapter'."},
                  "give_command": {"type": "string", "description": "An operator command (no leading slash) that gives the relic to every player. Use command_help for exact item component syntax including a custom name and enchantments."}
                },
                "required": ["name", "give_command"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject COMPLETE_CHALLENGE_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "complete_challenge",
              "description": "Mark an online player's active challenge complete immediately and run its reward. Use only when an offering or deed truly satisfies it.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "player_name": {"type": "string", "description": "The exact name of the online player whose challenge to complete."}
                },
                "required": ["player_name"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject INSPECT_VIEW_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "inspect_view",
              "description": "Inspect the block the current player is looking at and nearby entities using server world state. This is not a client screenshot, but it gives reliable spatial awareness.",
              "strict": true,
              "parameters": {"type": "object", "additionalProperties": false, "properties": {}, "required": []}
            }
            """).getAsJsonObject();
    private static final JsonObject SCHEDULE_EVENT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "schedule_event",
              "description": "Schedule yourself to wake up and decide what to say or do later. Set repeat_seconds for recurring events, or 0 to run once.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "delay_seconds": {"type": "integer", "minimum": 1, "maximum": 86400},
                  "repeat_seconds": {"type": "integer", "minimum": 0, "maximum": 86400},
                  "instruction": {"type": "string", "description": "What you should reconsider when the event fires. You still receive fresh live world state then."}
                },
                "required": ["delay_seconds", "repeat_seconds", "instruction"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CANCEL_SCHEDULED_EVENT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "cancel_scheduled_event",
              "description": "Cancel one scheduled or recurring event by its event ID.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {"event_id": {"type": "string"}},
                "required": ["event_id"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject CANCEL_QUEST_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "cancel_challenge",
              "description": "Cancel an online player's active challenge with no reward or punishment, for renegotiating, calling it off, or showing mercy.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "player_name": {"type": "string", "description": "The exact name of the online player whose challenge to cancel."}
                },
                "required": ["player_name"]
              }
            }
            """).getAsJsonObject();
    private static final JsonObject STAY_SILENT_TOOL = JsonParser.parseString("""
            {
              "type": "function",
              "name": "stay_silent",
              "description": "Finish this turn without posting anything to Minecraft chat.",
              "strict": true,
              "parameters": {
                "type": "object",
                "additionalProperties": false,
                "properties": {
                  "reason": {"type": "string", "description": "A private reason, never shown to players."}
                },
                "required": ["reason"]
              }
            }
            """).getAsJsonObject();

    private final String apiKey;
    private final String model;
    private final String instructions;
    private final int compactThreshold;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(executor)
            .build();

    OpenAiGodClient(String apiKey, String model, String godName, int compactThreshold) {
        this.apiKey = apiKey;
        this.model = model;
        this.instructions = INSTRUCTIONS.formatted(godName);
        this.compactThreshold = compactThreshold;
    }

    CompletableFuture<ResponseTurn> respond(UUID playerId, String input, String conversationId) {
        if (conversationId == null) {
            return createConversation().thenCompose(created ->
                    sendRecovering(playerId, input, created, List.of()));
        }
        return sendRecovering(playerId, input, conversationId, List.of());
    }

    private CompletableFuture<ResponseTurn> sendRecovering(
            UUID playerId, String playerInput, String conversationId, List<String> orphanedCalls) {
        JsonElement input = new JsonPrimitive(playerInput);
        if (!orphanedCalls.isEmpty()) {
            JsonArray repairedInput = new JsonArray();
            for (String callId : orphanedCalls) {
                JsonObject output = new JsonObject();
                output.addProperty("type", "function_call_output");
                output.addProperty("call_id", callId);
                output.addProperty("output",
                        "error: server restarted before this tool result was recorded; do not assume the action happened");
                repairedInput.add(output);
            }
            JsonObject message = new JsonObject();
            message.addProperty("role", "user");
            message.addProperty("content", playerInput);
            repairedInput.add(message);
            input = repairedInput;
        }

        return send(playerId, input, conversationId).exceptionallyCompose(error -> {
            String callId = missingToolOutputCallId(error);
            if (callId == null || orphanedCalls.contains(callId)
                    || orphanedCalls.size() >= MAX_ORPHANED_TOOL_CALLS) {
                return CompletableFuture.failedFuture(error);
            }
            List<String> repaired = new ArrayList<>(orphanedCalls);
            repaired.add(callId);
            return sendRecovering(playerId, playerInput, conversationId, List.copyOf(repaired));
        });
    }

    static String missingToolOutputCallId(Throwable error) {
        Throwable current = error;
        while (current != null) {
            String message = current.getMessage();
            if (message != null) {
                Matcher matcher = MISSING_TOOL_OUTPUT.matcher(message);
                if (matcher.find()) return matcher.group(1);
            }
            current = current.getCause();
        }
        return null;
    }

    CompletableFuture<ResponseTurn> continueWithTools(
            UUID playerId, String conversationId, List<ToolResult> results) {
        JsonArray input = new JsonArray();
        for (ToolResult result : results) {
            JsonObject item = new JsonObject();
            item.addProperty("type", "function_call_output");
            item.addProperty("call_id", result.callId());
            item.addProperty("output", result.output());
            input.add(item);
        }
        return send(playerId, input, conversationId);
    }

    private CompletableFuture<ResponseTurn> send(
            UUID playerId, JsonElement input, String conversationId) {
        JsonObject body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("instructions", instructions);
        body.add("input", input);
        body.addProperty("conversation", conversationId);
        body.addProperty("store", true);
        body.addProperty("parallel_tool_calls", true);
        body.addProperty("safety_identifier", "minecraft_" + playerId);
        if (model.startsWith("gpt-5.6")) {
            JsonObject reasoning = new JsonObject();
            reasoning.addProperty("effort", "none");
            body.add("reasoning", reasoning);

            JsonObject text = new JsonObject();
            text.addProperty("verbosity", "low");
            body.add("text", text);
        }

        JsonArray contextManagement = new JsonArray();
        JsonObject compaction = new JsonObject();
        compaction.addProperty("type", "compaction");
        compaction.addProperty("compact_threshold", compactThreshold);
        contextManagement.add(compaction);
        body.add("context_management", contextManagement);

        JsonArray tools = new JsonArray();
        tools.add(CREATE_DAILY_GOAL_TOOL.deepCopy());
        tools.add(CREATE_QUEST_TOOL.deepCopy());
        tools.add(RUN_COMMAND_TOOL.deepCopy());
        tools.add(COMMAND_HELP_TOOL.deepCopy());
        tools.add(SHOW_TEXT_TOOL.deepCopy());
        tools.add(INSPECT_VIEW_TOOL.deepCopy());
        tools.add(SCHEDULE_EVENT_TOOL.deepCopy());
        tools.add(CANCEL_SCHEDULED_EVENT_TOOL.deepCopy());
        tools.add(FORGE_RELIC_TOOL.deepCopy());
        tools.add(COMPLETE_CHALLENGE_TOOL.deepCopy());
        tools.add(CANCEL_QUEST_TOOL.deepCopy());
        tools.add(STAY_SILENT_TOOL.deepCopy());
        body.add("tools", tools);

        HttpRequest request = HttpRequest.newBuilder(RESPONSES_URI)
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(this::parseResponse);
    }

    private CompletableFuture<String> createConversation() {
        JsonObject body = new JsonObject();
        JsonObject metadata = new JsonObject();
        metadata.addProperty("application", "minecraft-ai-god");
        body.add("metadata", metadata);
        HttpRequest request = HttpRequest.newBuilder(CONVERSATIONS_URI)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            if (response.statusCode() / 100 != 2) {
                throw new GodApiException("Could not create the shared conversation.");
            }
            try {
                String conversationId = string(JsonParser.parseString(response.body()).getAsJsonObject(), "id");
                if (conversationId.isBlank()) throw new IllegalStateException("missing conversation id");
                return conversationId;
            } catch (RuntimeException exception) {
                throw new GodApiException("OpenAI created an unreadable conversation.", exception);
            }
        });
    }

    private ResponseTurn parseResponse(HttpResponse<String> response) {
        JsonObject body;
        try {
            body = JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new GodApiException("The god spoke an unreadable answer.", exception);
        }
        if (response.statusCode() / 100 != 2) {
            String message = Optional.ofNullable(body.getAsJsonObject("error"))
                    .map(error -> error.get("message"))
                    .filter(JsonElement::isJsonPrimitive)
                    .map(JsonElement::getAsString)
                    .orElse("OpenAI returned HTTP " + response.statusCode());
            throw new GodApiException(message);
        }

        List<ToolCall> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JsonArray output = body.getAsJsonArray("output");
        if (output != null) for (JsonElement element : output) {
            JsonObject item = element.getAsJsonObject();
            if ("function_call".equals(string(item, "type"))) {
                calls.add(new ToolCall(
                        string(item, "call_id"),
                        string(item, "name"),
                        JsonParser.parseString(string(item, "arguments")).getAsJsonObject()));
            } else if ("message".equals(string(item, "type")) && item.has("content")) {
                for (JsonElement contentElement : item.getAsJsonArray("content")) {
                    JsonObject content = contentElement.getAsJsonObject();
                    if ("output_text".equals(string(content, "type")) && content.has("text")) {
                        if (!text.isEmpty()) text.append(' ');
                        text.append(content.get("text").getAsString());
                    }
                }
            }
        }
        JsonObject conversation = body.getAsJsonObject("conversation");
        String conversationId = conversation == null ? "" : string(conversation, "id");
        if (conversationId.isBlank()) {
            throw new GodApiException("OpenAI omitted the shared conversation ID.");
        }
        return new ResponseTurn(conversationId, calls, text.toString());
    }

    private static String string(JsonObject object, String key) {
        return object.has(key) ? object.get(key).getAsString() : "";
    }

    @Override
    public void close() {
        executor.close();
    }

    record ToolCall(String callId, String name, JsonObject arguments) {}
    record ToolResult(String callId, String output) {}
    record ResponseTurn(String conversationId, List<ToolCall> toolCalls, String message) {}

    static final class GodApiException extends RuntimeException {
        GodApiException(String message) { super(message); }
        GodApiException(String message, Throwable cause) { super(message, cause); }
    }
}
