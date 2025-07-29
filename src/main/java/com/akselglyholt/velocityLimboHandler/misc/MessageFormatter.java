package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;

public class MessageFormatter {
    public static String formatMessage(String msg, Player player) {
        if (msg.contains("[queue-position]")) {
            int position = VelocityLimboHandler.getPlayerManager().getQueuePosition(player);

            msg = msg.replace("[queue-position]", Integer.toString(position));
        }

        return msg;
    }
}
