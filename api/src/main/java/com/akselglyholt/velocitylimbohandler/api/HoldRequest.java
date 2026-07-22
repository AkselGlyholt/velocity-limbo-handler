package com.akselglyholt.velocitylimbohandler.api;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** Diagnostic information and optional lifetime for a hold. */
public record HoldRequest(String reason, Optional<Duration> duration) {
    public HoldRequest {
        reason = Objects.requireNonNull(reason, "reason").trim();
        duration = Objects.requireNonNull(duration, "duration");
        if (reason.isEmpty()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        duration.ifPresent(value -> {
            if (value.isZero() || value.isNegative()) {
                throw new IllegalArgumentException("duration must be positive");
            }
        });
    }

    public HoldRequest(String reason) {
        this(reason, Optional.empty());
    }

    public HoldRequest(String reason, Duration duration) {
        this(reason, Optional.of(Objects.requireNonNull(duration, "duration")));
    }
}
