package com.akselglyholt.velocityLimboHandler.misc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReconnectBlocker implements ReconnectBlocker {
    private final ConcurrentHashMap<UUID, Boolean> map = new ConcurrentHashMap<>();

    @Override public void block(UUID id, String reason) {
        map.put(id, Boolean.TRUE);
    }

    @Override public void unblock(UUID id) {
        map.remove(id);
    }

    @Override public boolean isBlocked(UUID id) {
        return map.containsKey(id);
    }
}

