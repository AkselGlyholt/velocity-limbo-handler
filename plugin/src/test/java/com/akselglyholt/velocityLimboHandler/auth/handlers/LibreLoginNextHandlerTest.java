package com.akselglyholt.velocityLimboHandler.auth.handlers;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LibreLoginNextHandlerTest {
    private MockedStatic<VelocityLimboHandler> plugin;
    private ProxyServer proxy;
    private ReconnectBlocker blocker;
    private PlayerManager playerManager;
    private Logger logger;
    private LibreLoginNextHandler handler;

    @BeforeEach
    void setUp() {
        plugin = mockStatic(VelocityLimboHandler.class);
        proxy = mock(ProxyServer.class);
        blocker = mock(ReconnectBlocker.class);
        playerManager = mock(PlayerManager.class);
        logger = mock(Logger.class);
        PluginManager pluginManager = mock(PluginManager.class);

        plugin.when(VelocityLimboHandler::getPlayerManager).thenReturn(playerManager);
        plugin.when(VelocityLimboHandler::getLogger).thenReturn(logger);
        when(proxy.getPluginManager()).thenReturn(pluginManager);
        when(pluginManager.getPlugin("libreloginnext")).thenReturn(Optional.empty());

        handler = new LibreLoginNextHandler(proxy, blocker);
    }

    @AfterEach
    void tearDown() {
        plugin.close();
    }

    @Test
    void uuidFallbackResolvesAndRegistersActivePlayer() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        RegisteredServer server = mock(RegisteredServer.class);
        when(player.isActive()).thenReturn(true);
        when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));
        when(playerManager.getPreviousServer(player)).thenReturn(server);

        handler.handleAuthenticationEvent(new UuidEvent(playerId));

        verify(blocker).unblock(playerId);
        verify(playerManager).addPlayer(player, server);
    }

    @Test
    void missingDestinationDoesNotRegisterPlayer() {
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.isActive()).thenReturn(true);
        when(player.getUsername()).thenReturn("Tester");
        when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));

        handler.handleAuthenticationEvent(new UuidEvent(playerId));

        verify(blocker).unblock(playerId);
        verify(playerManager, never()).addPlayer(player, null);
        verify(logger).warning(contains("no destination server is available"));
    }

    public record UuidEvent(UuidUser user) {
        public UuidUser getUser() {
            return user;
        }

        private UuidEvent(UUID playerId) {
            this(new UuidUser(playerId));
        }
    }

    public record UuidUser(UUID uuid) {
        public UUID getUuid() {
            return uuid;
        }
    }
}
