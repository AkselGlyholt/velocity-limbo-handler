package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocitylimbohandler.api.Availability;
import com.akselglyholt.velocitylimbohandler.api.ReconnectOutcome;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.route.Route;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class PlayerManager {
    private static final long PRUNE_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

    public record QueuedPlayer(UUID uuid, String name) {
    }

    private final PlayerConnectionState connectionState = new PlayerConnectionState();
    private final ReconnectQueueState reconnectQueueState = new ReconnectQueueState(
            this::removePlayerStateIfInactive,
            this::getActivePlayer
    );
    private final AtomicLong nextPruneAtNanos = new AtomicLong();
    private volatile String queuePositionMsg;
    private volatile VelocityLimboApiImpl api;

    public PlayerManager() {
        reloadMessages();
    }

    public void attachApi(VelocityLimboApiImpl api) {
        this.api = api;
    }

    private boolean isAuthBlocked(Player player) {
        var authManager = VelocityLimboHandler.getAuthManager();
        return authManager != null && authManager.isAuthBlocked(player);
    }

    public void reloadMessages() {
        queuePositionMsg = VelocityLimboHandler.getMessageConfig().getString(Route.from("queuePositionJoin"));
    }

    public void addPlayer(Player player, RegisteredServer registeredServer) {
        UUID playerId = player.getUniqueId();
        if (connectionState.isRegistered(playerId)) {
            Utility.logDebug(() -> String.format(
                    "Skipping addPlayer for %s — already registered for '%s'",
                    player.getUsername(),
                    connectionState.getRegisteredServer(playerId).orElse("unknown")));
            return;
        }

        if (isAuthBlocked(player)) {
            Utility.logDebug(() -> String.format("Skipping addPlayer for %s — auth blocked", player.getUsername()));
            return;
        }

        String serverName = registeredServer.getServerInfo().getName();
        connectionState.registerPlayer(playerId, serverName);

        Utility.logDebug(() -> String.format(
                "%s joined limbo — queued for %s", player.getUsername(), serverName));

        Utility.sendWelcomeMessage(player, null);

        if (VelocityLimboHandler.isQueueEnabled()) {
            reconnectQueueState.enqueue(player, registeredServer);
            player.sendMessage(MessageFormatter.formatComponent(queuePositionMsg, player));
        }
    }

    /** Registers arrival without admitting to the queue; API event handlers run between these operations. */
    public void registerPlayerInLimbo(Player player, RegisteredServer registeredServer) {
        UUID playerId = player.getUniqueId();
        connectionState.registerPlayer(playerId, registeredServer.getServerInfo().getName());
        Utility.logDebug(() -> String.format("%s joined limbo — destination %s",
                player.getUsername(), registeredServer.getServerInfo().getName()));
        Utility.sendWelcomeMessage(player, null);
    }

    /** Appends a player to their freshly evaluated permission tier when queueing is enabled. */
    public void admitPlayer(Player player, RegisteredServer registeredServer) {
        connectionState.registerPlayer(player.getUniqueId(), registeredServer.getServerInfo().getName());
        if (VelocityLimboHandler.isQueueEnabled()) {
            reconnectQueueState.enqueue(player, registeredServer);
            player.sendMessage(MessageFormatter.formatComponent(queuePositionMsg, player));
        }
    }

    public void removePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        reconnectQueueState.removePlayer(playerId);
        removePlayerState(playerId);
        VelocityLimboHandler.getReconnectBlocker().unblock(playerId);
    }

    public RegisteredServer getPreviousServer(Player player) {
        return connectionState.getRegisteredServer(player.getUniqueId())
                .flatMap(serverName -> VelocityLimboHandler.getProxyServer().getServer(serverName))
                .orElse(VelocityLimboHandler.getDirectConnectServer());
    }

    public boolean isPlayerRegistered(Player player) {
        return connectionState.isRegistered(player.getUniqueId());
    }

    public void addPlayerToQueue(Player player, RegisteredServer server) {
        reconnectQueueState.enqueue(player, server);
    }

    public void removePlayerFromQueue(Player player) {
        reconnectQueueState.removePlayer(player.getUniqueId());
    }

    public Player getNextQueuedPlayer(RegisteredServer server) {
        return reconnectQueueState.getNextQueuedPlayer(server);
    }

    public boolean hasQueuedPlayers(RegisteredServer server) {
        return reconnectQueueState.hasQueuedPlayers(server);
    }

    public int getQueuePosition(Player player) {
        RegisteredServer previousServer = getPreviousServer(player);
        if (previousServer == null) {
            return -1;
        }
        return reconnectQueueState.getQueuePosition(player.getUniqueId(), previousServer.getServerInfo().getName());
    }

    public void addPlayerWithIssue(Player player, String issue) {
        connectionState.addConnectionIssue(player.getUniqueId(), issue);
        VelocityLimboApiImpl currentApi = api;
        if (currentApi != null) currentApi.setConnectionIssue(player, true);
    }

    public boolean hasConnectionIssue(Player player) {
        return connectionState.hasConnectionIssue(player.getUniqueId());
    }

    public String getConnectionIssue(Player player) {
        return connectionState.getConnectionIssue(player.getUniqueId());
    }

    public String getConnectionIssue(UUID playerId) {
        return connectionState.getConnectionIssue(playerId);
    }

    public void removePlayerIssue(Player player) {
        connectionState.removeConnectionIssue(player.getUniqueId());
        VelocityLimboApiImpl currentApi = api;
        if (currentApi != null) currentApi.setConnectionIssue(player, false);
    }

    public void pruneInactivePlayers() {
        reconnectQueueState.pruneInactivePlayers();
        connectionState.pruneInactivePlayers(this::isInactiveOrMissing);
        nextPruneAtNanos.set(System.nanoTime() + PRUNE_INTERVAL_NANOS);
    }

    public void pruneInactivePlayersIfDue() {
        long now = System.nanoTime();
        long nextPrune = nextPruneAtNanos.get();
        if (now < nextPrune || !nextPruneAtNanos.compareAndSet(nextPrune, now + PRUNE_INTERVAL_NANOS)) {
            return;
        }

        reconnectQueueState.pruneInactivePlayers();
        connectionState.pruneInactivePlayers(this::isInactiveOrMissing);
    }

    public int getQueuedServerCount() {
        return reconnectQueueState.getQueuedServerCount();
    }

    public int getQueuedPlayerCount() {
        return reconnectQueueState.getQueuedPlayerCount();
    }

    public Map<String, Integer> getQueuedServerCounts() {
        return reconnectQueueState.getQueuedServerCounts();
    }

    public List<String> getQueuedServerNames() {
        return reconnectQueueState.getQueuedServerNames();
    }

    public int getQueueSize(String serverName) {
        return reconnectQueueState.getQueueSize(serverName);
    }

    public List<QueuedPlayer> getQueueForServer(String serverName) {
        return reconnectQueueState.getQueueForServer(serverName);
    }

    public static Player findFirstMaintenanceAllowedPlayer(RegisteredServer server) {
        return VelocityLimboHandler.getPlayerManager().reconnectQueueState.findFirstMaintenanceAllowedPlayer(server);
    }

    public boolean isPlayerConnecting(Player player) {
        return connectionState.isConnecting(player.getUniqueId());
    }

    public void setPlayerConnecting(Player player, Boolean add) {
        connectionState.setConnecting(player.getUniqueId(), add);
    }

    public boolean tryClaimConnection(Player player) {
        VelocityLimboApiImpl currentApi = api;
        if (currentApi != null && currentApi.availability() == Availability.READY) {
            if (!currentApi.tryClaimConnection(player)) return false;
            connectionState.setConnecting(player.getUniqueId(), true);
            return true;
        }
        if (isPlayerConnecting(player)) return false;
        setPlayerConnecting(player, true);
        return true;
    }

    public boolean usesApiLifecycle() {
        VelocityLimboApiImpl currentApi = api;
        return currentApi != null && currentApi.availability() == Availability.READY;
    }

    public void finishConnectionAttempt(Player player, String destination, ReconnectOutcome outcome, String failure) {
        connectionState.setConnecting(player.getUniqueId(), false);
        VelocityLimboApiImpl currentApi = api;
        if (currentApi != null) currentApi.finishConnectionAttempt(player, destination, outcome, failure);
    }

    public boolean isPlayerHeld(UUID playerId) {
        VelocityLimboApiImpl currentApi = api;
        return currentApi != null && currentApi.isPlayerHeld(playerId);
    }

    public boolean isServerHeld(String serverName) {
        VelocityLimboApiImpl currentApi = api;
        return currentApi != null && currentApi.isServerHeld(serverName);
    }

    public void setAuthenticationBlocked(UUID playerId, boolean blocked, String reason) {
        VelocityLimboApiImpl currentApi = api;
        if (currentApi != null) currentApi.setAuthenticationBlocked(playerId, blocked, reason);
    }

    public void retargetPlayer(Player player, RegisteredServer server, boolean enqueue) {
        reconnectQueueState.removePlayer(player.getUniqueId());
        connectionState.registerPlayer(player.getUniqueId(), server.getServerInfo().getName());
        if (enqueue && VelocityLimboHandler.isQueueEnabled()) {
            reconnectQueueState.enqueue(player, server);
        }
    }

    private void removePlayerState(UUID playerId) {
        connectionState.removePlayerState(playerId);
    }

    private void removePlayerStateIfInactive(UUID playerId) {
        connectionState.removePlayerStateIf(playerId, this::isInactiveOrMissing);
    }

    private Player getActivePlayer(UUID playerId) {
        return VelocityLimboHandler.getProxyServer()
                .getPlayer(playerId)
                .filter(Player::isActive)
                .orElse(null);
    }

    private boolean isInactiveOrMissing(UUID playerId) {
        return getActivePlayer(playerId) == null;
    }
}
