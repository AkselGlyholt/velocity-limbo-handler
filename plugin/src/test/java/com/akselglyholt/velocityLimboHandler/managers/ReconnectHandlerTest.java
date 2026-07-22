package com.akselglyholt.velocityLimboHandler.managers;

import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.ReconnectOutcome;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReconnectHandlerTest {
    private PlayerManager playerManager;
    private BackendHealthTracker healthTracker;
    private Player player;
    private RegisteredServer server;
    private ReconnectHandler reconnectHandler;
    private MockedStatic<Utility> utility;

    @BeforeEach
    void setUp() {
        playerManager = mock(PlayerManager.class);
        healthTracker = mock(BackendHealthTracker.class);
        player = mock(Player.class);
        server = mock(RegisteredServer.class);
        ServerInfo serverInfo = mock(ServerInfo.class);
        AuthManager authManager = mock(AuthManager.class);
        ConfigManager configManager = mock(ConfigManager.class);
        Logger logger = mock(Logger.class);
        utility = mockStatic(Utility.class);

        when(player.isActive()).thenReturn(true);
        when(player.getUsername()).thenReturn("TestPlayer");
        when(playerManager.getPreviousServer(player)).thenReturn(server);
        when(server.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("survival");

        reconnectHandler = new ReconnectHandler(
                playerManager,
                authManager,
                configManager,
                logger,
                healthTracker
        );
    }

    @AfterEach
    void tearDown() {
        utility.close();
    }

    @Test
    void exceptionalProbeAlwaysClearsConnectingState() {
        when(healthTracker.probe(server)).thenReturn(
                CompletableFuture.failedFuture(new IllegalStateException("probe failed"))
        );

        assertTrue(reconnectHandler.reconnectPlayer(player));

        verify(playerManager).setPlayerConnecting(player, true);
        verify(playerManager).setPlayerConnecting(player, false);
        verify(player, never()).createConnectionRequest(any());
    }

    @Test
    void exceptionalConnectionInvalidatesProbeAndClearsConnectingState() {
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        when(healthTracker.probe(server)).thenReturn(
                CompletableFuture.completedFuture(new BackendHealthTracker.Availability(true, false, 5))
        );
        when(player.createConnectionRequest(server)).thenReturn(request);
        when(request.connect()).thenReturn(connection);

        assertTrue(reconnectHandler.reconnectPlayer(player));
        connection.completeExceptionally(new IllegalStateException("connection failed"));

        verify(healthTracker).invalidate("survival");
        verify(playerManager).setPlayerConnecting(player, false);
    }

    @Test
    void synchronousConnectionFailureClearsConnectingState() {
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        when(healthTracker.probe(server)).thenReturn(
                CompletableFuture.completedFuture(new BackendHealthTracker.Availability(true, false, 5))
        );
        when(player.createConnectionRequest(server)).thenReturn(request);
        when(request.connect()).thenThrow(new IllegalStateException("connection failed"));

        assertTrue(reconnectHandler.reconnectPlayer(player));

        verify(healthTracker).invalidate("survival");
        verify(playerManager).setPlayerConnecting(player, false);
    }

    @Test
    void connectionInProgressPreservesLegacyConnectingState() {
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(healthTracker.probe(server)).thenReturn(
                CompletableFuture.completedFuture(new BackendHealthTracker.Availability(true, false, 5))
        );
        when(player.createConnectionRequest(server)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS);

        assertTrue(reconnectHandler.reconnectPlayer(player));

        verify(playerManager).setPlayerConnecting(player, true);
        verify(playerManager, never()).setPlayerConnecting(player, false);
    }

    @Test
    void connectionInProgressPreservesApiClaim() {
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(playerManager.usesApiLifecycle()).thenReturn(true);
        when(playerManager.tryClaimConnection(player)).thenReturn(true);
        when(healthTracker.probe(server)).thenReturn(
                CompletableFuture.completedFuture(new BackendHealthTracker.Availability(true, false, 5))
        );
        when(player.createConnectionRequest(server)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS);

        assertTrue(reconnectHandler.reconnectPlayer(player));

        verify(playerManager, never()).finishConnectionAttempt(
                player, "survival", ReconnectOutcome.CONNECTION_IN_PROGRESS, null
        );
    }

    @Test
    void maintenanceEnabledDuringProbeBlocksConnectionWithoutBypass() {
        CompletableFuture<BackendHealthTracker.Availability> probe = new CompletableFuture<>();
        when(healthTracker.probe(server)).thenReturn(probe);

        assertTrue(reconnectHandler.reconnectPlayer(player));
        utility.when(() -> Utility.isServerInMaintenance("survival")).thenReturn(true);
        utility.when(() -> Utility.playerMaintenanceWhitelisted(player)).thenReturn(false);
        probe.complete(new BackendHealthTracker.Availability(true, false, 5));

        verify(player, never()).createConnectionRequest(server);
        verify(playerManager).setPlayerConnecting(player, false);
    }

    @Test
    void maintenanceEnabledDuringProbeAllowsBypassConnection() {
        CompletableFuture<BackendHealthTracker.Availability> probe = new CompletableFuture<>();
        CompletableFuture<ConnectionRequestBuilder.Result> connection = new CompletableFuture<>();
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        when(healthTracker.probe(server)).thenReturn(probe);
        when(player.hasPermission("maintenance.bypass")).thenReturn(true);
        when(player.createConnectionRequest(server)).thenReturn(request);
        when(request.connect()).thenReturn(connection);

        assertTrue(reconnectHandler.reconnectPlayer(player));
        utility.when(() -> Utility.isServerInMaintenance("survival")).thenReturn(true);
        probe.complete(new BackendHealthTracker.Availability(true, false, 5));

        verify(player).createConnectionRequest(server);
    }
}
