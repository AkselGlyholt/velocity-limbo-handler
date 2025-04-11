package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.event.PostOrder;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

public class ConnectionListener {
    @Subscribe
    public void onPlayerPostConnect(@NotNull ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        // Todo add config check to see if player should bypass checks

        if (player.getCurrentServer().isEmpty()) {
            VelocityLimboHandler.getLogger().severe(String.format("Current server wasn't present for %s.", player.getUsername()));
            return;
        }

        ServerConnection currentServerConnection = player.getCurrentServer().get();

        RegisteredServer previousServer = event.getPreviousServer();
        if (previousServer == null) {
            // Check if player was on another server before
            if (VelocityLimboHandler.getPlayerManager().isPlayerRegistered(player)) {
                previousServer = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
            } else {
                // Didn't have previous server, so connect to direct connection server. e.g. Lobby
                previousServer = VelocityLimboHandler.getDirectConnectServer();
            }
        }

        // If a player gets redirected from Limbo to another server, remove them from the Map
        if (Utility.doServerNamesMatch(previousServer, VelocityLimboHandler.getLimboServer())) {
            VelocityLimboHandler.getPlayerManager().removePlayer(player);
            return;
        }

        // IF a player gets redirected to Limbo from another server, add them to the Map
        if (Utility.doServerNamesMatch(currentServerConnection.getServer(), VelocityLimboHandler.getLimboServer())) {
            VelocityLimboHandler.getPlayerManager().addPlayer(player, previousServer);
            Utility.sendWelcomeMessage(player, null);
        }
    }


    // Remove anyone who disconnects from the proxy from the playerData Map.
    @Subscribe
    public void onDisconnect(@NotNull DisconnectEvent event) {
        VelocityLimboHandler.getPlayerManager().removePlayer(event.getPlayer());
        VelocityLimboHandler.getPlayerManager().removePlayerIssue(event.getPlayer());
    }
}
