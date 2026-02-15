package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;

public class MessageFormatter {
    public static String formatMessage(String msg, Player player) {
        return formatMessage(msg, player, null);
    }

    public static String formatMessage(String msg, Player player, Integer queuePosition) {
        if (msg.contains("[queue-position]")) {
            int position = queuePosition != null
                    ? queuePosition
                    : VelocityLimboHandler.getPlayerManager().getQueuePosition(player);

            msg = msg.replace("[queue-position]", Integer.toString(position));
        }

        return msg;
    }
}
