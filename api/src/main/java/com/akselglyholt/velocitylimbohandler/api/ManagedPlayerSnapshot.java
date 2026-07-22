package com.akselglyholt.velocitylimbohandler.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/** Immutable view of all public state for a managed player. */
public record ManagedPlayerSnapshot(UUID playerId, String username, LimboPhase phase, String destination,
                                    OptionalInt position, Optional<QueueTier> tier,
                                    Optional<String> connectionIssue, List<HoldSnapshot> playerHolds,
                                    List<HoldSnapshot> serverHolds, long revision) {
    public ManagedPlayerSnapshot {
        Objects.requireNonNull(playerId, "playerId");
        username = Objects.requireNonNull(username, "username");
        Objects.requireNonNull(phase, "phase");
        destination = Objects.requireNonNull(destination, "destination");
        position = Objects.requireNonNull(position, "position");
        tier = Objects.requireNonNull(tier, "tier");
        connectionIssue = Objects.requireNonNull(connectionIssue, "connectionIssue");
        playerHolds = List.copyOf(playerHolds);
        serverHolds = List.copyOf(serverHolds);
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }
}
