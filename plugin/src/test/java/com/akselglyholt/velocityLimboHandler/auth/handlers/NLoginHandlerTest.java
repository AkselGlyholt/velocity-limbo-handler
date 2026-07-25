package com.akselglyholt.velocityLimboHandler.auth.handlers;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NLoginHandlerTest {
    private MockedStatic<VelocityLimboHandler> plugin;
    private PlayerManager playerManager;
    private ReconnectBlocker blocker;
    private VelocityLimboApiImpl api;
    private NLoginHandler handler;
    private ServerPreConnectEvent event;
    private Player player;
    private RegisteredServer intendedServer;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        plugin = mockStatic(VelocityLimboHandler.class);
        ProxyServer proxy = mock(ProxyServer.class);
        PluginManager pluginManager = mock(PluginManager.class);
        playerManager = mock(PlayerManager.class);
        blocker = mock(ReconnectBlocker.class);
        api = mock(VelocityLimboApiImpl.class);
        RegisteredServer limbo = mock(RegisteredServer.class);
        intendedServer = mock(RegisteredServer.class);
        ServerInfo intendedInfo = mock(ServerInfo.class);
        ServerConnection currentConnection = mock(ServerConnection.class);
        event = mock(ServerPreConnectEvent.class);
        player = mock(Player.class);
        playerId = UUID.randomUUID();

        plugin.when(VelocityLimboHandler::getPlayerManager).thenReturn(playerManager);
        plugin.when(VelocityLimboHandler::getLogger).thenReturn(mock(Logger.class));
        plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
        when(proxy.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("nlogin")).thenReturn(Optional.empty());
        when(event.getPlayer()).thenReturn(player);
        when(event.getOriginalServer()).thenReturn(intendedServer);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getCurrentServer()).thenReturn(Optional.of(currentConnection));
        when(currentConnection.getServer()).thenReturn(limbo);
        when(intendedServer.getServerInfo()).thenReturn(intendedInfo);
        when(intendedInfo.getName()).thenReturn("survival");

        handler = new NLoginHandler(proxy, blocker, api);
    }

    @AfterEach
    void tearDown() {
        plugin.close();
    }

    @Test
    void heldPlayerIsLeftForConnectionListener() {
        when(api.isPlayerHeld(playerId)).thenReturn(true);

        handler.onServerPreConnect(event);

        verify(event, never()).setResult(any());
        verify(playerManager, never()).addPlayer(any(), any());
    }

    @Test
    void unownedRedirectIsDeniedAndRegistered() {
        handler.onServerPreConnect(event);

        verify(event).setResult(any());
        verify(playerManager).addPlayer(player, intendedServer);
    }
}
