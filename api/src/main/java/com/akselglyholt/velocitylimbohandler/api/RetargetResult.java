package com.akselglyholt.velocitylimbohandler.api;

/** Result of changing a managed player's destination. */
public enum RetargetResult {
    SUCCESS,
    NOT_READY,
    INACTIVE_OR_UNMANAGED_PLAYER,
    CONNECTION_IN_PROGRESS,
    INVALID_TARGET,
    UNKNOWN_SERVER
}
