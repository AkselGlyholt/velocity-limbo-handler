package com.akselglyholt.velocityLimboHandler.misc;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.route.Route;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class Utility {
    private static final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final String welcomeMsg = VelocityLimboHandler.getMessageConfig().getString(Route.from("welcomeMessage"));
    private static final Map<Class<?>, Method> IS_MAINTENANCE_NO_ARG_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> IS_MAINTENANCE_STRING_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GET_SERVER_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> IS_MAINTENANCE_SERVER_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GET_SETTINGS_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Method> GET_WHITELISTED_PLAYERS_CACHE = new ConcurrentHashMap<>();

    // Returns whether the names of the servers match.
    public static boolean doServerNamesMatch(@NotNull RegisteredServer var0, @NotNull RegisteredServer var1) {
        return var0.getServerInfo().getName().equals(var1.getServerInfo().getName());
    }

    // Method with a specified reason
    public static void sendWelcomeMessage(Player player, String reason) {
        if (reason == null) reason = "unknown";

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
            default -> miniMessage.deserialize(MessageFormatter.formatMessage(welcomeMsg, player));
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

    public static void logInformational(String message) {
        VelocityLimboHandler.getLogger().info(message);
    }

    private static Method getCachedMethod(Map<Class<?>, Method> cache, Class<?> targetClass, String methodName, Class<?>... paramTypes) {
        return cache.computeIfAbsent(targetClass, key -> {
            try {
                return key.getMethod(methodName, paramTypes);
            } catch (NoSuchMethodException ignored) {
                return null;
            }
        });
    }

    private static Method resolveServerMaintenanceMethod(Class<?> apiClass, Class<?> serverClass) {
        Method cached = IS_MAINTENANCE_SERVER_CACHE.get(apiClass);
        if (cached != null && cached.getParameterTypes()[0].isAssignableFrom(serverClass)) {
            return cached;
        }

        for (Method method : apiClass.getMethods()) {
            if (!method.getName().equals("isMaintenance") || method.getParameterCount() != 1) {
                continue;
            }

            Class<?> paramType = method.getParameterTypes()[0];
            if (paramType.isAssignableFrom(serverClass)) {
                IS_MAINTENANCE_SERVER_CACHE.put(apiClass, method);
                return method;
            }
        }

        return null;
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

            Class<?> apiClass = maintenanceAPI.getClass();

            // First check if the entire proxy is in maintenance
            Method globalMaintenanceMethod = getCachedMethod(IS_MAINTENANCE_NO_ARG_CACHE, apiClass, "isMaintenance");
            if (globalMaintenanceMethod != null) {
                try {
                    boolean globalMaintenance = (boolean) globalMaintenanceMethod.invoke(maintenanceAPI);
                    if (globalMaintenance) {
                        return true;
                    }
                } catch (Exception ignored) {
                    // Ignore and continue to server-specific checks
                }
            }

            // Try direct name-based check first
            Method stringMaintenanceMethod = getCachedMethod(IS_MAINTENANCE_STRING_CACHE, apiClass, "isMaintenance", String.class);
            if (stringMaintenanceMethod != null) {
                try {
                    return (boolean) stringMaintenanceMethod.invoke(maintenanceAPI, serverName);
                } catch (Exception ignored) {
                    // Ignore and continue to server-object checks
                }
            }

            // Try server-object based check
            Method getServerMethod = getCachedMethod(GET_SERVER_CACHE, apiClass, "getServer", String.class);
            if (getServerMethod == null) {
                return false;
            }

            Object server = getServerMethod.invoke(maintenanceAPI, serverName);
            if (server == null) {
                return false;
            }

            Method serverMaintenanceMethod = resolveServerMaintenanceMethod(apiClass, server.getClass());
            if (serverMaintenanceMethod == null) {
                return false;
            }

            return (boolean) serverMaintenanceMethod.invoke(maintenanceAPI, server);

        } catch (Exception e) {
            VelocityLimboHandler.getLogger().warning("Failed to check maintenance status for server '" + serverName + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Check if a player is whitelisted on the server that has maintenance
     * @param player The player you want to check is whitelisted
     * @return true if whitelisted, otherwise returns false
     */
    public static boolean playerMaintenanceWhitelisted(Player player) {
        if (!hasMaintenance()) {
            return false;
        }

        try {
            Object maintenanceAPI = VelocityLimboHandler.getMaintenanceAPI();
            if (maintenanceAPI == null) {
                return false;
            }

            Method getSettingsMethod = getCachedMethod(GET_SETTINGS_CACHE, maintenanceAPI.getClass(), "getSettings");
            if (getSettingsMethod == null) {
                return false;
            }

            Object settings = getSettingsMethod.invoke(maintenanceAPI);
            if (settings == null) {
                return false;
            }

            Method getWhitelistedPlayersMethod = getCachedMethod(
                    GET_WHITELISTED_PLAYERS_CACHE,
                    settings.getClass(),
                    "getWhitelistedPlayers"
            );
            if (getWhitelistedPlayersMethod == null) {
                return false;
            }

            Object whitelistMapObj = getWhitelistedPlayersMethod.invoke(settings);

            if (!(whitelistMapObj instanceof Map<?, ?>)) {
                return false;
            }

            @SuppressWarnings("unchecked")
            Map<UUID, String> whitelistedPlayers = (Map<UUID, String>) whitelistMapObj;

            return whitelistedPlayers.containsKey(player.getUniqueId());

        } catch (Exception e) {
            VelocityLimboHandler.getLogger().warning("Failed to check if player is whitelisted in Maintenance plugin: " + e.getMessage());
            return false;
        }
    }
}
