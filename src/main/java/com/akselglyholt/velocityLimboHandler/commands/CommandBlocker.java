package com.akselglyholt.velocityLimboHandler.commands;

import java.util.HashMap;
import java.util.Map;
import java.util.Locale;

public class CommandBlocker {
    private final Map<String, CommandBlockRule> commandRules = new HashMap<>();

    public void blockCommand(String command, CommandBlockRule rule) {
        commandRules.put(command.toLowerCase(Locale.ROOT), rule);
    }

    public final Map<String, CommandBlockRule> getCommandRules() {
        return commandRules;
    }
}
