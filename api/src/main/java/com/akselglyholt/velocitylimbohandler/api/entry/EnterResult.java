package com.akselglyholt.velocitylimbohandler.api.entry;

import com.akselglyholt.velocitylimbohandler.api.hold.HoldLease;

import java.util.Objects;
import java.util.Optional;

/** Result of an atomic limbo-entry request, including the acquired initial hold when requested. */
public record EnterResult(EnterStatus status, Optional<HoldLease> initialHold) {
    public EnterResult {
        status = Objects.requireNonNull(status, "status");
        initialHold = Objects.requireNonNull(initialHold, "initialHold");
        if (status != EnterStatus.SUCCESS && initialHold.isPresent()) {
            throw new IllegalArgumentException("only a successful entry may contain an initial hold");
        }
    }

    public static EnterResult success(Optional<HoldLease> initialHold) {
        return new EnterResult(EnterStatus.SUCCESS, initialHold);
    }

    public static EnterResult failed(EnterStatus status) {
        if (status == EnterStatus.SUCCESS) {
            throw new IllegalArgumentException("use success for a successful entry");
        }
        return new EnterResult(status, Optional.empty());
    }
}
