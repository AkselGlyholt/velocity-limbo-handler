package com.akselglyholt.velocityLimboHandler.storage;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerConnectionStateTest {

    @Test
    void removePlayerState_clearsRegistrationConnectingAndIssue() {
        PlayerConnectionState state = new PlayerConnectionState();
        UUID playerId = UUID.randomUUID();

        state.registerPlayer(playerId, "survival");
        state.setConnecting(playerId, true);
        state.addConnectionIssue(playerId, "timeout");

        state.removePlayerState(playerId);

        assertFalse(state.isRegistered(playerId));
        assertFalse(state.isConnecting(playerId));
        assertFalse(state.hasConnectionIssue(playerId));
    }

    @Test
    void connectionIssueLifecycle_addGetAndRemove() {
        PlayerConnectionState state = new PlayerConnectionState();
        UUID playerId = UUID.randomUUID();

        state.addConnectionIssue(playerId, "server-offline");

        assertTrue(state.hasConnectionIssue(playerId));
        assertEquals("server-offline", state.getConnectionIssue(playerId));

        state.removeConnectionIssue(playerId);

        assertFalse(state.hasConnectionIssue(playerId));
    }

    @Test
    void pruneInactivePlayers_removesEntriesFromAllStateCollections() {
        PlayerConnectionState state = new PlayerConnectionState();
        UUID inactive = UUID.randomUUID();
        UUID active = UUID.randomUUID();

        state.registerPlayer(inactive, "survival");
        state.setConnecting(inactive, true);
        state.addConnectionIssue(inactive, "offline");

        state.registerPlayer(active, "factions");
        state.setConnecting(active, true);
        state.addConnectionIssue(active, "none");

        state.pruneInactivePlayers(inactive::equals);

        assertFalse(state.isRegistered(inactive));
        assertFalse(state.isConnecting(inactive));
        assertFalse(state.hasConnectionIssue(inactive));

        assertTrue(state.isRegistered(active));
        assertTrue(state.isConnecting(active));
        assertTrue(state.hasConnectionIssue(active));
    }
}
