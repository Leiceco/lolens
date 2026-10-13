package com.lolens.modules.skill;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTrackerTest {

    private SkillTracker tracker;

    @BeforeEach
    void setUp() {
        tracker = new SkillTracker();
    }

    @Test
    void notCastMeansAvailable() {
        assertFalse(tracker.wasCast("SPELL1"));
        assertEquals(0, tracker.remainingSeconds("SPELL1", 300));
    }

    @Test
    void countsDownAfterCast() {
        tracker.setGameTime(100.0);
        tracker.recordCast("SPELL1");
        assertTrue(tracker.wasCast("SPELL1"));
        assertEquals(300, tracker.remainingSeconds("SPELL1", 300));

        tracker.setGameTime(105.0);
        assertEquals(295, tracker.remainingSeconds("SPELL1", 300));
    }

    @Test
    void floorsAtZero() {
        tracker.setGameTime(100.0);
        tracker.recordCast("SPELL1");
        tracker.setGameTime(500.0);
        assertEquals(0, tracker.remainingSeconds("SPELL1", 300));
    }

    @Test
    void resetClearsAll() {
        tracker.setGameTime(100.0);
        tracker.recordCast("SPELL1");
        tracker.reset();
        assertFalse(tracker.wasCast("SPELL1"));
    }

    @Test
    void cooldownTableHasExpectedValues() {
        assertEquals(300, tracker.cooldownOf("SummonerFlash"));
        assertEquals(15, tracker.cooldownOf("SummonerSmite"));
        assertEquals(210, tracker.cooldownOf("SummonerBoost"));
        assertEquals(0, tracker.cooldownOf("UnknownSpell"));
    }
}
