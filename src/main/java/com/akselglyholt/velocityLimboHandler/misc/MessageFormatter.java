package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;

public class MessageFormatter {
    public static String formatMessage(String msg, Player player) {
        RegisteredServer server = null;

        if (msg.contains("[player]")) {
            msg = msg.replace("[player]", player.getUsername());
        }

        if (msg.contains("[queue-position]")) {
            int position = VelocityLimboHandler.getPlayerManager().getQueuePosition(player);
            msg = msg.replace("[queue-position]", Integer.toString(position));
        }

        if (msg.contains("[queue-size]")) {
            if (server == null) {
                server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
            }

            int size = VelocityLimboHandler.getPlayerManager().getQueueForServer(server.getServerInfo().getName()).size();
            msg = msg.replace("[queue-size]", Integer.toString(size));
        }

        if (msg.contains("[total-limbo]")) {
            int size = VelocityLimboHandler.getLimboServer().getPlayersConnected().size();
            msg = msg.replace("[total-limbo]", Integer.toString(size));
        }

        if (msg.contains("[queued-server]")) {
            if (server == null) {
                server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
            }

            String serverName = server.getServerInfo().getName();
            msg = msg.replace("[queued-server]", serverName);
        }

        return msg;
    }
}
