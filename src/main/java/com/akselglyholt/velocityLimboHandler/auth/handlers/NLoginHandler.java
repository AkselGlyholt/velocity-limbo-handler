package com.akselglyholt.velocityLimboHandler.auth.handlers;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.auth.AuthHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.lang.reflect.Method;
import java.util.logging.Logger;

public class NLoginHandler implements AuthHandler {
    private final ProxyServer proxy;
    private final ReconnectBlocker blocker;
    private final boolean active;
    private final Logger logger = VelocityLimboHandler.getLogger();
    private final PlayerManager playerManager = VelocityLimboHandler.getPlayerManager();

    public NLoginHandler(ProxyServer proxy, ReconnectBlocker blocker) {
        this.proxy = proxy;
        this.blocker = blocker;

        this.active = proxy.getPluginManager().getPlugin("nlogin").isPresent()
                || classPresent("com.nickuc.login.api.NLoginAPI");

        if (active) tryHook();
    }

    @Override
    public String name() {
        return "NLogin";
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

    private void tryHook() {
        try {
            logger.info("NLogin plugin detected! Integrating now");

            Class<?> eventClass = Class.forName("com.nickuc.login.api.event.velocity.auth.AuthenticateEvent");
            Method getPlayer = eventClass.getMethod("getPlayer");

            proxy.getEventManager().register(VelocityLimboHandler.getInstance(), eventClass, event -> {
                try {
                    Player player = (Player) getPlayer.invoke(event);
                    logger.info("Player " + player.getUsername() + " authenticated via NLogin — unblocked.");

                    blocker.unblock(player.getUniqueId());
                } catch (Exception ex) {
                    logger.warning("Failed to process NLogin auth event: " + ex.getMessage());
                }
            });

            // Register listener to intercept NLogin's redirection
            proxy.getEventManager().register(VelocityLimboHandler.getInstance(), ServerPreConnectEvent.class, this::onServerPreConnect);

            logger.info("Subscribed to NLogin AuthenticateEvent.");
        } catch (Exception e) {
            logger.warning("Failed to integrate with NLogin: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Subscribe
    public void onServerPreConnect(ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        RegisteredServer intendedServer = event.getOriginalServer();
        RegisteredServer limbo = VelocityLimboHandler.getLimboServer();

        if (limbo == null) return;

        // Return if the server the player is connecting from isn't the Limbo
        if (player.getCurrentServer().isPresent() && !player.getCurrentServer().get().getServer().equals(limbo)) {
            return;
        }

        // Let plugin-initiated connections pass through
        if (VelocityLimboHandler.getPlayerManager().isPlayerConnecting(player)) {
            return;
        }

        // Let connections to limbo pass through
        if (intendedServer.equals(limbo)) {
            return;
        }

        // Cancel NLogin's auto-redirect - queue will handle the connection
        if (!blocker.isBlocked(player.getUniqueId())) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());

            playerManager.addPlayer(player, intendedServer);
        }
    }

    @Override
    public void onShutdown() {
    }

    private static boolean classPresent(String fqn) {
        try {
            Class.forName(fqn, false, NLoginHandler.class.getClassLoader());
            return true;
        } catch (Throwable t) {
            return false;
        }
    }
}
