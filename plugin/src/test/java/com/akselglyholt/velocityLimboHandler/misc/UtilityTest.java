package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;

import java.util.Locale;
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
        Utility.clearMaintenanceAdapter();
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
        Utility.clearMaintenanceAdapter();
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

    @Test
    void welcomeReasonMatchingDoesNotDependOnSystemLocale() {
        Locale originalLocale = Locale.getDefault();
        Player player = mock(Player.class);
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            Utility.sendWelcomeMessage(player, "CONNECTION-ISSUE");

            ArgumentCaptor<Component> messageCaptor = ArgumentCaptor.forClass(Component.class);
            verify(player).sendMessage(messageCaptor.capture());
            String message = PlainTextComponentSerializer.plainText().serialize(messageCaptor.getValue());
            assertTrue(message.contains("connection issues"));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void maintenanceAndWhitelistFailuresAreLoggedIndependently() {
        FailingMaintenanceApi maintenanceApi = new FailingMaintenanceApi();
        mockedVelocityLimboHandler.when(VelocityLimboHandler::hasMaintenancePlugin).thenReturn(true);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getMaintenanceAPI).thenReturn(maintenanceApi);
        Utility.clearMaintenanceAdapter();

        assertFalse(Utility.isServerInMaintenance("survival"));
        assertFalse(Utility.isServerInMaintenance("survival"));
        assertFalse(Utility.playerMaintenanceWhitelisted(mock(Player.class)));

        verify(logger, times(1)).warning(contains("maintenance API"));
        verify(logger, times(1)).warning(contains("maintenance whitelist API"));
    }

    public static final class FailingMaintenanceApi {
        public boolean isMaintenance(String serverName) {
            throw new IllegalStateException("maintenance failed");
        }

        public FailingMaintenanceSettings getSettings() {
            return new FailingMaintenanceSettings();
        }
    }

    public static final class FailingMaintenanceSettings {
        public java.util.Map<java.util.UUID, String> getWhitelistedPlayers() {
            throw new IllegalStateException("whitelist failed");
        }
    }
}
