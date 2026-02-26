package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.route.Route;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerManagerIntegrationTest {

    private MockedStatic<VelocityLimboHandler> mockedVelocityLimboHandler;
    private AuthManager authManager;
    private ReconnectBlocker reconnectBlocker;
    private ProxyServer proxyServer;
    private YamlDocument messageConfig;
    private RegisteredServer directConnectServer;
    private Logger logger;

    private PlayerManager playerManager;

    @BeforeEach
    void setUp() {
        mockedVelocityLimboHandler = mockStatic(VelocityLimboHandler.class);
        authManager = mock(AuthManager.class);
        reconnectBlocker = mock(ReconnectBlocker.class);
        proxyServer = mock(ProxyServer.class);
        messageConfig = mock(YamlDocument.class);
        directConnectServer = mock(RegisteredServer.class);
        logger = mock(Logger.class);

        mockedVelocityLimboHandler.when(VelocityLimboHandler::getAuthManager).thenReturn(authManager);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getReconnectBlocker).thenReturn(reconnectBlocker);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getProxyServer).thenReturn(proxyServer);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getMessageConfig).thenReturn(messageConfig);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getDirectConnectServer).thenReturn(directConnectServer);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getLogger).thenReturn(logger);

        mockedVelocityLimboHandler.when(VelocityLimboHandler::isQueueEnabled).thenReturn(true);
        when(messageConfig.getString(any(Route.class))).thenReturn("Queue Position: %position%");
        when(authManager.isAuthBlocked(any(Player.class))).thenReturn(false);

        ServerInfo directInfo = mock(ServerInfo.class);
        when(directInfo.getName()).thenReturn("hub");
        when(directConnectServer.getServerInfo()).thenReturn(directInfo);

        playerManager = new PlayerManager();
    }

    @AfterEach
    void tearDown() {
        mockedVelocityLimboHandler.close();
    }

    @Test
    void addPlayer_registersAndQueues_whenEligible() {
        RegisteredServer survival = mockServer("survival");
        Player player = mockPlayer(UUID.randomUUID(), "Alpha", true);

        playerManager.addPlayer(player, survival);

        assertTrue(playerManager.isPlayerRegistered(player));
        assertTrue(playerManager.hasQueuedPlayers(survival));
        assertEquals(1, playerManager.getQueuePosition(player));
        assertEquals(1, playerManager.getQueuedPlayerCount());
        assertEquals(Map.of("survival", 1), playerManager.getQueuedServerCounts());
    }

    @Test
    void removePlayer_clearsStateQueueAndUnblocksReconnect() {
        RegisteredServer survival = mockServer("survival");
        Player player = mockPlayer(UUID.randomUUID(), "Bravo", true);

        playerManager.addPlayer(player, survival);
        playerManager.setPlayerConnecting(player, true);
        playerManager.addPlayerWithIssue(player, "connection-issue");

        playerManager.removePlayer(player);

        assertFalse(playerManager.isPlayerRegistered(player));
        assertFalse(playerManager.isPlayerConnecting(player));
        assertFalse(playerManager.hasConnectionIssue(player));
        assertFalse(playerManager.hasQueuedPlayers(survival));
        assertEquals(0, playerManager.getQueuedPlayerCount());
        verify(reconnectBlocker).unblock(player.getUniqueId());
    }

    @Test
    void pruneInactivePlayers_cleansQueueAndConnectionViews() {
        RegisteredServer survival = mockServer("survival");
        Player active = mockPlayer(UUID.randomUUID(), "Active", true);
        Player stale = mockPlayer(UUID.randomUUID(), "Stale", true);

        playerManager.addPlayer(active, survival);
        playerManager.addPlayer(stale, survival);
        playerManager.setPlayerConnecting(active, true);
        playerManager.setPlayerConnecting(stale, true);
        playerManager.addPlayerWithIssue(stale, "timed-out");

        assertEquals(2, playerManager.getQueuedPlayerCount());

        when(proxyServer.getPlayer(stale.getUniqueId())).thenReturn(Optional.empty());

        assertEquals(1, playerManager.getQueuedPlayerCount());
        assertEquals(Map.of("survival", 1), playerManager.getQueuedServerCounts());
        assertTrue(playerManager.isPlayerRegistered(active));
        assertFalse(playerManager.isPlayerRegistered(stale));
        assertTrue(playerManager.isPlayerConnecting(active));
        assertFalse(playerManager.isPlayerConnecting(stale));
        assertFalse(playerManager.hasConnectionIssue(stale));
    }

    private RegisteredServer mockServer(String serverName) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn(serverName);
        when(proxyServer.getServer(serverName)).thenReturn(Optional.of(server));
        return server;
    }

    private Player mockPlayer(UUID playerId, String username, boolean active) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn(username);
        when(player.isActive()).thenReturn(active);
        when(player.hasPermission(any())).thenReturn(false);
        when(proxyServer.getPlayer(playerId)).thenReturn(Optional.of(player));
        return player;
    }
}
