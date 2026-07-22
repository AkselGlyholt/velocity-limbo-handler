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
import java.util.concurrent.atomic.AtomicBoolean;

public class Utility {
    private static final MiniMessage miniMessage = MiniMessage.miniMessage();
    private static final String welcomeMsg = VelocityLimboHandler.getMessageConfig().getString(Route.from("welcomeMessage"));
    private static final Object MAINTENANCE_ADAPTER_LOCK = new Object();
    private static volatile MaintenanceAdapter maintenanceAdapter;

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

    public static void logDebug(String message) {
        if (VelocityLimboHandler.getConfigManager().isDebugEnabled()) {
            VelocityLimboHandler.getLogger().info("[DEBUG] " + message);
        }
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

        Object maintenanceAPI = VelocityLimboHandler.getMaintenanceAPI();
        return maintenanceAPI != null && getMaintenanceAdapter(maintenanceAPI).isServerInMaintenance(serverName);
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

        Object maintenanceAPI = VelocityLimboHandler.getMaintenanceAPI();
        return maintenanceAPI != null && getMaintenanceAdapter(maintenanceAPI).isWhitelisted(player.getUniqueId());
    }

    public static void clearMaintenanceAdapter() {
        maintenanceAdapter = null;
    }

    private static MaintenanceAdapter getMaintenanceAdapter(Object maintenanceAPI) {
        MaintenanceAdapter current = maintenanceAdapter;
        if (current != null && current.isFor(maintenanceAPI)) {
            return current;
        }

        synchronized (MAINTENANCE_ADAPTER_LOCK) {
            current = maintenanceAdapter;
            if (current == null || !current.isFor(maintenanceAPI)) {
                current = new MaintenanceAdapter(maintenanceAPI);
                maintenanceAdapter = current;
            }
            return current;
        }
    }

    private static final class MaintenanceAdapter {
        private final Object api;
        private final Optional<Method> globalMaintenanceMethod;
        private final Optional<Method> stringMaintenanceMethod;
        private final Optional<Method> getServerMethod;
        private final Optional<Method> getSettingsMethod;
        private final Map<Class<?>, Optional<Method>> serverMaintenanceMethods = new ConcurrentHashMap<>();
        private final Map<Class<?>, Optional<Method>> whitelistMethods = new ConcurrentHashMap<>();
        private final AtomicBoolean failureLogged = new AtomicBoolean();

        private MaintenanceAdapter(Object api) {
            this.api = api;
            Class<?> apiClass = api.getClass();
            globalMaintenanceMethod = findMethod(apiClass, "isMaintenance");
            stringMaintenanceMethod = findMethod(apiClass, "isMaintenance", String.class);
            getServerMethod = findMethod(apiClass, "getServer", String.class);
            getSettingsMethod = findMethod(apiClass, "getSettings");
        }

        private boolean isFor(Object candidate) {
            return api == candidate;
        }

        private boolean isServerInMaintenance(String serverName) {
            if (globalMaintenanceMethod.isPresent()) {
                Boolean globalMaintenance = invokeBoolean(globalMaintenanceMethod.get());
                if (Boolean.TRUE.equals(globalMaintenance)) {
                    return true;
                }
            }

            if (stringMaintenanceMethod.isPresent()) {
                Boolean serverMaintenance = invokeBoolean(stringMaintenanceMethod.get(), serverName);
                if (serverMaintenance != null) {
                    return serverMaintenance;
                }
            }

            if (getServerMethod.isEmpty()) {
                return false;
            }

            try {
                Object server = getServerMethod.get().invoke(api, serverName);
                if (server == null) {
                    return false;
                }

                Optional<Method> serverMethod = serverMaintenanceMethods.computeIfAbsent(
                        server.getClass(),
                        serverClass -> findServerMaintenanceMethod(api.getClass(), serverClass)
                );
                return serverMethod.isPresent() && Boolean.TRUE.equals(invokeBoolean(serverMethod.get(), server));
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logFailureOnce("server maintenance", exception);
                return false;
            }
        }

        private boolean isWhitelisted(UUID playerId) {
            try {
                if (getSettingsMethod.isEmpty()) {
                    return false;
                }
                Object settings = getSettingsMethod.get().invoke(api);
                if (settings == null) {
                    return false;
                }

                Optional<Method> whitelistMethod = whitelistMethods.computeIfAbsent(
                        settings.getClass(),
                        settingsClass -> findMethod(settingsClass, "getWhitelistedPlayers")
                );
                if (whitelistMethod.isEmpty()) {
                    return false;
                }

                Object whitelist = whitelistMethod.get().invoke(settings);
                return whitelist instanceof Map<?, ?> whitelistMap && whitelistMap.containsKey(playerId);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logFailureOnce("maintenance whitelist", exception);
                return false;
            }
        }

        private void logFailureOnce(String operation, Exception exception) {
            if (failureLogged.compareAndSet(false, true)) {
                VelocityLimboHandler.getLogger().warning(
                        "Failed to access " + operation + " API: " + exception.getMessage()
                );
            }
        }

        private Boolean invokeBoolean(Method method, Object... arguments) {
            try {
                return (Boolean) method.invoke(api, arguments);
            } catch (ReflectiveOperationException | RuntimeException exception) {
                logFailureOnce("maintenance", exception);
                return null;
            }
        }

        private static Optional<Method> findMethod(Class<?> targetClass, String name, Class<?>... parameterTypes) {
            try {
                return Optional.of(targetClass.getMethod(name, parameterTypes));
            } catch (NoSuchMethodException ignored) {
                return Optional.empty();
            }
        }

        private static Optional<Method> findServerMaintenanceMethod(Class<?> apiClass, Class<?> serverClass) {
            for (Method method : apiClass.getMethods()) {
                if (method.getName().equals("isMaintenance")
                        && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].isAssignableFrom(serverClass)) {
                    return Optional.of(method);
                }
            }
            return Optional.empty();
        }
    }
}
