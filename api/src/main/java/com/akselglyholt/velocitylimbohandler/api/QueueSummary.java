package com.akselglyholt.velocitylimbohandler.api;

import java.util.Map;
import java.util.Objects;

/** Immutable counts for one destination queue. */
public record QueueSummary(String destination, int size, Map<QueueTier, Integer> tierCounts,
                           boolean serverHeld, long revision) {
    public QueueSummary {
        destination = Objects.requireNonNull(destination, "destination");
        tierCounts = Map.copyOf(tierCounts);
        if (size < 0 || revision < 0) throw new IllegalArgumentException("counts and revision must be non-negative");
    }
}
