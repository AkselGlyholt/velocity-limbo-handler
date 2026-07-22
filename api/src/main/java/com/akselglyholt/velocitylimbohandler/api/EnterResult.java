package com.akselglyholt.velocitylimbohandler.api;

/** Result of an atomic limbo-entry request. */
public enum EnterResult {
    SUCCESS,
    NOT_READY,
    INACTIVE_PLAYER,
    ALREADY_MANAGED,
    CONNECTION_IN_PROGRESS,
    INVALID_TARGET,
    UNKNOWN_SERVER,
    CONNECTION_FAILURE
}
