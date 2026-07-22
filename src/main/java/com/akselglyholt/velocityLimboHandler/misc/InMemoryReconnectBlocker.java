package com.akselglyholt.velocityLimboHandler.misc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class InMemoryReconnectBlocker implements ReconnectBlocker {
    private final Set<UUID> blockedPlayers = ConcurrentHashMap.newKeySet();

    @Override public void block(UUID id, String reason) {
        blockedPlayers.add(id);
    }

    @Override public void unblock(UUID id) {
        blockedPlayers.remove(id);
    }

    @Override public boolean isBlocked(UUID id) {
        return blockedPlayers.contains(id);
    }
}
