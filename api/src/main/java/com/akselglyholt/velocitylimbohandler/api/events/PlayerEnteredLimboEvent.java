package com.akselglyholt.velocitylimbohandler.api.events;

import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;
import com.velocitypowered.api.proxy.Player;

import java.util.Objects;

/**
 * Fired on limbo arrival; VLH waits for all handlers before queue admission.
 *
 * <p>There is no timeout. The player stays in {@code ADMITTING} until every handler, including async
 * {@code EventTask}s, completes. For long or open-ended work, acquire a player hold in the handler
 * and return; the hold keeps the player out of the queue until it is released.</p>
 */
public record PlayerEnteredLimboEvent(Player player, ManagedPlayerSnapshot snapshot) {
    public PlayerEnteredLimboEvent {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(snapshot, "snapshot");
    }
}
