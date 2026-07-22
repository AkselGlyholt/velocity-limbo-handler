package com.akselglyholt.velocitylimbohandler.api;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable diagnostic view of a hold lease. */
public record HoldSnapshot(UUID leaseId, String ownerId, HoldTarget targetType, String target,
                           String reason, Instant acquiredAt, Optional<Instant> expiresAt, long revision) {
    public HoldSnapshot {
        Objects.requireNonNull(leaseId, "leaseId");
        ownerId = Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(targetType, "targetType");
        target = Objects.requireNonNull(target, "target");
        reason = Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(acquiredAt, "acquiredAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }
}
