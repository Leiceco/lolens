package com.lolens.services.opgg.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PositionTest {

    @Test
    void parsesCanonicalSlugs() {
        assertEquals(Position.TOP, Position.fromSlug("top"));
        assertEquals(Position.JUNGLE, Position.fromSlug("jungle"));
        assertEquals(Position.MIDDLE, Position.fromSlug("middle"));
        assertEquals(Position.BOTTOM, Position.fromSlug("bottom"));
        assertEquals(Position.UTILITY, Position.fromSlug("utility"));
    }

    @Test
    void parsesAliases() {
        assertEquals(Position.MIDDLE, Position.fromSlug("mid"));
        assertEquals(Position.BOTTOM, Position.fromSlug("adc"));
        assertEquals(Position.UTILITY, Position.fromSlug("support"));
    }

    @Test
    void ignoresCaseAndWhitespace() {
        assertEquals(Position.TOP, Position.fromSlug(" TOP "));
        assertEquals(Position.JUNGLE, Position.fromSlug("Jungle"));
    }

    @Test
    void returnsNullForUnknownOrBlank() {
        assertNull(Position.fromSlug("unknown"));
        assertNull(Position.fromSlug(""));
        assertNull(Position.fromSlug(null));
    }
}
