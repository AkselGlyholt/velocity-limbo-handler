package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;

final class ReconnectQueueState {
    private static final long POSITION_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Map<String, ServerQueue> reconnectQueues = new ConcurrentHashMap<>();
    private final Map<String, QueuePositionCacheEntry> queuePositionCache = new ConcurrentHashMap<>();
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
        invalidatePositionCache(serverName);
    }

    void removePlayer(UUID playerId) {
        reconnectQueues.forEach((serverName, serverQueue) -> {
            if (serverQueue.remove(playerId)) {
                invalidatePositionCache(serverName);
            }
        });
    }

    Player getNextQueuedPlayer(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null) {
            return null;
        }

        long beforeVersion = serverQueue.version();
        Player nextPlayer = serverQueue.getNextActivePlayer(this::getActivePlayer, staleEntryRemover);
        if (serverQueue.version() != beforeVersion) {
            invalidatePositionCache(serverName);
        }

        return nextPlayer;
    }

    boolean hasQueuedPlayers(RegisteredServer server) {
        return getNextQueuedPlayer(server) != null;
    }

    int getQueuePosition(UUID targetId, String serverName) {
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null) {
            return -1;
        }

        return getOrBuildQueuePositions(serverName, serverQueue).getOrDefault(targetId, -1);
    }

    void pruneInactivePlayers() {
        reconnectQueues.forEach((serverName, serverQueue) -> {
            if (serverQueue.pruneInactivePlayers(this::getActivePlayer, staleEntryRemover)) {
                invalidatePositionCache(serverName);
            }
        });
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

        long beforeVersion = serverQueue.version();
        List<PlayerManager.QueuedPlayer> queuedPlayers = serverQueue.getActiveQueuedPlayers(this::getActivePlayer, staleEntryRemover);
        if (serverQueue.version() != beforeVersion) {
            invalidatePositionCache(serverName);
        }

        return queuedPlayers;
    }

    Player findFirstMaintenanceAllowedPlayer(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null) {
            return null;
        }

        long beforeVersion = serverQueue.version();
        Player maintenanceAllowed = serverQueue.findFirstActiveMatching(
                this::getActivePlayer,
                staleEntryRemover,
                player -> player.hasPermission("maintenance.admin")
                        || player.hasPermission("maintenance.bypass")
                        || player.hasPermission("maintenance.singleserver.bypass." + serverName)
                        || Utility.playerMaintenanceWhitelisted(player)
        );
        if (serverQueue.version() != beforeVersion) {
            invalidatePositionCache(serverName);
        }

        return maintenanceAllowed;
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

    private Player getActivePlayer(UUID playerId) {
        return activePlayerResolver.apply(playerId);
    }

    private Map<UUID, Integer> getOrBuildQueuePositions(String serverName, ServerQueue serverQueue) {
        QueuePositionCacheEntry cached = queuePositionCache.get(serverName);
        long now = System.nanoTime();
        long version = serverQueue.version();

        if (cached != null && cached.version() == version && now < cached.expiresAtNanos()) {
            return cached.positions();
        }

        Map<UUID, Integer> positions = serverQueue.buildPositionMap(this::getActivePlayer, staleEntryRemover);
        QueuePositionCacheEntry fresh = new QueuePositionCacheEntry(
                serverQueue.version(),
                now + POSITION_CACHE_TTL_NANOS,
                positions
        );
        queuePositionCache.put(serverName, fresh);
        return positions;
    }

    private void invalidatePositionCache(String serverName) {
        queuePositionCache.remove(serverName);
    }

    private record QueuePositionCacheEntry(long version, long expiresAtNanos, Map<UUID, Integer> positions) {
    }
}
