package com.akselglyholt.velocityLimboHandler.api;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocitylimbohandler.api.LimboController;
import com.akselglyholt.velocitylimbohandler.api.VelocityLimboApi;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.entry.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerEnteredLimboEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerLeftLimboEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerLimboStateChangedEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerReconnectAttemptEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerReconnectResultEvent;
import com.akselglyholt.velocitylimbohandler.api.events.ServerHoldChangedEvent;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldLease;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldReleaseResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldResult;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldSnapshot;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldStatus;
import com.akselglyholt.velocitylimbohandler.api.hold.HoldTarget;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.Availability;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.LimboPhase;
import com.akselglyholt.velocitylimbohandler.api.lifecycle.ReconnectOutcome;
import com.akselglyholt.velocitylimbohandler.api.player.ManagedPlayerSnapshot;
import com.akselglyholt.velocitylimbohandler.api.player.RetargetResult;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueSnapshot;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueSummary;
import com.akselglyholt.velocitylimbohandler.api.queue.QueueTier;
import com.akselglyholt.velocitylimbohandler.api.queue.QueuedPlayerSnapshot;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.scheduler.ScheduledTask;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Runtime implementation kept out of the published API artifact. */
public final class VelocityLimboApiImpl implements VelocityLimboApi {
    public static final String AUTH_OWNER = "velocity-limbo-handler:authentication";
    private static final long INCIDENTAL_INTENT_MAX_AGE_SECONDS = 60;

    private final Object lock = new Object();
    private final ProxyServer proxy;
    private final VelocityLimboHandler plugin;
    private final PlayerManager playerManager;
    private final Map<UUID, ManagedState> players = new HashMap<>();
    private final Map<UUID, EntryIntent> entryIntents = new HashMap<>();
    private final Map<UUID, LeaseRecord> leases = new LinkedHashMap<>();
    private final Map<UUID, LinkedHashSet<UUID>> playerLeaseIds = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> serverLeaseIds = new HashMap<>();
    private final Map<String, LinkedHashSet<UUID>> ownerLeaseIds = new HashMap<>();
    private final Map<String, String> serverHoldTargets = new HashMap<>();
    private final NavigableMap<Instant, LinkedHashSet<UUID>> expirations = new TreeMap<>();
    private ScheduledTask expiryTask;
    private Instant expiryTaskAt;
    private long expiryGeneration;
    private volatile Availability availability = Availability.STARTING;
    private long revision;

    public VelocityLimboApiImpl(ProxyServer proxy, VelocityLimboHandler plugin, PlayerManager playerManager) {
        this.proxy = Objects.requireNonNull(proxy, "proxy");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.playerManager = Objects.requireNonNull(playerManager, "playerManager");
    }

    @Override
    public Availability availability() {
        return availability;
    }

    public void markReady() {
        availability = Availability.READY;
    }

    public void shutdown() {
        availability = Availability.STOPPING;
        synchronized (lock) {
            if (expiryTask != null) expiryTask.cancel();
            expiryTask = null;
            expiryTaskAt = null;
            expiryGeneration++;
            expirations.clear();
            entryIntents.clear();
            leases.clear();
            playerLeaseIds.clear();
            serverLeaseIds.clear();
            ownerLeaseIds.clear();
            serverHoldTargets.clear();
            players.clear();
            revision++;
        }
    }

    @Override
    public LimboController controllerFor(Object pluginInstance) {
        Objects.requireNonNull(pluginInstance, "pluginInstance");
        PluginContainer owner = proxy.getPluginManager().fromInstance(pluginInstance)
                .orElseThrow(() -> new IllegalArgumentException("owner must be a loaded Velocity plugin instance"));
        return new Controller(owner.getDescription().getId());
    }

    @Override
    public Optional<ManagedPlayerSnapshot> player(UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        expireDueLeases();
        synchronized (lock) {
            return Optional.ofNullable(snapshotLocked(playerId));
        }
    }

    @Override
    public QueueSnapshot queue(String serverName) {
        String normalized = requireServerName(serverName);
        expireDueLeases();
        return queueSnapshot(normalized);
    }

    @Override
    public List<QueueSummary> queues() {
        expireDueLeases();
        Map<String, String> names = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        playerManager.getQueuedServerNames().forEach(name -> names.put(name, name));
        synchronized (lock) {
            serverHoldTargets.values().forEach(name -> names.put(name, name));
        }
        return names.values().stream().map(this::queueSummary).toList();
    }

