package com.akselglyholt.velocityLimboHandler.commands;

import java.util.HashMap;
import java.util.Map;

public class CommandBlocker {
    private final Map<String, CommandBlockRule> commandRules = new HashMap<>();

    public void blockCommand(String command, CommandBlockRule rule) {
        commandRules.put(command.toLowerCase(), rule);
    }

    public final Map<String, CommandBlockRule> getCommandRules() {
        return commandRules;
    }
}
