package com.akselglyholt.velocitylimbohandler.api;

import java.util.Objects;
import java.util.UUID;

/** Immutable queue entry view. */
public record QueuedPlayerSnapshot(UUID playerId, String username, int position, QueueTier tier, long revision) {
    public QueuedPlayerSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        username = Objects.requireNonNull(username, "username");
        Objects.requireNonNull(tier, "tier");
        if (position < 1 || revision < 0) {
            throw new IllegalArgumentException("position must be positive and revision non-negative");
        }
    }
}
