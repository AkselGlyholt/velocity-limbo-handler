package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;
import com.velocitypowered.api.proxy.Player;

import java.util.Objects;

/** Fired on limbo arrival; VLH waits for all handlers before queue admission. */
public record PlayerEnteredLimboEvent(Player player, ManagedPlayerSnapshot snapshot) {
    public PlayerEnteredLimboEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
