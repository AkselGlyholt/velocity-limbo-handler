package com.akselglyholt.velocityLimboHandler.managers;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class BackendHealthTrackerTest {

    @Test
    void probeCoalescesConcurrentRequestsAndReportsFreeSlots() {
        BackendHealthTracker tracker = new BackendHealthTracker();
        RegisteredServer server = mockServer("survival");
        CompletableFuture<ServerPing> pingFuture = new CompletableFuture<>();
        ServerPing ping = mock(ServerPing.class);
        ServerPing.Players players = mock(ServerPing.Players.class);

        when(server.ping()).thenReturn(pingFuture);
        when(ping.getPlayers()).thenReturn(Optional.of(players));
        when(players.getMax()).thenReturn(100);
        when(players.getOnline()).thenReturn(73);

        CompletableFuture<BackendHealthTracker.Availability> first = tracker.probe(server);
        CompletableFuture<BackendHealthTracker.Availability> second = tracker.probe(server);

        assertSame(first, second);
        pingFuture.complete(ping);

        BackendHealthTracker.Availability availability = first.join();
        assertTrue(availability.reachable());
        assertFalse(availability.full());
        assertEquals(27, availability.reportedFreeSlots());
        verify(server, times(1)).ping();
    }

    @Test
    void probeCachesFailureDuringBackoff() {
        BackendHealthTracker tracker = new BackendHealthTracker();
        RegisteredServer server = mockServer("offline");
        when(server.ping()).thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));

        assertFalse(tracker.probe(server).join().reachable());
        assertFalse(tracker.probe(server).join().reachable());

        verify(server, times(1)).ping();
    }

    private RegisteredServer mockServer(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        return server;
    }
}
