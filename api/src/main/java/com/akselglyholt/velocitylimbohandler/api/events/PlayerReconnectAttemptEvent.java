package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.ManagedPlayerSnapshot;
import com.velocitypowered.api.proxy.Player;

import java.util.Objects;

/** Fired after VLH atomically claims a player for a reconnect attempt. */
public record PlayerReconnectAttemptEvent(Player player, ManagedPlayerSnapshot snapshot) {
    public PlayerReconnectAttemptEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
