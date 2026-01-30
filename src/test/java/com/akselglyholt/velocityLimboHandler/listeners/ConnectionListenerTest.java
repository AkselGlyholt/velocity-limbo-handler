package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.config.ProxyConfig;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.net.InetSocketAddress;
import java.util.*;
import java.util.logging.Logger;

import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.route.Route;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class ConnectionListenerTest {

    private MockedStatic<VelocityLimboHandler> mockedVelocityLimboHandler;
    private PlayerManager playerManager;
    private ProxyServer proxyServer;
    private RegisteredServer limboServer;
    private RegisteredServer directConnectServer;
    private ReconnectBlocker reconnectBlocker;
    private Logger logger;
    private YamlDocument messageConfig;

    private ConnectionListener connectionListener;

    @BeforeEach
    void setUp() {
        mockedVelocityLimboHandler = mockStatic(VelocityLimboHandler.class);
        playerManager = mock(PlayerManager.class);
        proxyServer = mock(ProxyServer.class);
        limboServer = mock(RegisteredServer.class);
        directConnectServer = mock(RegisteredServer.class);
        reconnectBlocker = mock(ReconnectBlocker.class);
        logger = mock(Logger.class);
        messageConfig = mock(YamlDocument.class);

        mockedVelocityLimboHandler.when(VelocityLimboHandler::getPlayerManager).thenReturn(playerManager);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getProxyServer).thenReturn(proxyServer);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getLimboServer).thenReturn(limboServer);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getDirectConnectServer).thenReturn(directConnectServer);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getReconnectBlocker).thenReturn(reconnectBlocker);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getLogger).thenReturn(logger);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getMessageConfig).thenReturn(messageConfig);

        when(messageConfig.getString(any(Route.class))).thenReturn("Welcome!");

        // Setup server names
        ServerInfo limboInfo = mock(ServerInfo.class);
        when(limboInfo.getName()).thenReturn("limbo");
        when(limboServer.getServerInfo()).thenReturn(limboInfo);

        ServerInfo directInfo = mock(ServerInfo.class);
        when(directInfo.getName()).thenReturn("hub");
        when(directConnectServer.getServerInfo()).thenReturn(directInfo);

        connectionListener = new ConnectionListener();
    }

    @AfterEach
    void tearDown() {
        mockedVelocityLimboHandler.close();
    }

    @Test
    void testOnPlayerPreConnect_RerouteToLimbo() {
        ServerPreConnectEvent event = mock(ServerPreConnectEvent.class);
        Player player = mock(Player.class);
        RegisteredServer intendedServer = mock(RegisteredServer.class);
        ServerInfo intendedInfo = mock(ServerInfo.class);

        when(event.getPlayer()).thenReturn(player);
        when(event.getOriginalServer()).thenReturn(intendedServer);
        when(intendedServer.getServerInfo()).thenReturn(intendedInfo);
        when(intendedInfo.getName()).thenReturn("survival");

        when(playerManager.isPlayerConnecting(player)).thenReturn(false);
        when(playerManager.hasQueuedPlayers(intendedServer)).thenReturn(true);

        connectionListener.onPlayerPreConnect(event);

        verify(event).setResult(argThat(result -> 
            result.getServer().isPresent() && result.getServer().get().equals(limboServer)
        ));
    }

    @Test
    void testOnPlayerPreConnect_NoRerouteIfAlreadyLimbo() {
        ServerPreConnectEvent event = mock(ServerPreConnectEvent.class);
        Player player = mock(Player.class);
        RegisteredServer intendedServer = mock(RegisteredServer.class);
        ServerInfo intendedInfo = mock(ServerInfo.class);

        when(event.getPlayer()).thenReturn(player);
        when(event.getOriginalServer()).thenReturn(intendedServer);
        when(intendedServer.getServerInfo()).thenReturn(intendedInfo);
        when(intendedInfo.getName()).thenReturn("limbo");

        connectionListener.onPlayerPreConnect(event);

        verify(event, never()).setResult(any());
    }

    @Test
    void testOnPlayerPostConnect_JoinedLimbo() {
        ServerPostConnectEvent event = mock(ServerPostConnectEvent.class);
        Player player = mock(Player.class);
        ServerConnection serverConnection = mock(ServerConnection.class);

        when(event.getPlayer()).thenReturn(player);
        when(player.getCurrentServer()).thenReturn(Optional.of(serverConnection));
        when(serverConnection.getServer()).thenReturn(limboServer);
        when(event.getPreviousServer()).thenReturn(null);

        // Mock VirtualHost
        when(player.getVirtualHost()).thenReturn(Optional.empty());

        connectionListener.onPlayerPostConnect(event);

        // Should be added to player manager targeting directConnectServer (since no previous server and no virtual host)
        verify(playerManager).addPlayer(player, directConnectServer);
    }
    
    @Test
    void testOnPlayerPostConnect_LeftLimbo() {
        ServerPostConnectEvent event = mock(ServerPostConnectEvent.class);
        Player player = mock(Player.class);
        ServerConnection serverConnection = mock(ServerConnection.class);
        RegisteredServer currentServer = mock(RegisteredServer.class);
        ServerInfo currentInfo = mock(ServerInfo.class);

        when(event.getPlayer()).thenReturn(player);
        when(player.getCurrentServer()).thenReturn(Optional.of(serverConnection));
        when(serverConnection.getServer()).thenReturn(currentServer);
        when(currentServer.getServerInfo()).thenReturn(currentInfo);
        when(currentInfo.getName()).thenReturn("hub");
        
        when(event.getPreviousServer()).thenReturn(limboServer);

        connectionListener.onPlayerPostConnect(event);

        verify(playerManager).removePlayer(player);
    }

    @Test
    void testOnDisconnect() {
        DisconnectEvent event = mock(DisconnectEvent.class);
        Player player = mock(Player.class);
        UUID uuid = UUID.randomUUID();

        when(event.getPlayer()).thenReturn(player);
        when(player.getUniqueId()).thenReturn(uuid);

        connectionListener.onDisconnect(event);

        verify(playerManager).removePlayer(player);
        verify(playerManager).removePlayerIssue(player);
        verify(reconnectBlocker).unblock(uuid);
    }
}
