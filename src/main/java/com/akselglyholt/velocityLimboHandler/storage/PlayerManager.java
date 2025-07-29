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
    private final Map<Player, RegisteredServer> playerData;
    private final Map<RegisteredServer, Queue<Player>> reconnectQueues = new ConcurrentHashMap<>();
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, String> playerConnectionIssues = new ConcurrentHashMap<>();

    private static String queuePositionMsg;

    public PlayerManager() {
        this.playerData = new LinkedHashMap<>();

        queuePositionMsg = VelocityLimboHandler.getMessageConfig().getString(Route.from("queuePositionJoin"));
    }

    public void addPlayer(Player player, RegisteredServer registeredServer) {
        // Don't override if the player is already registered
        if (this.playerData.containsKey(player)) return;

        this.playerData.put(player, registeredServer);

        Utility.sendWelcomeMessage(player, null);

        // Only maintain a reconnect queue when queue mode is enabled
        Queue<Player> queue = reconnectQueues.computeIfAbsent(registeredServer, s -> new ConcurrentLinkedQueue<>());
        if (VelocityLimboHandler.isQueueEnabled() && !queue.contains(player)) {
            addPlayerToQueue(player, registeredServer);

            String formatedMsg = MessageFormatter.formatMessage(queuePositionMsg, player);
            player.sendMessage(miniMessage.deserialize(formatedMsg));
        }
    }


    public void removePlayer(Player player) {
        removePlayerFromQueue(player);
        this.playerData.remove(player);
    }

    public RegisteredServer getPreviousServer(Player player) {
        return this.playerData.getOrDefault(player, VelocityLimboHandler.getDirectConnectServer());
    }

    public boolean isPlayerRegistered(Player player) {
        return playerData.containsKey(player);
    }

    public void addPlayerToQueue(Player player, RegisteredServer server) {
        reconnectQueues.computeIfAbsent(server, s -> new ConcurrentLinkedQueue<>()).add(player);
    }

    public void removePlayerFromQueue(Player player) {
        RegisteredServer server = this.playerData.getOrDefault(player, VelocityLimboHandler.getDirectConnectServer());

        Queue<Player> queue = reconnectQueues.get(server);
        if (queue != null) queue.remove(player);
    }

    public Player getNextQueuedPlayer(RegisteredServer server) {
        Queue<Player> queue = reconnectQueues.get(server);
        return queue == null ? null : queue.peek();
    }

    public boolean hasQueuedPlayers(RegisteredServer server) {
        Queue<Player> queue = reconnectQueues.get(server);
        return queue != null && !queue.isEmpty();
    }

    public int getQueuePosition(Player player) {
        RegisteredServer server = getPreviousServer(player);

        Queue<Player> queue = reconnectQueues.get(server);
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
}
