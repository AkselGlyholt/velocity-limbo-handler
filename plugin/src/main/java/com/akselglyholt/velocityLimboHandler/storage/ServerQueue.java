package com.akselglyholt.velocityLimboHandler.storage;

import com.velocitypowered.api.proxy.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

final class ServerQueue {
    private static final long VERSION_MISMATCH = -1L;

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
                // A repeated enqueue can represent a new player lifecycle for the same UUID.
                version.incrementAndGet();
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

        while (true) {
            HeadSnapshot head = snapshotHead();
            if (head == null) {
                return null;
            }

            Player player = activePlayerResolver.apply(head.playerId());
            if (player != null) {
                if (isCurrentHead(head)) {
                    return player;
                }
                continue;
            }

            if (removeCurrentHead(head)) {
                staleEntryRemover.accept(head.playerId());
            }
        }
    }

    boolean pruneInactivePlayers(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        while (true) {
            QueueSnapshot snapshot = snapshotQueue();
            List<UUID> staleEntries = new ArrayList<>();
            for (QueuedEntry entry : snapshot.entries()) {
                UUID playerId = entry.playerId();
                if (activePlayerResolver.apply(playerId) == null) {
                    staleEntries.add(playerId);
                }
            }

            long snapshotVersion = removeStaleIfUnchanged(snapshot.version(), staleEntries);
            if (snapshotVersion == VERSION_MISMATCH) {
                continue;
            }

            staleEntries.forEach(staleEntryRemover);
            return !staleEntries.isEmpty();
        }
    }

    PositionSnapshot buildPositionSnapshot(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        while (true) {
            QueueSnapshot snapshot = snapshotQueue();
            List<UUID> staleEntries = new ArrayList<>();
            Map<UUID, Integer> positions = new HashMap<>();
            int position = 1;

            for (QueuedEntry entry : snapshot.entries()) {
                UUID playerId = entry.playerId();
                if (activePlayerResolver.apply(playerId) != null) {
                    positions.put(playerId, position++);
                } else {
                    staleEntries.add(playerId);
                }
            }

            long snapshotVersion = removeStaleIfUnchanged(snapshot.version(), staleEntries);
            if (snapshotVersion == VERSION_MISMATCH) {
                continue;
            }

            staleEntries.forEach(staleEntryRemover);
            return new PositionSnapshot(snapshotVersion, positions);
        }
    }

    List<PlayerManager.QueuedPlayer> getActiveQueuedPlayers(Function<UUID, Player> activePlayerResolver, Consumer<UUID> staleEntryRemover) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");

        while (true) {
            QueueSnapshot snapshot = snapshotQueue();
            List<UUID> staleEntries = new ArrayList<>();
            List<PlayerManager.QueuedPlayer> activePlayers = new ArrayList<>(snapshot.entries().size());

            for (QueuedEntry entry : snapshot.entries()) {
                UUID playerId = entry.playerId();
                Player player = activePlayerResolver.apply(playerId);
                if (player != null) {
                    activePlayers.add(new PlayerManager.QueuedPlayer(playerId, player.getUsername(), entry.tier()));
                } else {
                    staleEntries.add(playerId);
                }
            }

            if (removeStaleIfUnchanged(snapshot.version(), staleEntries) == VERSION_MISMATCH) {
                continue;
            }

            staleEntries.forEach(staleEntryRemover);
            return activePlayers;
        }
    }

    MatchSnapshot findFirstActiveMatching(Function<UUID, Player> activePlayerResolver,
                                          Consumer<UUID> staleEntryRemover,
                                          Predicate<Player> matcher) {
        Objects.requireNonNull(activePlayerResolver, "activePlayerResolver");
        Objects.requireNonNull(staleEntryRemover, "staleEntryRemover");
        Objects.requireNonNull(matcher, "matcher");

        while (true) {
            QueueSnapshot snapshot = snapshotQueue();
            List<UUID> staleEntries = new ArrayList<>();
            Player matchedPlayer = null;

            for (QueuedEntry entry : snapshot.entries()) {
                UUID playerId = entry.playerId();
                Player player = activePlayerResolver.apply(playerId);
                if (player == null) {
                    staleEntries.add(playerId);
                } else if (matcher.test(player)) {
                    matchedPlayer = player;
                    break;
                }
            }

            long snapshotVersion = removeStaleIfUnchanged(snapshot.version(), staleEntries);
            if (snapshotVersion == VERSION_MISMATCH) {
                continue;
            }

            staleEntries.forEach(staleEntryRemover);
            return new MatchSnapshot(snapshotVersion, matchedPlayer);
        }
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

    Optional<QueueTier> tier(UUID playerId) {
        lock.lock();
        try {
            return Optional.ofNullable(tierByPlayer.get(playerId));
        } finally {
            lock.unlock();
        }
    }

    long version() {
        return version.get();
    }

    private HeadSnapshot snapshotHead() {
        lock.lock();
        try {
            for (QueueTier tier : QueueTier.values()) {
                Iterator<UUID> iterator = tierSet(tier).iterator();
                if (iterator.hasNext()) {
                    return new HeadSnapshot(version.get(), iterator.next(), tier);
                }
            }
            return null;
        } finally {
            lock.unlock();
        }
    }

    private boolean isCurrentHead(HeadSnapshot expected) {
        lock.lock();
        try {
            if (version.get() != expected.version()) {
                return false;
            }
            Iterator<UUID> iterator = tierSet(expected.tier()).iterator();
            return iterator.hasNext() && iterator.next().equals(expected.playerId());
        } finally {
            lock.unlock();
        }
    }

    private boolean removeCurrentHead(HeadSnapshot expected) {
        lock.lock();
        try {
            if (version.get() != expected.version()) {
                return false;
            }
            Iterator<UUID> iterator = tierSet(expected.tier()).iterator();
            if (!iterator.hasNext() || !iterator.next().equals(expected.playerId())) {
                return false;
            }
            iterator.remove();
            tierByPlayer.remove(expected.playerId());
            version.incrementAndGet();
            return true;
        } finally {
            lock.unlock();
        }
    }

    private QueueSnapshot snapshotQueue() {
        lock.lock();
        try {
            List<QueuedEntry> entries = new ArrayList<>(tierByPlayer.size());
            bypass.forEach(playerId -> entries.add(new QueuedEntry(playerId, QueueTier.BYPASS)));
            priority.forEach(playerId -> entries.add(new QueuedEntry(playerId, QueueTier.PRIORITY)));
            normal.forEach(playerId -> entries.add(new QueuedEntry(playerId, QueueTier.NORMAL)));
            return new QueueSnapshot(version.get(), entries);
        } finally {
            lock.unlock();
        }
    }

    private long removeStaleIfUnchanged(long expectedVersion, List<UUID> staleEntries) {
        lock.lock();
        try {
            if (version.get() != expectedVersion) {
                return VERSION_MISMATCH;
            }

            boolean mutated = false;
            for (UUID playerId : staleEntries) {
                QueueTier tier = tierByPlayer.remove(playerId);
                if (tier != null) {
                    tierSet(tier).remove(playerId);
                    mutated = true;
                }
            }
            if (mutated) {
                version.incrementAndGet();
            }
            return version.get();
        } finally {
            lock.unlock();
        }
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

    record PositionSnapshot(long version, Map<UUID, Integer> positions) {
    }

    record MatchSnapshot(long version, Player player) {
    }

    private record HeadSnapshot(long version, UUID playerId, QueueTier tier) {
    }

    private record QueuedEntry(UUID playerId, QueueTier tier) {
    }

    private record QueueSnapshot(long version, List<QueuedEntry> entries) {
    }

}
