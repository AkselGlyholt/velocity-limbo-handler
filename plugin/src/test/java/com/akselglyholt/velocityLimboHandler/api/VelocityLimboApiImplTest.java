package com.akselglyholt.velocityLimboHandler.api;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocitylimbohandler.api.LimboController;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerLeftLimboEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerReconnectAttemptEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerReconnectResultEvent;
import com.akselglyholt.velocitylimbohandler.api.events.ServerHoldChangedEvent;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldReleaseResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldStatus;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.Availability;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.LimboPhase;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.ReconnectOutcome;
import com.akselglyholt.velocitylimbohandler.api.player.RetargetResult;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueTier;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.PluginDescription;
import com.velocitypowered.api.plugin.PluginManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.scheduler.ScheduledTask;
import com.velocitypowered.api.scheduler.Scheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import org.mockito.MockedStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class VelocityLimboApiImplTest {
    private ProxyServer proxy;
    private PluginManager pluginManager;
    private EventManager eventManager;
    private PlayerManager playerManager;
    private VelocityLimboApiImpl api;
    private RegisteredServer destination;
    private Player player;
    private UUID playerId;

    @BeforeEach
    void setUp() {
        proxy = mock(ProxyServer.class);
        pluginManager = mock(PluginManager.class);
        eventManager = mock(EventManager.class);
        playerManager = mock(PlayerManager.class);
        api = new VelocityLimboApiImpl(proxy, mock(VelocityLimboHandler.class), playerManager);
        destination = server("survival");
        player = mock(Player.class);
        playerId = UUID.randomUUID();

        when(proxy.getPluginManager()).thenReturn(pluginManager);
        when(proxy.getEventManager()).thenReturn(eventManager);
        Scheduler scheduler = mock(Scheduler.class);
        Scheduler.TaskBuilder taskBuilder = mock(Scheduler.TaskBuilder.class);
        when(proxy.getScheduler()).thenReturn(scheduler);
        when(scheduler.buildTask(any(), any(Runnable.class))).thenReturn(taskBuilder);
        when(taskBuilder.delay(anyLong(), eq(TimeUnit.MILLISECONDS))).thenReturn(taskBuilder);
        when(taskBuilder.schedule()).thenReturn(mock(ScheduledTask.class));
        when(proxy.getServer("survival")).thenReturn(Optional.of(destination));
        when(proxy.getPlayer(playerId)).thenReturn(Optional.of(player));
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getUsername()).thenReturn("Tester");
        when(player.isActive()).thenReturn(true);
        when(eventManager.fire(any())).thenAnswer(invocation ->
                CompletableFuture.completedFuture(invocation.getArgument(0)));
        api.markReady();
    }

    @Test
    void controllerOwnershipMustResolveThroughVelocity() {
        assertThrows(IllegalArgumentException.class, () -> api.controllerFor(new Object()));

        Object owner = new Object();
        LimboController controller = controller(owner, "example");
        assertEquals("example", controller.ownerId());
    }

    @Test
    void lifecycleAvailabilityAndShutdownGateAllControllerMutations() {
        VelocityLimboApiImpl starting = new VelocityLimboApiImpl(
                proxy, mock(VelocityLimboHandler.class), playerManager);
        assertEquals(Availability.STARTING, starting.availability());

        LimboController controller = controller(new Object(), "example");
        managePlayer();
        api.shutdown();

        assertEquals(Availability.STOPPING, api.availability());
        assertTrue(api.player(playerId).isEmpty());
        assertEquals(HoldStatus.NOT_READY,
                controller.holdPlayer(playerId, new HoldRequest("pause")).status());
        assertEquals(HoldStatus.NOT_READY,
                controller.holdServer("survival", new HoldRequest("deploy")).status());
        assertEquals(HoldReleaseResult.NOT_READY, controller.releaseHold(UUID.randomUUID()));
        assertEquals(0, controller.releaseAllHolds());
        assertEquals(RetargetResult.NOT_READY, controller.retargetPlayer(playerId, "survival"));
        assertEquals(EnterResult.NOT_READY,
                controller.enterLimbo(player, EnterRequest.destination("survival")).toCompletableFuture().join());
    }

    @Test
    void playerHoldAndReleaseReportMissingTargets() {
        LimboController controller = controller(new Object(), "example");

        assertEquals(HoldStatus.INACTIVE_OR_UNMANAGED_PLAYER,
                controller.holdPlayer(playerId, new HoldRequest("pause")).status());
        assertEquals(HoldReleaseResult.NOT_FOUND, controller.releaseHold(UUID.randomUUID()));
    }

    @Test
    void serverHoldRejectsBlankUnknownAndLimboTargets() {
        LimboController controller = controller(new Object(), "example");
        RegisteredServer limbo = server("limbo");
        when(proxy.getServer("limbo")).thenReturn(Optional.of(limbo));

        assertEquals(HoldStatus.INVALID_TARGET,
                controller.holdServer("  ", new HoldRequest("deploy")).status());
        assertEquals(HoldStatus.UNKNOWN_SERVER,
                controller.holdServer("missing", new HoldRequest("deploy")).status());
        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
            assertEquals(HoldStatus.INVALID_TARGET,
                    controller.holdServer("limbo", new HoldRequest("deploy")).status());
        }
    }

    @Test
    void retargetReportsInvalidUnknownAndUnmanagedTargets() {
        LimboController controller = controller(new Object(), "example");
        RegisteredServer limbo = server("limbo");
        when(proxy.getServer("limbo")).thenReturn(Optional.of(limbo));

        assertEquals(RetargetResult.INVALID_TARGET, controller.retargetPlayer(playerId, "  "));
        assertEquals(RetargetResult.UNKNOWN_SERVER, controller.retargetPlayer(playerId, "missing"));
        assertEquals(RetargetResult.INACTIVE_OR_UNMANAGED_PLAYER,
                controller.retargetPlayer(playerId, "survival"));
        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
            assertEquals(RetargetResult.INVALID_TARGET, controller.retargetPlayer(playerId, "limbo"));
        }
    }

    @Test
    void entryReportsInactiveInvalidAndUnknownTargets() {
        LimboController controller = controller(new Object(), "example");

        when(player.isActive()).thenReturn(false);
        assertEquals(EnterResult.INACTIVE_PLAYER,
                controller.enterLimbo(player, EnterRequest.destination("survival")).toCompletableFuture().join());
        when(player.isActive()).thenReturn(true);
        assertEquals(EnterResult.INVALID_TARGET,
                controller.enterLimbo(player, EnterRequest.currentServer()).toCompletableFuture().join());
        assertEquals(EnterResult.UNKNOWN_SERVER,
                controller.enterLimbo(player, EnterRequest.destination("missing")).toCompletableFuture().join());
    }

    @Test
    void entryRejectsAnAlreadyManagedPlayer() {
        LimboController controller = controller(new Object(), "example");
        RegisteredServer limbo = server("limbo");
        managePlayer();

        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
            assertEquals(EnterResult.ALREADY_MANAGED,
                    controller.enterLimbo(player, EnterRequest.destination("survival")).toCompletableFuture().join());
        }
    }

    @Test
    void queueQueriesRejectMissingAndBlankServerNames() {
        assertThrows(NullPointerException.class, () -> api.queue(null));
        assertThrows(IllegalArgumentException.class, () -> api.queue(" \t "));
    }

    @Test
    void successfulEntryKeepsItsInitialHoldAcrossArrival() {
        LimboController controller = controller(new Object(), "example");
        RegisteredServer limbo = server("limbo");
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(player.createConnectionRequest(limbo)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(result.isSuccessful()).thenReturn(true);

        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
            plugin.when(VelocityLimboHandler::getLogger).thenReturn(Logger.getLogger("test"));
            EnterResult outcome = controller.enterLimbo(player,
                    EnterRequest.destination("survival").withInitialHold(new HoldRequest("profile sync")))
                    .toCompletableFuture().join();
            assertEquals(EnterResult.SUCCESS, outcome);
        }

        assertEquals(LimboPhase.ENTERING, api.player(playerId).orElseThrow().phase());
        api.onPlayerArrived(player, destination).toCompletableFuture().join();
        assertEquals(LimboPhase.HELD, api.player(playerId).orElseThrow().phase());
        assertEquals(1, api.player(playerId).orElseThrow().playerHolds().size());
        verify(playerManager, never()).admitPlayer(player, destination);
    }

    @Test
    void entryConnectionInProgressCleansManagedState() {
        LimboController controller = controller(new Object(), "example");
        RegisteredServer limbo = server("limbo");
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(player.createConnectionRequest(limbo)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(result.isSuccessful()).thenReturn(false);
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS);

        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
            assertEquals(EnterResult.CONNECTION_IN_PROGRESS,
                    controller.enterLimbo(player, EnterRequest.destination("survival")).toCompletableFuture().join());
        }

        assertTrue(api.player(playerId).isEmpty());
    }

    @Test
    void independentOwnersCannotReleaseEachOthersHoldsAndFinalReleaseReadmits() {
        LimboController first = controller(new Object(), "first");
        LimboController second = controller(new Object(), "second");
        managePlayer();
        reset(playerManager);

        var firstHold = first.holdPlayer(playerId, new HoldRequest("first reason"));
        var secondHold = second.holdPlayer(playerId, new HoldRequest("second reason"));
        assertEquals(HoldStatus.ACQUIRED, firstHold.status());
        assertEquals(HoldStatus.ACQUIRED, secondHold.status());
        assertEquals(LimboPhase.HELD, api.player(playerId).orElseThrow().phase());
        assertFalse(api.player(playerId).orElseThrow().position().isPresent());

        UUID firstLease = firstHold.lease().orElseThrow().id();
        assertEquals(HoldReleaseResult.NOT_OWNER, second.releaseHold(firstLease));
        assertEquals(HoldReleaseResult.RELEASED, first.releaseHold(firstLease));
        assertEquals(LimboPhase.HELD, api.player(playerId).orElseThrow().phase());

        assertEquals(HoldReleaseResult.RELEASED,
                second.releaseHold(secondHold.lease().orElseThrow().id()));
        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
        verify(playerManager).admitPlayer(player, destination);
    }

    @Test
    void reconnectClaimRejectsSubsequentHoldAndRetarget() {
        LimboController controller = controller(new Object(), "example");
        managePlayer();

        assertTrue(api.tryClaimConnection(player));
        assertEquals(HoldStatus.CONNECTION_IN_PROGRESS,
                controller.holdPlayer(playerId, new HoldRequest("too late")).status());
        assertEquals(RetargetResult.CONNECTION_IN_PROGRESS,
                controller.retargetPlayer(playerId, "survival"));
    }

    @Test
    void serverHoldDoesNotMutateQueueMembership() {
        LimboController controller = controller(new Object(), "example");
        var hold = controller.holdServer("survival", new HoldRequest("deploy"));

        assertEquals(HoldStatus.ACQUIRED, hold.status());
        assertTrue(api.queue("survival").serverHeld());
        verify(playerManager, never()).removePlayerFromQueue(any());
    }

    @Test
    void timedHoldsShareOneSchedulerTaskAndIndexesCountDistinctTargets() {
        LimboController controller = controller(new Object(), "example");
        managePlayer();

        controller.holdServer("survival", new HoldRequest("first deploy", Duration.ofMinutes(1)));
        controller.holdServer("survival", new HoldRequest("second deploy", Duration.ofMinutes(2)));
        controller.holdPlayer(playerId, new HoldRequest("profile sync", Duration.ofMinutes(3)));

        assertEquals(1, api.heldServerCount());
        assertEquals(1, api.heldPlayerCount());
        verify(proxy.getScheduler(), times(1)).buildTask(any(), any(Runnable.class));

        assertEquals(3, controller.releaseAllHolds());
        assertEquals(0, api.heldServerCount());
        assertEquals(0, api.heldPlayerCount());
    }

    @Test
    void queuePermissionChecksDoNotBlockHoldMutations() throws Exception {
        LimboController controller = controller(new Object(), "example");
        managePlayer();
        when(playerManager.getQueueForServer("survival"))
                .thenReturn(java.util.List.of(new PlayerManager.QueuedPlayer(playerId, "Tester")));

        CountDownLatch permissionCheckStarted = new CountDownLatch(1);
        CountDownLatch releasePermissionCheck = new CountDownLatch(1);
        when(player.hasPermission(any(String.class))).thenAnswer(ignored -> {
            permissionCheckStarted.countDown();
            releasePermissionCheck.await(5, TimeUnit.SECONDS);
            return false;
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var queueFuture = executor.submit(() -> api.queue("survival"));
            assertTrue(permissionCheckStarted.await(2, TimeUnit.SECONDS));

            var holdFuture = executor.submit(() -> controller.holdServer("survival", new HoldRequest("deploy")));
            assertEquals(HoldStatus.ACQUIRED, holdFuture.get(2, TimeUnit.SECONDS).status());

            releasePermissionCheck.countDown();
            assertEquals(1, queueFuture.get(2, TimeUnit.SECONDS).players().size());
        } finally {
            releasePermissionCheck.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void enteredEventIsAnAdmissionBarrierAndCanAcquireAHold() {
        LimboController controller = controller(new Object(), "example");
        CompletableFuture<Object> barrier = new CompletableFuture<>();
        when(eventManager.fire(any())).thenAnswer(invocation -> barrier.thenApply(ignored -> invocation.getArgument(0)));

        var arrival = api.onPlayerArrived(player, destination);
        verify(playerManager, never()).admitPlayer(player, destination);

        var hold = controller.holdPlayer(playerId, new HoldRequest("loading profile"));
        barrier.complete(new Object());
        arrival.toCompletableFuture().join();

        assertEquals(LimboPhase.HELD, api.player(playerId).orElseThrow().phase());
        verify(playerManager, never()).admitPlayer(player, destination);

        controller.releaseHold(hold.lease().orElseThrow().id());
        verify(playerManager).admitPlayer(player, destination);
    }

    @Test
    void queuedAndHeldRetargetingUseTheCorrectAdmissionBehavior() {
        RegisteredServer factions = server("factions");
        when(proxy.getServer("factions")).thenReturn(Optional.of(factions));
        LimboController controller = controller(new Object(), "example");
        managePlayer();
        reset(playerManager);

        assertEquals(RetargetResult.SUCCESS, controller.retargetPlayer(playerId, "factions"));
        verify(playerManager).retargetPlayer(player, factions, true);
        assertEquals("factions", api.player(playerId).orElseThrow().destination());

        controller.holdPlayer(playerId, new HoldRequest("pause"));
        reset(playerManager);
        assertEquals(RetargetResult.SUCCESS, controller.retargetPlayer(playerId, "survival"));
        verify(playerManager).retargetPlayer(player, destination, false);
    }

    @Test
    void failedAtomicEntryCleansIntentAndInitialHold() {
        LimboController controller = controller(new Object(), "example");
        RegisteredServer limbo = server("limbo");
        ConnectionRequestBuilder request = mock(ConnectionRequestBuilder.class);
        ConnectionRequestBuilder.Result result = mock(ConnectionRequestBuilder.Result.class);
        when(player.createConnectionRequest(limbo)).thenReturn(request);
        when(request.connect()).thenReturn(CompletableFuture.completedFuture(result));
        when(result.isSuccessful()).thenReturn(false);
        when(result.getStatus()).thenReturn(ConnectionRequestBuilder.Status.SERVER_DISCONNECTED);

        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLimboServer).thenReturn(limbo);
            plugin.when(VelocityLimboHandler::getLogger).thenReturn(Logger.getLogger("test"));
            EnterResult outcome = controller.enterLimbo(player,
                    EnterRequest.destination("survival").withInitialHold(new HoldRequest("atomic")))
                    .toCompletableFuture().join();
            assertEquals(EnterResult.CONNECTION_FAILURE, outcome);
        }

        assertTrue(api.player(playerId).isEmpty());
        assertEquals(0, controller.releaseAllHolds());
    }

    @Test
    void expiredFinalHoldReturnsPlayerToWaiting() throws InterruptedException {
        LimboController controller = controller(new Object(), "example");
        managePlayer();
        reset(playerManager);

        controller.holdPlayer(playerId, new HoldRequest("brief pause", Duration.ofMillis(1)));
        Thread.sleep(5);

        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
        verify(playerManager).admitPlayer(player, destination);
    }

    @Test
    void authenticationReleaseCannotClearAnotherOwnersHold() {
        LimboController controller = controller(new Object(), "example");
        managePlayer();
        reset(playerManager);

        api.setAuthenticationBlocked(playerId, true, "authentication");
        var pluginHold = controller.holdPlayer(playerId, new HoldRequest("profile sync"));
        api.setAuthenticationBlocked(playerId, false, "complete");

        var held = api.player(playerId).orElseThrow();
        assertEquals(LimboPhase.HELD, held.phase());
        assertEquals(1, held.playerHolds().size());
        assertEquals("example", held.playerHolds().getFirst().ownerId());
        verify(playerManager, never()).admitPlayer(player, destination);

        controller.releaseHold(pluginHold.lease().orElseThrow().id());
        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
        verify(playerManager).admitPlayer(player, destination);
    }

    @Test
    void queueAndSummaryExposePositionsPermissionTiersAndCounts() {
        when(playerManager.getQueueForServer("survival"))
                .thenReturn(java.util.List.of(new PlayerManager.QueuedPlayer(playerId, "Tester")));
        when(playerManager.getQueuedServerNames()).thenReturn(java.util.List.of("zeta", "survival"));
        when(player.hasPermission("vlh.queue.priority")).thenReturn(true);

        var queue = api.queue("survival");
        assertEquals("survival", queue.destination());
        assertEquals(1, queue.players().size());
        assertEquals(1, queue.players().getFirst().position());
        assertEquals(QueueTier.PRIORITY, queue.players().getFirst().tier());

        var summaries = api.queues();
        assertEquals(java.util.List.of("survival", "zeta"),
                summaries.stream().map(summary -> summary.destination()).toList());
        assertEquals(1, summaries.getFirst().size());
        assertEquals(1, summaries.getFirst().tierCounts().get(QueueTier.PRIORITY));
        assertEquals(0, summaries.getFirst().tierCounts().get(QueueTier.BYPASS));
        assertEquals(0, summaries.getFirst().tierCounts().get(QueueTier.NORMAL));
    }

    @Test
    void serverHoldEventsContainTheCurrentLeaseSet() {
        LimboController controller = controller(new Object(), "example");
        ArgumentCaptor<ServerHoldChangedEvent> events = ArgumentCaptor.forClass(ServerHoldChangedEvent.class);

        var acquired = controller.holdServer("survival", new HoldRequest("deploy"));
        assertEquals(HoldReleaseResult.RELEASED,
                controller.releaseHold(acquired.lease().orElseThrow().id()));

        verify(eventManager, times(2)).fireAndForget(events.capture());
        assertEquals(1, events.getAllValues().get(0).holds().size());
        assertTrue(events.getAllValues().get(1).holds().isEmpty());
        assertTrue(events.getAllValues().get(1).revision() > events.getAllValues().get(0).revision());
    }

    @Test
    void reconnectAttemptAndFailurePublishEventsAndRestoreWaitingPhase() {
        managePlayer();
        reset(eventManager);

        assertTrue(api.tryClaimConnection(player));
        assertEquals(LimboPhase.CONNECTING, api.player(playerId).orElseThrow().phase());
        api.finishConnectionAttempt(player, "stale-destination", ReconnectOutcome.SERVER_DISCONNECTED, "offline");

        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
        ArgumentCaptor<PlayerReconnectAttemptEvent> attempt =
                ArgumentCaptor.forClass(PlayerReconnectAttemptEvent.class);
        verify(eventManager).fireAndForget(attempt.capture());
        assertEquals(LimboPhase.CONNECTING, attempt.getValue().snapshot().phase());

        ArgumentCaptor<PlayerReconnectResultEvent> result =
                ArgumentCaptor.forClass(PlayerReconnectResultEvent.class);
        verify(eventManager).fireAndForget(result.capture());
        assertEquals("survival", result.getValue().destination());
        assertEquals(ReconnectOutcome.SERVER_DISCONNECTED, result.getValue().outcome());
        assertEquals(Optional.of("offline"), result.getValue().failure());
    }

    @Test
    void failedEnteredEventHandlerStillCompletesAdmission() {
        CompletableFuture<Object> failedEvent = new CompletableFuture<>();
        failedEvent.completeExceptionally(new IllegalStateException("listener failed"));
        when(eventManager.fire(any())).thenAnswer(invocation -> failedEvent);

        Logger silentLogger = Logger.getLogger("failed-event-test");
        silentLogger.setLevel(Level.OFF);
        try (MockedStatic<VelocityLimboHandler> plugin = mockStatic(VelocityLimboHandler.class)) {
            plugin.when(VelocityLimboHandler::getLogger).thenReturn(silentLogger);
            api.onPlayerArrived(player, destination).toCompletableFuture().join();
        }

        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
        verify(playerManager).admitPlayer(player, destination);
    }

    @Test
    void connectionIssueTransitionsRemainVisibleInPlayerSnapshots() {
        managePlayer();
        when(playerManager.getConnectionIssue(playerId)).thenReturn("maintenance");

        api.setConnectionIssue(player, true);
        assertEquals(LimboPhase.CONNECTION_ISSUE, api.player(playerId).orElseThrow().phase());
        assertEquals(Optional.of("maintenance"), api.player(playerId).orElseThrow().connectionIssue());

        api.setConnectionIssue(player, false);
        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
    }

    @Test
    void preConnectReroutePreservesTheAttemptedDestinationAcrossArrival() {
        RegisteredServer previous = server("lobby");

        api.recordRerouteIntent(player, destination);
        api.onPlayerArrived(player, previous).toCompletableFuture().join();

        assertEquals("survival", api.player(playerId).orElseThrow().destination());
        verify(playerManager).registerPlayerInLimbo(player, destination);
    }

    @Test
    void disconnectRemovesPlayerHoldsAndEmitsLeftEvent() {
        LimboController controller = controller(new Object(), "example");
        managePlayer();
        controller.holdPlayer(playerId, new HoldRequest("cleanup"));

        api.onPlayerDisconnected(player);

        assertTrue(api.player(playerId).isEmpty());
        assertEquals(0, controller.releaseAllHolds());
        verify(eventManager).fireAndForget(isA(PlayerLeftLimboEvent.class));
    }

    private void managePlayer() {
        api.onPlayerArrived(player, destination).toCompletableFuture().join();
        assertEquals(LimboPhase.WAITING, api.player(playerId).orElseThrow().phase());
    }

    private LimboController controller(Object owner, String id) {
        PluginContainer container = mock(PluginContainer.class);
        PluginDescription description = mock(PluginDescription.class);
        when(description.getId()).thenReturn(id);
        when(container.getDescription()).thenReturn(description);
        when(pluginManager.fromInstance(owner)).thenReturn(Optional.of(container));
        return api.controllerFor(owner);
    }

    private RegisteredServer server(String name) {
        RegisteredServer server = mock(RegisteredServer.class);
        ServerInfo info = mock(ServerInfo.class);
        when(info.getName()).thenReturn(name);
        when(server.getServerInfo()).thenReturn(info);
        return server;
    }
}
