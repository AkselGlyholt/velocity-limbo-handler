package com.akselglyholt.velocityLimboHandler.commands;

import com.velocitypowered.api.proxy.Player;

@FunctionalInterface
public interface CommandBlockRule {
    boolean shouldBlock(Player player);

    static CommandBlockRule onServer(String serverName) {
        return player -> player.getCurrentServer()
                .map(s -> s.getServerInfo().getName().equalsIgnoreCase(serverName))
                .orElse(false);
    }

    // Add more reusable helpers later if needed
}
