package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.Optional;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;
import dev.dejvokep.boostedyaml.YamlDocument;
import dev.dejvokep.boostedyaml.route.Route;
import static org.mockito.Mockito.*;

class UtilityTest {

    private MockedStatic<VelocityLimboHandler> mockedVelocityLimboHandler;
    private ProxyServer proxyServer;
    private Logger logger;
    private YamlDocument messageConfig;

    @BeforeEach
    void setUp() {
        mockedVelocityLimboHandler = mockStatic(VelocityLimboHandler.class);
        proxyServer = mock(ProxyServer.class);
        logger = mock(Logger.class);
        messageConfig = mock(YamlDocument.class);

        mockedVelocityLimboHandler.when(VelocityLimboHandler::getProxyServer).thenReturn(proxyServer);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getLogger).thenReturn(logger);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getMessageConfig).thenReturn(messageConfig);
        
        when(messageConfig.getString(any(Route.class))).thenReturn("Welcome!");
    }

    @AfterEach
    void tearDown() {
        mockedVelocityLimboHandler.close();
    }

    @Test
    void testDoServerNamesMatch() {
        RegisteredServer server1 = mock(RegisteredServer.class);
        RegisteredServer server2 = mock(RegisteredServer.class);
        ServerInfo info1 = mock(ServerInfo.class);
        ServerInfo info2 = mock(ServerInfo.class);

        when(server1.getServerInfo()).thenReturn(info1);
        when(server2.getServerInfo()).thenReturn(info2);
        when(info1.getName()).thenReturn("limbo");
        when(info2.getName()).thenReturn("limbo");

        assertTrue(Utility.doServerNamesMatch(server1, server2));

        when(info2.getName()).thenReturn("lobby");
        assertFalse(Utility.doServerNamesMatch(server1, server2));
    }

    @Test
    void testGetServerByName_Found() {
        String serverName = "limbo";
        RegisteredServer server = mock(RegisteredServer.class);
        when(proxyServer.getServer(serverName)).thenReturn(Optional.of(server));

        RegisteredServer result = Utility.getServerByName(serverName);

        assertNotNull(result);
        assertEquals(server, result);
    }

    @Test
    void testGetServerByName_NotFound() {
        String serverName = "invalid";
        when(proxyServer.getServer(serverName)).thenReturn(Optional.empty());

        RegisteredServer result = Utility.getServerByName(serverName);

        assertNull(result);
        verify(logger).severe(contains("is invalid"));
    }
}
