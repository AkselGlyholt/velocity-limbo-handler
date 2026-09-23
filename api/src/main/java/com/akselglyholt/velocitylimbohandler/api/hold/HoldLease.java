package com.akselglyholt.velocitylimbohandler.api.hold;

import java.util.UUID;

/** Opaque identity of an owner-scoped hold; pass it to the owning controller's {@code releaseHold} to release it. */
public interface HoldLease {
    UUID id();
}
