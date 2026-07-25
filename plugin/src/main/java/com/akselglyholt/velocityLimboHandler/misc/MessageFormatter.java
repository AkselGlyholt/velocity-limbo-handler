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
        boolean hasPlayer = msg.contains("[player]");
        boolean hasQueuePosition = msg.contains("[queue-position]");
        boolean hasQueueSize = msg.contains("[queue-size]");
        boolean hasTotalLimbo = msg.contains("[total-limbo]");
        boolean hasQueuedServer = msg.contains("[queued-server]");
        PlaceholderValues values = resolvePlaceholderValues(
                player, knownPosition, knownServer,
                hasPlayer, hasQueuePosition, hasQueueSize, hasTotalLimbo, hasQueuedServer
        );

        if (hasPlayer) {
            msg = msg.replace("[player]", values.player());
        }
        if (hasQueuePosition) {
            msg = msg.replace("[queue-position]", Integer.toString(values.queuePosition()));
        }
        if (hasQueueSize && values.queueSize() != null) {
            msg = msg.replace("[queue-size]", Integer.toString(values.queueSize()));
        }
        if (hasTotalLimbo) {
            msg = msg.replace("[total-limbo]", Integer.toString(values.totalLimbo()));
        }
        if (hasQueuedServer && values.queuedServer() != null) {
            msg = msg.replace("[queued-server]", values.queuedServer());
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

    private static PlaceholderValues resolvePlaceholderValues(
            Player player, Integer knownPosition, RegisteredServer knownServer,
            boolean hasPlayer, boolean hasQueuePosition, boolean hasQueueSize,
            boolean hasTotalLimbo, boolean hasQueuedServer
    ) {
        String playerName = hasPlayer ? player.getUsername() : null;
        Integer queuePosition = hasQueuePosition
                ? knownPosition != null ? knownPosition : VelocityLimboHandler.getPlayerManager().getQueuePosition(player)
                : null;

        RegisteredServer server = knownServer;
        if ((hasQueueSize || hasQueuedServer) && server == null) {
            server = VelocityLimboHandler.getPlayerManager().getPreviousServer(player);
        }
        String serverName = (hasQueueSize || hasQueuedServer) && server != null
                ? server.getServerInfo().getName()
                : null;
        Integer queueSize = hasQueueSize && serverName != null
                ? VelocityLimboHandler.getPlayerManager().getQueueSize(serverName)
                : null;
        Integer totalLimbo = hasTotalLimbo
                ? VelocityLimboHandler.getLimboServer().getPlayersConnected().size()
                : null;

        return new PlaceholderValues(playerName, queuePosition, queueSize, totalLimbo,
                hasQueuedServer ? serverName : null);
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
            PlaceholderValues values = resolvePlaceholderValues(
                    player, knownPosition, knownServer,
                    hasPlayer, hasQueuePosition, hasQueueSize, hasTotalLimbo, hasQueuedServer
            );

            if (hasPlayer) {
                result = replaceLiteral(result, "[player]", Component.text(values.player()));
            }
            if (hasQueuePosition) {
                result = replaceLiteral(result, "[queue-position]", Component.text(values.queuePosition()));
            }
            if (hasQueueSize && values.queueSize() != null) {
                result = replaceLiteral(result, "[queue-size]", Component.text(values.queueSize()));
            }
            if (hasTotalLimbo) {
                result = replaceLiteral(result, "[total-limbo]", Component.text(values.totalLimbo()));
            }
            if (hasQueuedServer && values.queuedServer() != null) {
                result = replaceLiteral(result, "[queued-server]", Component.text(values.queuedServer()));
            }

            return result;
        }

        private Component replaceLiteral(Component source, String literal, Component replacement) {
            return source.replaceText(builder -> builder.matchLiteral(literal).replacement(replacement));
        }
    }

    private record PlaceholderValues(String player, Integer queuePosition, Integer queueSize,
                                     Integer totalLimbo, String queuedServer) {
    }
}
