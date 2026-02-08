package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.route.Route;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlayerManager {
    public record QueuedPlayer(UUID uuid, String name) {}

    private final Map<UUID, String> playerData;
    private final Map<UUID, Boolean> connectingPlayers;
    private final Map<String, Queue<UUID>> reconnectQueues = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> playerConnectionIssues = new ConcurrentHashMap<>();
    private static String queuePositionMsg;

    /**
     * @param player the player of which you're checking
     * @return returns a boolean value, true or false depending on if the player is blocked
     */
    private boolean isAuthBlocked(Player player) {
        var am = VelocityLimboHandler.getAuthManager();
        return am != null && am.isAuthBlocked(player);
    }

    public PlayerManager() {
        this.playerData = new ConcurrentHashMap<>();
        this.connectingPlayers = new ConcurrentHashMap<>();

        reloadMessages();
    }

    public void reloadMessages() {
        queuePositionMsg = VelocityLimboHandler.getMessageConfig().getString(Route.from("queuePositionJoin"));
    }

    /**
     * Initialize a player into the system
     * @param player The player you're trying to add
     * @param registeredServer The server of which the player should be reconnected to
     */
    public void addPlayer(Player player, RegisteredServer registeredServer) {
        UUID playerId = player.getUniqueId();

        // Don't override if the player is already registered
        if (this.playerData.containsKey(playerId)) return;

        if (isAuthBlocked(player)) return;

        String serverName = registeredServer.getServerInfo().getName();
        this.playerData.put(playerId, serverName);

        Utility.sendWelcomeMessage(player, null);

        // Only maintain a reconnect queue when queue mode is enabled
        Queue<UUID> queue = reconnectQueues.computeIfAbsent(serverName, s -> new ConcurrentLinkedQueue<>());
        if (VelocityLimboHandler.isQueueEnabled() && !queue.contains(playerId)) {
            addPlayerToQueue(player, registeredServer);

            String formatedMsg = MessageFormatter.formatMessage(queuePositionMsg, player);
            player.sendMessage(miniMessage.deserialize(formatedMsg));
        }
    }

    /**
     * Removes a player from the system
     * @param player The player to remove
     */
    public void removePlayer(Player player) {
        UUID playerId = player.getUniqueId();
        removePlayerFromQueue(player);
        removePlayerState(playerId);
        VelocityLimboHandler.getReconnectBlocker().unblock(playerId);
    }

    /**
     * Get the server that the player is trying to reconnect to
     * @param player The player of which
     * @return Returns a server of type RegisteredServer
     */
    public RegisteredServer getPreviousServer(Player player) {
        String serverName = this.playerData.get(player.getUniqueId());

        if (serverName != null) {
            return VelocityLimboHandler.getProxyServer()
                    .getServer(serverName)
                    .orElse(VelocityLimboHandler.getDirectConnectServer());
        }

        return VelocityLimboHandler.getDirectConnectServer();
    }

    public boolean isPlayerRegistered(Player player) {
        return playerData.containsKey(player.getUniqueId());
    }

    public void addPlayerToQueue(Player player, RegisteredServer server) {
        reconnectQueues
                .computeIfAbsent(server.getServerInfo().getName(), s -> new ConcurrentLinkedQueue<>())
                .add(player.getUniqueId());
    }

    public void removePlayerFromQueue(Player player) {
        UUID playerId = player.getUniqueId();
        reconnectQueues.values().forEach(queue -> queue.remove(playerId));
    }

    public Player getNextQueuedPlayer(RegisteredServer server) {
        Queue<UUID> queue = reconnectQueues.get(server.getServerInfo().getName());
        if (queue == null) return null;

        while (!queue.isEmpty()) {
            UUID queuedPlayerId = queue.peek();
            if (queuedPlayerId == null) return null;

            Player queuedPlayer = VelocityLimboHandler.getProxyServer().getPlayer(queuedPlayerId).orElse(null);
            if (queuedPlayer != null && queuedPlayer.isActive()) {
                return queuedPlayer;
            }

            queue.poll();
            removePlayerState(queuedPlayerId);
        }

        return null;
    }

    public boolean hasQueuedPlayers(RegisteredServer server) {
        Queue<UUID> queue = reconnectQueues.get(server.getServerInfo().getName());
        if (queue == null || queue.isEmpty()) return false;

        getNextQueuedPlayer(server); // prune stale queue entries
        return !queue.isEmpty();
    }

    public int getQueuePosition(Player player) {
        RegisteredServer server = getPreviousServer(player);

        Queue<UUID> queue = reconnectQueues.get(server.getServerInfo().getName());
        if (queue == null) return -1;

        int position = 1;
        UUID targetId = player.getUniqueId();
        for (UUID playerId : queue) {
            if (playerId.equals(targetId)) return position;
            position++;
        }

        return -1;

    }

    public void addPlayerWithIssue(Player player, String issue) {
        playerConnectionIssues.put(player.getUniqueId(), issue);
    }

    public boolean hasConnectionIssue(Player player) {
        return playerConnectionIssues.containsKey(player.getUniqueId());
    }

    public String getConnectionIssue(Player player) {
        return playerConnectionIssues.get(player.getUniqueId());
    }

    public void removePlayerIssue(Player player) {
        playerConnectionIssues.remove(player.getUniqueId());
    }

    public void pruneInactivePlayers() {
        for (Queue<UUID> queue : reconnectQueues.values()) {
            queue.removeIf(playerId -> VelocityLimboHandler.getProxyServer()
                    .getPlayer(playerId)
                    .map(player -> !player.isActive())
                    .orElse(true));
        }

        playerData.keySet().removeIf(playerId -> VelocityLimboHandler.getProxyServer()
                .getPlayer(playerId)
                .map(player -> !player.isActive())
                .orElse(true));

        connectingPlayers.keySet().removeIf(playerId -> VelocityLimboHandler.getProxyServer()
                .getPlayer(playerId)
                .map(player -> !player.isActive())
                .orElse(true));

        playerConnectionIssues.keySet().removeIf(playerId -> VelocityLimboHandler.getProxyServer()
                .getPlayer(playerId)
                .map(player -> !player.isActive())
                .orElse(true));
    }

    public int getQueuedServerCount() {
        pruneInactivePlayers();
        return (int) reconnectQueues.values().stream()
                .filter(queue -> !queue.isEmpty())
                .count();
    }

    public int getQueuedPlayerCount() {
        pruneInactivePlayers();
        return reconnectQueues.values().stream()
                .mapToInt(Queue::size)
                .sum();
    }

    public Map<String, Integer> getQueuedServerCounts() {
        pruneInactivePlayers();

        Map<String, Integer> queueCounts = new LinkedHashMap<>();
        reconnectQueues.forEach((serverName, queue) -> {
            if (!queue.isEmpty()) {
                queueCounts.put(serverName, queue.size());
            }
        });

        return queueCounts;
    }

    public List<QueuedPlayer> getQueueForServer(String serverName) {
        Queue<UUID> queue = reconnectQueues.get(serverName);
        if (queue == null || queue.isEmpty()) {
            return List.of();
        }

        List<QueuedPlayer> queuedPlayers = new ArrayList<>();

        for (UUID queuedPlayerId : queue) {
            Player queuedPlayer = VelocityLimboHandler.getProxyServer().getPlayer(queuedPlayerId).orElse(null);
            if (queuedPlayer != null && queuedPlayer.isActive()) {
                queuedPlayers.add(new QueuedPlayer(queuedPlayerId, queuedPlayer.getUsername()));
                continue;
            }

            queue.remove(queuedPlayerId);
            removePlayerState(queuedPlayerId);
        }

        return queuedPlayers;
    }

    /**
     * @param server the server of which you need to find the first whitelisted/permission allow player
     * @return Player object
     */
    public static Player findFirstMaintenanceAllowedPlayer(RegisteredServer server) {
        // Find the queue for the server
        Queue<UUID> queue = VelocityLimboHandler.getPlayerManager().reconnectQueues.get(server.getServerInfo().getName());
        if (queue == null) return null;

        // Loop through all players and check if any match is found
        for (UUID playerId : queue) {
            Player player = VelocityLimboHandler.getProxyServer().getPlayer(playerId).orElse(null);
            if (player == null || !player.isActive()) continue;

            if (player.hasPermission("maintenance.admin")
                    || player.hasPermission("maintenance.bypass")
                    || player.hasPermission("maintenance.singleserver.bypass." + server.getServerInfo().getName())
                    || Utility.playerMaintenanceWhitelisted(player)) {
                return player;
            }
        }

        return null;
    }

    // Check if player is in the hashmap
    public boolean isPlayerConnecting(Player player) {
        return this.connectingPlayers.containsKey(player.getUniqueId());
    }

    /**
     * Set the players status to connecting to a server, so we don't try and connect them twice
     * @param player is the player of which you want to set the status of
     * @param add    is whether you want to add the player, or remove it
     */
    public void setPlayerConnecting(Player player, Boolean add) {
        UUID playerId = player.getUniqueId();
        if (add) {
            this.connectingPlayers.put(playerId, true);
        } else {
            this.connectingPlayers.remove(playerId);
        }
    }

    private void removePlayerState(UUID playerId) {
        playerData.remove(playerId);
        connectingPlayers.remove(playerId);
        playerConnectionIssues.remove(playerId);
    }
}
