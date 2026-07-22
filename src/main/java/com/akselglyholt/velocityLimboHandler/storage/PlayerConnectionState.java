package com.akselglyholt.velocityLimboHandler.storage;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class PlayerConnectionState {
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    void registerPlayer(UUID playerId, String serverName) {
        states.compute(playerId, (ignored, state) -> {
            if (state == null) {
                return new State(serverName);
            }
            state.serverName = serverName;
            return state;
        });
    }

    Optional<String> getRegisteredServer(UUID playerId) {
        State state = states.get(playerId);
        return state == null ? Optional.empty() : Optional.ofNullable(state.serverName);
    }

    boolean isRegistered(UUID playerId) {
        State state = states.get(playerId);
        return state != null && state.serverName != null;
    }

    void removePlayerState(UUID playerId) {
        states.remove(playerId);
    }

    boolean isConnecting(UUID playerId) {
        State state = states.get(playerId);
        return state != null && state.connecting;
    }

    void setConnecting(UUID playerId, boolean connecting) {
        if (connecting) {
            states.computeIfAbsent(playerId, ignored -> new State(null)).connecting = true;
            return;
        }

        states.computeIfPresent(playerId, (ignored, state) -> {
            state.connecting = false;
            return state.isEmpty() ? null : state;
        });
    }

    void addConnectionIssue(UUID playerId, String issue) {
        states.computeIfAbsent(playerId, ignored -> new State(null)).connectionIssue = issue;
    }

    boolean hasConnectionIssue(UUID playerId) {
        State state = states.get(playerId);
        return state != null && state.connectionIssue != null;
    }

    String getConnectionIssue(UUID playerId) {
        State state = states.get(playerId);
        return state == null ? null : state.connectionIssue;
    }

    void removeConnectionIssue(UUID playerId) {
        states.computeIfPresent(playerId, (ignored, state) -> {
            state.connectionIssue = null;
            return state.isEmpty() ? null : state;
        });
    }

    void pruneInactivePlayers(Predicate<UUID> inactiveOrMissing) {
        states.keySet().removeIf(inactiveOrMissing);
    }

    private static final class State {
        private volatile String serverName;
        private volatile boolean connecting;
        private volatile String connectionIssue;

        private State(String serverName) {
            this.serverName = serverName;
        }

        private boolean isEmpty() {
            return serverName == null && !connecting && connectionIssue == null;
        }
    }
}
