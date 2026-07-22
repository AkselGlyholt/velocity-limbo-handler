package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;
import com.velocitypowered.api.proxy.Player;

import java.util.Objects;

/** Fired immediately before managed state is removed after a player leaves limbo. */
public record PlayerLeftLimboEvent(Player player, ManagedPlayerSnapshot snapshot) {
    public PlayerLeftLimboEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
