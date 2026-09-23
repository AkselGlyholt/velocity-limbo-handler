package com.akselglyholt.velocitylimbohandler.api.entry;

import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;

import java.util.Objects;
import java.util.Optional;

/** Atomic request to move a player to limbo and assign their destination. */
public final class EnterRequest {
    private final Optional<String> destination;
    private final Optional<HoldRequest> initialHold;

    private EnterRequest(Optional<String> destination, Optional<HoldRequest> initialHold) {
        this.destination = destination.map(String::trim);
        if (this.destination.isPresent() && this.destination.orElseThrow().isEmpty()) {
            throw new IllegalArgumentException("destination must not be blank");
        }
        this.initialHold = Objects.requireNonNull(initialHold, "initialHold");
    }

    /** Uses the player's current server as the eventual destination. */
    public static EnterRequest currentServer() {
        return new EnterRequest(Optional.empty(), Optional.empty());
    }

    /** Uses an explicitly named registered server as the eventual destination. */
    public static EnterRequest destination(String serverName) {
        return new EnterRequest(Optional.of(Objects.requireNonNull(serverName, "serverName")), Optional.empty());
    }

    /** Returns a copy that atomically acquires the given hold before movement begins. */
    public EnterRequest withInitialHold(HoldRequest hold) {
        return new EnterRequest(destination, Optional.of(Objects.requireNonNull(hold, "hold")));
    }

    public Optional<String> destination() {
        return destination;
    }

    public Optional<HoldRequest> initialHold() {
        return initialHold;
    }
}
