package com.akselglyholt.velocitylimbohandler.api.lifecycle;

/** Observable outcome of a reconnect request. */
public enum ReconnectOutcome {
    /** The player connected to the destination. */
    SUCCESS,
    /** Velocity reported that another connection is already running. */
    CONNECTION_IN_PROGRESS,
    /** Another plugin or Velocity cancelled the connection. */
    CONNECTION_CANCELLED,
    /** The destination disconnected or became unavailable during the attempt. */
    SERVER_DISCONNECTED,
    /** The attempt failed for another reason. */
    CONNECTION_FAILURE
}
