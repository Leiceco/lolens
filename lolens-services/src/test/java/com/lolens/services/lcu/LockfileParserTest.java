package com.lolens.services.lcu;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockfileParserTest {

    @Test
    void parsesValidLockfile() {
        Optional<Lockfile> lf = LockfileParser.parseFromContent(
                "LeagueClient:12345:56789:abc123:https");
        assertTrue(lf.isPresent());
        assertEquals("LeagueClient", lf.get().processName());
        assertEquals(12345, lf.get().pid());
        assertEquals(56789, lf.get().port());
        assertEquals("abc123", lf.get().password());
        assertEquals("https", lf.get().protocol());
        assertEquals("https://127.0.0.1:56789", lf.get().baseUrl());
    }

    @Test
    void rejectsNonHttpsProtocol() {
        assertTrue(LockfileParser.parseFromContent("LeagueClient:1:2:pw:http").isEmpty());
    }

    @Test
    void rejectsInvalidPort() {
        assertTrue(LockfileParser.parseFromContent("LeagueClient:1:0:pw:https").isEmpty());
        assertTrue(LockfileParser.parseFromContent("LeagueClient:1:70000:pw:https").isEmpty());
    }

    @Test
    void rejectsMalformedContent() {
        assertTrue(LockfileParser.parseFromContent("").isEmpty());
        assertTrue(LockfileParser.parseFromContent(null).isEmpty());
        assertTrue(LockfileParser.parseFromContent("only-two-parts").isEmpty());
        assertTrue(LockfileParser.parseFromContent("a:b:c:d").isEmpty());
    }

    @Test
    void rejectsBlankPassword() {
        assertTrue(LockfileParser.parseFromContent("LeagueClient:1:2::https").isEmpty());
    }
}
