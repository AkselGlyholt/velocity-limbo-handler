package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class MessageFormatter {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final Map<String, CompiledMessage> COMPILED_MESSAGES = new ConcurrentHashMap<>();

    public static String formatMessage(String msg, Player player) {
        return formatMessage(msg, player, null, null);
    }

    public static String formatMessage(String msg, Player player, Integer knownPosition, RegisteredServer knownServer) {
        RegisteredServer server = null;

        if (msg.contains("[player]")) {
            msg = msg.replace("[player]", player.getUsername());
        }

        if (msg.contains("[queue-position]")) {
            int position = knownPosition != null
                    ? knownPosition
                    : VelocityLimboHandler.getPlayerManager().getQueuePosition(player);
            msg = msg.replace("[queue-position]", Integer.toString(position));
        }

        if (msg.contains("[queue-size]")) {
            if (knownServer != null) {
                server = knownServer;
            } else if (server == null) {
                server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
            }

            int size = VelocityLimboHandler.getPlayerManager().getQueueSize(server.getServerInfo().getName());
            msg = msg.replace("[queue-size]", Integer.toString(size));
        }

        if (msg.contains("[total-limbo]")) {
            int size = VelocityLimboHandler.getLimboServer().getPlayersConnected().size();
            msg = msg.replace("[total-limbo]", Integer.toString(size));
        }

        if (msg.contains("[queued-server]")) {
            if (knownServer != null) {
                server = knownServer;
            } else if (server == null) {
                server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
            }

            String serverName = server.getServerInfo().getName();
            msg = msg.replace("[queued-server]", serverName);
        }

        return msg;
    }

    public static Component formatComponent(String message, Player player) {
        return formatComponent(message, player, null, null);
    }

    public static Component formatComponent(String message, Player player, Integer knownPosition,
                                            RegisteredServer knownServer) {
        return COMPILED_MESSAGES.computeIfAbsent(message, CompiledMessage::new)
                .render(player, knownPosition, knownServer);
    }

    public static void clearCache() {
        COMPILED_MESSAGES.clear();
    }

    private static final class CompiledMessage {
        private final Component template;
        private final boolean hasPlayer;
        private final boolean hasQueuePosition;
        private final boolean hasQueueSize;
        private final boolean hasTotalLimbo;
        private final boolean hasQueuedServer;

        private CompiledMessage(String message) {
            template = MINI_MESSAGE.deserialize(message);
            hasPlayer = message.contains("[player]");
            hasQueuePosition = message.contains("[queue-position]");
            hasQueueSize = message.contains("[queue-size]");
            hasTotalLimbo = message.contains("[total-limbo]");
            hasQueuedServer = message.contains("[queued-server]");
        }

        private Component render(Player player, Integer knownPosition, RegisteredServer knownServer) {
            Component result = template;
            RegisteredServer server = knownServer;

            if (hasPlayer) {
                result = replaceLiteral(result, "[player]", Component.text(player.getUsername()));
            }
            if (hasQueuePosition) {
                int position = knownPosition != null
                        ? knownPosition
                        : VelocityLimboHandler.getPlayerManager().getQueuePosition(player);
                result = replaceLiteral(result, "[queue-position]", Component.text(position));
            }
            if (hasQueueSize) {
                if (server == null) {
                    server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
                }
                int size = VelocityLimboHandler.getPlayerManager().getQueueSize(server.getServerInfo().getName());
                result = replaceLiteral(result, "[queue-size]", Component.text(size));
            }
            if (hasTotalLimbo) {
                int size = VelocityLimboHandler.getLimboServer().getPlayersConnected().size();
                result = replaceLiteral(result, "[total-limbo]", Component.text(size));
            }
            if (hasQueuedServer) {
                if (server == null) {
                    server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
                }
                result = replaceLiteral(result, "[queued-server]", Component.text(server.getServerInfo().getName()));
            }

            return result;
        }

        private Component replaceLiteral(Component source, String literal, Component replacement) {
            return source.replaceText(builder -> builder.matchLiteral(literal).replacement(replacement));
        }
    }
}
