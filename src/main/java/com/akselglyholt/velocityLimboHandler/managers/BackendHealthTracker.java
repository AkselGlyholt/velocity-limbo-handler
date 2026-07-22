package com.akselglyholt.velocityLimboHandler.managers;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

final class BackendHealthTracker {
    private static final long SUCCESS_TTL_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long FULL_TTL_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long FAILURE_BACKOFF_BASE_NANOS = TimeUnit.SECONDS.toNanos(3);
    private static final long FAILURE_BACKOFF_MAX_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long PING_TIMEOUT_SECONDS = 5;

    private final Map<String, ProbeState> states = new ConcurrentHashMap<>();

    CompletableFuture<Availability> probe(RegisteredServer server) {
        String serverName = server.getServerInfo().getName();
        ProbeState state = states.computeIfAbsent(serverName, ignored -> new ProbeState());

        synchronized (state) {
            if (state.inFlight != null) {
                return state.inFlight;
            }

            long now = System.nanoTime();
            if (state.lastResult != null && now < state.nextProbeAtNanos) {
                return CompletableFuture.completedFuture(state.lastResult);
            }

            CompletableFuture<Availability> probe;
            try {
                probe = server.ping()
                        .orTimeout(PING_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .handle((ping, throwable) -> toAvailability(ping, throwable));
            } catch (RuntimeException exception) {
                probe = CompletableFuture.completedFuture(Availability.offline());
            }

            state.inFlight = probe;
            probe.whenComplete((availability, ignored) -> finishProbe(state, availability));
            return probe;
        }
    }

    void invalidate(String serverName) {
        states.remove(serverName);
    }

    void clear() {
        states.clear();
    }

    private void finishProbe(ProbeState state, Availability availability) {
        synchronized (state) {
            Availability resolved = availability != null ? availability : Availability.offline();
            state.lastResult = resolved;
            state.inFlight = null;

            long delay;
            if (!resolved.reachable()) {
                state.consecutiveFailures = Math.min(state.consecutiveFailures + 1, 31);
                int shift = Math.min(state.consecutiveFailures - 1, 4);
                delay = Math.min(FAILURE_BACKOFF_BASE_NANOS << shift, FAILURE_BACKOFF_MAX_NANOS);
                if (delay < FAILURE_BACKOFF_MAX_NANOS) {
                    delay = Math.min(
                            FAILURE_BACKOFF_MAX_NANOS,
                            delay + ThreadLocalRandom.current().nextLong(Math.max(1L, delay / 10L))
                    );
                }
            } else {
                state.consecutiveFailures = 0;
                delay = resolved.full() ? FULL_TTL_NANOS : SUCCESS_TTL_NANOS;
            }
            state.nextProbeAtNanos = System.nanoTime() + delay;
        }
    }

    private Availability toAvailability(ServerPing ping, Throwable throwable) {
        if (throwable != null || ping == null) {
            return Availability.offline();
        }

        return ping.getPlayers()
                .map(players -> {
                    int freeSlots = Math.max(0, players.getMax() - players.getOnline());
                    return new Availability(true, freeSlots == 0, freeSlots);
                })
                .orElseGet(Availability::onlineWithoutPlayerCount);
    }

    record Availability(boolean reachable, boolean full, int reportedFreeSlots) {
        static Availability offline() {
            return new Availability(false, false, 0);
        }

        static Availability onlineWithoutPlayerCount() {
            return new Availability(true, false, Integer.MAX_VALUE);
        }
    }

    private static final class ProbeState {
        private Availability lastResult;
        private CompletableFuture<Availability> inFlight;
        private long nextProbeAtNanos;
        private int consecutiveFailures;
    }
}
