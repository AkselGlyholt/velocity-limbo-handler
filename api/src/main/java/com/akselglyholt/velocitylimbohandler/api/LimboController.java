package com.akselglyholt.velocitylimbohandler.api;

import com.akselglyholt.velocitylimbohandler.api.entry.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldReleaseResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldResult;
import com.akselglyholt.velocitylimbohandler.api.hold.ReleaseAllHoldsResult;
import com.akselglyholt.velocitylimbohandler.api.player.RetargetResult;
import com.velocitypowered.api.proxy.Player;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Owner-scoped mutation surface for one loaded Velocity plugin.
 *
 * <p>Operational failures are returned as typed results. Argument validation failures still throw
 * the documented runtime exceptions. Methods may be called from Velocity event handlers or
 * scheduler tasks. No callback or API event is guaranteed to run on a particular thread.</p>
 */
public interface LimboController {
    /** Returns the Velocity plugin ID that owns this controller and every lease it creates. */
    String ownerId();

    /**
     * Requests an atomic move into limbo. A successful result contains the owner-scoped initial hold
     * when {@link EnterRequest#withInitialHold(HoldRequest)} was used.
     *
     * <p>No completion executor is guaranteed. Consumers must move thread-affine follow-up work
     * onto their own scheduler.</p>
     *
     * @param player active player to move
     * @param request destination and optional initial hold
     * @return asynchronous entry result; the stage normally completes with a status rather than exceptionally
     * @throws NullPointerException when an argument is {@code null}
     */
    CompletionStage<EnterResult> enterLimbo(Player player, EnterRequest request);

    /**
     * Removes a managed player from their queue and acquires an owner-scoped hold.
     *
     * @param playerId managed player's unique ID
     * @param request reason and optional positive duration
     * @return acquisition result and lease when acquired
     * @throws NullPointerException when an argument is {@code null}
     */
    HoldResult holdPlayer(UUID playerId, HoldRequest request);

    /**
     * Pauses new connection attempts to a destination without changing existing queue positions.
     * The name may refer to a temporarily unregistered server so a hold can cover dynamic server
     * registration. The configured limbo server and blank names are invalid targets.
     *
     * @param serverName destination name, compared case-insensitively
     * @param request reason and optional positive duration
     * @return acquisition result and lease when acquired
     * @throws NullPointerException when an argument is {@code null}
     */
    HoldResult holdServer(String serverName, HoldRequest request);

    /**
     * Releases one lease. A controller cannot release a lease owned by another plugin.
     *
     * @param leaseId opaque lease ID returned by a successful hold
     * @return release result
     * @throws NullPointerException when {@code leaseId} is {@code null}
     */
    HoldReleaseResult releaseHold(UUID leaseId);

    /** Releases every player and server hold owned by this controller. */
    ReleaseAllHoldsResult releaseAllHolds();

    /**
     * Changes the destination of an active managed player. Waiting players join the back of the
     * permission-derived tier for the new destination; held players remain outside the queue.
     *
     * @param playerId managed player's unique ID
     * @param serverName registered destination server
     * @return retarget result
     * @throws NullPointerException when an argument is {@code null}
     */
    RetargetResult retargetPlayer(UUID playerId, String serverName);
}
