package com.akselglyholt.velocityLimboHandler.storage;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

final class PlayerConnectionState {
    private final Map<UUID, String> playerData = new ConcurrentHashMap<>();
    private final Set<UUID> connectingPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, String> playerConnectionIssues = new ConcurrentHashMap<>();

    void registerPlayer(UUID playerId, String serverName) {
        playerData.put(playerId, serverName);
    }

    Optional<String> getRegisteredServer(UUID playerId) {
        return Optional.ofNullable(playerData.get(playerId));
    }

    boolean isRegistered(UUID playerId) {
        return playerData.containsKey(playerId);
    }

    void removePlayerState(UUID playerId) {
        playerData.remove(playerId);
        connectingPlayers.remove(playerId);
        playerConnectionIssues.remove(playerId);
    }

    boolean isConnecting(UUID playerId) {
        return connectingPlayers.contains(playerId);
    }

    void setConnecting(UUID playerId, boolean connecting) {
        if (connecting) {
            connectingPlayers.add(playerId);
            return;
        }

        connectingPlayers.remove(playerId);
    }

    void addConnectionIssue(UUID playerId, String issue) {
        playerConnectionIssues.put(playerId, issue);
    }

    boolean hasConnectionIssue(UUID playerId) {
        return playerConnectionIssues.containsKey(playerId);
    }

    String getConnectionIssue(UUID playerId) {
        return playerConnectionIssues.get(playerId);
    }

    void removeConnectionIssue(UUID playerId) {
        playerConnectionIssues.remove(playerId);
    }

    void pruneInactivePlayers(Predicate<UUID> inactiveOrMissing) {
        playerData.keySet().removeIf(inactiveOrMissing);
        connectingPlayers.removeIf(inactiveOrMissing);
        playerConnectionIssues.keySet().removeIf(inactiveOrMissing);
    }
}
