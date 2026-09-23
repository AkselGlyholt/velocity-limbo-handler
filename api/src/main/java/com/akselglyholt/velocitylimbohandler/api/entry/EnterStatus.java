package com.akselglyholt.velocitylimbohandler.api.entry;

/** Status of an atomic limbo-entry request. */
public enum EnterStatus {
    /** The connection request to limbo succeeded. */
    SUCCESS,
    /** VLH is still starting, is stopping, or its configured limbo server is unavailable. */
    NOT_READY,
    /** The player is no longer active on this proxy. */
    INACTIVE_PLAYER,
    /** VLH already manages the player or has registered them in limbo. */
    ALREADY_MANAGED,
    /** Velocity is already processing another connection for the player. */
    CONNECTION_IN_PROGRESS,
    /** The target is blank, absent for a current-server request, or is the limbo server itself. */
    INVALID_TARGET,
    /** An explicitly named destination is not registered with Velocity. */
    UNKNOWN_SERVER,
    /** Velocity rejected or failed the connection to limbo. */
    CONNECTION_FAILURE
}
