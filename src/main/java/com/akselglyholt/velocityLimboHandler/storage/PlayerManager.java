package com.akselglyholt.velocityLimboHandler.storage;

import com.akselglyholt.velocityLimboHandler.VelocityLimboHandler;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.akselglyholt.velocityLimboHandler.misc.Utility;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.dejvokep.boostedyaml.route.Route;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public class PlayerManager {
    private final Map<Player, String> playerData;
    private final Map<Player, Boolean> connectingPlayers;
    private final Map<String, Queue<Player>> reconnectQueues = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> playerConnectionIssues = new ConcurrentHashMap<>();
    private static String queuePositionMsg;

    private boolean isAuthBlocked(Player player) {
        var am = VelocityLimboHandler.getAuthManager();
        return am != null && am.isAuthBlocked(player);
    }

    public PlayerManager() {
        this.playerData = new LinkedHashMap<>();
        this.connectingPlayers = new LinkedHashMap<>();

        queuePositionMsg = VelocityLimboHandler.getMessageConfig().getString(Route.from("queuePositionJoin"));
    }

    public void addPlayer(Player player, RegisteredServer registeredServer) {
        // Don't override if the player is already registered
        if (this.playerData.containsKey(player)) return;

        if (isAuthBlocked(player)) return;

        String serverName = registeredServer.getServerInfo().getName();
        this.playerData.put(player, serverName);

        Utility.sendWelcomeMessage(player, null);

        // Only maintain a reconnect queue when queue mode is enabled
        Queue<Player> queue = reconnectQueues.computeIfAbsent(serverName, s -> new ConcurrentLinkedQueue<>());
        if (VelocityLimboHandler.isQueueEnabled() && !queue.contains(player)) {
            addPlayerToQueue(player, registeredServer);

            String formatedMsg = MessageFormatter.formatMessage(queuePositionMsg, player);
            player.sendMessage(miniMessage.deserialize(formatedMsg));
        }
    }


    public void removePlayer(Player player) {
        removePlayerFromQueue(player);
        this.playerData.remove(player);
        this.connectingPlayers.remove(player);
        VelocityLimboHandler.getReconnectBlocker().unblock(player.getUniqueId());
    }

    public RegisteredServer getPreviousServer(Player player) {
        String serverName = this.playerData.get(player);

        if (serverName != null) {
            return VelocityLimboHandler.getProxyServer()
                    .getServer(serverName)
                    .orElse(VelocityLimboHandler.getDirectConnectServer());
        }

        return VelocityLimboHandler.getDirectConnectServer();
    }

    public boolean isPlayerRegistered(Player player) {
        return playerData.containsKey(player);
    }

    public void addPlayerToQueue(Player player, RegisteredServer server) {
        reconnectQueues.computeIfAbsent(server.getServerInfo().getName(), s -> new ConcurrentLinkedQueue<>()).add(player);
    }

    public void removePlayerFromQueue(Player player) {
        String serverName = this.playerData.get(player);
        if (serverName == null && VelocityLimboHandler.getDirectConnectServer() != null) {
            serverName = VelocityLimboHandler.getDirectConnectServer().getServerInfo().getName();
        }

        if (serverName == null) return;

        Queue<Player> queue = reconnectQueues.get(serverName);
        if (queue != null) queue.remove(player);
    }

    public Player getNextQueuedPlayer(RegisteredServer server) {
        Queue<Player> queue = reconnectQueues.get(server.getServerInfo().getName());
        return queue == null ? null : queue.peek();
    }

    public boolean hasQueuedPlayers(RegisteredServer server) {
        Queue<Player> queue = reconnectQueues.get(server.getServerInfo().getName());
        return queue != null && !queue.isEmpty();
    }

    public int getQueuePosition(Player player) {
        RegisteredServer server = getPreviousServer(player);

        Queue<Player> queue = reconnectQueues.get(server.getServerInfo().getName());
        if (queue == null) return -1;

        int position = 1;
        for (Player p : queue) {
            if (p.equals(player)) return position;
            position++;
        }

        return -1;

    }

    public void addPlayerWithIssue(Player player, String issue) {
        playerConnectionIssues.put(player.getUniqueId(), issue);
    }

    public boolean hasConnectionIssue(Player player) {
        return playerConnectionIssues.containsKey(player.getUniqueId());
    }

    public String getConnectionIssue(Player player) {
        return playerConnectionIssues.get(player.getUniqueId());
    }

    public void removePlayerIssue(Player player) {
        playerConnectionIssues.remove(player.getUniqueId());
    }

    public void pruneInactivePlayers() {
        for (Queue<Player> queue : reconnectQueues.values()) {
            queue.removeIf(p -> !p.isActive());
        }
        playerData.keySet().removeIf(p -> !p.isActive());
    }

    /**
     * @param server the server of which you need to find the first whitelisted/permission allow player
     * @return Player object
     */
    public static Player findFirstMaintenanceAllowedPlayer(RegisteredServer server) {
        // Find the queue for the server
        Queue<Player> queue = VelocityLimboHandler.getPlayerManager().reconnectQueues.get(server.getServerInfo().getName());
        if (queue == null) return null;

        // Loop through all players and check if any match is found
        for (Player player : queue) {
            if (player.hasPermission("maintenance.admin")
                    || player.hasPermission("maintenance.bypass")
                    || player.hasPermission("maintenance.singleserver.bypass." + server.getServerInfo().getName())
                    || Utility.playerMaintenanceWhitelisted(player)) {
                return player;
            }
        }

        return null;
    }

    // Check if player is in the hashmap
    public boolean isPlayerConnecting(Player player) {
        return this.connectingPlayers.containsKey(player);
    }

    /**
     * @param player is the player of which you want to set the status of
     * @param add    is whether you want to add the player, or remove it
     */
    public void setPlayerConnecting(Player player, Boolean add) {
        if (add) {
            this.connectingPlayers.put(player, true);
        } else {
            this.connectingPlayers.remove(player);
        }
    }
}
