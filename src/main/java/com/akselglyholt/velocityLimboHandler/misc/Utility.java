package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import eu.kennytv.maintenance.api.proxy.MaintenanceProxy;
import eu.kennytv.maintenance.api.proxy.Server;
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

    public static boolean hasMaintenance() {
        return VelocityLimboHandler.hasMaintenancePlugin();
    }

    /**
     * Check if a specific server is in maintenance mode
     * @param serverName The name of the server to check
     * @return true if the server is in maintenance, false otherwise
     */
    public static boolean isServerInMaintenance(String serverName) {
        if (!hasMaintenance()) {
            return false; // No maintenance plugin, assume not in maintenance
        }

        try {
            Object maintenanceAPI = VelocityLimboHandler.getMaintenanceAPI();
            if (maintenanceAPI == null) {
                return false;
            }

            // First check if the entire proxy is in maintenance
            try {
                boolean globalMaintenance = (boolean) maintenanceAPI.getClass()
                        .getMethod("isMaintenance")
                        .invoke(maintenanceAPI);
                if (globalMaintenance) {
                    return true; // If proxy is in global maintenance, all servers are in maintenance
                }
            } catch (Exception e) {
                // Ignore, continue to server-specific check
            }

            // Now try server-specific maintenance using different approaches
            try {
                // Approach 1: Try direct server name method (if it exists)
                try {
                    boolean result = (boolean) maintenanceAPI.getClass()
                            .getMethod("isMaintenance", String.class)
                            .invoke(maintenanceAPI, serverName);
                    return result;
                } catch (NoSuchMethodException e) {
                    // Method doesn't exist, try server object approach
                }

                // Approach 2: Get server object first
                Object server = maintenanceAPI.getClass()
                        .getMethod("getServer", String.class)
                        .invoke(maintenanceAPI, serverName);

                if (server == null) {
                    // Server not configured in maintenance plugin, assume not in maintenance
                    return false;
                }

                // Try different method signatures for isMaintenance
                Class<?> apiClass = maintenanceAPI.getClass();
                java.lang.reflect.Method[] methods = apiClass.getMethods();

                for (java.lang.reflect.Method method : methods) {
                    if (method.getName().equals("isMaintenance") &&
                            method.getParameterCount() == 1) {

                        Class<?> paramType = method.getParameterTypes()[0];
                        if (paramType.isAssignableFrom(server.getClass())) {
                            boolean result = (boolean) method.invoke(maintenanceAPI, server);
                            return result;
                        }
                    }
                }

                // If no suitable method found, return false
                return false;

            } catch (Exception ex) {
                VelocityLimboHandler.getLogger().info("Server-specific maintenance check failed for '" + serverName + "': " + ex.getMessage());
                return false;
            }

        } catch (Exception e) {
            VelocityLimboHandler.getLogger().warning("Failed to check maintenance status for server '" + serverName + "': " + e.getMessage());
            return false; // Assume not in maintenance if check fails
        }
    }
}
