package com.lolens.services.lcu;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LcuProcessResolverTest {

    @Test
    void parsesTencentCommandLine() {
        String cmd = "f:/lei/英雄联盟/LeagueClient/LeagueClientUx.exe "
                + "\"--remoting-auth-token=NYVguVLDA7GkhIzdz0vIOA\" "
                + "\"--app-port=50085\" \"--app-pid=35620\"";
        Optional<Lockfile> lf = LcuProcessResolver.parseFromCommandLine(cmd);
        assertTrue(lf.isPresent());
        assertEquals(50085, lf.get().port());
        assertEquals("NYVguVLDA7GkhIzdz0vIOA", lf.get().password());
        assertEquals(35620, lf.get().pid());
        assertEquals("https", lf.get().protocol());
        assertEquals("https://127.0.0.1:50085", lf.get().baseUrl());
    }

    @Test
    void ignoresRiotClientAuthToken() {
        // Riot 客户端自身的 --riotclient-app-port / --riotclient-auth-token 不应被误当作 LCU 凭据。
        String cmd = "\"--riotclient-auth-token=AAA\" \"--riotclient-app-port=62821\" "
                + "\"--remoting-auth-token=LCU_TOKEN\" \"--app-port=1234\"";
        Optional<Lockfile> lf = LcuProcessResolver.parseFromCommandLine(cmd);
        assertTrue(lf.isPresent());
        assertEquals(1234, lf.get().port());
        assertEquals("LCU_TOKEN", lf.get().password());
    }

    @Test
    void returnsEmptyWhenNoLcuArgs() {
        assertTrue(LcuProcessResolver.parseFromCommandLine("").isEmpty());
        assertTrue(LcuProcessResolver.parseFromCommandLine(null).isEmpty());
        assertTrue(LcuProcessResolver.parseFromCommandLine("some unrelated command").isEmpty());
    }

    @Test
    void rejectsInvalidPort() {
        assertTrue(LcuProcessResolver.parseFromCommandLine("--app-port=0 --remoting-auth-token=x").isEmpty());
        assertTrue(LcuProcessResolver.parseFromCommandLine("--app-port=70000 --remoting-auth-token=x").isEmpty());
    }
}
