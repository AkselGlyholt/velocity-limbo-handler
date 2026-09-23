package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

public final class InMemoryReconnectBlocker implements ReconnectBlocker {
    private final Set<UUID> blockedPlayers = ConcurrentHashMap.newKeySet();
    private final VelocityLimboApiImpl api;

    public InMemoryReconnectBlocker() {
        this(null);
    }

    public InMemoryReconnectBlocker(VelocityLimboApiImpl api) {
        this.api = api;
    }

    @Override public void block(UUID id, String reason) {
        blockedPlayers.add(id);
        if (api != null) api.setAuthenticationBlocked(id, true, reason);
    }

    @Override public void unblock(UUID id) {
        blockedPlayers.remove(id);
        if (api != null) api.setAuthenticationBlocked(id, false, "authentication complete");
    }

    @Override public boolean isBlocked(UUID id) {
        return blockedPlayers.contains(id);
    }
}
