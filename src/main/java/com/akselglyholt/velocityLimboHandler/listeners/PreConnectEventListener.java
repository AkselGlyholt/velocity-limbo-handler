package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import org.jetbrains.annotations.NotNull;

import java.net.InetSocketAddress;
import java.util.List;

public class PreConnectEventListener {
    @Subscribe
    public void onPreConnect(@NotNull ServerPreConnectEvent event) {
        Player player = event.getPlayer();
        String virtualHost = player.getVirtualHost().map(InetSocketAddress::getHostName).orElse(null);

        if (virtualHost != null) {
            List<String> forcedServers = VelocityLimboHandler.getProxyServer().getConfiguration().getForcedHosts().get(virtualHost);

            if (forcedServers != null && !forcedServers.isEmpty()) {
                String forcedTarget = forcedServers.get(0);
                RegisteredServer forcedServer = VelocityLimboHandler.getProxyServer().getServer(forcedTarget).orElse(null);

                if (forcedServer != null) {
                    // Store this as the "intended" server before rerouting them to limbo
                    VelocityLimboHandler.getPlayerManager().addPlayer(player, forcedServer);
                }
            }
        }
    }
}
