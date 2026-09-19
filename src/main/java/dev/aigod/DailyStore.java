package dev.aigod;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Persists the server-wide daily goal, the day it was issued, and recent goal texts. */
final class DailyStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int HISTORY_LIMIT = 7;

    static final class State {
        long lastIssuedDay = -1;
        int chapter = 1;
        int winStreak;
        long nextWorldEventAtMillis;
        List<String> pastGoals = new ArrayList<>();
        List<String> relics = new ArrayList<>();
        ServerGoal activeGoal;

        void remember(long day, String goal) {
            lastIssuedDay = day;
            pastGoals.add(goal);
            while (pastGoals.size() > HISTORY_LIMIT) pastGoals.remove(0);
        }
    }

    private final Path path;
    private final Logger logger;

    DailyStore(Path path, Logger logger) {
        this.path = path;
        this.logger = logger;
    }

    State load() {
        if (!Files.exists(path)) return new State();
        try {
            JsonObject saved = JsonParser.parseString(Files.readString(path)).getAsJsonObject();
            if (saved.has("lastIssuedDay") || saved.has("activeGoal") || saved.has("pastGoals")) {
                State state = GSON.fromJson(saved, State.class);
                if (state == null) return new State();
                if (state.pastGoals == null) state.pastGoals = new ArrayList<>();
                if (state.relics == null) state.relics = new ArrayList<>();
                if (state.activeGoal != null) state.activeGoal.normalize();
                if (state.chapter < 1) state.chapter = 1;
                return state;
            }
            return migratePlayerDailies(saved);
        } catch (IOException | JsonParseException | IllegalStateException exception) {
            logger.error("Could not load AI God daily state from {}", path, exception);
            return new State();
        }
    }

    private static State migratePlayerDailies(JsonObject saved) {
        State state = new State();
        for (Map.Entry<String, JsonElement> entry : saved.entrySet()) {
            JsonElement value = entry.getValue();
            long day = value.isJsonPrimitive() ? value.getAsLong()
                    : value.getAsJsonObject().get("lastIssuedDay").getAsLong();
            state.lastIssuedDay = Math.max(state.lastIssuedDay, day);
            if (!value.isJsonObject() || !value.getAsJsonObject().has("pastChallenges")) continue;
            for (JsonElement challenge : value.getAsJsonObject().getAsJsonArray("pastChallenges")) {
                state.pastGoals.add(challenge.getAsString());
            }
        }
        while (state.pastGoals.size() > HISTORY_LIMIT) state.pastGoals.remove(0);
        return state;
    }

    void save(State state) {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(state));
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException exception) {
            logger.error("Could not save AI God daily state to {}", path, exception);
        }
    }
}
