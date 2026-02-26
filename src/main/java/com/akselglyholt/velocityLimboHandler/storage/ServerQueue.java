package com.akselglyholt.velocityLimboHandler.storage;

import com.velocitypowered.api.proxy.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Iterator;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class ServerQueue {
    private final LinkedHashSet<UUID> bypass = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> priority = new LinkedHashSet<>();
    private final LinkedHashSet<UUID> normal = new LinkedHashSet<>();
    private final Map<UUID, QueueTier> tierByPlayer = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicLong version = new AtomicLong();

    void enqueue(UUID playerId, QueueTier tier) {
        lock.lock();
        try {
            QueueTier existingTier = tierByPlayer.get(playerId);
            if (existingTier == tier) {
                return;
            }

            if (existingTier != null) {
                tierSet(existingTier).remove(playerId);
            }

            tierSet(tier).add(playerId);
            tierByPlayer.put(playerId, tier);
            version.incrementAndGet();
        } finally {
            lock.unlock();
        }
    }

    boolean remove(UUID playerId) {
        lock.lock();
        try {
            QueueTier tier = tierByPlayer.remove(playerId);
            if (tier == null) {
                return false;
            }

            boolean removed = tierSet(tier).remove(playerId);
            if (removed) {
                version.incrementAndGet();
            }

            return removed;
        } finally {
            lock.unlock();
        }
    }

    Player getNextActivePlayer(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        List<UUID> staleEntries = new ArrayList<>();
        Player nextPlayer = null;
        boolean mutated = false;

        lock.lock();
        try {
            for (LinkedHashSet<UUID> tierSet : orderedTierSets()) {
                Iterator<UUID> iterator = tierSet.iterator();
                while (iterator.hasNext()) {
                    UUID playerId = iterator.next();
                    Player player = activePlayerResolver.apply(playerId);
                    if (player != null) {
                        nextPlayer = player;
                        break;
                    }

                    iterator.remove();
                    tierByPlayer.remove(playerId);
                    staleEntries.add(playerId);
                    mutated = true;
                }

                if (nextPlayer != null) {
                    break;
                }
            }

            if (mutated) {
                version.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }

        staleEntries.forEach(staleEntryRemover);
        return nextPlayer;
    }

    boolean pruneInactivePlayers(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        List<UUID> staleEntries = new ArrayList<>();
        boolean mutated = false;

        lock.lock();
        try {
            for (LinkedHashSet<UUID> tierSet : orderedTierSets()) {
                Iterator<UUID> iterator = tierSet.iterator();
                while (iterator.hasNext()) {
                    UUID playerId = iterator.next();
                    if (activePlayerResolver.apply(playerId) != null) {
                        continue;
                    }

                    iterator.remove();
                    tierByPlayer.remove(playerId);
                    staleEntries.add(playerId);
                    mutated = true;
                }
            }

            if (mutated) {
                version.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }

        staleEntries.forEach(staleEntryRemover);
        return mutated;
    }

    Map<UUID, Integer> buildPositionMap(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        List<UUID> staleEntries = new ArrayList<>();
        Map<UUID, Integer> positions = new HashMap<>();
        int position = 1;
        boolean mutated = false;

        lock.lock();
        try {
            for (LinkedHashSet<UUID> tierSet : orderedTierSets()) {
                Iterator<UUID> iterator = tierSet.iterator();
                while (iterator.hasNext()) {
                    UUID playerId = iterator.next();
                    Player player = activePlayerResolver.apply(playerId);
                    if (player != null) {
                        positions.putIfAbsent(playerId, position++);
                        continue;
                    }

                    iterator.remove();
                    tierByPlayer.remove(playerId);
                    staleEntries.add(playerId);
                    mutated = true;
                }
            }

            if (mutated) {
                version.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }

        staleEntries.forEach(staleEntryRemover);
        return positions;
    }

    List<PlayerManager.QueuedPlayer> getActiveQueuedPlayers(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        List<UUID> staleEntries = new ArrayList<>();
        List<PlayerManager.QueuedPlayer> activePlayers = new ArrayList<>();
        boolean mutated = false;

        lock.lock();
        try {
            for (LinkedHashSet<UUID> tierSet : orderedTierSets()) {
                Iterator<UUID> iterator = tierSet.iterator();
                while (iterator.hasNext()) {
                    UUID playerId = iterator.next();
                    Player player = activePlayerResolver.apply(playerId);
                    if (player != null) {
                        activePlayers.add(new PlayerManager.QueuedPlayer(playerId, player.getUsername()));
                        continue;
                    }

                    iterator.remove();
                    tierByPlayer.remove(playerId);
                    staleEntries.add(playerId);
                    mutated = true;
                }
            }

            if (mutated) {
                version.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }

        staleEntries.forEach(staleEntryRemover);
        return activePlayers;
    }

    Player findFirstActiveMatching(Function<UUID, Player> activePlayerResolver,
                                   Consumer<UUID> staleEntryRemover,
                                   Predicate<Player> matcher) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");
        Objects.requireNonNull(matcher, "matcher");

        List<UUID> staleEntries = new ArrayList<>();
        Player matchedPlayer = null;
        boolean mutated = false;

        lock.lock();
        try {
            for (LinkedHashSet<UUID> tierSet : orderedTierSets()) {
                Iterator<UUID> iterator = tierSet.iterator();
                while (iterator.hasNext()) {
                    UUID playerId = iterator.next();
                    Player player = activePlayerResolver.apply(playerId);
                    if (player == null) {
                        iterator.remove();
                        tierByPlayer.remove(playerId);
                        staleEntries.add(playerId);
                        mutated = true;
                        continue;
                    }

                    if (matcher.test(player)) {
                        matchedPlayer = player;
                        break;
                    }
                }

                if (matchedPlayer != null) {
                    break;
                }
            }

            if (mutated) {
                version.incrementAndGet();
            }
        } finally {
            lock.unlock();
        }

        staleEntries.forEach(staleEntryRemover);
        return matchedPlayer;
    }

    List<Queue<UUID>> orderedQueues() {
        lock.lock();
        try {
            return List.of(new ArrayDeque<>(bypass), new ArrayDeque<>(priority), new ArrayDeque<>(normal));
        } finally {
            lock.unlock();
        }
    }

    boolean isEmpty() {
        lock.lock();
        try {
            return tierByPlayer.isEmpty();
        } finally {
            lock.unlock();
        }
    }

    int size() {
        lock.lock();
        try {
            return tierByPlayer.size();
        } finally {
            lock.unlock();
        }
    }

    long version() {
        return version.get();
    }

    private LinkedHashSet<UUID> tierSet(QueueTier tier) {
        return switch (tier) {
            case BYPASS -> bypass;
            case PRIORITY -> priority;
            case NORMAL -> normal;
        };
    }

    private List<LinkedHashSet<UUID>> orderedTierSets() {
        return List.of(bypass, priority, normal);
    }

}
