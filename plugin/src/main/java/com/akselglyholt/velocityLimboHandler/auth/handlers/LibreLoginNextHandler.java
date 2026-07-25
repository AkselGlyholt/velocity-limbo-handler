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
import java.util.UUID;
import java.util.logging.Logger;

public class LibreLoginNextHandler implements AuthHandler {
    private final ProxyServer proxy;
    private final ReconnectBlocker blocker;
    private final boolean active;
    private final Logger logger = VelocityLimboHandler.getLogger();
    private final PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();

    public LibreLoginNextHandler(ProxyServer proxy, ReconnectBlocker blocker) {
        this.proxy = proxy;
        this.blocker = blocker;

        // Detection by plugin id or class existence
        boolean detected = proxy.getPluginManager().getPlugin("libreloginnext").isPresent();
        this.active = detected && tryHook();
    }

    @Override
    public String name() {
        return "LibreLoginNext";
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
            logger.info("LibreLoginNext plugin detected! Integrating now");

            // 1) Get bootstrap (VelocityBootstrap) instance
            var containerOpt = proxy.getPluginManager().getPlugin("libreloginnext");
            var instanceOpt = containerOpt.flatMap(com.velocitypowered.api.plugin.PluginContainer::getInstance);
            if (instanceOpt.isEmpty()) {
                logger.warning("LibreLoginNext plugin instance not available.");
                return false;
            }
            Object bootstrap = instanceOpt.get();

            // 2) bootstrap.getLibreLogin() -> core plugin
            Method getLibreLogin = bootstrap.getClass().getMethod("getLibreLoginNext");
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
            Class<?> eventTypeClass = Class.forName("xyz.miguvt.libreloginnext.api.event.EventType");
            Method subscribe = eventProvider.getClass().getMethod("subscribe", eventTypeClass, java.util.function.Consumer.class);

            java.util.function.Consumer<Object> handler = this::handleAuthenticationEvent;

            subscribe.invoke(eventProvider, authType, handler);

            logger.info("Subscribed to LibreLoginNext 'authenticated' event.");
            return true;
        } catch (Exception e) {
            logger.warning("Failed to integrate with LibreLoginNext: " + e.getMessage());
            return false;
        }
    }

    void handleAuthenticationEvent(Object event) {
        try {
            Player player = extractPlayerFromLibreEvent(event);
            if (player != null) {
                unblockAndRegister(player);
                return;
            }

            UUID playerId = extractPlayerId(event);
            if (playerId == null) return;

            blocker.unblock(playerId);
            proxy.getPlayer(playerId).filter(Player::isActive).ifPresent(this::registerAuthenticatedPlayer);
        } catch (Exception ex) {
            logger.warning("Failed to process LibreLoginNext auth event: " + ex.getMessage());
        }
    }

    private void unblockAndRegister(Player player) {
        Utility.logDebug(() -> "Player " + player.getUsername()
                + " authenticated via LibreLoginNext — unblocked.");
        blocker.unblock(player.getUniqueId());
        registerAuthenticatedPlayer(player);
    }

    private void registerAuthenticatedPlayer(Player player) {
        RegisteredServer server = playerManager.getPreviousServer(player);
        if (server == null) {
            logger.warning("Could not register authenticated LibreLoginNext player "
                    + player.getUsername() + ": no destination server is available");
            return;
        }
        playerManager.addPlayer(player, server);
    }

    private UUID extractPlayerId(Object event) throws ReflectiveOperationException {
        Method getUser = safeMethod(event.getClass(), "getUser");
        if (getUser == null) return null;

        Object user = getUser.invoke(event);
        if (user == null) return null;

        Method getUuid = safeMethod(user.getClass(), "getUuid");
        if (getUuid == null) return null;

        Object value = getUuid.invoke(user);
        return value instanceof UUID playerId ? playerId : null;
    }

    private static Method safeMethod(Class<?> c, String name, Class<?>... params) {
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

}
