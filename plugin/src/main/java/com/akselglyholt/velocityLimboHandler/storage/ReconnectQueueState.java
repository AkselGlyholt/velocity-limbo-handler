package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

final class ReconnectQueueState {
    private static final long POSITION_CACHE_TTL_NANOS = TimeUnit.MINUTES.toNanos(1);
    private static final long MAINTENANCE_CANDIDATE_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);

    private final Map<String, ServerQueue> reconnectQueues = new ConcurrentHashMap<>();
    private final Map<UUID, String> queueByPlayer = new ConcurrentHashMap<>();
    private final Map<String, QueuePositionCacheEntry> queuePositionCache = new ConcurrentHashMap<>();
    private final Map<String, MaintenanceCandidateCacheEntry> maintenanceCandidateCache = new ConcurrentHashMap<>();
    private final Consumer<UUID> staleEntryRemover;
    private final Function<UUID, Player> activePlayerResolver;

    ReconnectQueueState(Consumer<UUID> staleEntryRemover, Function<UUID, Player> activePlayerResolver) {
        this.staleEntryRemover = staleEntryRemover;
        this.activePlayerResolver = activePlayerResolver;
    }

    void enqueue(Player player, RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        UUID playerId = player.getUniqueId();

        queueByPlayer.compute(playerId, (ignored, previousServerName) -> {
            if (previousServerName != null && !previousServerName.equals(serverName)) {
                removeFromServerQueue(previousServerName, playerId);
            }

            reconnectQueues.compute(serverName, (ignoredServerName, serverQueue) -> {
                ServerQueue targetQueue = serverQueue != null ? serverQueue : new ServerQueue();
                targetQueue.enqueue(playerId, getTier(player, serverName));
                return targetQueue;
            });
            invalidatePositionCache(serverName);
            return serverName;
        });
    }

    void removePlayer(UUID playerId) {
        queueByPlayer.computeIfPresent(playerId, (ignored, serverName) -> {
            removeFromServerQueue(serverName, playerId);
            return null;
        });
    }

    Player getNextQueuedPlayer(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null) {
            return null;
        }

        long beforeVersion = serverQueue.version();
        Player nextPlayer = serverQueue.getNextActivePlayer(
                this::getActivePlayer,
                playerId -> removeStaleOwnership(serverName, playerId)
        );
        if (serverQueue.version() != beforeVersion) {
            invalidateQueueCaches(serverName);
        }
        removeServerQueueIfEmpty(serverName, serverQueue);

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

        int position = getOrBuildQueuePositions(serverName, serverQueue).getOrDefault(targetId, -1);
        removeServerQueueIfEmpty(serverName, serverQueue);
        return position;
    }

    void pruneInactivePlayers() {
        reconnectQueues.forEach((serverName, serverQueue) -> {
            if (serverQueue.pruneInactivePlayers(
                    this::getActivePlayer,
                    playerId -> removeStaleOwnership(serverName, playerId)
            )) {
                invalidateQueueCaches(serverName);
            }
            removeServerQueueIfEmpty(serverName, serverQueue);
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

    List<String> getQueuedServerNames() {
        List<String> serverNames = new ArrayList<>();
        reconnectQueues.forEach((serverName, serverQueue) -> {
            if (!serverQueue.isEmpty()) {
                serverNames.add(serverName);
            }
        });
        return serverNames;
    }

    int getQueueSize(String serverName) {
        ServerQueue serverQueue = getServerQueue(serverName);
        return serverQueue == null ? 0 : serverQueue.size();
    }

    List<PlayerManager.QueuedPlayer> getQueueForServer(String serverName) {
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null || serverQueue.isEmpty()) {
            return List.of();
        }

        long beforeVersion = serverQueue.version();
        List<PlayerManager.QueuedPlayer> queuedPlayers = serverQueue.getActiveQueuedPlayers(
                this::getActivePlayer,
                playerId -> removeStaleOwnership(serverName, playerId)
        );
        if (serverQueue.version() != beforeVersion) {
            invalidateQueueCaches(serverName);
        }
        removeServerQueueIfEmpty(serverName, serverQueue);

        return queuedPlayers;
    }

    Player findFirstMaintenanceAllowedPlayer(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ServerQueue serverQueue = getServerQueue(serverName);
        if (serverQueue == null) {
            return null;
        }

        long now = System.nanoTime();
        long version = serverQueue.version();
        MaintenanceCandidateCacheEntry cached = maintenanceCandidateCache.get(serverName);
        if (cached != null && cached.version() == version && now < cached.expiresAtNanos()) {
            if (cached.playerId() == null) {
                return null;
            }
            Player cachedPlayer = getActivePlayer(cached.playerId());
            if (cachedPlayer != null && isMaintenanceAllowed(cachedPlayer, serverName)) {
                return cachedPlayer;
            }
            maintenanceCandidateCache.remove(serverName, cached);
        }

        long beforeVersion = version;
        ServerQueue.MatchSnapshot match = serverQueue.findFirstActiveMatching(
                this::getActivePlayer,
                playerId -> removeStaleOwnership(serverName, playerId),
                player -> isMaintenanceAllowed(player, serverName)
        );
        if (serverQueue.version() != beforeVersion) {
            invalidateQueueCaches(serverName);
        }

        Player maintenanceAllowed = match.player();
        maintenanceCandidateCache.put(serverName, new MaintenanceCandidateCacheEntry(
                match.version(),
                now + MAINTENANCE_CANDIDATE_TTL_NANOS,
                maintenanceAllowed == null ? null : maintenanceAllowed.getUniqueId()
        ));
        removeServerQueueIfEmpty(serverName, serverQueue);

        return maintenanceAllowed;
    }

    private boolean isMaintenanceAllowed(Player player, String serverName) {
        return player.hasPermission("maintenance.admin")
                || player.hasPermission("maintenance.bypass")
                || player.hasPermission("maintenance.singleserver.bypass." + serverName)
                || Utility.playerMaintenanceWhitelisted(player);
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

    private Player getActivePlayer(UUID playerId) {
        return activePlayerResolver.apply(playerId);
    }

    private Map<UUID, Integer> getOrBuildQueuePositions(String serverName, ServerQueue serverQueue) {
        while (true) {
            QueuePositionCacheEntry cached = queuePositionCache.get(serverName);
            long now = System.nanoTime();
            long version = serverQueue.version();

            if (cached != null && cached.version() == version && now < cached.expiresAtNanos()) {
                return cached.positions();
            }

            ServerQueue.PositionSnapshot snapshot = serverQueue.buildPositionSnapshot(
                    this::getActivePlayer,
                    playerId -> removeStaleOwnership(serverName, playerId)
            );
            QueuePositionCacheEntry fresh = new QueuePositionCacheEntry(
                    snapshot.version(),
                    now + POSITION_CACHE_TTL_NANOS,
                    snapshot.positions()
            );
            queuePositionCache.put(serverName, fresh);

            if (serverQueue.version() == snapshot.version()) {
                return snapshot.positions();
            }

            queuePositionCache.remove(serverName, fresh);
        }
    }

    private void invalidatePositionCache(String serverName) {
        queuePositionCache.remove(serverName);
    }

    private void invalidateQueueCaches(String serverName) {
        invalidatePositionCache(serverName);
        maintenanceCandidateCache.remove(serverName);
    }

    private void removeFromServerQueue(String serverName, UUID playerId) {
        reconnectQueues.computeIfPresent(serverName, (ignored, serverQueue) -> {
            if (serverQueue.remove(playerId)) {
                invalidateQueueCaches(serverName);
            }
            return serverQueue.isEmpty() ? null : serverQueue;
        });
    }

    private void removeStaleOwnership(String serverName, UUID playerId) {
        AtomicBoolean ownershipRemoved = new AtomicBoolean();
        queueByPlayer.computeIfPresent(playerId, (ignored, ownedServerName) -> {
            if (!ownedServerName.equals(serverName) || getActivePlayer(playerId) != null) {
                return ownedServerName;
            }
            ownershipRemoved.set(true);
            return null;
        });

        if (ownershipRemoved.get()) {
            staleEntryRemover.accept(playerId);
        }
    }

    private void removeServerQueueIfEmpty(String serverName, ServerQueue expectedQueue) {
        ServerQueue remainingQueue = reconnectQueues.computeIfPresent(serverName, (ignored, currentQueue) ->
                currentQueue == expectedQueue && currentQueue.isEmpty() ? null : currentQueue
        );
        if (remainingQueue == null) {
            invalidateQueueCaches(serverName);
        }
    }

    private record QueuePositionCacheEntry(long version, long expiresAtNanos, Map<UUID, Integer> positions) {
    }

    private record MaintenanceCandidateCacheEntry(long version, long expiresAtNanos, UUID playerId) {
    }
}
