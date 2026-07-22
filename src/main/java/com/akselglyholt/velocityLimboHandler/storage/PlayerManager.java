package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
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
    private final ReconnectQueueState reconnectQueueState = new ReconnectQueueState(this::removePlayerState, this::getActivePlayer);
    private final AtomicLong nextPruneAtNanos = new AtomicLong();
    private volatile String queuePositionMsg;

    public PlayerManager() {
        reloadMessages();
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
        return reconnectQueueState.getQueuePosition(player.getUniqueId(), previousServer.getServerInfo().getName());
    }

    public void addPlayerWithIssue(Player player, String issue) {
        connectionState.addConnectionIssue(player.getUniqueId(), issue);
    }

    public boolean hasConnectionIssue(Player player) {
        return connectionState.hasConnectionIssue(player.getUniqueId());
    }

    public String getConnectionIssue(Player player) {
        return connectionState.getConnectionIssue(player.getUniqueId());
    }

    public void removePlayerIssue(Player player) {
        connectionState.removeConnectionIssue(player.getUniqueId());
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

    private void removePlayerState(UUID playerId) {
        connectionState.removePlayerState(playerId);
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
