package com.akselglyholt.velocitylimbohandler.api.hold;

import java.util.Objects;
import java.util.Optional;

/** Result and optional lease returned by a hold acquisition. */
public record HoldResult(HoldStatus status, Optional<HoldLease> lease) {
    public HoldResult {
        status = Objects.requireNonNull(status, "status");
        lease = Objects.requireNonNull(lease, "lease");
        if ((status == HoldStatus.ACQUIRED) != lease.isPresent()) {
            throw new IllegalArgumentException("only an acquired result may contain a lease");
        }
    }

    public static HoldResult acquired(HoldLease lease) {
        return new HoldResult(HoldStatus.ACQUIRED, Optional.of(lease));
    }

    public static HoldResult failed(HoldStatus status) {
        return new HoldResult(status, Optional.empty());
    }
}
