package com.akselglyholt.velocitylimbohandler.api.hold;

/** Result status for acquiring a player or server hold. */
public enum HoldStatus {
    /** The hold was created and the result contains its lease. */
    ACQUIRED,
    /** VLH is still starting or is stopping. */
    NOT_READY,
    /** A player hold targeted a player who is inactive or not managed by VLH. */
    INACTIVE_OR_UNMANAGED_PLAYER,
    /** A player hold arrived after VLH claimed that player for a backend connection. */
    CONNECTION_IN_PROGRESS,
    /** The target is blank or is the configured limbo server. */
    INVALID_TARGET
}
