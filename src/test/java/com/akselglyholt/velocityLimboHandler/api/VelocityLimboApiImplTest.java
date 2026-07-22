package com.akselglyholt.velocityLimboHandler.api;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocitylimbohandler.api.LimboController;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerLeftLimboEvent;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldReleaseResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldStatus;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.LimboPhase;
import com.akselglyholt.velocitylimbohandler.api.player.RetargetResult;
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

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
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
