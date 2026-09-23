package com.akselglyholt.velocitylimbohandler.api.hold;

/** Result of releasing a hold through an owner-scoped controller. */
public enum HoldReleaseStatus {
    /** The lease existed, belonged to this controller, and was released. */
    RELEASED,
    /** VLH is still starting or is stopping. */
    NOT_READY,
    /** No active lease has this ID, including a lease that already expired. */
    NOT_FOUND,
    /** The lease belongs to a different plugin owner. */
    NOT_OWNER
}
