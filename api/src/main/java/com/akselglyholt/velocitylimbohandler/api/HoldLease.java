package com.akselglyholt.velocitylimbohandler.api;

import java.util.UUID;

/** Opaque identity of an owner-scoped hold. */
public interface HoldLease {
    UUID id();
}
