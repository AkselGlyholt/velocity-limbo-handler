package com.akselglyholt.velocityLimboHandler.api;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.akselglyholt.velocitylimbohandler.api.Availability;
import com.akselglyholt.velocitylimbohandler.api.EnterRequest;
import com.akselglyholt.velocitylimbohandler.api.EnterResult;
import com.akselglyholt.velocitylimbohandler.api.HoldLease;
import com.akselglyholt.velocitylimbohandler.api.HoldReleaseResult;
import com.akselglyholt.velocitylimbohandler.api.HoldRequest;
import com.akselglyholt.velocitylimbohandler.api.HoldResult;
import com.akselglyholt.velocitylimbohandler.api.HoldSnapshot;
import com.akselglyholt.velocitylimbohandler.api.HoldStatus;
import com.akselglyholt.velocitylimbohandler.api.HoldTarget;
import com.akselglyholt.velocitylimbohandler.api.LimboController;
import com.akselglyholt.velocitylimbohandler.api.LimboPhase;
import com.akselglyholt.velocitylimbohandler.api.ManagedPlayerSnapshot;
import com.akselglyholt.velocitylimbohandler.api.QueueSnapshot;
import com.akselglyholt.velocitylimbohandler.api.QueueSummary;
import com.akselglyholt.velocitylimbohandler.api.QueueTier;
import com.akselglyholt.velocitylimbohandler.api.QueuedPlayerSnapshot;
import com.akselglyholt.velocitylimbohandler.api.ReconnectOutcome;
import com.akselglyholt.velocitylimbohandler.api.RetargetResult;
import com.akselglyholt.velocitylimbohandler.api.VelocityLimboApi;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerEnteredLimboEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerLeftLimboEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerLimboStateChangedEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerReconnectAttemptEvent;
import com.akselglyholt.velocitylimbohandler.api.events.PlayerReconnectResultEvent;
import com.akselglyholt.velocitylimbohandler.api.events.ServerHoldChangedEvent;
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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
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
    private final Map<UUID, ScheduledTask> expiryTasks = new HashMap<>();
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
            expiryTasks.values().forEach(ScheduledTask::cancel);
            expiryTasks.clear();
            entryIntents.clear();
            leases.clear();
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
        synchronized (lock) {
            pruneExpiredLocked();
            return Optional.ofNullable(snapshotLocked(playerId));
        }
    }

    @Override
    public QueueSnapshot queue(String serverName) {
        String normalized = requireServerName(serverName);
        synchronized (lock) {
            pruneExpiredLocked();
            return queueSnapshotLocked(normalized);
        }
    }

    @Override
    public List<QueueSummary> queues() {
        synchronized (lock) {
            pruneExpiredLocked();
            List<String> names = new ArrayList<>(playerManager.getQueuedServerNames());
            leases.values().stream()
                    .filter(lease -> lease.targetType == HoldTarget.SERVER)
                    .map(lease -> lease.target)
                    .filter(name -> names.stream().noneMatch(name::equalsIgnoreCase))
                    .forEach(names::add);
            names.sort(String.CASE_INSENSITIVE_ORDER);
            return names.stream().map(this::queueSummaryLocked).toList();
        }
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
        List<UUID> leaseIds = leases.values().stream()
                .filter(lease -> lease.targetType == HoldTarget.PLAYER && lease.target.equals(playerId.toString()))
                .map(lease -> lease.id).toList();
        leaseIds.forEach(this::removeLeaseLocked);
        players.remove(playerId);
        revision++;
    }

    public boolean isPlayerHeld(UUID playerId) {
        synchronized (lock) {
            pruneExpiredLocked();
            return hasPlayerHoldsLocked(playerId);
        }
    }

    public boolean isServerHeld(String serverName) {
        synchronized (lock) {
            pruneExpiredLocked();
            return leases.values().stream().anyMatch(lease -> lease.targetType == HoldTarget.SERVER
                    && lease.target.equalsIgnoreCase(serverName));
        }
    }

    /** Atomically claims an eligible managed player before a probe or connection begins. */
    public boolean tryClaimConnection(Player player) {
        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        synchronized (lock) {
            pruneExpiredLocked();
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
        if (blocked) {
            boolean managedAndActive;
            synchronized (lock) {
                boolean exists = leases.values().stream().anyMatch(lease -> lease.ownerId.equals(AUTH_OWNER)
                        && lease.targetType == HoldTarget.PLAYER && lease.target.equals(playerId.toString()));
                if (exists) return;
                managedAndActive = players.containsKey(playerId)
                        && proxy.getPlayer(playerId).filter(Player::isActive).isPresent();
                if (!managedAndActive) {
                    acquirePlayerHoldLocked(AUTH_OWNER, playerId,
                            new HoldRequest(reason == null ? "authentication" : reason));
                    return;
                }
            }
            acquirePlayerHold(AUTH_OWNER, playerId,
                    new HoldRequest(reason == null ? "authentication" : reason));
            return;
        }

        List<UUID> owned;
        synchronized (lock) {
            owned = leases.values().stream().filter(lease -> lease.ownerId.equals(AUTH_OWNER)
                    && lease.targetType == HoldTarget.PLAYER && lease.target.equals(playerId.toString()))
                    .map(lease -> lease.id).toList();
        }
        owned.forEach(id -> releaseLease(AUTH_OWNER, id));
    }

    private HoldResult acquirePlayerHold(String ownerId, UUID playerId, HoldRequest request) {
        ManagedPlayerSnapshot before;
        ManagedPlayerSnapshot after;
        HoldResult result;
        synchronized (lock) {
            pruneExpiredLocked();
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
        leases.put(lease.id, lease);
        scheduleExpiry(lease);
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

        LeaseRecord lease;
        List<HoldSnapshot> holds;
        long eventRevision;
        synchronized (lock) {
            pruneExpiredLocked();
            lease = newLease(ownerId, HoldTarget.SERVER, server.getServerInfo().getName(), request);
            leases.put(lease.id, lease);
            eventRevision = ++revision;
            scheduleExpiry(lease);
            holds = serverHoldsLocked(lease.target);
        }
        logLease("acquired", lease);
        proxy.getEventManager().fireAndForget(new ServerHoldChangedEvent(lease.target, holds, eventRevision));
        return HoldResult.acquired(new LeaseHandle(lease.id));
    }

    private HoldReleaseResult releaseLease(String ownerId, UUID leaseId) {
        LeaseRecord lease;
        ManagedPlayerSnapshot before = null;
        ManagedPlayerSnapshot after = null;
        List<HoldSnapshot> serverHolds = null;
        long eventRevision = 0;
        synchronized (lock) {
            pruneExpiredLocked();
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
        List<UUID> ids;
        synchronized (lock) {
            pruneExpiredLocked();
            ids = leases.values().stream().filter(lease -> lease.ownerId.equals(ownerId)).map(lease -> lease.id).toList();
        }
        ids.forEach(id -> releaseLease(ownerId, id));
        return ids.size();
    }

    private RetargetResult retarget(UUID playerId, String requestedName) {
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
            List<UUID> abandonedHolds = leases.values().stream()
                    .filter(lease -> lease.targetType == HoldTarget.PLAYER
                            && lease.target.equals(playerId.toString())
                            && !lease.ownerId.equals(AUTH_OWNER))
                    .map(lease -> lease.id).toList();
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

    private QueueSnapshot queueSnapshotLocked(String serverName) {
        List<PlayerManager.QueuedPlayer> queue = playerManager.getQueueForServer(serverName);
        List<QueuedPlayerSnapshot> entries = new ArrayList<>(queue.size());
        int position = 1;
        for (PlayerManager.QueuedPlayer queued : queue) {
            Player player = proxy.getPlayer(queued.uuid()).orElse(null);
            QueueTier tier = player == null ? QueueTier.NORMAL : tierFor(player, serverName);
            ManagedState state = players.get(queued.uuid());
            entries.add(new QueuedPlayerSnapshot(queued.uuid(), queued.name(), position++, tier,
                    state == null ? revision : state.revision));
        }
        return new QueueSnapshot(canonicalServerName(serverName), entries, serverHoldsLocked(serverName), revision);
    }

    private QueueSummary queueSummaryLocked(String serverName) {
        QueueSnapshot snapshot = queueSnapshotLocked(serverName);
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
        return leases.values().stream()
                .filter(lease -> lease.targetType == type && lease.target.equalsIgnoreCase(target))
                .sorted(Comparator.comparing(lease -> lease.acquiredAt))
                .map(LeaseRecord::snapshot).toList();
    }

    private boolean hasPlayerHoldsLocked(UUID playerId) {
        String target = playerId.toString();
        return leases.values().stream().anyMatch(lease -> lease.targetType == HoldTarget.PLAYER && lease.target.equals(target));
    }

    private boolean isServerHeldLocked(String serverName) {
        return leases.values().stream().anyMatch(lease -> lease.targetType == HoldTarget.SERVER
                && lease.target.equalsIgnoreCase(serverName));
    }

    private LeaseRecord newLease(String ownerId, HoldTarget targetType, String target, HoldRequest request) {
        Instant acquired = Instant.now();
        return new LeaseRecord(UUID.randomUUID(), ownerId, targetType, target, request.reason(), acquired,
                request.duration().map(acquired::plus), ++revision);
    }

    private void scheduleExpiry(LeaseRecord lease) {
        lease.expiresAt.ifPresent(expires -> {
            long delay = Math.max(1, expires.toEpochMilli() - Instant.now().toEpochMilli());
            try {
                ScheduledTask task = proxy.getScheduler().buildTask(plugin, () -> expireLease(lease.id))
                        .delay(delay, TimeUnit.MILLISECONDS).schedule();
                expiryTasks.put(lease.id, task);
            } catch (RuntimeException exception) {
                VelocityLimboHandler.getLogger().log(Level.WARNING, "Could not schedule hold expiry", exception);
            }
        });
    }

    private void expireLease(UUID leaseId) {
        LeaseRecord lease;
        ManagedPlayerSnapshot before = null;
        ManagedPlayerSnapshot after = null;
        List<HoldSnapshot> holds = null;
        long eventRevision;
        synchronized (lock) {
            lease = leases.get(leaseId);
            if (lease == null || lease.expiresAt.isEmpty() || lease.expiresAt.orElseThrow().isAfter(Instant.now())) return;
            UUID playerId = lease.targetType == HoldTarget.PLAYER ? UUID.fromString(lease.target) : null;
            if (playerId != null) before = snapshotLocked(playerId);
            removeLeaseLocked(leaseId);
            eventRevision = ++revision;
            if (playerId != null) after = resumeAfterFinalHoldLocked(playerId);
            else holds = serverHoldsLocked(lease.target);
        }
        logLease("expired", lease);
        if (before != null && after != null) publishTransition(before, after);
        if (holds != null) proxy.getEventManager().fireAndForget(new ServerHoldChangedEvent(lease.target, holds, eventRevision));
    }

    private void pruneExpiredLocked() {
        Instant now = Instant.now();
        List<UUID> expired = leases.values().stream()
                .filter(lease -> lease.expiresAt.map(expiry -> !expiry.isAfter(now)).orElse(false))
                .map(lease -> lease.id).toList();
        expired.forEach(id -> {
            LeaseRecord lease = leases.get(id);
            if (lease != null) {
                UUID playerId = lease.targetType == HoldTarget.PLAYER ? UUID.fromString(lease.target) : null;
                ManagedPlayerSnapshot before = playerId == null ? null : snapshotLocked(playerId);
                removeLeaseLocked(id);
                long eventRevision = ++revision;
                ManagedPlayerSnapshot after = playerId == null ? null : resumeAfterFinalHoldLocked(playerId);
                logLease("expired", lease);
                if (before != null && after != null) publishTransition(before, after);
                if (playerId == null) {
                    proxy.getEventManager().fireAndForget(new ServerHoldChangedEvent(
                            lease.target, serverHoldsLocked(lease.target), eventRevision));
                }
            }
        });
    }

    private void removeLeaseLocked(UUID leaseId) {
        leases.remove(leaseId);
        ScheduledTask task = expiryTasks.remove(leaseId);
        if (task != null) task.cancel();
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
        synchronized (lock) {
            pruneExpiredLocked();
            return (int) leases.values().stream().filter(lease -> lease.targetType == HoldTarget.PLAYER)
                    .map(lease -> lease.target).distinct().count();
        }
    }

    public int heldServerCount() {
        synchronized (lock) {
            pruneExpiredLocked();
            return (int) leases.values().stream().filter(lease -> lease.targetType == HoldTarget.SERVER)
                    .map(lease -> lease.target.toLowerCase(Locale.ROOT)).distinct().count();
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

    private record LeaseRecord(UUID id, String ownerId, HoldTarget targetType, String target, String reason,
                               Instant acquiredAt, Optional<Instant> expiresAt, long revision) {
        private HoldSnapshot snapshot() {
            return new HoldSnapshot(id, ownerId, targetType, target, reason, acquiredAt, expiresAt, revision);
        }
    }
}
