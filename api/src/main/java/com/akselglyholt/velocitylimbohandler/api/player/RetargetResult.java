package com.akselglyholt.velocitylimbohandler.api.player;

/** Result of changing a managed player's destination. */
public enum RetargetResult {
    /** The destination was changed. */
    SUCCESS,
    /** VLH is still starting or is stopping. */
    NOT_READY,
    /** The player is inactive or not currently managed by VLH. */
    INACTIVE_OR_UNMANAGED_PLAYER,
    /** VLH already claimed the player for a backend connection. */
    CONNECTION_IN_PROGRESS,
    /** The destination is blank or is the limbo server itself. */
    INVALID_TARGET,
    /** The destination is not registered with Velocity. */
    UNKNOWN_SERVER
}
