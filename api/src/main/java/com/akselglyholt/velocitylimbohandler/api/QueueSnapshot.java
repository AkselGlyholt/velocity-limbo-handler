package com.akselglyholt.velocitylimbohandler.api;

import java.util.List;
import java.util.Objects;

/** Immutable ordered view of one destination queue. */
public record QueueSnapshot(String destination, List<QueuedPlayerSnapshot> players,
                            List<HoldSnapshot> serverHolds, long revision) {
    public QueueSnapshot {
        destination = Objects.requireNonNull(destination, "destination");
        players = List.copyOf(players);
        serverHolds = List.copyOf(serverHolds);
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }

    public boolean serverHeld() {
        return !serverHolds.isEmpty();
    }
}
