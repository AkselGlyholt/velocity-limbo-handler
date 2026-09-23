package com.akselglyholt.velocitylimbohandler.api.lifecycle;

/** Runtime availability of Velocity Limbo Handler. */
public enum Availability {
    /** The plugin instance exists but initialization has not completed. */
    STARTING,
    /** Read and mutation operations are available. */
    READY,
    /** Proxy shutdown has begun; mutations return their not-ready result. */
    STOPPING
}
