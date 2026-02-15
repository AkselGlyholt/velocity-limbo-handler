package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

class ReconnectQueueStateTest {

    @Test
    void getNextQueuedPlayer_prioritizesBypassThenPriorityThenNormal() {
        Map<UUID, Player> activePlayers = new ConcurrentHashMap<>();
        ReconnectQueueState state = new ReconnectQueueState(id -> {
        }, activePlayers::get);
        RegisteredServer server = mockServer("survival");

        Player normal = mockPlayer(UUID.randomUUID(), "Normal", activePlayers);
        Player priority = mockPlayer(UUID.randomUUID(), "Priority", activePlayers);
        Player bypass = mockPlayer(UUID.randomUUID(), "Bypass", activePlayers);

        when(normal.hasPermission(anyString())).thenReturn(false);
        when(priority.hasPermission("vlh.queue.priority")).thenReturn(true);
        when(bypass.hasPermission("vlh.queue.bypass")).thenReturn(true);

        state.enqueue(normal, server);
        state.enqueue(priority, server);
        state.enqueue(bypass, server);

        assertSame(bypass, state.getNextQueuedPlayer(server));
    }

    @Test
    void getQueuePosition_skipsAndRemovesStaleEntries() {
        Map<UUID, Player> activePlayers = new ConcurrentHashMap<>();
        List<UUID> removedStale = new ArrayList<>();
        ReconnectQueueState state = new ReconnectQueueState(removedStale::add, activePlayers::get);
        RegisteredServer server = mockServer("survival");

        Player stale = mockPlayer(UUID.randomUUID(), "Stale", activePlayers);
        Player target = mockPlayer(UUID.randomUUID(), "Target", activePlayers);
        Player other = mockPlayer(UUID.randomUUID(), "Other", activePlayers);

        activePlayers.remove(stale.getUniqueId());

        state.enqueue(stale, server);
        state.enqueue(target, server);
        state.enqueue(other, server);

        int position = state.getQueuePosition(target.getUniqueId(), "survival");

        assertEquals(1, position);
        assertTrue(removedStale.contains(stale.getUniqueId()));
        assertEquals(List.of(target.getUniqueId(), other.getUniqueId()),
                state.getQueueForServer("survival").stream().map(PlayerManager.QueuedPlayer::uuid).toList());
    }

    @Test
    void findFirstMaintenanceAllowedPlayer_respectsEligibilityChecks() {
        Map<UUID, Player> activePlayers = new ConcurrentHashMap<>();
        ReconnectQueueState state = new ReconnectQueueState(id -> {
        }, activePlayers::get);
        RegisteredServer server = mockServer("factions");

        Player regular = mockPlayer(UUID.randomUUID(), "Regular", activePlayers);
        Player admin = mockPlayer(UUID.randomUUID(), "Admin", activePlayers);
        Player whitelisted = mockPlayer(UUID.randomUUID(), "Whitelisted", activePlayers);

        when(admin.hasPermission("maintenance.admin")).thenReturn(true);

        state.enqueue(regular, server);
        state.enqueue(admin, server);
        state.enqueue(whitelisted, server);

        try (MockedStatic<Utility> mockedUtility = mockStatic(Utility.class)) {
            mockedUtility.when(() -> Utility.playerMaintenanceWhitelisted(whitelisted)).thenReturn(true);
            mockedUtility.when(() -> Utility.playerMaintenanceWhitelisted(regular)).thenReturn(false);
            mockedUtility.when(() -> Utility.playerMaintenanceWhitelisted(admin)).thenReturn(false);

            assertSame(admin, state.findFirstMaintenanceAllowedPlayer(server));

            state.removePlayer(admin.getUniqueId());
            assertSame(whitelisted, state.findFirstMaintenanceAllowedPlayer(server));

            state.removePlayer(whitelisted.getUniqueId());
            assertNull(state.findFirstMaintenanceAllowedPlayer(server));
        }
    }

    @Test
    void pruneInactivePlayers_updatesCountsAndServerMap() {
        Map<UUID, Player> activePlayers = new ConcurrentHashMap<>();
        ReconnectQueueState state = new ReconnectQueueState(id -> {
        }, activePlayers::get);
        RegisteredServer survival = mockServer("survival");
        RegisteredServer factions = mockServer("factions");

        Player active = mockPlayer(UUID.randomUUID(), "Active", activePlayers);
        Player stale = mockPlayer(UUID.randomUUID(), "Stale", activePlayers);
        Player secondActive = mockPlayer(UUID.randomUUID(), "Second", activePlayers);

        activePlayers.remove(stale.getUniqueId());

        state.enqueue(active, survival);
        state.enqueue(stale, survival);
        state.enqueue(secondActive, factions);

        state.pruneInactivePlayers();

        assertEquals(2, state.getQueuedPlayerCount());
        assertEquals(2, state.getQueuedServerCount());
        assertEquals(Map.of("survival", 1, "factions", 1), state.getQueuedServerCounts());
    }

    private RegisteredServer mockServer(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        return server;
    }

    private Player mockPlayer(UUID id, String username, Map<UUID, Player> activePlayers) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(id);
        when(player.getUsername()).thenReturn(username);
        when(player.hasPermission(anyString())).thenReturn(false);
        activePlayers.put(id, player);
        return player;
    }
}
