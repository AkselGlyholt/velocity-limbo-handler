package com.akselglyholt.velocityLimboHandler.listeners;

import com.akselglyholt.velocityLimboHandler.commands.CommandBlockRule;
import com.akselglyholt.velocityLimboHandler.commands.CommandBlocker;
import com.akselglyholt.velocityLimboHandler.config.ConfigManager;
import com.akselglyholt.velocityLimboHandler.misc.MessageFormatter;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;

import java.util.Optional;
import java.util.Locale;

public class CommandExecuteEventListener {
    private final CommandBlocker commandBlocker;
    private final ConfigManager configManager;

    public CommandExecuteEventListener(CommandBlocker commandBlocker, ConfigManager configManager) {
        this.commandBlocker = commandBlocker;
        this.configManager = configManager;
    }

    @Subscribe
    public void onCommandExecute(CommandExecuteEvent event) {
        if (!(event.getCommandSource() instanceof Player player)) {
            return;
        }

        Optional<ServerConnection> serverConnection = player.getCurrentServer();

        if (serverConnection.isPresent()) {
            String command = event.getCommand();
            int separator = command.indexOf(' ');
            String commandName = (separator < 0 ? command : command.substring(0, separator)).toLowerCase(Locale.ROOT);

            // Get the rule for this specific command
            CommandBlockRule rule = commandBlocker.getRule(commandName);

            if (rule != null && rule.shouldBlock(player)) {
                event.setResult(CommandExecuteEvent.CommandResult.denied());
                String message = configManager.getCommandBlockedMsg();
                player.sendMessage(MessageFormatter.formatComponent(message, player));
            }
        }
    }
}
