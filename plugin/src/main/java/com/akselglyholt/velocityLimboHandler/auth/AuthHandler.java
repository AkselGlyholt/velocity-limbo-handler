package com.akselglyholt.velocityLimboHandler.auth;

import com.velocitypowered.api.proxy.Player;

public interface AuthHandler extends AutoCloseable {
    String name();
    boolean isActive(); // detection succeeded
    void onPlayerJoin(Player player);      // usually block here
    void onShutdown();                     // cleanup if needed
    @Override default void close() { onShutdown(); }
}
