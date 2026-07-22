package com.akselglyholt.velocitylimbohandler.api;

/** Result status for acquiring a player or server hold. */
public enum HoldStatus {
    ACQUIRED,
    NOT_READY,
    INACTIVE_OR_UNMANAGED_PLAYER,
    CONNECTION_IN_PROGRESS,
    INVALID_TARGET,
    UNKNOWN_SERVER
}
