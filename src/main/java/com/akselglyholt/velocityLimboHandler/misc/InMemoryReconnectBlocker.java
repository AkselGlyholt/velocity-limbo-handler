package com.akselglyholt.velocityLimboHandler.misc;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReconnectBlocker implements ReconnectBlocker {
    private static final class Entry { String reason; }
    private final ConcurrentHashMap<UUID, Entry> map = new ConcurrentHashMap<>();

    @Override public void block(UUID id, String reason) {
        Entry e = new Entry();
        e.reason = reason;
        map.put(id, e);
    }

    @Override public void unblock(UUID id) {
        map.remove(id);
    }

    @Override public boolean isBlocked(UUID id) {
        return map.containsKey(id);
    }
}