    public CompletionStage<Void> onPlayerArrived(Player player, RegisteredServer fallbackDestination) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(fallbackDestination, "fallbackDestination");
        ManagedPlayerSnapshot entered;
        synchronized (lock) {
            EntryIntent intent = entryIntents.remove(player.getUniqueId());
            if (intent != null && !intent.apiEntry
                    && intent.createdAt.plusSeconds(INCIDENTAL_INTENT_MAX_AGE_SECONDS).isBefore(Instant.now())) {
                intent = null;
            }
            String destination = intent == null ? fallbackDestination.getServerInfo().getName() : intent.destination;
            ManagedState state = players.computeIfAbsent(player.getUniqueId(), ignored -> new ManagedState());
            state.username = player.getUsername();
            state.destination = destination;
            state.phase = LimboPhase.ADMITTING;
            state.revision = ++revision;
            playerManager.registerPlayerInLimbo(player, requireRegisteredServer(destination));
            entered = snapshotLocked(player.getUniqueId());
        }

        CompletableFuture<PlayerEnteredLimboEvent> barrier;
        try {
            barrier = proxy.getEventManager().fire(new PlayerEnteredLimboEvent(player, entered));
        } catch (RuntimeException exception) {
            VelocityLimboHandler.getLogger().log(Level.WARNING, "Failed to dispatch PlayerEnteredLimboEvent", exception);
            completeAdmission(player);
            return CompletableFuture.completedFuture(null);
        }

