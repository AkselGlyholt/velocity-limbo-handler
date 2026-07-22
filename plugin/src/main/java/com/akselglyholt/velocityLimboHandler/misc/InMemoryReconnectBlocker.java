package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class InMemoryReconnectBlocker implements ReconnectBlocker {
    private final Set<UUID> blockedPlayers = ConcurrentHashMap.newKeySet();
    private final PlayerManager playerManager;

    public InMemoryReconnectBlocker() {
        this(null);
    }

    public InMemoryReconnectBlocker(PlayerManager playerManager) {
        this.playerManager = playerManager;
    }

    @Override public void block(UUID id, String reason) {
        blockedPlayers.add(id);
        if (playerManager != null) playerManager.setAuthenticationBlocked(id, true, reason);
    }

    @Override public void unblock(UUID id) {
        blockedPlayers.remove(id);
        if (playerManager != null) playerManager.setAuthenticationBlocked(id, false, "authentication complete");
    }

    @Override public boolean isBlocked(UUID id) {
        return blockedPlayers.contains(id);
    }
}
