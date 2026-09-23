package com.akselglyholt.velocityLimboHandler.auth.handlers;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.auth.AuthHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.lang.reflect.Method;
import java.util.logging.Logger;

public class LibreLoginHandler implements AuthHandler {
    private final ProxyServer proxy;
    private final ReconnectBlocker blocker;
    private final boolean active;
    private final Logger logger = VelocityLimboHandler.getLogger();
    private final PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();

    public LibreLoginHandler(ProxyServer proxy, ReconnectBlocker blocker) {
        this.proxy = proxy;
        this.blocker = blocker;

        // Detection by plugin id or class existence
        boolean detected = proxy.getPluginManager().getPlugin("librelogin").isPresent()
                || classPresent("xyz.kyngs.librelogin.api.LibreLoginPlugin");
        this.active = detected && tryHook();
    }

    @Override
    public String name() {
        return "LibreLogin";
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void onPlayerJoin(Player player) {
        if (!active) return;

        blocker.block(player.getUniqueId(), "auth");
    }

    private boolean tryHook() {
        try {
            logger.info("LibreLogin plugin detected! Integrating now");

            // 1) Get bootstrap (VelocityBootstrap) instance
            var containerOpt = proxy.getPluginManager().getPlugin("librelogin");
            var instanceOpt = containerOpt.flatMap(com.velocitypowered.api.plugin.PluginContainer::getInstance);
            if (instanceOpt.isEmpty()) {
                logger.warning("LibreLogin plugin instance not available.");
                return false;
            }
            Object bootstrap = instanceOpt.get();

            // 2) bootstrap.getLibreLogin() -> core plugin
            Method getLibreLogin = bootstrap.getClass().getMethod("getLibreLogin");
            Object core = getLibreLogin.invoke(bootstrap);

            // 3) core.getEventProvider()
            Method getEventProvider = core.getClass().getMethod("getEventProvider");
            Object eventProvider = getEventProvider.invoke(core);

            // 4) provider.getTypes() and pick "authenticated"
            Method getTypes = eventProvider.getClass().getMethod("getTypes");
            Object types = getTypes.invoke(eventProvider);

            // Use the exact field name from your logs
            var authField = types.getClass().getField("authenticated");
            Object authType = authField.get(types);

            // 5) Subscribe: subscribe(EventType<T>, Consumer<T>)
            Class<?> eventTypeClass = Class.forName("xyz.kyngs.librelogin.api.event.EventType");
            Method subscribe = eventProvider.getClass().getMethod("subscribe", eventTypeClass, java.util.function.Consumer.class);

            java.util.function.Consumer<Object> handler = (Object event) -> {
                try {
                    Player p = extractPlayerFromLibreEvent(event);
                    if (p != null) {
                        Utility.logDebug(() -> "Player " + p.getUsername() + " authenticated via LibreLogin — unblocked.");
                        blocker.unblock(p.getUniqueId());

                        RegisteredServer server = playerManager.getPreviousServer(p);
                        playerManager.addPlayer(p, server);
                    } else {
                        // Fallback: try UUID path
                        Method getUser = safeMethod(event.getClass(), "getUser");
                        if (getUser != null) {
                            Object user = getUser.invoke(event);
                            if (user != null) {
                                Method getUuid = safeMethod(user.getClass(), "getUuid");
                                if (getUuid != null) {
                                    Object uuid = getUuid.invoke(user);
                                    blocker.unblock((java.util.UUID) uuid);
                                    // logger.info("Authenticated (UUID only) — unblocked.");
                                }
                            }
                        }
                    }
                } catch (Exception ex) {
                    logger.warning("Failed to process LibreLogin auth event: " + ex.getMessage());
                }
            };

            subscribe.invoke(eventProvider, authType, handler);

            logger.info("Subscribed to LibreLogin 'authenticated' event.");
            return true;
        } catch (Exception e) {
            logger.warning("Failed to integrate with LibreLogin: " + e.getMessage());
            return false;
        }
    }

    private static java.lang.reflect.Method safeMethod(Class<?> c, String name, Class<?>... params) {
        try {
            return c.getMethod(name, params);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }

    private Player extractPlayerFromLibreEvent(Object event) {
        try {
            // Try event.getPlayer()
            Method getPlayer = safeMethod(event.getClass(), "getPlayer");
            if (getPlayer != null) {
                Object p = getPlayer.invoke(event);
                if (p instanceof Player) return (Player) p;
            }
            // Try event.getUser().getProxyPlayer()/getPlayer()
            Method getUser = safeMethod(event.getClass(), "getUser");
            if (getUser != null) {
                Object user = getUser.invoke(event);
                if (user != null) {
                    Method proxyPlayer = safeMethod(user.getClass(), "getProxyPlayer");
                    if (proxyPlayer != null) {
                        Object p = proxyPlayer.invoke(user);
                        if (p instanceof Player) return (Player) p;
                    }
                    Method getPlayer2 = safeMethod(user.getClass(), "getPlayer");
                    if (getPlayer2 != null) {
                        Object p = getPlayer2.invoke(user);
                        if (p instanceof Player) return (Player) p;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }


    @Override
    public void onShutdown() {

    }

    private static boolean classPresent(String fqn) {
        try {
            Class.forName(fqn, false, LibreLoginHandler.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
