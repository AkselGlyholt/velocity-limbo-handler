package com.akselglyholt.velocityLimboHandler.auth;

import com.akselglyholt.velocityLimboHandler.auth.handlers.LibreLoginHandler;
import com.akselglyholt.velocityLimboHandler.auth.handlers.NoopHandler;
import com.akselglyholt.velocityLimboHandler.misc.ReconnectBlocker;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;

import java.util.ArrayList;
import java.util.List;

public final class AuthManager implements AutoCloseable {
    private final ProxyServer proxy;
    private final ReconnectBlocker blocker;
    private final List<AuthHandler> handlers = new ArrayList<>();
    private AuthHandler active = new NoopHandler();

    public AuthManager(Object plugin, ProxyServer proxy, ReconnectBlocker blocker) {
        this.proxy = proxy;
        this.blocker = blocker;
        // order matters if multiple are present
        handlers.add(new LibreLoginHandler(proxy, blocker));
        // add future handlers here
        selectActive();
        proxy.getEventManager().register(plugin, PostLoginEvent.class, evt -> {
            Player p = evt.getPlayer();
            active.onPlayerJoin(p);
        });
    }

    private void selectActive() {
        for (var h : handlers) {
            if (h.isActive()) {
                active = h;
                return;
            }
        }
    }

    @Override
    public void close() {
        active.close();
    }

    public boolean isAuthBlocked(Player p) {
        return this.blocker != null && blocker.isBlocked(p.getUniqueId());
    }
}
