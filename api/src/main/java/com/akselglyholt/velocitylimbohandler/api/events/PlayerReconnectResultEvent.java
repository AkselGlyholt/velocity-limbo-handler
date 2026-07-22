package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.lifecycle.ReconnectOutcome;
import com.velocitypowered.api.proxy.Player;

import java.util.Objects;
import java.util.Optional;

/** Fired when a claimed reconnect attempt finishes. */
public record PlayerReconnectResultEvent(Player player, String destination, ReconnectOutcome outcome,
                                         Optional<String> failure) {
    public PlayerReconnectResultEvent {
        Objects.requireNonNull(player, "player");
        destination = Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(outcome, "outcome");
        failure = Objects.requireNonNull(failure, "failure");
    }
}
