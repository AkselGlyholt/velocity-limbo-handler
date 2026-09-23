package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;

import java.util.Objects;

/** Fired after an observable managed-player state transition. */
public record PlayerLimboStateChangedEvent(ManagedPlayerSnapshot previous, ManagedPlayerSnapshot current) {
    public PlayerLimboStateChangedEvent {
        Objects.requireNonNull(previous, "previous");
        Objects.requireNonNull(current, "current");
    }
}
