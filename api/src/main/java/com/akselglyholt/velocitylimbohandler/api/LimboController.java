package com.akselglyholt.velocitylimbohandler.api;

import com.akselglyholt.velocitylimbohandler.api.entry.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldReleaseResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldResult;
import com.akselglyholt.velocitylimbohandler.api.player.RetargetResult;
import com.velocitypowered.api.proxy.Player;

import java.util.UUID;
import java.util.concurrent.CompletionStage;

/** Owner-scoped mutation surface for one loaded Velocity plugin. */
public interface LimboController {
    String ownerId();

    /**
     * Requests an atomic move into limbo. A successful result contains the owner-scoped initial hold
     * when {@link EnterRequest#withInitialHold(HoldRequest)} was used.
     */
    CompletionStage<EnterResult> enterLimbo(Player player, EnterRequest request);

    HoldResult holdPlayer(UUID playerId, HoldRequest request);

    HoldResult holdServer(String serverName, HoldRequest request);

    HoldReleaseResult releaseHold(UUID leaseId);

    int releaseAllHolds();

    RetargetResult retargetPlayer(UUID playerId, String serverName);
}
