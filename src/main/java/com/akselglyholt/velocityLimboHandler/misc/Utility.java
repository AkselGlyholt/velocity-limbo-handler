package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;

public class Utility {
    private static final MiniMessage miniMessage = MiniMessage.miniMessage();

    // Returns whether the names of the servers match.
    public static boolean doServerNamesMatch(@NotNull RegisteredServer var0, @NotNull RegisteredServer var1) {
        return var0.getServerInfo().getName().equals(var1.getServerInfo().getName());
    }

    // Method with a specified reason
    public static void sendWelcomeMessage(Player player, String reason) {
        if (reason == null) reason = "unknown";

        // TODO: When config is added, add a check for whether messages should be sent
        Component message = switch (reason.toLowerCase()) {
            case "afk" ->
                    miniMessage.deserialize("<yellow>⏳ You were inactive for too long and moved to Limbo.</yellow>\n" +
                            "<gray>You will be reconnected when you interact with the game.</gray>");
            case "server-restart" ->
                    miniMessage.deserialize("<red>🔄 The server is restarting, so you have been moved to Limbo.</red>\n" +
                            "<gray>You will be reconnected automatically when the server is back.</gray>");
            case "connection-issue" ->
                    miniMessage.deserialize("<dark_red>⚠ You had connection issues and were placed in Limbo.</dark_red>\n" +
                            "<gray>Try reconnecting or wait for a stable connection.</gray>");
            default -> miniMessage.deserialize("<blue>🌌 You were sent to Limbo.</blue>\n" +
                    "<gray>You will be reconnected when the system allows.</gray>");
        };

        player.sendMessage(message);
    }


    public static @Nullable RegisteredServer getServerByName(String serverName) {
        Optional<RegisteredServer> optionalServer = VelocityLimboHandler.getProxyServer().getServer(serverName);

        if (optionalServer.isPresent()) {
            return optionalServer.get();
        }

        VelocityLimboHandler.getLogger().severe(String.format("Server \"%s\" is invalid, VelocityLimboHandler will not function!", serverName));

        return null;
    }

    public static RegisteredServer getServerFromProperty(String propertyName) {
        // TODO: Add this logic, uses hard coded value for now!
        return getServerByName("limbo");
    }

    public static void logInformational(String message) {
        // TODO: Add check in config for these logs
        VelocityLimboHandler.getLogger().info(message);
    }
}
