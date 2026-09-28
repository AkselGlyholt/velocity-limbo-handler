package com.akselglyholt.velocityLimboHandler.auth;

import com.akselglyholt.velocityLimboHandler.auth.handlers.LibreLoginHandler;
import com.akselglyholt.velocityLimboHandler.auth.handlers.LibreLoginNextHandler;
import com.akselglyholt.velocityLimboHandler.auth.handlers.NLoginHandler;
import com.akselglyholt.velocityLimboHandler.auth.handlers.NoopHandler;
import com.akselglyholt.velocityLimboHandler.api.VelocityLimboApiImpl;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.akselglyholt.velocityLimboHandler.storage.PlayerManager;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

public final class AuthManager implements AutoCloseable {
    private final ReconnectBlocker blocker;
    private final AuthHandler active;

    public AuthManager(Object plugin, ProxyServer proxy, ReconnectBlocker blocker,
                       PlayerManager playerManager, VelocityLimboApiImpl api) {
        this.blocker = blocker;
        active = selectActive(proxy, blocker, playerManager, api);
        proxy.getEventManager().register(plugin, PostLoginEvent.class, evt -> {
            Player p = evt.getPlayer();
            active.onPlayerJoin(p);
        });
    }

    private AuthHandler selectActive(ProxyServer proxy, ReconnectBlocker blocker,
                                     PlayerManager playerManager, VelocityLimboApiImpl api) {
        AuthHandler candidate = new LibreLoginHandler(proxy, blocker);
        if (candidate.isActive()) {
            return candidate;
        }

        candidate = new LibreLoginNextHandler(proxy, blocker);
        if (candidate.isActive()) {
            return candidate;
        }

        candidate = new NLoginHandler(proxy, blocker, playerManager, api);
        if (candidate.isActive()) {
            return candidate;
        }

        return new NoopHandler();
    }

    @Override
    public void close() {
        active.close();
    }

    public boolean isAuthBlocked(Player p) {
        return blocker != null && blocker.isBlocked(p.getUniqueId());
    }
}
