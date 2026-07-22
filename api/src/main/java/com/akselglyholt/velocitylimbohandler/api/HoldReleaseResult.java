package com.akselglyholt.velocitylimbohandler.api;

/** Result of releasing a hold through an owner-scoped controller. */
public enum HoldReleaseResult {
    RELEASED,
    NOT_READY,
    NOT_FOUND,
    NOT_OWNER
}
