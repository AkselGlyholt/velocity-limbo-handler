package com.akselglyholt.velocityLimboHandler.misc;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryReconnectBlocker implements ReconnectBlocker {
    private static final class Entry { Instant until; String reason; }
    private final ConcurrentHashMap<UUID, Entry> map = new ConcurrentHashMap<>();

    @Override public void block(UUID id, String reason, Duration ttl) {
        Entry e = new Entry();
        e.until = Instant.now().plus(ttl);
        e.reason = reason;
        map.put(id, e);
    }
    @Override public void unblock(UUID id) { map.remove(id); }

    @Override public boolean isBlocked(UUID id) {
        Entry e = map.get(id);
        if (e == null) return false;
        if (Instant.now().isAfter(e.until)) { map.remove(id); return false; }
        return true;
    }
}