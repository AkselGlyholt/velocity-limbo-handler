package com.akselglyholt.velocityLimboHandler.auth.handlers;

import com.akselglyholt.velocityLimboHandler.auth.AuthHandler;
import com.velocitypowered.api.proxy.Player;

public final class NoopHandler implements AuthHandler {

    @Override
    public String name() {
        return "Noop";
    }

    @Override
    public boolean isActive() {
        return true; // Always "active" so AuthManager has something
    }

    @Override
    public void onPlayerJoin(Player player) {
        // Do nothing
    }

    @Override
    public void onShutdown() {
        // Do nothing
    }
}
