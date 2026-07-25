package com.akselglyholt.velocitylimbohandler.api.entry;

/** Status of an atomic limbo-entry request. */
public enum EnterStatus {
    SUCCESS,
    NOT_READY,
    INACTIVE_PLAYER,
    ALREADY_MANAGED,
    CONNECTION_IN_PROGRESS,
    INVALID_TARGET,
    UNKNOWN_SERVER,
    CONNECTION_FAILURE
}
