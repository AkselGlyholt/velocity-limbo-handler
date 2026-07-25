package com.akselglyholt.velocityLimboHandler.storage;

import org.junit.jupiter.api.Test;

import java.util.Queue;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerQueueTest {

    @Test
    void enqueue_placesPlayersInTierOrder() {
        ServerQueue queue = new ServerQueue();
        UUID bypass = UUID.randomUUID();
        UUID priority = UUID.randomUUID();
        UUID normal = UUID.randomUUID();

        queue.enqueue(normal, QueueTier.NORMAL);
        queue.enqueue(priority, QueueTier.PRIORITY);
        queue.enqueue(bypass, QueueTier.BYPASS);

        Queue<UUID> bypassQueue = queue.orderedQueues().get(0);
        Queue<UUID> priorityQueue = queue.orderedQueues().get(1);
        Queue<UUID> normalQueue = queue.orderedQueues().get(2);

        assertEquals(bypass, bypassQueue.peek());
        assertEquals(priority, priorityQueue.peek());
        assertEquals(normal, normalQueue.peek());
        assertEquals(3, queue.size());
    }

    @Test
    void enqueue_samePlayerInNewTier_removesOldTierEntry() {
        ServerQueue queue = new ServerQueue();
        UUID playerId = UUID.randomUUID();

        queue.enqueue(playerId, QueueTier.NORMAL);
        queue.enqueue(playerId, QueueTier.BYPASS);

        assertFalse(queue.orderedQueues().get(2).contains(playerId));
        assertTrue(queue.orderedQueues().get(0).contains(playerId));
        assertEquals(1, queue.size());
    }

    @Test
    void remove_clearsPlayerFromAllTiers() {
        ServerQueue queue = new ServerQueue();
        UUID playerId = UUID.randomUUID();

        queue.enqueue(playerId, QueueTier.PRIORITY);
        assertFalse(queue.isEmpty());

        queue.remove(playerId);

        assertTrue(queue.isEmpty());
        assertEquals(0, queue.size());
    }
}
