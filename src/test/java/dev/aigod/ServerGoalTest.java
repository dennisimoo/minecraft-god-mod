package dev.aigod;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerGoalTest {
    private static ServerGoal goal(Quest.Objective objective, String target, int amount) {
        return new ServerGoal("Together now", objective, target, amount,
                3, 84_000, "give {player} diamond 1", "summon lightning_bolt ~ ~ ~", false);
    }

    @Test
    void killsFromAnyPlayerPoolIntoOneTotal() {
        ServerGoal goal = goal(Quest.Objective.KILL, "minecraft:zombie", 3);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        assertTrue(goal.recordEvent(alice, Quest.Objective.KILL, "minecraft:zombie"));
        assertFalse(goal.recordEvent(alice, Quest.Objective.KILL, "minecraft:cow"));
        assertTrue(goal.recordEvent(bob, Quest.Objective.KILL, "minecraft:zombie"));
        assertTrue(goal.recordEvent(bob, Quest.Objective.KILL, "minecraft:zombie"));
        assertTrue(goal.complete());
        assertEquals(3, goal.progress());
    }

    @Test
    void collectionSumsEachPlayersGainsAboveTheirOwnBaseline() {
        ServerGoal goal = goal(Quest.Objective.COLLECT, "minecraft:cobblestone", 12);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();

        goal.updateTotal(alice, 10);   // baseline 10
        goal.updateTotal(bob, 0);      // baseline 0
        assertEquals(0, goal.progress());

        assertTrue(goal.updateTotal(alice, 15)); // +5
        assertTrue(goal.updateTotal(bob, 4));    // +4
        assertEquals(9, goal.progress());
        assertFalse(goal.complete());

        assertTrue(goal.updateTotal(bob, 7));    // +7 total
        assertTrue(goal.complete());
        assertEquals(12, goal.progress());
    }

    @Test
    void expiryOnlyBitesIncompleteGoals() {
        ServerGoal goal = goal(Quest.Objective.MINE, "minecraft:stone", 1);
        assertFalse(goal.expired(83_999));
        assertTrue(goal.expired(84_000));
        goal.recordEvent(UUID.randomUUID(), Quest.Objective.MINE, "minecraft:stone");
        assertFalse(goal.expired(Long.MAX_VALUE));
    }

    @Test
    void eventGoalsCreditThePlayerWhoCausedThem() {
        ServerGoal goal = goal(Quest.Objective.KILL, "minecraft:zombie", 5);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        goal.recordEvent(alice, Quest.Objective.KILL, "minecraft:zombie");
        goal.recordEvent(bob, Quest.Objective.KILL, "minecraft:zombie");
        goal.recordEvent(alice, Quest.Objective.KILL, "minecraft:zombie");
        // A kill that does not match the goal credits nobody.
        goal.recordEvent(bob, Quest.Objective.KILL, "minecraft:cow");

        assertEquals(2, goal.contributions().get(alice));
        assertEquals(1, goal.contributions().get(bob));
        assertEquals(alice, goal.leaderboard().get(0).getKey());
        assertEquals(3, goal.progress());
    }

    @Test
    void totalGoalsCreditEachPlayersGainNotTheirRawCount() {
        ServerGoal goal = goal(Quest.Objective.COLLECT, "minecraft:cobblestone", 20);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        goal.updateTotal(alice, 100); // starts rich, baseline 100
        goal.updateTotal(bob, 0);
        goal.updateTotal(alice, 103); // +3
        goal.updateTotal(bob, 9);     // +9

        // Bob leads despite Alice holding far more cobblestone overall.
        assertEquals(bob, goal.leaderboard().get(0).getKey());
        assertEquals(9, goal.contributions().get(bob));
        assertEquals(3, goal.contributions().get(alice));
    }

    @Test
    void playersWhoContributedNothingAreLeftOutOfTheBoard() {
        ServerGoal goal = goal(Quest.Objective.COLLECT, "minecraft:cobblestone", 20);
        UUID idle = UUID.randomUUID();
        goal.updateTotal(idle, 7);
        goal.updateTotal(idle, 7);
        assertTrue(goal.contributions().isEmpty());
        assertTrue(goal.leaderboard().isEmpty());
    }

    @Test
    void unattributedEventsStillCountTowardProgress() {
        ServerGoal goal = goal(Quest.Objective.MINE, "minecraft:stone", 2);
        assertTrue(goal.recordEvent(null, Quest.Objective.MINE, "minecraft:stone"));
        assertEquals(1, goal.progress());
        assertTrue(goal.contributions().isEmpty());
    }

    @Test
    void leaderboardOrderIsStableWhenPlayersTie() {
        ServerGoal goal = goal(Quest.Objective.KILL, "minecraft:zombie", 4);
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        goal.recordEvent(one, Quest.Objective.KILL, "minecraft:zombie");
        goal.recordEvent(two, Quest.Objective.KILL, "minecraft:zombie");
        assertEquals(goal.leaderboard(), goal.leaderboard());
        assertEquals(2, goal.leaderboard().size());
    }
}
