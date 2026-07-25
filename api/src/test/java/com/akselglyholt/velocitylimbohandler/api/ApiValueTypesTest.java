package com.akselglyholt.velocitylimbohandler.api;

import com.akselglyholt.velocitylimbohandler.api.entry.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterStatus;
import com.akselglyholt.velocitylimbohandler.api.events.ServerHoldChangedEvent;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldLease;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldSnapshot;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldStatus;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldTarget;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.LimboPhase;
import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueSnapshot;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueSummary;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueTier;
import com.akselglyholt.velocitylimbohandler.api.queue.QueuedPlayerSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiValueTypesTest {
    @Test
    void enterRequestsNormalizeDestinationAndPreserveInitialHold() {
        HoldRequest hold = new HoldRequest("profile sync");
        EnterRequest request = EnterRequest.destination("  survival  ").withInitialHold(hold);

        assertEquals(Optional.of("survival"), request.destination());
        assertSame(hold, request.initialHold().orElseThrow());
        assertTrue(EnterRequest.currentServer().destination().isEmpty());
        assertTrue(EnterRequest.currentServer().initialHold().isEmpty());
    }

    @Test
    void enterRequestsRejectInvalidArguments() {
        assertThrows(NullPointerException.class, () -> EnterRequest.destination(null));
        assertThrows(IllegalArgumentException.class, () -> EnterRequest.destination(" \t "));
        assertThrows(NullPointerException.class, () -> EnterRequest.currentServer().withInitialHold(null));
    }

    @Test
    void enterResultsExposeOnlySuccessfulInitialHolds() {
        HoldLease lease = UUID::randomUUID;

        assertSame(lease, EnterResult.success(Optional.of(lease)).initialHold().orElseThrow());
        assertEquals(EnterStatus.SUCCESS, EnterResult.success(Optional.empty()).status());
        assertEquals(EnterStatus.NOT_READY, EnterResult.failed(EnterStatus.NOT_READY).status());
        assertThrows(IllegalArgumentException.class,
                () -> new EnterResult(EnterStatus.CONNECTION_FAILURE, Optional.of(lease)));
        assertThrows(IllegalArgumentException.class, () -> EnterResult.failed(EnterStatus.SUCCESS));
    }

    @Test
    void holdRequestsNormalizeAndValidateTheirFields() {
        HoldRequest permanent = new HoldRequest("  deploy  ");
        HoldRequest timed = new HoldRequest("deploy", Duration.ofSeconds(5));

        assertEquals("deploy", permanent.reason());
        assertTrue(permanent.duration().isEmpty());
        assertEquals(Optional.of(Duration.ofSeconds(5)), timed.duration());
        assertThrows(NullPointerException.class, () -> new HoldRequest(null));
        assertThrows(IllegalArgumentException.class, () -> new HoldRequest("  "));
        assertThrows(IllegalArgumentException.class, () -> new HoldRequest("deploy", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new HoldRequest("deploy", Duration.ofSeconds(-1)));
    }

    @Test
    void holdResultsEnforceTheLeaseInvariant() {
        HoldLease lease = UUID::randomUUID;

        assertSame(lease, HoldResult.acquired(lease).lease().orElseThrow());
        assertFalse(HoldResult.failed(HoldStatus.UNKNOWN_SERVER).lease().isPresent());
        assertThrows(IllegalArgumentException.class,
                () -> new HoldResult(HoldStatus.ACQUIRED, Optional.empty()));
        assertThrows(IllegalArgumentException.class,
                () -> new HoldResult(HoldStatus.UNKNOWN_SERVER, Optional.of(lease)));
    }

    @Test
    void collectionBearingContractsDefensivelyCopyInputs() {
        HoldSnapshot hold = holdSnapshot(1);
        List<HoldSnapshot> playerHolds = new ArrayList<>(List.of(hold));
        List<HoldSnapshot> serverHolds = new ArrayList<>(List.of(hold));
        ManagedPlayerSnapshot player = new ManagedPlayerSnapshot(UUID.randomUUID(), "Tester", LimboPhase.HELD,
                "survival", OptionalInt.empty(), Optional.empty(), Optional.empty(), playerHolds, serverHolds, 2);
        playerHolds.clear();
        serverHolds.clear();

        assertEquals(1, player.playerHolds().size());
        assertEquals(1, player.serverHolds().size());
        assertThrows(UnsupportedOperationException.class, () -> player.playerHolds().clear());

        List<HoldSnapshot> eventHolds = new ArrayList<>(List.of(hold));
        ServerHoldChangedEvent event = new ServerHoldChangedEvent("survival", eventHolds, 3);
        eventHolds.clear();
        assertEquals(1, event.holds().size());
        assertThrows(UnsupportedOperationException.class, () -> event.holds().clear());

        Map<QueueTier, Integer> counts = new EnumMap<>(Map.of(QueueTier.NORMAL, 1));
        QueueSummary summary = new QueueSummary("survival", 1, counts, true, 4);
        counts.clear();
        assertEquals(Map.of(QueueTier.NORMAL, 1), summary.tierCounts());
        assertThrows(UnsupportedOperationException.class,
                () -> summary.tierCounts().put(QueueTier.PRIORITY, 1));
    }

    @Test
    void publicSnapshotsRejectInvalidNumericState() {
        UUID playerId = UUID.randomUUID();

        assertThrows(IllegalArgumentException.class,
                () -> new QueuedPlayerSnapshot(playerId, "Tester", 0, QueueTier.NORMAL, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new QueuedPlayerSnapshot(playerId, "Tester", 1, QueueTier.NORMAL, -1));
        assertThrows(IllegalArgumentException.class,
                () -> new QueueSnapshot("survival", List.of(), List.of(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new QueueSummary("survival", -1, Map.of(), false, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new ManagedPlayerSnapshot(playerId, "Tester", LimboPhase.WAITING, "survival",
                        OptionalInt.empty(), Optional.empty(), Optional.empty(), List.of(), List.of(), -1));
        assertThrows(IllegalArgumentException.class,
                () -> new ServerHoldChangedEvent("survival", List.of(), -1));
        assertThrows(IllegalArgumentException.class, () -> holdSnapshot(-1));
    }

    private HoldSnapshot holdSnapshot(long revision) {
        return new HoldSnapshot(UUID.randomUUID(), "owner", HoldTarget.SERVER, "survival", "deploy",
                Instant.now(), Optional.empty(), revision);
    }
}