        return barrier.handle((ignored, throwable) -> {
            if (throwable != null) {
                VelocityLimboHandler.getLogger().log(Level.WARNING,
                        "A PlayerEnteredLimboEvent handler failed; continuing admission", throwable);
            }
            completeAdmission(player);
            return null;
        });
    }

    /** Preserves the original target across a Velocity pre-connect reroute into limbo. */
    public void recordRerouteIntent(Player player, RegisteredServer intendedServer) {
        expireDueLeases();
        ManagedPlayerSnapshot before = null;
        ManagedPlayerSnapshot after = null;
        synchronized (lock) {
            ManagedState state = players.get(player.getUniqueId());
            if (state == null) {
                entryIntents.put(player.getUniqueId(), new EntryIntent(
                        intendedServer.getServerInfo().getName(), false, Instant.now()));
                return;
            }
            if (state.phase == LimboPhase.CONNECTING) return;
            before = snapshotLocked(player.getUniqueId());
            state.destination = intendedServer.getServerInfo().getName();
            playerManager.retargetPlayer(player, intendedServer,
                    state.phase == LimboPhase.WAITING && !hasPlayerHoldsLocked(player.getUniqueId()));
            state.revision = ++revision;
            after = snapshotLocked(player.getUniqueId());
        }
        publishTransition(before, after);
    }

    private void completeAdmission(Player player) {
        expireDueLeases();
        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        synchronized (lock) {
            ManagedState state = players.get(player.getUniqueId());
            if (state == null || state.phase != LimboPhase.ADMITTING || !player.isActive()) {
                return;
            }
            before = snapshotLocked(player.getUniqueId());
            boolean held = hasPlayerHoldsLocked(player.getUniqueId());
            boolean issue = playerManager.hasConnectionIssue(player);
            if (held) {
                playerManager.removePlayerFromQueue(player);
                state.phase = LimboPhase.HELD;
            } else if (issue) {
                state.phase = LimboPhase.CONNECTION_ISSUE;
            } else {
                playerManager.admitPlayer(player, requireRegisteredServer(state.destination));
                state.phase = LimboPhase.WAITING;
            }
            state.revision = ++revision;
            after = snapshotLocked(player.getUniqueId());
        }
        publishTransition(before, after);
    }

    public void onPlayerLeft(Player player) {
        ManagedPlayerSnapshot snapshot;
        synchronized (lock) {
            snapshot = snapshotLocked(player.getUniqueId());
            removePlayerLocked(player.getUniqueId());
        }
        if (snapshot != null) {
            proxy.getEventManager().fireAndForget(new PlayerLeftLimboEvent(player, snapshot));
        }
    }

    public void onPlayerDisconnected(Player player) {
        onPlayerLeft(player);
    }

    private void removePlayerLocked(UUID playerId) {
        entryIntents.remove(playerId);
        List<UUID> leaseIds = List.copyOf(playerLeaseIds.getOrDefault(playerId, new LinkedHashSet<>()));
        leaseIds.forEach(this::removeLeaseLocked);
        players.remove(playerId);
        revision++;
    }

    public boolean isPlayerHeld(UUID playerId) {
        expireDueLeases();
        synchronized (lock) {
            return hasPlayerHoldsLocked(playerId);
        }
    }

    public boolean isServerHeld(String serverName) {
        expireDueLeases();
        synchronized (lock) {
            return isServerHeldLocked(serverName);
        }
    }

    /** Atomically claims an eligible managed player before a probe or connection begins. */
    public boolean tryClaimConnection(Player player) {
        expireDueLeases();
        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        synchronized (lock) {
            ManagedState state = players.get(player.getUniqueId());
            if (state == null || state.phase == LimboPhase.CONNECTING || state.phase == LimboPhase.ENTERING
                    || state.phase == LimboPhase.ADMITTING || state.phase == LimboPhase.CONNECTION_ISSUE
                    || hasPlayerHoldsLocked(player.getUniqueId()) || isServerHeldLocked(state.destination)) {
                return false;
            }
            before = snapshotLocked(player.getUniqueId());
            state.phase = LimboPhase.CONNECTING;
            state.revision = ++revision;
            after = snapshotLocked(player.getUniqueId());
        }
        publishTransition(before, after);
        proxy.getEventManager().fireAndForget(new PlayerReconnectAttemptEvent(player, after));
        return true;
    }

    public void finishConnectionAttempt(Player player, String attemptedDestination, ReconnectOutcome outcome, String failure) {
        ManagedPlayerSnapshot before = null;
        ManagedPlayerSnapshot after = null;
        String destination = attemptedDestination;
        synchronized (lock) {
            ManagedState state = players.get(player.getUniqueId());
            if (state != null) {
                destination = state.destination;
                before = snapshotLocked(player.getUniqueId());
                if (outcome != ReconnectOutcome.SUCCESS) {
                    state.phase = playerManager.hasConnectionIssue(player)
                            ? LimboPhase.CONNECTION_ISSUE
                            : hasPlayerHoldsLocked(player.getUniqueId()) ? LimboPhase.HELD : LimboPhase.WAITING;
                    state.revision = ++revision;
                    after = snapshotLocked(player.getUniqueId());
                }
            }
        }
        if (before != null && after != null) publishTransition(before, after);
        proxy.getEventManager().fireAndForget(new PlayerReconnectResultEvent(
                player, destination, outcome, Optional.ofNullable(failure)));
    }

    public void setConnectionIssue(Player player, boolean present) {
        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        synchronized (lock) {
            ManagedState state = players.get(player.getUniqueId());
            if (state == null) return;
            before = snapshotLocked(player.getUniqueId());
            if (present) {
                state.phase = LimboPhase.CONNECTION_ISSUE;
            } else if (hasPlayerHoldsLocked(player.getUniqueId())) {
                state.phase = LimboPhase.HELD;
            } else if (state.phase != LimboPhase.CONNECTING) {
                state.phase = LimboPhase.WAITING;
            }
            state.revision = ++revision;
            after = snapshotLocked(player.getUniqueId());
        }
        publishTransition(before, after);
    }

    public void setAuthenticationBlocked(UUID playerId, boolean blocked, String reason) {
        expireDueLeases();
        if (blocked) {
            boolean managedAndActive;
            synchronized (lock) {
                boolean exists = playerLeaseIds.getOrDefault(playerId, new LinkedHashSet<>()).stream()
                        .map(leases::get).filter(Objects::nonNull)
                        .anyMatch(lease -> lease.ownerId.equals(AUTH_OWNER));
                if (exists) return;
                managedAndActive = players.containsKey(playerId)
                        && proxy.getPlayer(playerId).filter(Player::isActive).isPresent();
                if (!managedAndActive) {
                    acquirePlayerHoldLocked(AUTH_OWNER, playerId,
                            new HoldRequest(reason == null ? "authentication" : reason));
                    return;
                }
            }
            HoldResult result = acquirePlayerHold(AUTH_OWNER, playerId,
                    new HoldRequest(reason == null ? "authentication" : reason));
            if (result.status() != HoldStatus.ACQUIRED) {
                String playerName = proxy.getPlayer(playerId).map(Player::getUsername).orElse("unavailable");
                VelocityLimboHandler.getLogger().warning("Could not acquire authentication hold for "
                        + playerName + " (" + playerId + "): " + result.status());
            }
            return;
        }

        List<UUID> owned;
        synchronized (lock) {
            owned = playerLeaseIds.getOrDefault(playerId, new LinkedHashSet<>()).stream()
                    .map(leases::get).filter(Objects::nonNull)
                    .filter(lease -> lease.ownerId.equals(AUTH_OWNER)).map(lease -> lease.id).toList();
        }
        owned.forEach(id -> releaseLease(AUTH_OWNER, id));
    }

    private HoldResult acquirePlayerHold(String ownerId, UUID playerId, HoldRequest request) {
        expireDueLeases();
        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        HoldResult result;
        synchronized (lock) {
            ManagedState state = players.get(playerId);
            Optional<Player> player = proxy.getPlayer(playerId).filter(Player::isActive);
            if (state == null || player.isEmpty()) return HoldResult.failed(HoldStatus.INACTIVE_OR_UNMANAGED_PLAYER);
            if (state.phase == LimboPhase.CONNECTING) return HoldResult.failed(HoldStatus.CONNECTION_IN_PROGRESS);
            before = snapshotLocked(playerId);
            result = acquirePlayerHoldLocked(ownerId, playerId, request);
            playerManager.removePlayerFromQueue(player.orElseThrow());
            if (state.phase != LimboPhase.ADMITTING && state.phase != LimboPhase.ENTERING) {
                state.phase = LimboPhase.HELD;
            }
            state.revision = ++revision;
            after = snapshotLocked(playerId);
        }
        publishTransition(before, after);
        return result;
    }

    private HoldResult acquirePlayerHoldLocked(String ownerId, UUID playerId, HoldRequest request) {
        LeaseRecord lease = newLease(ownerId, HoldTarget.PLAYER, playerId.toString(), request);
        addLeaseLocked(lease);
        logLease("acquired", lease);
        return HoldResult.acquired(new LeaseHandle(lease.id));
    }

    private HoldResult acquireServerHold(String ownerId, String requestedName, HoldRequest request) {
        String serverName;
        try {
            serverName = requireServerName(requestedName);
        } catch (IllegalArgumentException exception) {
            return HoldResult.failed(HoldStatus.INVALID_TARGET);
        }
        RegisteredServer server = proxy.getServer(serverName).orElse(null);
        if (server == null) return HoldResult.failed(HoldStatus.UNKNOWN_SERVER);
        if (isLimboServer(serverName)) return HoldResult.failed(HoldStatus.INVALID_TARGET);

        expireDueLeases();
        LeaseRecord lease;
        List<HoldSnapshot> holds;
        long eventRevision;
        synchronized (lock) {
            lease = newLease(ownerId, HoldTarget.SERVER, server.getServerInfo().getName(), request);
            addLeaseLocked(lease);
            eventRevision = ++revision;
            holds = serverHoldsLocked(lease.target);
        }
        logLease("acquired", lease);
        proxy.getEventManager().fireAndForget(new ServerHoldChangedEvent(lease.target, holds, eventRevision));
        return HoldResult.acquired(new LeaseHandle(lease.id));
    }

    private HoldReleaseResult releaseLease(String ownerId, UUID leaseId) {
        expireDueLeases();
        LeaseRecord lease;
        ManagedPlayerSnapshot before = null;
        ManagedPlayerSnapshot after = null;
        List<HoldSnapshot> serverHolds = null;
        long eventRevision = 0;
        synchronized (lock) {
            lease = leases.get(leaseId);
            if (lease == null) return HoldReleaseResult.NOT_FOUND;
            if (!lease.ownerId.equals(ownerId)) return HoldReleaseResult.NOT_OWNER;
            UUID playerId = lease.targetType == HoldTarget.PLAYER ? UUID.fromString(lease.target) : null;
            if (playerId != null) before = snapshotLocked(playerId);
            removeLeaseLocked(leaseId);
            eventRevision = ++revision;
            if (playerId != null) {
                after = resumeAfterFinalHoldLocked(playerId);
            } else {
                serverHolds = serverHoldsLocked(lease.target);
            }
        }
        logLease("released", lease);
        if (before != null && after != null) publishTransition(before, after);
        if (serverHolds != null) {
            proxy.getEventManager().fireAndForget(new ServerHoldChangedEvent(lease.target, serverHolds, eventRevision));
        }
        return HoldReleaseResult.RELEASED;
    }

    private ManagedPlayerSnapshot resumeAfterFinalHoldLocked(UUID playerId) {
        if (hasPlayerHoldsLocked(playerId)) return snapshotLocked(playerId);
        ManagedState state = players.get(playerId);
        Player player = proxy.getPlayer(playerId).filter(Player::isActive).orElse(null);
        if (state == null || player == null || state.phase == LimboPhase.CONNECTING
                || state.phase == LimboPhase.ENTERING || state.phase == LimboPhase.ADMITTING) {
            return snapshotLocked(playerId);
        }
        if (playerManager.hasConnectionIssue(player)) {
            state.phase = LimboPhase.CONNECTION_ISSUE;
        } else {
            playerManager.admitPlayer(player, requireRegisteredServer(state.destination));
            state.phase = LimboPhase.WAITING;
        }
        state.revision = ++revision;
        return snapshotLocked(playerId);
    }

    private int releaseAll(String ownerId) {
        expireDueLeases();
        List<UUID> ids;
        synchronized (lock) {
            ids = List.copyOf(ownerLeaseIds.getOrDefault(ownerId, new LinkedHashSet<>()));
        }
        ids.forEach(id -> releaseLease(ownerId, id));
        return ids.size();
    }

    private RetargetResult retarget(UUID playerId, String requestedName) {
        expireDueLeases();
        String serverName;
        try {
            serverName = requireServerName(requestedName);
        } catch (IllegalArgumentException exception) {
            return RetargetResult.INVALID_TARGET;
        }
        RegisteredServer server = proxy.getServer(serverName).orElse(null);
        if (server == null) return RetargetResult.UNKNOWN_SERVER;
        if (isLimboServer(serverName)) return RetargetResult.INVALID_TARGET;

        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        synchronized (lock) {
            ManagedState state = players.get(playerId);
            Player player = proxy.getPlayer(playerId).filter(Player::isActive).orElse(null);
            if (state == null || player == null) return RetargetResult.INACTIVE_OR_UNMANAGED_PLAYER;
            if (state.phase == LimboPhase.CONNECTING) return RetargetResult.CONNECTION_IN_PROGRESS;
            before = snapshotLocked(playerId);
            state.destination = server.getServerInfo().getName();
            entryIntents.computeIfPresent(playerId, (ignored, intent) ->
                    new EntryIntent(state.destination, intent.apiEntry, intent.createdAt));
            playerManager.retargetPlayer(player, server, state.phase == LimboPhase.WAITING && !hasPlayerHoldsLocked(playerId));
            state.revision = ++revision;
            after = snapshotLocked(playerId);
        }
        publishTransition(before, after);
        return RetargetResult.SUCCESS;
    }

    private CompletionStage<EnterResult> enter(String ownerId, Player player, EnterRequest request) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(request, "request");
        if (availability != Availability.READY) return CompletableFuture.completedFuture(EnterResult.NOT_READY);
        if (!player.isActive()) return CompletableFuture.completedFuture(EnterResult.INACTIVE_PLAYER);

        RegisteredServer target;
        if (request.destination().isPresent()) {
            String requested;
            try {
                requested = requireServerName(request.destination().orElseThrow());
            } catch (IllegalArgumentException exception) {
                return CompletableFuture.completedFuture(EnterResult.INVALID_TARGET);
            }
            target = proxy.getServer(requested).orElse(null);
            if (target == null) return CompletableFuture.completedFuture(EnterResult.UNKNOWN_SERVER);
        } else {
            target = player.getCurrentServer().map(connection -> connection.getServer()).orElse(null);
            if (target == null) return CompletableFuture.completedFuture(EnterResult.INVALID_TARGET);
        }
        if (isLimboServer(target.getServerInfo().getName())) {
            return CompletableFuture.completedFuture(EnterResult.INVALID_TARGET);
        }
        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();
        if (limbo == null) return CompletableFuture.completedFuture(EnterResult.NOT_READY);

        synchronized (lock) {
            if (players.containsKey(player.getUniqueId()) || playerManager.isPlayerRegistered(player)) {
                return CompletableFuture.completedFuture(EnterResult.ALREADY_MANAGED);
            }
            ManagedState state = new ManagedState();
            state.username = player.getUsername();
            state.destination = target.getServerInfo().getName();
            state.phase = LimboPhase.ENTERING;
            state.revision = ++revision;
            players.put(player.getUniqueId(), state);
            if (request.initialHold().isPresent()) {
                acquirePlayerHoldLocked(ownerId, player.getUniqueId(), request.initialHold().orElseThrow());
            }
            entryIntents.put(player.getUniqueId(), new EntryIntent(state.destination, true, Instant.now()));
        }

        try {
            return player.createConnectionRequest(limbo).connect().handle((result, throwable) -> {
                if (throwable != null || result == null || !result.isSuccessful()) {
                    cleanupFailedEntry(player.getUniqueId());
                    if (result != null && result.getStatus() == ConnectionRequestBuilder.Status.CONNECTION_IN_PROGRESS) {
                        return EnterResult.CONNECTION_IN_PROGRESS;
                    }
                    return EnterResult.CONNECTION_FAILURE;
                }
                return EnterResult.SUCCESS;
            });
        } catch (RuntimeException exception) {
            cleanupFailedEntry(player.getUniqueId());
            return CompletableFuture.completedFuture(EnterResult.CONNECTION_FAILURE);
        }
    }

    private void cleanupFailedEntry(UUID playerId) {
        synchronized (lock) {
            entryIntents.remove(playerId);
            List<UUID> abandonedHolds = playerLeaseIds.getOrDefault(playerId, new LinkedHashSet<>()).stream()
                    .map(leases::get).filter(Objects::nonNull)
                    .filter(lease -> !lease.ownerId.equals(AUTH_OWNER)).map(lease -> lease.id).toList();
            abandonedHolds.forEach(this::removeLeaseLocked);
            ManagedState state = players.get(playerId);
            if (state != null && state.phase == LimboPhase.ENTERING) players.remove(playerId);
            revision++;
        }
    }

    private ManagedPlayerSnapshot snapshotLocked(UUID playerId) {
        ManagedState state = players.get(playerId);
        if (state == null || state.destination == null) return null;
        Player player = proxy.getPlayer(playerId).orElse(null);
        String username = player == null ? state.username : player.getUsername();
        int position = player == null ? -1 : playerManager.getQueuePosition(player);
        OptionalInt positionValue = position > 0 ? OptionalInt.of(position) : OptionalInt.empty();
        Optional<QueueTier> tier = position > 0 && player != null
                ? Optional.of(tierFor(player, state.destination)) : Optional.empty();
        return new ManagedPlayerSnapshot(playerId, username == null ? playerId.toString() : username,
                state.phase, state.destination, positionValue, tier,
                Optional.ofNullable(playerManager.getConnectionIssue(playerId)),
                playerHoldsLocked(playerId), serverHoldsLocked(state.destination), state.revision);
    }

    private QueueSnapshot queueSnapshot(String serverName) {
        List<PlayerManager.QueuedPlayer> queue = playerManager.getQueueForServer(serverName);
        Map<UUID, Long> playerRevisions = new HashMap<>();
        List<HoldSnapshot> serverHolds;
        long snapshotRevision;
        synchronized (lock) {
            for (PlayerManager.QueuedPlayer queued : queue) {
                ManagedState state = players.get(queued.uuid());
                if (state != null) playerRevisions.put(queued.uuid(), state.revision);
            }
            serverHolds = serverHoldsLocked(serverName);
            snapshotRevision = revision;
        }

        List<QueuedPlayerSnapshot> entries = new ArrayList<>(queue.size());
        int position = 1;
        for (PlayerManager.QueuedPlayer queued : queue) {
            Player player = proxy.getPlayer(queued.uuid()).orElse(null);
            QueueTier tier = player == null ? QueueTier.NORMAL : tierFor(player, serverName);
            entries.add(new QueuedPlayerSnapshot(queued.uuid(), queued.name(), position++, tier,
                    playerRevisions.getOrDefault(queued.uuid(), snapshotRevision)));
        }
        return new QueueSnapshot(canonicalServerName(serverName), entries, serverHolds, snapshotRevision);
    }

    private QueueSummary queueSummary(String serverName) {
        QueueSnapshot snapshot = queueSnapshot(serverName);
        Map<QueueTier, Integer> counts = new EnumMap<>(QueueTier.class);
        for (QueueTier tier : QueueTier.values()) counts.put(tier, 0);
        snapshot.players().forEach(player -> counts.compute(player.tier(), (ignored, count) -> count + 1));
        return new QueueSummary(snapshot.destination(), snapshot.players().size(), counts,
                snapshot.serverHeld(), snapshot.revision());
    }

    private List<HoldSnapshot> playerHoldsLocked(UUID playerId) {
        return holdsLocked(HoldTarget.PLAYER, playerId.toString());
    }

    private List<HoldSnapshot> serverHoldsLocked(String serverName) {
        return holdsLocked(HoldTarget.SERVER, serverName);
    }

    private List<HoldSnapshot> holdsLocked(HoldTarget type, String target) {
        Set<UUID> ids = type == HoldTarget.PLAYER
                ? playerLeaseIds.getOrDefault(UUID.fromString(target), new LinkedHashSet<>())
                : serverLeaseIds.getOrDefault(normalizeServerName(target), new LinkedHashSet<>());
        return ids.stream().map(leases::get).filter(Objects::nonNull)
                .sorted(Comparator.comparing(lease -> lease.acquiredAt))
                .map(LeaseRecord::snapshot).toList();
    }

    private boolean hasPlayerHoldsLocked(UUID playerId) {
        Set<UUID> ids = playerLeaseIds.get(playerId);
        return ids != null && !ids.isEmpty();
    }

    private boolean isServerHeldLocked(String serverName) {
        Set<UUID> ids = serverLeaseIds.get(normalizeServerName(serverName));
        return ids != null && !ids.isEmpty();
    }

    private LeaseRecord newLease(String ownerId, HoldTarget targetType, String target, HoldRequest request) {
        Instant acquired = Instant.now();
        return new LeaseRecord(UUID.randomUUID(), ownerId, targetType, target, request.reason(), acquired,
                request.duration().map(acquired::plus), ++revision);
    }

    private void addLeaseLocked(LeaseRecord lease) {
        leases.put(lease.id, lease);
        ownerLeaseIds.computeIfAbsent(lease.ownerId, ignored -> new LinkedHashSet<>()).add(lease.id);
        if (lease.targetType == HoldTarget.PLAYER) {
            playerLeaseIds.computeIfAbsent(UUID.fromString(lease.target), ignored -> new LinkedHashSet<>()).add(lease.id);
        } else {
            String normalized = normalizeServerName(lease.target);
            serverLeaseIds.computeIfAbsent(normalized, ignored -> new LinkedHashSet<>()).add(lease.id);
            serverHoldTargets.putIfAbsent(normalized, lease.target);
        }
        lease.expiresAt.ifPresent(expires -> {
            expirations.computeIfAbsent(expires, ignored -> new LinkedHashSet<>()).add(lease.id);
            if (expiryTaskAt == null || expires.isBefore(expiryTaskAt)) scheduleNextExpiryLocked();
        });
    }

    private void scheduleNextExpiryLocked() {
        if (expiryTask != null) expiryTask.cancel();
        expiryTask = null;
        expiryTaskAt = null;
        long generation = ++expiryGeneration;
        if (availability == Availability.STOPPING || expirations.isEmpty()) return;

        Instant nextExpiry = expirations.firstKey();
        long delay = Math.max(1, nextExpiry.toEpochMilli() - Instant.now().toEpochMilli());
        expiryTaskAt = nextExpiry;
        try {
            expiryTask = proxy.getScheduler().buildTask(plugin, () -> expireDueLeases(generation))
                    .delay(delay, TimeUnit.MILLISECONDS).schedule();
        } catch (RuntimeException exception) {
            expiryTaskAt = null;
            VelocityLimboHandler.getLogger().log(Level.WARNING, "Could not schedule hold expiry", exception);
        }
    }

    private void expireDueLeases() {
        expireDueLeases(-1);
    }

    private void expireDueLeases(long expectedGeneration) {
        List<ExpiredLeaseResult> expired = new ArrayList<>();
        synchronized (lock) {
            if (expectedGeneration >= 0 && expectedGeneration != expiryGeneration) return;
            Instant now = Instant.now();
            if (expirations.isEmpty() || expirations.firstKey().isAfter(now)) {
                if (expectedGeneration >= 0) scheduleNextExpiryLocked();
                return;
            }

            if (expiryTask != null) expiryTask.cancel();
            expiryTask = null;
            expiryTaskAt = null;
            expiryGeneration++;

            List<UUID> dueIds = new ArrayList<>();
            while (!expirations.isEmpty() && !expirations.firstKey().isAfter(now)) {
                dueIds.addAll(expirations.pollFirstEntry().getValue());
            }

            for (UUID leaseId : dueIds) {
                ExpiredLeaseResult result = expireLeaseLocked(leaseId, now);
                if (result != null) expired.add(result);
            }
            scheduleNextExpiryLocked();
        }

        for (ExpiredLeaseResult result : expired) {
            logLease("expired", result.lease);
            if (result.before != null && result.after != null) publishTransition(result.before, result.after);
            if (result.serverHolds != null) {
                proxy.getEventManager().fireAndForget(new ServerHoldChangedEvent(
                        result.lease.target, result.serverHolds, result.eventRevision));
            }
        }
    }

    private ExpiredLeaseResult expireLeaseLocked(UUID leaseId, Instant now) {
        LeaseRecord lease = leases.get(leaseId);
        if (lease == null || lease.expiresAt.isEmpty() || lease.expiresAt.orElseThrow().isAfter(now)) {
            return null;
        }

        UUID playerId = lease.targetType == HoldTarget.PLAYER ? UUID.fromString(lease.target) : null;
        ManagedPlayerSnapshot before = playerId == null ? null : snapshotLocked(playerId);
        removeLeaseIndexesLocked(lease);
        long eventRevision = ++revision;
        ManagedPlayerSnapshot after = playerId == null ? null : resumeAfterFinalHoldLocked(playerId);
        List<HoldSnapshot> holds = playerId == null ? serverHoldsLocked(lease.target) : null;
        return new ExpiredLeaseResult(lease, before, after, holds, eventRevision);
    }

    private void removeLeaseLocked(UUID leaseId) {
        LeaseRecord lease = leases.get(leaseId);
        if (lease == null) return;
        removeLeaseIndexesLocked(lease);
        lease.expiresAt.ifPresent(expires -> {
            LinkedHashSet<UUID> ids = expirations.get(expires);
            if (ids == null) return;
            ids.remove(leaseId);
            if (ids.isEmpty()) {
                expirations.remove(expires);
                if (expires.equals(expiryTaskAt)) scheduleNextExpiryLocked();
            }
        });
    }

    private void removeLeaseIndexesLocked(LeaseRecord lease) {
        leases.remove(lease.id);
        removeFromIndex(ownerLeaseIds, lease.ownerId, lease.id);
        if (lease.targetType == HoldTarget.PLAYER) {
            removeFromIndex(playerLeaseIds, UUID.fromString(lease.target), lease.id);
        } else {
            String normalized = normalizeServerName(lease.target);
            removeFromIndex(serverLeaseIds, normalized, lease.id);
            if (!serverLeaseIds.containsKey(normalized)) serverHoldTargets.remove(normalized);
        }
    }

    private <K> void removeFromIndex(Map<K, LinkedHashSet<UUID>> index, K key, UUID leaseId) {
        LinkedHashSet<UUID> ids = index.get(key);
        if (ids == null) return;
        ids.remove(leaseId);
        if (ids.isEmpty()) index.remove(key);
    }

    private String normalizeServerName(String serverName) {
        return serverName.toLowerCase(Locale.ROOT);
    }

    private RegisteredServer requireRegisteredServer(String serverName) {
        return proxy.getServer(serverName).orElseThrow(() -> new IllegalStateException("Server disappeared: " + serverName));
    }

    private String canonicalServerName(String serverName) {
        return proxy.getServer(serverName).map(server -> server.getServerInfo().getName()).orElse(serverName);
    }

    private String requireServerName(String serverName) {
        String normalized = Objects.requireNonNull(serverName, "serverName").trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException("serverName must not be blank");
        return normalized;
    }

    private boolean isLimboServer(String name) {
        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();
        return limbo != null && limbo.getServerInfo().getName().equalsIgnoreCase(name);
    }

    private QueueTier tierFor(Player player, String serverName) {
        String normalized = serverName.toLowerCase(Locale.ROOT);
        if (player.hasPermission("vlh.queue.bypass") || player.hasPermission("vlh.queue.bypass." + normalized)) {
            return QueueTier.BYPASS;
        }
        if (player.hasPermission("vlh.queue.priority") || player.hasPermission("vlh.queue.priority." + normalized)) {
            return QueueTier.PRIORITY;
        }
        return QueueTier.NORMAL;
    }

    private void publishTransition(ManagedPlayerSnapshot before, ManagedPlayerSnapshot after) {
        if (before != null && after != null && !before.equals(after)) {
            proxy.getEventManager().fireAndForget(new PlayerLimboStateChangedEvent(before, after));
        }
    }

    private void logLease(String action, LeaseRecord lease) {
        VelocityLimboHandler.getLogger().info(() -> "Hold " + action + ": owner=" + lease.ownerId
                + ", target=" + lease.targetType.name().toLowerCase(Locale.ROOT) + ":" + lease.target
                + ", reason=" + lease.reason + ", lease=" + lease.id);
    }

    public int heldPlayerCount() {
        expireDueLeases();
        synchronized (lock) {
            return playerLeaseIds.size();
        }
    }

    public int heldServerCount() {
        expireDueLeases();
        synchronized (lock) {
            return serverLeaseIds.size();
        }
    }

    private final class Controller implements LimboController {
        private final String ownerId;

        private Controller(String ownerId) {
            this.ownerId = ownerId;
        }

        @Override public String ownerId() { return ownerId; }

        @Override public CompletionStage<EnterResult> enterLimbo(Player player, EnterRequest request) {
            return enter(ownerId, player, request);
        }

        @Override public HoldResult holdPlayer(UUID playerId, HoldRequest request) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(request, "request");
            return availability == Availability.READY
                    ? acquirePlayerHold(ownerId, playerId, request) : HoldResult.failed(HoldStatus.NOT_READY);
        }

        @Override public HoldResult holdServer(String serverName, HoldRequest request) {
            Objects.requireNonNull(serverName, "serverName");
            Objects.requireNonNull(request, "request");
            return availability == Availability.READY
                    ? acquireServerHold(ownerId, serverName, request) : HoldResult.failed(HoldStatus.NOT_READY);
        }

        @Override public HoldReleaseResult releaseHold(UUID leaseId) {
            Objects.requireNonNull(leaseId, "leaseId");
            return availability == Availability.READY
                    ? releaseLease(ownerId, leaseId) : HoldReleaseResult.NOT_READY;
        }

        @Override public int releaseAllHolds() {
            return availability == Availability.READY ? releaseAll(ownerId) : 0;
        }

        @Override public RetargetResult retargetPlayer(UUID playerId, String serverName) {
            Objects.requireNonNull(playerId, "playerId");
            Objects.requireNonNull(serverName, "serverName");
            return availability == Availability.READY ? retarget(playerId, serverName) : RetargetResult.NOT_READY;
        }
    }

    private static final class ManagedState {
        private String username;
        private String destination;
        private LimboPhase phase;
        private long revision;
    }

    private record EntryIntent(String destination, boolean apiEntry, Instant createdAt) { }

    private record LeaseHandle(UUID id) implements HoldLease { }

    private record ExpiredLeaseResult(LeaseRecord lease, ManagedPlayerSnapshot before,
                                      ManagedPlayerSnapshot after, List<HoldSnapshot> serverHolds,
                                      long eventRevision) { }

    private record LeaseRecord(UUID id, String ownerId, HoldTarget targetType, String target, String reason,
                               Instant acquiredAt, Optional<Instant> expiresAt, long revision) {
        private HoldSnapshot snapshot() {
            return new HoldSnapshot(id, ownerId, targetType, target, reason, acquiredAt, expiresAt, revision);
        }
    }
}
