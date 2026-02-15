package com.akselglyholt.velocityLimboHandler.storage;

import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

final class ServerQueue {
    private final Queue<UUID> bypass = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> priority = new ConcurrentLinkedQueue<>();
    private final Queue<UUID> normal = new ConcurrentLinkedQueue<>();

    void enqueue(UUID playerId, QueueTier tier) {
        remove(playerId);

        switch (tier) {
            case BYPASS -> bypass.add(playerId);
            case PRIORITY -> priority.add(playerId);
            case NORMAL -> normal.add(playerId);
        }
    }

    void remove(UUID playerId) {
        bypass.remove(playerId);
        priority.remove(playerId);
        normal.remove(playerId);
    }

    List<Queue<UUID>> orderedQueues() {
        return List.of(bypass, priority, normal);
    }

    boolean isEmpty() {
        return bypass.isEmpty() && priority.isEmpty() && normal.isEmpty();
    }

    int size() {
        return bypass.size() + priority.size() + normal.size();
    }
}
