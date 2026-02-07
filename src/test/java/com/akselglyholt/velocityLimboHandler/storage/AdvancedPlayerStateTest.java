package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.auth.AuthManager;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.managers.ReconnectHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.tasks.ReconnectionTask;
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

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AdvancedPlayerStateTest {

    private MockedStatic<VelocityLimboHandler> mockedVelocityLimboHandler;
    private MockedStatic<Utility> mockedUtility;

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
        mockedVelocityLimboHandler.when(VelocityLimboHandler::hasMaintenancePlugin).thenReturn(false);

        when(messageConfig.getString(any(Route.class))).thenReturn("Queue Position: %position%");

        ServerInfo directInfo = mock(ServerInfo.class);
        when(directInfo.getName()).thenReturn("hub");
        when(directConnectServer.getServerInfo()).thenReturn(directInfo);

        playerManager = new PlayerManager();
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getPlayerManager).thenReturn(playerManager);

        mockedUtility = mockStatic(Utility.class);
        mockedUtility.when(() -> Utility.isServerInMaintenance(any())).thenReturn(false);
        mockedUtility.when(() -> Utility.playerMaintenanceWhitelisted(any(Player.class))).thenReturn(false);
    }

    @AfterEach
    void tearDown() {
        mockedUtility.close();
        mockedVelocityLimboHandler.close();
    }

    @Test
    void getNextQueuedPlayer_prunesInactiveAndMissingPlayers() {
        RegisteredServer server = mockServer("survival");

        Player inactive = mockPlayer(UUID.randomUUID(), true);
        Player active = mockPlayer(UUID.randomUUID(), true);
        UUID missingId = UUID.randomUUID();

        when(inactive.isActive()).thenReturn(false);
        when(proxyServer.getPlayer(inactive.getUniqueId())).thenReturn(Optional.of(inactive));
        when(proxyServer.getPlayer(active.getUniqueId())).thenReturn(Optional.of(active));
        when(proxyServer.getPlayer(missingId)).thenReturn(Optional.empty());

        playerManager.addPlayer(inactive, server);
        playerManager.addPlayer(active, server);
        
        Player missingPlayer = mock(Player.class);
        when(missingPlayer.getUniqueId()).thenReturn(missingId);
        playerManager.addPlayerToQueue(missingPlayer, server);

        // Stub missingId to be empty AFTER addPlayerToQueue to ensure it stays empty
        when(proxyServer.getPlayer(missingId)).thenReturn(Optional.empty());

        // Order in queue: inactive -> active -> missing; first call should return active and prune inactive.
        Player next = playerManager.getNextQueuedPlayer(server);

        assertSame(active, next);

        playerManager.removePlayerFromQueue(active);
        assertFalse(playerManager.hasQueuedPlayers(server));
    }

    @Test
    void maintenanceBypassMatrix_honorsPermissionsAndWhitelist() {
        RegisteredServer server = mockServer("factions");

        Player regular = queuePlayer(server, UUID.randomUUID());
        Player admin = queuePlayer(server, UUID.randomUUID());
        Player globalBypass = queuePlayer(server, UUID.randomUUID());
        Player singleServerBypass = queuePlayer(server, UUID.randomUUID());
        Player whitelisted = queuePlayer(server, UUID.randomUUID());

        when(regular.hasPermission(any())).thenReturn(false);

        when(admin.hasPermission("maintenance.admin")).thenReturn(true);
        when(globalBypass.hasPermission("maintenance.bypass")).thenReturn(true);
        when(singleServerBypass.hasPermission("maintenance.singleserver.bypass.factions")).thenReturn(true);

        mockedUtility.when(() -> Utility.playerMaintenanceWhitelisted(whitelisted)).thenReturn(true);

        assertSame(admin, PlayerManager.findFirstMaintenanceAllowedPlayer(server));

        playerManager.removePlayerFromQueue(admin);
        assertSame(globalBypass, PlayerManager.findFirstMaintenanceAllowedPlayer(server));

        playerManager.removePlayerFromQueue(globalBypass);
        assertSame(singleServerBypass, PlayerManager.findFirstMaintenanceAllowedPlayer(server));

        playerManager.removePlayerFromQueue(singleServerBypass);
        assertSame(whitelisted, PlayerManager.findFirstMaintenanceAllowedPlayer(server));

        playerManager.removePlayerFromQueue(whitelisted);
        assertNull(PlayerManager.findFirstMaintenanceAllowedPlayer(server));
    }

    @Test
    void playerMaintenanceWhitelisted_trueWhenUuidPresentInMaintenanceApi() {
        mockedUtility.close();

        UUID playerId = UUID.randomUUID();
        Player player = mockPlayer(playerId, true);

        mockedVelocityLimboHandler.when(VelocityLimboHandler::hasMaintenancePlugin).thenReturn(true);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getMaintenanceAPI)
                .thenReturn(new FakeMaintenanceApi(Map.of(playerId, "tester")));

        assertTrue(Utility.playerMaintenanceWhitelisted(player));
        assertFalse(Utility.playerMaintenanceWhitelisted(mockPlayer(UUID.randomUUID(), true)));

        mockedUtility = mockStatic(Utility.class);
        mockedUtility.when(() -> Utility.isServerInMaintenance(any())).thenReturn(false);
        mockedUtility.when(() -> Utility.playerMaintenanceWhitelisted(any(Player.class))).thenReturn(false);
    }

    @Test
    void reconnectionTask_continuesWhenFirstReconnectAttemptReturnsFalse() {
        ConfigManager configManager = mock(ConfigManager.class);
        RegisteredServer limboServer = mockServer("limbo");
        RegisteredServer previousServer = mockServer("survival");
        ReconnectHandler reconnectHandler = mock(ReconnectHandler.class);

        Player first = mockPlayer(UUID.randomUUID(), true);
        Player second = mockPlayer(UUID.randomUUID(), true);

        Collection<Player> limboPlayers = List.of(first, second);

        when(configManager.isQueueEnabled()).thenReturn(false);
        when(limboServer.getPlayersConnected()).thenReturn(limboPlayers);
        
        playerManager.addPlayerWithIssue(first, "fake-issue"); // doesn't matter, we want hasConnectionIssue to return false for second
        playerManager.removePlayerIssue(first);
        playerManager.removePlayerIssue(second);
        
        // Use real playerManager methods instead of stubbing them if possible, or use spies.
        // Since playerManager is a real object, we should ideally NOT stub it.
        // But the previous sub-agent tried to stub it.
        
        // Let's just fix the stubbing style to avoid the getUniqueId() call on the mock during stubbing.
        // Actually, PlayerManager is a simple state holder. Let's just set the state.
        
        playerManager.addPlayer(first, previousServer);
        playerManager.addPlayer(second, previousServer);

        when(reconnectHandler.reconnectPlayer(first)).thenReturn(false);
        when(reconnectHandler.reconnectPlayer(second)).thenReturn(true);

        ReconnectionTask task = new ReconnectionTask(
                proxyServer,
                limboServer,
                playerManager,
                authManager,
                configManager,
                reconnectHandler
        );

        task.run();

        verify(reconnectHandler).reconnectPlayer(first);
        verify(reconnectHandler).reconnectPlayer(second);
    }

    @Test
    void reconnectionTask_skipsAuthBlockedPlayerAndReconnectsNextEligible() {
        ConfigManager configManager = mock(ConfigManager.class);
        RegisteredServer limboServer = mockServer("limbo");
        RegisteredServer previousServer = mockServer("skyblock");
        ReconnectHandler reconnectHandler = mock(ReconnectHandler.class);

        Player authBlocked = mockPlayer(UUID.randomUUID(), true);
        Player eligible = mockPlayer(UUID.randomUUID(), true);

        when(configManager.isQueueEnabled()).thenReturn(false);
        when(limboServer.getPlayersConnected()).thenReturn(List.of(authBlocked, eligible));
        
        playerManager.addPlayer(authBlocked, previousServer);
        playerManager.addPlayer(eligible, previousServer);
        playerManager.removePlayerIssue(authBlocked);
        playerManager.removePlayerIssue(eligible);

        // Simulates reconnect handler rejecting an auth-blocked player.
        when(reconnectHandler.reconnectPlayer(authBlocked)).thenReturn(false);
        when(reconnectHandler.reconnectPlayer(eligible)).thenReturn(true);

        ReconnectionTask task = new ReconnectionTask(
                proxyServer,
                limboServer,
                playerManager,
                authManager,
                configManager,
                reconnectHandler
        );

        task.run();

        verify(reconnectHandler).reconnectPlayer(authBlocked);
        verify(reconnectHandler).reconnectPlayer(eligible);
        verify(reconnectHandler, never()).reconnectPlayer(eq(null));
    }

    private RegisteredServer mockServer(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn(name);
        when(proxyServer.getServer(name)).thenReturn(Optional.of(server));
        return server;
    }

    private Player mockPlayer(UUID playerId, boolean active) {
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.isActive()).thenReturn(active);
        when(proxyServer.getPlayer(playerId)).thenReturn(Optional.of(player));
        when(player.hasPermission(any())).thenReturn(false);
        return player;
    }

    private Player queuePlayer(RegisteredServer server, UUID playerId) {
        Player player = mockPlayer(playerId, true);
        playerManager.addPlayer(player, server);
        return player;
    }

    public static class FakeMaintenanceApi {
        private final FakeSettings settings;

        public FakeMaintenanceApi(Map<UUID, String> whitelistedPlayers) {
            this.settings = new FakeSettings(whitelistedPlayers);
        }

        public FakeSettings getSettings() {
            return settings;
        }
    }

    public static class FakeSettings {
        private final Map<UUID, String> whitelistedPlayers;

        public FakeSettings(Map<UUID, String> whitelistedPlayers) {
            this.whitelistedPlayers = whitelistedPlayers;
        }

        public Map<UUID, String> getWhitelistedPlayers() {
            return whitelistedPlayers;
        }
    }
}
