package com.akselglyholt.velocitylimbohandler.api.queue;

import com.akselglyholt.velocitylimbohandler.api.hold.HoldSnapshot;

import java.util.List;
import java.util.Objects;

/**
 * Immutable ordered view of one destination queue.
 * {@code revision} orders this snapshot against other snapshots and events; a higher value is newer.
 */
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
