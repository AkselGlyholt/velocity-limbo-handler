package com.akselglyholt.velocitylimbohandler.api.hold;

import java.util.UUID;

/** Opaque identity of an owner-scoped hold; pass {@link #id()} to the owning controller to release it. */
public interface HoldLease {
    UUID id();
}
