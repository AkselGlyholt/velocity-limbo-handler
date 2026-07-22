package com.akselglyholt.velocitylimbohandler.api;

/** Observable outcome of a reconnect request. */
public enum ReconnectOutcome {
    SUCCESS,
    CONNECTION_IN_PROGRESS,
    CONNECTION_CANCELLED,
    SERVER_DISCONNECTED,
    CONNECTION_FAILURE
}
