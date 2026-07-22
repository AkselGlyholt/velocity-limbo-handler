package com.akselglyholt.velocitylimbohandler.api;

/** A managed player's current lifecycle phase. */
public enum LimboPhase {
    ENTERING,
    ADMITTING,
    WAITING,
    HELD,
    CONNECTING,
    CONNECTION_ISSUE
}
