package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MessageFormatterTest {

    private MockedStatic<VelocityLimboHandler> mockedVelocityLimboHandler;
    private PlayerManager playerManager;
    private Player player;
    private RegisteredServer previousServer;
    private RegisteredServer limboServer;
    private ServerInfo serverInfo;

    @BeforeEach
    void setUp() {
        mockedVelocityLimboHandler = mockStatic(VelocityLimboHandler.class);
        playerManager = mock(PlayerManager.class);
        player = mock(Player.class);
        previousServer = mock(RegisteredServer.class);
        limboServer = mock(RegisteredServer.class);
        serverInfo = mock(ServerInfo.class);

        mockedVelocityLimboHandler.when(VelocityLimboHandler::getPlayerManager).thenReturn(playerManager);
        mockedVelocityLimboHandler.when(VelocityLimboHandler::getLimboServer).thenReturn(limboServer);

        when(player.getUsername()).thenReturn("Aksel");
        when(playerManager.getQueuePosition(player)).thenReturn(4);
        when(playerManager.getPreviousServerName(player)).thenReturn("survival");
        when(playerManager.getQueueSize("survival")).thenReturn(3);
        when(previousServer.getServerInfo()).thenReturn(serverInfo);
        when(serverInfo.getName()).thenReturn("survival");
        when(limboServer.getPlayersConnected()).thenReturn(List.of(player, mock(Player.class)));
    }

    @AfterEach
    void tearDown() {
        mockedVelocityLimboHandler.close();
    }

    @Test
    void testFormatMessage_ReplacesAllSupportedPlaceholders() {
        String result = MessageFormatter.formatMessage(
                "[player] is #[queue-position] of [queue-size] for [queued-server] with [total-limbo] in limbo",
                player
        );

        assertEquals("Aksel is #4 of 3 for survival with 2 in limbo", result);
        verify(playerManager).getQueuePosition(player);
        verify(playerManager).getQueueSize("survival");
        verify(playerManager, times(1)).getPreviousServerName(player);
    }

    @Test
    void testFormatMessage_ReusesPreviousServerAcrossServerPlaceholders() {
        String result = MessageFormatter.formatMessage("[queue-size] players waiting for [queued-server]", player);

        assertEquals("3 players waiting for survival", result);
        verify(playerManager, times(1)).getPreviousServerName(player);
    }

    @Test
    void testFormatMessage_LeavesMessageUntouchedWhenNoPlaceholdersExist() {
        String message = "Nothing to replace here";

        String result = MessageFormatter.formatMessage(message, player);

        assertEquals(message, result);
        verifyNoInteractions(playerManager, limboServer, previousServer, serverInfo);
    }

    @Test
    void formatComponent_reusesKnownQueueValuesWithoutAnotherPositionLookup() {
        var component = MessageFormatter.formatComponent(
                "<yellow>#[queue-position] of [queue-size] for [queued-server]</yellow>",
                player,
                7,
                previousServer
        );

        assertEquals("#7 of 3 for survival", PlainTextComponentSerializer.plainText().serialize(component));
        verify(playerManager, never()).getQueuePosition(player);
        verify(playerManager).getQueueSize("survival");
    }

    @Test
    void formatComponent_usesTheSameValuesAsStringFormatting() {
        String message = "[player] is #[queue-position] of [queue-size] for [queued-server] "
                + "with [total-limbo] in limbo";

        String formatted = MessageFormatter.formatMessage(message, player);
        var component = MessageFormatter.formatComponent(message, player);

        assertEquals(formatted, PlainTextComponentSerializer.plainText().serialize(component));
    }

    @Test
    void missingPreviousServerLeavesServerPlaceholdersUnresolved() {
        String message = "[queue-size] players waiting for [queued-server]";
        when(playerManager.getPreviousServerName(player)).thenReturn(null);

        assertEquals(message, MessageFormatter.formatMessage(message, player));
        assertEquals(message, PlainTextComponentSerializer.plainText().serialize(
                MessageFormatter.formatComponent(message, player)
        ));
        verify(playerManager, never()).getQueueSize(any());
    }
}
