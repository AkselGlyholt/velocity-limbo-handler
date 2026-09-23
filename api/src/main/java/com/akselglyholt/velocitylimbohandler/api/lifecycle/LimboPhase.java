package com.akselglyholt.velocitylimbohandler.api.lifecycle;

/** A managed player's current lifecycle phase. */
public enum LimboPhase {
    /** VLH has requested a move to limbo but arrival has not been observed. */
    ENTERING,
    /** The player arrived and entry-event handlers are running before queue admission. */
    ADMITTING,
    /** The player is eligible for reconnect attempts and normally has a queue position. */
    WAITING,
    /** One or more player holds keep the player outside the reconnect queue. */
    HELD,
    /** VLH has claimed the player for one backend connection attempt. */
    CONNECTING,
    /** A persistent player-specific problem currently prevents reconnect attempts. */
    CONNECTION_ISSUE
}
