package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.HoldSnapshot;

import java.util.List;
import java.util.Objects;

/** Fired after a server hold is acquired, released, or expires. */
public record ServerHoldChangedEvent(String serverName, List<HoldSnapshot> holds, long revision) {
    public ServerHoldChangedEvent {
        serverName = Objects.requireNonNull(serverName, "serverName");
        holds = List.copyOf(holds);
        if (revision < 0) throw new IllegalArgumentException("revision must be non-negative");
    }
}
