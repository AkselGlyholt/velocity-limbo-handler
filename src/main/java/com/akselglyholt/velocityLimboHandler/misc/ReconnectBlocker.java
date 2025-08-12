package com.akselglyholt.velocityLimboHandler.misc;

import java.time.Duration;
import java.util.UUID;

public interface ReconnectBlocker {
    void block(UUID uuid, String reason);
    void unblock(UUID uuid);
    boolean isBlocked(UUID uuid);
}
