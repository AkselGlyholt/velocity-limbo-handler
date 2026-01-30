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

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlayerManagerTest {

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
        
        // Default behavior
        mockedVelocityLimboHandler.when(VelocityLimboHandler::isQueueEnabled).thenReturn(true);
        when(messageConfig.getString(any(Route.class))).thenReturn("Queue Position: %position%");
        
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
    void testAddPlayer() {
        Player player = mock(Player.class);
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn("survival");
        
        // Stub proxyServer to return the server
        when(proxyServer.getServer("survival")).thenReturn(Optional.of(server));

        playerManager.addPlayer(player, server);

        assertTrue(playerManager.isPlayerRegistered(player));
        assertEquals(server, playerManager.getPreviousServer(player));
    }

    @Test
    void testRemovePlayer() {
        Player player = mock(Player.class);
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        UUID uuid = UUID.randomUUID();

        when(player.getUniqueId()).thenReturn(uuid);
        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn("survival");

        playerManager.addPlayer(player, server);
        assertTrue(playerManager.isPlayerRegistered(player));

        playerManager.removePlayer(player);

        assertFalse(playerManager.isPlayerRegistered(player));
        verify(reconnectBlocker).unblock(uuid);
    }

    @Test
    void testQueueLogic() {
        Player player1 = mock(Player.class);
        Player player2 = mock(Player.class);
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);

        when(server.getServerInfo()).thenReturn(info);
        when(info.getName()).thenReturn("survival");

        playerManager.addPlayer(player1, server);
        playerManager.addPlayer(player2, server); // Implicitly adds to queue if queue enabled

        assertTrue(playerManager.hasQueuedPlayers(server));
        
        // Verify queue order
        assertEquals(player1, playerManager.getNextQueuedPlayer(server));
        
        // getNextQueuedPlayer acts as peek, doesn't remove
        assertEquals(player1, playerManager.getNextQueuedPlayer(server)); 
        
        // Remove player 1
        playerManager.removePlayerFromQueue(player1);
        
        assertEquals(player2, playerManager.getNextQueuedPlayer(server));
    }
}
