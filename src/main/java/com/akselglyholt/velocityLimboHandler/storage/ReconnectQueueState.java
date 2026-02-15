package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

final class ReconnectQueueState {
    private final Map<String, ServerQueue> reconnectQueues = new ConcurrentHashMap<>();
    private final Consumer<UUID> staleEntryRemover;
    private final Function<UUID, Player> activePlayerResolver;

    ReconnectQueueState(Consumer<UUID> staleEntryRemover, Function<UUID, Player> activePlayerResolver) {
        this.staleEntryRemover = staleEntryRemover;
        this.activePlayerResolver = activePlayerResolver;
    }

    void enqueue(Player player, RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        UUID playerId = player.getUniqueId();

        ServerQueue serverQueue = getOrCreateServerQueue(serverName);
        serverQueue.enqueue(playerId, getTier(player, serverName));
    }

    void removePlayer(UUID playerId) {
        reconnectQueues.values().forEach(serverQueue -> serverQueue.remove(playerId));
    }

    Player getNextQueuedPlayer(RegisteredServer server) {
        ServerQueue serverQueue = getServerQueue(server.getServerInfo().getName());
        if (serverQueue == null) {
            return null;
        }

        for (Queue<UUID> queue : serverQueue.orderedQueues()) {
            Player nextPlayer = getNextActiveFromQueue(queue);
            if (nextPlayer != null) {
                return nextPlayer;
            }
        }

        return null;
    }

    boolean hasQueuedPlayers(RegisteredServer server) {
        return getNextQueuedPlayer(server) != null;
    }

    int getQueuePosition(UUID targetId, String serverName) {
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null) {
            return -1;
        }

        int position = 1;
        for (Queue<UUID> queue : serverQueue.orderedQueues()) {
            QueueScanResult result = scanQueueForPosition(queue, targetId, position);
            if (result.foundPosition() != -1) {
                return result.foundPosition();
            }

            position = result.nextPosition();
        }

        return -1;
    }

    void pruneInactivePlayers() {
        for (ServerQueue serverQueue : reconnectQueues.values()) {
            for (Queue<UUID> queue : serverQueue.orderedQueues()) {
                pruneInactiveFromQueue(queue);
            }
        }
    }

    int getQueuedServerCount() {
        return (int) reconnectQueues.values().stream()
                .filter(serverQueue -> !serverQueue.isEmpty())
                .count();
    }

    int getQueuedPlayerCount() {
        return reconnectQueues.values().stream()
                .mapToInt(ServerQueue::size)
                .sum();
    }

    Map<String, Integer> getQueuedServerCounts() {
        Map<String, Integer> queueCounts = new LinkedHashMap<>();
        reconnectQueues.forEach((serverName, serverQueue) -> {
            int total = serverQueue.size();
            if (total > 0) {
                queueCounts.put(serverName, total);
            }
        });

        return queueCounts;
    }

    List<PlayerManager.QueuedPlayer> getQueueForServer(String serverName) {
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null || serverQueue.isEmpty()) {
            return List.of();
        }

        List<PlayerManager.QueuedPlayer> queuedPlayers = new ArrayList<>();
        for (Queue<UUID> queue : serverQueue.orderedQueues()) {
            appendActivePlayers(queue, queuedPlayers);
        }

        return queuedPlayers;
    }

    Player findFirstMaintenanceAllowedPlayer(RegisteredServer server) {
        ServerQueue serverQueue = getServerQueue(server.getServerInfo().getName());
        if (serverQueue == null) {
            return null;
        }

        for (Queue<UUID> queue : serverQueue.orderedQueues()) {
            Player maintenanceAllowed = findFirstMaintenanceAllowedFromQueue(queue, server);
            if (maintenanceAllowed != null) {
                return maintenanceAllowed;
            }
        }

        return null;
    }

    private Player getNextActiveFromQueue(Queue<UUID> queue) {
        while (!queue.isEmpty()) {
            UUID queuedPlayerId = queue.peek();
            if (queuedPlayerId == null) {
                return null;
            }

            Player queuedPlayer = getActivePlayer(queuedPlayerId);
            if (queuedPlayer != null) {
                return queuedPlayer;
            }

            queue.poll();
            staleEntryRemover.accept(queuedPlayerId);
        }

        return null;
    }

    private QueueTier getTier(Player player, String serverName) {
        String normalizedServerName = serverName.toLowerCase(Locale.ROOT);

        if (player.hasPermission("vlh.queue.bypass")
                || player.hasPermission("vlh.queue.bypass." + normalizedServerName)) {
            return QueueTier.BYPASS;
        }

        if (player.hasPermission("vlh.queue.priority")
                || player.hasPermission("vlh.queue.priority." + normalizedServerName)) {
            return QueueTier.PRIORITY;
        }

        return QueueTier.NORMAL;
    }

    private ServerQueue getServerQueue(String serverName) {
        return reconnectQueues.get(serverName);
    }

    private ServerQueue getOrCreateServerQueue(String serverName) {
        return reconnectQueues.computeIfAbsent(serverName, key -> new ServerQueue());
    }

    private void pruneInactiveFromQueue(Queue<UUID> queue) {
        queue.removeIf(playerId -> getActivePlayer(playerId) == null);
    }

    private QueueScanResult scanQueueForPosition(Queue<UUID> queue, UUID targetId, int startPosition) {
        int position = startPosition;
        List<UUID> staleEntries = new ArrayList<>();

        for (UUID queuedPlayerId : queue) {
            Player queuedPlayer = getActivePlayer(queuedPlayerId);
            if (queuedPlayer == null) {
                staleEntries.add(queuedPlayerId);
                continue;
            }

            if (queuedPlayerId.equals(targetId)) {
                removeStaleEntries(queue, staleEntries);
                return new QueueScanResult(position, position);
            }

            position++;
        }

        removeStaleEntries(queue, staleEntries);
        return new QueueScanResult(position, -1);
    }

    private void appendActivePlayers(Queue<UUID> queue, List<PlayerManager.QueuedPlayer> queuedPlayers) {
        List<UUID> staleEntries = new ArrayList<>();

        for (UUID queuedPlayerId : queue) {
            Player queuedPlayer = getActivePlayer(queuedPlayerId);
            if (queuedPlayer != null) {
                queuedPlayers.add(new PlayerManager.QueuedPlayer(queuedPlayerId, queuedPlayer.getUsername()));
                continue;
            }

            staleEntries.add(queuedPlayerId);
        }

        removeStaleEntries(queue, staleEntries);
    }

    private Player findFirstMaintenanceAllowedFromQueue(Queue<UUID> queue, RegisteredServer server) {
        List<UUID> staleEntries = new ArrayList<>();

        for (UUID playerId : queue) {
            Player player = getActivePlayer(playerId);
            if (player == null) {
                staleEntries.add(playerId);
                continue;
            }

            if (player.hasPermission("maintenance.admin")
                    || player.hasPermission("maintenance.bypass")
                    || player.hasPermission("maintenance.singleserver.bypass." + server.getServerInfo().getName())
                    || Utility.playerMaintenanceWhitelisted(player)) {
                removeStaleEntries(queue, staleEntries);
                return player;
            }
        }

        removeStaleEntries(queue, staleEntries);
        return null;
    }

    private Player getActivePlayer(UUID playerId) {
        return activePlayerResolver.apply(playerId);
    }

    private void removeStaleEntries(Queue<UUID> queue, List<UUID> staleEntries) {
        staleEntries.forEach(staleId -> {
            queue.remove(staleId);
            staleEntryRemover.accept(staleId);
        });
    }

    private record QueueScanResult(int nextPosition, int foundPosition) {
    }
}
