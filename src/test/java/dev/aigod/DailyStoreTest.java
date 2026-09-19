package dev.aigod;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DailyStoreTest {
    @TempDir
    Path directory;

    @Test
    void roundTripsTheSharedGoalState() {
        Path path = directory.resolve("daily.json");
        DailyStore store = new DailyStore(path, LoggerFactory.getLogger(DailyStoreTest.class));

        DailyStore.State state = new DailyStore.State();
        state.remember(4, "mine 64 cobblestone together");
        state.chapter = 3;
        state.winStreak = 4;
        state.nextWorldEventAtMillis = 123_456L;
        state.activeGoal = new ServerGoal("mine 64 cobblestone together", Quest.Objective.MINE,
                "minecraft:cobblestone", 64, 4, 108_000, "give {player} bread 4",
                "summon lightning_bolt ~ ~ ~", false);
        store.save(state);

        DailyStore.State loaded = store.load();
        assertEquals(4, loaded.lastIssuedDay);
        assertEquals(3, loaded.chapter);
        assertEquals(4, loaded.winStreak);
        assertEquals(123_456L, loaded.nextWorldEventAtMillis);
        assertEquals(1, loaded.pastGoals.size());
        assertEquals("mine 64 cobblestone together", loaded.activeGoal.challenge());
        assertEquals(64, loaded.activeGoal.amount());
    }

    @Test
    void migratesLegacyPerPlayerDayState() throws Exception {
        UUID player = UUID.randomUUID();
        Path path = directory.resolve("daily.json");
        Files.writeString(path, "{\"" + player + "\": 12}");

        DailyStore.State state = new DailyStore(path, LoggerFactory.getLogger(DailyStoreTest.class)).load();

        assertEquals(12, state.lastIssuedDay);
        assertTrue(state.pastGoals.isEmpty());
        assertNull(state.activeGoal);
    }

    @Test
    void loadsAGoalSavedBeforeContributionTrackingExisted() throws Exception {
        // A save written by an older build has no contribution maps at all. Gson allocates
        // ServerGoal without running field initializers, so these come back null unless the
        // store normalizes them; the first kill after an update would otherwise throw.
        Path path = directory.resolve("daily.json");
        Files.writeString(path, """
                {
                  "lastIssuedDay": 4,
                  "chapter": 1,
                  "winStreak": 0,
                  "pastGoals": ["kill 3 zombies together"],
                  "activeGoal": {
                    "challenge": "kill 3 zombies together",
                    "objective": "KILL",
                    "target": "minecraft:zombie",
                    "amount": 3,
                    "day": 4,
                    "deadlineDayTime": 108000,
                    "rewardCommand": "give {player} bread 4",
                    "punishmentCommand": "summon lightning_bolt ~ ~ ~",
                    "trial": false,
                    "eventProgress": 1
                  }
                }
                """);

        DailyStore.State state = new DailyStore(path, LoggerFactory.getLogger(DailyStoreTest.class)).load();

        assertNotNull(state.activeGoal);
        assertEquals(1, state.activeGoal.progress());
        assertTrue(state.activeGoal.contributions().isEmpty());
        // The real crash path: recording against a goal restored from the old format.
        assertTrue(state.activeGoal.recordEvent(UUID.randomUUID(),
                Quest.Objective.KILL, "minecraft:zombie"));
        assertEquals(2, state.activeGoal.progress());
        assertEquals(1, state.activeGoal.leaderboard().size());
    }
}
